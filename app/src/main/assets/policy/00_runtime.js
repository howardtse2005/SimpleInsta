/*
 * 00_runtime.js — SimpleInsta policy runtime.
 *
 * Injected at document start, before Instagram's bundle, so the fetch/XHR
 * patches are in place before the app makes its first request.
 *
 * Design rules (see PLAN.md appendix):
 *   - Structural matching only. Never match on doc_id, query name, or hashed
 *     CSS class names.
 *   - Every entry point is wrapped in try/catch. A policy bug must never take
 *     the page down; it must degrade to "we changed nothing".
 */
(function () {
  if (window.__SIMPLEINSTA__) return;

  var CFG = window.__ENOUGH_CONFIG__ || {};

  var E = window.__SIMPLEINSTA__ = {
    version: CFG.policyVersion || 'dev',
    cfg: {
      feedPageCap: CFG.feedPageCap || 2,
      watermark: CFG.watermark || 0,
      queueMaxAgeDays: CFG.queueMaxAgeDays || 14,
      debug: !!CFG.debug
    },
    state: {
      pages: 0,
      feedPayloads: 0,
      feedSeen: false,
      oldest: 0,
      caughtUp: false,
      reelsSent: 0,
      followingTried: false,
      followingTab: false,
      lastError: ''
    }
  };

  E.log = function () {
    if (!E.cfg.debug) return;
    try {
      var args = Array.prototype.slice.call(arguments);
      args.unshift('[simpleinsta]');
      console.log.apply(console, args);
    } catch (e) { /* ignore */ }
  };

  /**
   * Diagnostic channel to the debug sheet.
   *
   * Deliberately not gated behind `debug`: when the policy misbehaves on a real
   * device this is the only way to find out why, and asking someone to rebuild
   * with a flag set is not a debugging strategy.
   */
  E.logEvent = function (name, detail) {
    try {
      E.post('log', { name: String(name), detail: String(detail == null ? '' : detail) });
    } catch (e) { /* ignore */ }
  };

  E.fail = function (where, err) {
    E.state.lastError = where + ': ' + (err && err.message ? err.message : String(err));
    E.log(E.state.lastError);
  };

  /* ------------------------------------------------------------------ *
   * Bridge to Kotlin
   * ------------------------------------------------------------------ */

  E.post = function (type, payload) {
    try {
      var msg = { type: type, v: E.version };
      if (payload) {
        for (var k in payload) {
          if (Object.prototype.hasOwnProperty.call(payload, k)) msg[k] = payload[k];
        }
      }
      var raw = JSON.stringify(msg);
      if (window.simpleinstaBridge && typeof window.simpleinstaBridge.postMessage === 'function') {
        window.simpleinstaBridge.postMessage(raw);
      }
    } catch (e) {
      E.fail('post', e);
    }
  };

  /* ------------------------------------------------------------------ *
   * Structural walkers
   * ------------------------------------------------------------------ */

  /**
   * Depth-first walk over every object in a JSON tree.
   * Iterative (no recursion) so a pathological payload cannot blow the stack.
   */
  E.walk = function (root, visit) {
    if (!root || typeof root !== 'object') return;
    var stack = [root];
    var seen = new Set();
    var guard = 0;

    while (stack.length && guard < 300000) {
      guard++;
      var node = stack.pop();
      if (!node || typeof node !== 'object' || seen.has(node)) continue;
      seen.add(node);

      try {
        visit(node);
      } catch (e) {
        E.fail('walk', e);
      }

      for (var key in node) {
        if (!Object.prototype.hasOwnProperty.call(node, key)) continue;
        var child = node[key];
        if (child && typeof child === 'object') stack.push(child);
      }
    }
  };

  /** A media object that carries a playable video: the shape we care about. */
  E.isVideoMedia = function (o) {
    if (!o || typeof o !== 'object') return false;
    var code = o.code || o.shortcode;
    if (typeof code !== 'string' || code.length < 5) return false;
    var versions = o.video_versions;
    if (!Array.isArray(versions) || !versions.length) return false;
    return !!(versions[0] && typeof versions[0].url === 'string');
  };

  E.timestampOf = function (o) {
    var candidates = [o.taken_at, o.taken_at_timestamp, o.device_timestamp, o.timestamp];
    for (var i = 0; i < candidates.length; i++) {
      var t = candidates[i];
      if (typeof t === 'number' && t > 1000000000) return t;
    }
    return 0;
  };

  /** Monetisation / recommendation markers. Structural, not positional. */
  var SUGGESTED_TEXT = /suggested|recommended|because you|popular|trending/i;

  E.isPromoted = function (o) {
    if (!o || typeof o !== 'object') return false;

    if (o.ad_id) return true;
    if (o.is_ad === true) return true;
    if (o.product_type === 'ad') return true;
    if (o.is_suggested === true) return true;
    if (o.explore_story) return true;
    if (o.injected) return true;
    if (o.brs_severity) return true;

    if (typeof o.social_context === 'string' && SUGGESTED_TEXT.test(o.social_context)) return true;
    if (typeof o.inventory_source === 'string' && SUGGESTED_TEXT.test(o.inventory_source)) return true;
    if (typeof o.connection_id === 'string' && /suggested|explore|recs/i.test(o.connection_id)) return true;

    // The strongest signal there is: Instagram telling us outright that this
    // author is not in your following list. Guarded against your own posts,
    // where `following` is false because you do not follow yourself.
    var user = o.user || o.owner;
    if (user && user.is_self !== true && user.friendship_status &&
        user.friendship_status.following === false) {
      return true;
    }

    return false;
  };

  /**
   * Flip every paginated connection in the tree to "no next page".
   * This is what actually ends the feed: Relay derives hasNext from
   * pageInfo.hasNextPage, so loadNext() becomes a no-op.
   */
  E.killPagination = function (json) {
    var touched = 0;
    E.walk(json, function (o) {
      var pi = o.page_info;
      if (pi && pi.has_next_page === true) {
        pi.has_next_page = false;
        pi.end_cursor = null;
        touched++;
      }
      if (o.has_next_page === true && Object.prototype.hasOwnProperty.call(o, 'end_cursor')) {
        o.has_next_page = false;
        o.end_cursor = null;
        touched++;
      }
    });
    return touched;
  };

  /* ------------------------------------------------------------------ *
   * Request classification
   * ------------------------------------------------------------------ */

  E.isInstagramUrl = function (url) {
    return typeof url === 'string' && url.indexOf('instagram.com') !== -1;
  };

  /**
   * Resolve a possibly-relative URL against the current document.
   *
   * This was the bug that made the whole policy a no-op on device: Instagram
   * issues same-origin requests as `fetch('/graphql/query')`, so the raw string
   * never contained "instagram.com", every response was skipped, and the debug
   * sheet said "no feed response yet" while everything else looked healthy.
   */
  E.resolve = function (url) {
    if (!url) return '';
    try {
      return new URL(String(url), location.href).href;
    } catch (e) {
      return String(url);
    }
  };

  E.isGraphql = function (url) {
    return typeof url === 'string' &&
      (url.indexOf('/graphql') !== -1 || url.indexOf('/api/graphql') !== -1);
  };

  /** On the home feed only. Post pages paginate comments, which we leave alone. */
  E.onFeedRoute = function () {
    var p = location.pathname;
    return p === '/' || p === '' || p === '/feed/';
  };

  /** On any direct-message route. */
  E.onDirectRoute = function () {
    return location.pathname.indexOf('/direct') === 0;
  };

  E.hasCursor = function (body) {
    if (typeof body !== 'string') return false;
    return body.indexOf('"after"') !== -1 || body.indexOf('after=') !== -1;
  };

  /**
   * Should we parse this response at all?
   *
   * Deliberately not limited to GraphQL. The first version only inspected
   * `/graphql`, so if Instagram served the feed from `/api/v1/feed/timeline/`
   * nothing was cleaned and suggested posts survived. Route-gated to keep the
   * cost down: on the feed we inspect everything data-shaped, elsewhere only DM
   * traffic.
   */
  E.shouldInspect = function (url) {
    var resolved = E.resolve(url);
    if (!E.isInstagramUrl(resolved)) return false;

    // No route gate. It was added to shorten a race window and it broke feed
    // filtering whenever a response landed mid-navigation.

    if (resolved.indexOf('direct') !== -1) return true;
    if (E.onDirectRoute()) return true;

    if (!E.onFeedRoute()) return false;
    return E.isGraphql(resolved) || resolved.indexOf('/api/v1/') !== -1;
  };

  /** Cheap structural test for a DM payload, before the expensive walk. */
  E.hasDirectShape = function (json) {
    var found = false;
    E.walk(json, function (o) {
      if (found) return;
      if (typeof o.item_type === 'string') found = true;
    });
    return found;
  };

  /** True if any connection in this payload still claims another page. */
  E.anyHasNext = function (json) {
    var found = false;
    E.walk(json, function (o) {
      if (found) return;
      var pi = o.page_info;
      if (pi && pi.has_next_page === true) found = true;
      else if (o.has_next_page === true) found = true;
    });
    return found;
  };

  /**
   * Does this payload contain a paginated connection at all?
   *
   * This gate matters more than it looks. Instagram's logged-out landing page
   * still returns JSON with page_info in it. Without this check we treated an
   * empty payload as "end of feed", reported caught-up, and moved the watermark
   * to now — which then hid the real feed after signing in.
   */
  E.hasFeedShape = function (json) {
    var found = false;
    E.walk(json, function (o) {
      if (found) return;
      if (o.page_info && typeof o.page_info === 'object') found = true;
      else if (typeof o.has_next_page === 'boolean') found = true;
    });
    return found;
  };

  /**
   * Structural fingerprint of a payload: the top-level and second-level key
   * names only. No values, so nothing personal ends up in the debug sheet — but
   * enough to see what Instagram actually sent when our cleaner finds nothing.
   */
  E.shapeOf = function (json) {
    try {
      if (!json || typeof json !== 'object') return String(json);
      var outer = Object.keys(json).slice(0, 5).join(',');
      var inner = json.data && typeof json.data === 'object'
        ? Object.keys(json.data).slice(0, 5).join(',')
        : '';
      return inner ? outer + ' > ' + inner : outer;
    } catch (e) {
      return '';
    }
  };

  /* ------------------------------------------------------------------ *
   * Dispatcher
   * ------------------------------------------------------------------ */

  /**
   * @returns {boolean} true if the JSON was mutated and must be re-serialised.
   */
  E.onResponse = function (url, json, meta) {
    var changed = false;
    try {
      // Page counting lives inside processFeed, not here: a page only counts
      // when it actually contained posts. Counting requests made the cap fire on
      // config responses.
      // No `meta.feed` requirement. Gating on the current route meant a feed
      // response arriving just after a navigation was skipped entirely — no
      // filtering, no cap — which is exactly the regression that let suggested
      // posts back in. `processFeed` self-gates on payload shape instead.
      if (typeof E.processFeed === 'function') {
        changed = E.processFeed(json, meta) || changed;
      }

      // Route-aware, not URL-only: thread payloads can arrive over GraphQL.
      if ((url.indexOf('direct') !== -1 || E.onDirectRoute()) &&
          typeof E.processDirect === 'function') {
        changed = E.processDirect(json) || changed;
      }
    } catch (e) {
      E.fail('onResponse', e);
    }
    return changed;
  };

  /* ------------------------------------------------------------------ *
   * fetch patch
   * ------------------------------------------------------------------ */

  var originalFetch = window.fetch;

  if (typeof originalFetch === 'function') {
    window.fetch = function (input, init) {
      var url = '';
      var body = '';

      try {
        url = E.resolve(typeof input === 'string' ? input : (input && input.url) || '');
        if (init && typeof init.body === 'string') body = init.body;
        else if (init && init.body && typeof init.body.toString === 'function' && init.body instanceof URLSearchParams) {
          body = init.body.toString();
        }
      } catch (e) { /* ignore */ }

      var promise = originalFetch.apply(this, arguments);

      // Never touch a mutation. A like, save, follow or repost has to reach
      // Instagram exactly as it was built — rewriting the response is how an
      // optimistic update ends up showing "liked" while the server disagrees.
      // Logged too, because "it looks liked but isn't" needs evidence.
      if (body && /mutation/i.test(body)) {
        return promise.then(function (response) {
          try {
            E.logEvent('mutation', response.status + ' ' + url.replace(location.origin || '', ''));
          } catch (e) { /* ignore */ }
          return response;
        });
      }

      if (!E.shouldInspect(url)) return promise;

      var feed = E.onFeedRoute();

      return promise.then(function (response) {
        if (!response || !response.ok) return response;

        var ct = '';
        try { ct = response.headers.get('content-type') || ''; } catch (e) { /* ignore */ }
        if (ct.indexOf('json') === -1) return response;

        return response.clone().text().then(function (text) {
          // Cheapest possible filter, and the biggest single win for
          // responsiveness: most responses on this page are not feed pages or
          // DM threads. Parsing, walking and re-serialising all of them was
          // doing real work for nothing.
          if (text.indexOf('"page_info"') === -1 && text.indexOf('"item_type"') === -1) {
            return response;
          }

          var json;
          try {
            json = JSON.parse(text);
          } catch (e) {
            return response;
          }

          var changed;
          try {
            changed = E.onResponse(url, json, { feed: feed, pagination: E.hasCursor(body) });
          } catch (e) {
            E.fail('onResponse-throw', e);
            return response;
          }

          if (!changed) return response;

          try {
            var rewritten = new Response(JSON.stringify(json), {
              status: response.status,
              statusText: response.statusText,
              headers: response.headers
            });
            try {
              Object.defineProperty(rewritten, 'url', { value: response.url });
            } catch (e) { /* ignore */ }
            return rewritten;
          } catch (e) {
            E.fail('reserialize', e);
            return response;
          }
        }).catch(function () {
          return response;
        });
      });
    };
  }

  /* ------------------------------------------------------------------ *
   * XHR patch (secondary path; IG web is mostly fetch/Relay)
   * ------------------------------------------------------------------ */

  try {
    var proto = XMLHttpRequest.prototype;
    var textDesc = Object.getOwnPropertyDescriptor(proto, 'responseText');
    var responseDesc = Object.getOwnPropertyDescriptor(proto, 'response');
    var originalOpen = proto.open;
    var originalSend = proto.send;

    proto.open = function (method, url) {
      try { this.__simpleinstaUrl = E.resolve(url); } catch (e) { /* ignore */ }
      return originalOpen.apply(this, arguments);
    };

    proto.send = function () {
      try {
        var url = this.__simpleinstaUrl || '';
        var self = this;

        if (!E.shouldInspect(url)) {
          return originalSend.apply(this, arguments);
        }

        var feed = E.onFeedRoute();
        var settled = false;
        var text = null;
        var object = null;
        var changed = false;

        function settle() {
          if (settled || self.readyState !== 4) return;
          settled = true;
          try {
            if (!textDesc || !textDesc.get) return;
            var raw = textDesc.get.call(self);
            if (typeof raw !== 'string' || !raw.length) return;
            var json = JSON.parse(raw);
            changed = E.onResponse(url, json, { feed: feed, pagination: false });
            if (changed) text = JSON.stringify(json);
          } catch (e) {
            E.fail('xhr-settle', e);
          }
        }

        if (textDesc && textDesc.get) {
          Object.defineProperty(self, 'responseText', {
            configurable: true,
            get: function () {
              var raw;
              try { raw = textDesc.get.call(self); } catch (e) { return ''; }
              if (self.readyState !== 4) return raw;
              settle();
              return text !== null ? text : raw;
            }
          });
        }

        if (responseDesc && responseDesc.get) {
          Object.defineProperty(self, 'response', {
            configurable: true,
            get: function () {
              var raw;
              try { raw = responseDesc.get.call(self); } catch (e) { return null; }
              if (self.readyState !== 4) return raw;
              settle();
              if (!changed) return raw;
              if (object === null && text !== null) {
                try { object = JSON.parse(text); } catch (e) { return raw; }
              }
              return object !== null ? object : raw;
            }
          });
        }
      } catch (e) {
        E.fail('xhr-send', e);
      }

      return originalSend.apply(this, arguments);
    };
  } catch (e) {
    E.fail('xhr-install', e);
  }

  /* ------------------------------------------------------------------ *
   * Link firewall
   *
   * Two things must never reach the browser:
   *   1. Instagram's app handoff (`intent://instagram.com/_n/mainfeed`). That is
   *      the bypass this product exists to prevent, and letting it through
   *      produces net::ERR_UNKNOWN_URL_SCHEME.
   *   2. Any link into Instagram's reel pager (`/reel/<code>`), including a reel
   *      a friend sent. That pager is the doomscroll: when the sent reel ends it
   *      rolls straight into recommendations. Reels go to the native queue,
   *      which has a Next button and an end.
   * ------------------------------------------------------------------ */

  var HANDOFF_SCHEME = /^(intent|instagram|fb|fb-messenger|market|whatsapp):/i;

  /** `/reel/<code>` or `/reels/<code>` -> code. Empty for music pages. */
  function reelCode(href) {
    var match = /\/reels?\/([A-Za-z0-9_-]{5,})/.exec(href || '');
    if (!match) return '';
    return match[1].toLowerCase() === 'audio' ? '' : match[1];
  }

  try {
    document.addEventListener('click', function (event) {
      try {
        var node = event.target;
        while (node && node !== document && node.tagName !== 'A') {
          node = node.parentNode;
        }

        // Everything a tap in DMs lands on is worth recording: if the reel
        // bubble turns out not to be an anchor, this is how we find that out.
        if (location.pathname.indexOf('/direct') === 0) {
          E.logEvent('tap', node && node.tagName === 'A' ? (node.getAttribute('href') || '(no href)') : '(not an anchor)');
        }

        if (!node || node.tagName !== 'A') return;

        var href = node.getAttribute('href') || '';

        if (HANDOFF_SCHEME.test(href)) {
          event.preventDefault();
          event.stopPropagation();
          E.post('blocked', { label: 'The Instagram app' });
          return;
        }

        var code = reelCode(href);
        if (code) {
          event.preventDefault();
          event.stopPropagation();
          E.logEvent('reel.link', code);
          E.post('openReel', { shortcode: code });
        }
      } catch (e) {
        E.fail('link-firewall', e);
      }
    }, true);

    var originalWindowOpen = window.open;
    if (typeof originalWindowOpen === 'function') {
      window.open = function (url) {
        if (typeof url === 'string' && HANDOFF_SCHEME.test(url)) {
          E.post('blocked', { label: 'The Instagram app' });
          return null;
        }
        return originalWindowOpen.apply(this, arguments);
      };
    }
  } catch (e) {
    E.fail('link-firewall-install', e);
  }

  /* ------------------------------------------------------------------ *
   * Reel modal guard
   *
   * Not every shared reel is an anchor. Sometimes Instagram opens it in a
   * dialog without changing the URL, which the click firewall and the native
   * route block both miss — and that dialog is still a vertical pager. If a
   * dialog containing a video AND a reel link appears inside DMs, send it to the
   * queue and close it. Requiring the reel link is what keeps this from firing
   * on ordinary video posts.
   * ------------------------------------------------------------------ */

  try {
    if (typeof MutationObserver !== 'undefined' && document.documentElement) {
      var modalTimer = null;

      var inspectModal = function () {
        try {
          if (location.pathname.indexOf('/direct') !== 0) return;

          var dialog = document.querySelector('div[role="dialog"]');
          if (!dialog || !dialog.querySelector('video')) return;

          var link = dialog.querySelector('a[href*="/reel/"]');
          if (!link) return;

          E.post('openReel', { shortcode: reelCode(link.getAttribute('href')) });

          var close = dialog.querySelector('[aria-label="Close"]');
          if (close && typeof close.click === 'function') close.click();
        } catch (e) {
          E.fail('reel-modal-inspect', e);
        }
      };

      new MutationObserver(function () {
        if (modalTimer) return;
        modalTimer = setTimeout(function () {
          modalTimer = null;
          inspectModal();
        }, 900);
      }).observe(document.documentElement, { childList: true, subtree: true });
    }
  } catch (e) {
    E.fail('reel-modal-install', e);
  }

  /* ------------------------------------------------------------------ *
   * URL-level reel guard
   *
   * The click firewall assumes a shared reel is an <a href="/reel/...">. On
   * device it clearly was not, because the pager still opened. So guard the
   * layer a SPA cannot avoid: its own router.
   *
   * Any transition to /reel/<code> or /reels/<code> is reversed and handed to
   * the queue. The interval is a backstop for route changes that bypass
   * pushState (replaceState, popstate, or a fresh document).
   * ------------------------------------------------------------------ */

  try {
    var REEL_PATH = /^\/reels?\/([A-Za-z0-9_-]{5,})/;
    var leavingReel = false;
    var lastSafe = location.pathname + location.search;

    function reelFromPath(path) {
      var match = REEL_PATH.exec(path || '');
      if (!match) return '';
      return match[1].toLowerCase() === 'audio' ? '' : match[1];
    }

    function leaveReel(code) {
      if (leavingReel) return;
      leavingReel = true;

      E.logEvent('reel.url', code || location.pathname);
      E.post('openReel', { shortcode: code || '' });

      // Put the route back without a reload, so the reel view unmounts.
      try {
        history.replaceState(null, '', lastSafe || '/');
        window.dispatchEvent(new PopStateEvent('popstate'));
      } catch (e) {
        E.fail('leave-reel', e);
      }

      setTimeout(function () { leavingReel = false; }, 1000);
    }

    if (typeof history !== 'undefined' && typeof history.pushState === 'function') {
      var rawPushState = history.pushState;
      history.pushState = function (state, title, url) {
        var result = rawPushState.apply(this, arguments);
        try {
          var path = url ? E.resolve(url).replace(location.origin, '') : location.pathname;
          var code = reelFromPath(path);
          if (code) leaveReel(code);
        } catch (e) {
          E.fail('push-guard', e);
        }
        return result;
      };
    }

    if (typeof setInterval !== 'undefined') {
      setInterval(function () {
        try {
          var code = reelFromPath(location.pathname);
          if (code) leaveReel(code);
          else lastSafe = location.pathname + location.search;
        } catch (e) { /* ignore */ }
      }, 1000);
    }
  } catch (e) {
    E.fail('reel-url-install', e);
  }

  /* ------------------------------------------------------------------ *
   * Reel shield
   *
   * The on-device log said `tap: (not an anchor)`, and the viewer was a
   * full-screen overlay that never changed the URL. Both earlier defences — the
   * click firewall and the router guard — were therefore blind to it.
   *
   * So stop trying to detect the *tap* and detect the *result*: a video filling
   * the viewport inside DMs. That is a reel pager. Lay a transparent,
   * touch-consuming layer over it. Swipes never reach Instagram, so it cannot
   * scroll, and the only exits are ours.
   *
   * This is the one guarantee in the app that does not depend on Instagram's
   * markup: it depends on the video being big.
   * ------------------------------------------------------------------ */

  /* ------------------------------------------------------------------ *
   * Reel scroll freeze
   *
   * The first version laid a full-screen touch-eating layer over the viewer.
   * That stopped scrolling, but it also killed every button on the reel — like,
   * comment, share, save, follow, tap-to-pause — which is not a trade worth
   * making. Scrolling is the problem, not interaction.
   *
   * So: cancel *drags* and leave *taps* alone. A tap has no movement, so
   * `touchmove` never fires for it and every control keeps working. The pager's
   * own scroll container is also pinned to `overflow-y: hidden`, so even a
   * gesture that slips past cannot move it.
   * ------------------------------------------------------------------ */

  var frozenScroller = null;
  var frozenOverflow = '';
  var freezeActive = false;
  var freezeLogged = false;

  function isFullScreen(video, width, height) {
    try {
      var rect = video.getBoundingClientRect();
      return rect.width >= width * 0.9 && rect.height >= height * 0.55;
    } catch (e) {
      return false;
    }
  }

  function fullScreenVideos() {
    var out = [];
    try {
      var width = window.innerWidth || 0;
      var height = window.innerHeight || 0;
      if (!width || !height) return out;

      var videos = document.querySelectorAll('video');
      for (var i = 0; i < videos.length; i++) {
        if (isFullScreen(videos[i], width, height)) out.push(videos[i]);
      }
    } catch (e) { /* ignore */ }
    return out;
  }

  /**
   * Fill the screen and make it audible.
   *
   * Reels are portrait inside a container that is often not, so the default
   * `contain` leaves bars. `cover` crops instead, and the parent's padding is
   * cleared because that is usually where the letterboxing comes from.
   */
  /**
   * Fit the video to its box and make it audible. Applied once per element.
   *
   * Two earlier attempts to force edge-to-edge both broke the overlay: resizing
   * ancestors to `innerWidth` pushed the engagement rail off-screen, and a
   * `scale()` transform widened the document and did the same. Instagram's web
   * viewer frames the video itself; overriding that is not worth losing the
   * buttons, so this now only normalises the fit and leaves geometry alone.
   *
   * No getBoundingClientRect here on purpose — this runs on a timer, and layout
   * reads on a timer are exactly what makes a WebView feel sluggish.
   */
  function fitVideo(video) {
    try {
      if (video.getAttribute('data-simpleinsta-fit') !== '1') {
        video.setAttribute('data-simpleinsta-fit', '1');
        // Fill (cover, crops the edges) or whole (contain, leaves bars). The
        // measurements said the width already matches the viewport, so the only
        // remaining question is which trade the user prefers.
        video.style.setProperty(
          'object-fit',
          E.cfg.reelFit === 'contain' ? 'contain' : 'cover',
          'important'
        );
        // Geometry is left entirely to Instagram.
        //
        // Everything I tried here — forcing `width: 100vw`, zeroing padding,
        // widening ancestors, `translateX` nudges, `scale()` — either shifted the
        // frame off-centre or moved elements it was not aimed at. Each fix needed
        // another fix to look right, which is the definition of the wrong
        // approach. The reel now renders exactly as the web app builds it.
        //
        // What remains is the one thing that is genuinely ours: the fit mode the
        // user picked, and unmuting.
        var frame = video.parentElement;

        // WebView mutes media that starts without a gesture, so unmute once —
        // once, so a later press of Instagram's own mute button sticks.
        try {
          video.muted = false;
          video.removeAttribute('muted');
          if (!video.volume) video.volume = 1;
        } catch (e) { /* ignore */ }

        // Offsets matter more than sizes here: a rail is clipped because of where
        // its containing block *starts*, not how wide the video is.
        try {
          var doc = document.documentElement;
          var vr = video.getBoundingClientRect();
          var fr = frame ? frame.getBoundingClientRect() : null;
          E.logEvent(
            'reel.geom',
            'video ' + Math.round(vr.width) + 'x' + Math.round(vr.height) +
            '@' + Math.round(vr.left) +
            ' frame ' + (fr ? Math.round(fr.width) + 'x' + Math.round(fr.height) +
              '@' + Math.round(fr.left) : '?') +
            ' inner ' + (window.innerWidth || 0) +
            ' client ' + doc.clientWidth +
            ' scroll ' + doc.scrollWidth +
            ' visual ' + (window.visualViewport
              ? Math.round(window.visualViewport.width) + 'x' +
                Math.round(window.visualViewport.height) +
                '@' + window.visualViewport.scale.toFixed(2)
              : '?')
          );
        } catch (e) { /* ignore */ }

        // The reel viewer sizes its overlay by the video's aspect ratio at full
        // height, which on a tall phone produces a box wider than the screen
        // (measured: 503 px at x=16 in a 390 px viewport). Anything anchored to
        // that box's right edge — the like/comment rail — lands off-screen.
        //
        // Report only. Clamping these was layout surgery on React-owned nodes and
        // never fixed the rail.
        try {
          var limit = window.innerWidth || 0;
          var offenders = [];
          var nodes = document.querySelectorAll('div, ul, section');

          for (var i = 0; i < nodes.length && i < 600; i++) {
            var node = nodes[i];
            var box = node.getBoundingClientRect();
            if (box.width <= 8 || box.right <= limit + 1) continue;

            if (offenders.length < 4) {
              offenders.push(Math.round(box.left) + '..' + Math.round(box.right));
            }

            // No mutation here any more. Rewriting `max-width` across arbitrary
            // boxes is layout surgery on React-owned nodes — the most plausible
            // trigger for "Something went wrong" when opening a story — and it
            // never fixed the rail. This loop reports; it does not change.  
          }

          // Belt and braces: a tall, narrow box flush against the right edge is
          // the action rail. Even when nothing overflows the page, its counts sit
          // hard against the screen edge with no inset, so nudge it in.
          // The rail-nudge pass is gone. It matched 25 elements on device
          // (`reel.rail: inset 25`) and translated all of them 12 px left, which
          // shifted the layout it was supposed to fix. The rail's inset comes
          // from Instagram's own padding, which we now leave alone.

          E.logEvent('reel.overflow', offenders.length ? offenders.join(' ') : 'none');
        } catch (e) { /* ignore */ }
      }

      // Nothing periodic here on purpose.
      //
      // This used to re-unmute and re-play on every tick. That undid the user's
      // own mute press within 1.5 seconds, and `play()` ran on *every*
      // full-screen video — the pager keeps neighbours mounted, so several
      // started at once and the audio stacked. Playback belongs to Instagram.
    } catch (e) { /* ignore */ }
  }

  /** The nearest ancestor that actually scrolls — the pager, not the page. */
  function scrollerFor(video) {
    try {
      var element = video.parentElement;
      var guard = 0;
      while (element && element !== document.body && element !== document.documentElement && guard++ < 20) {
        var style = window.getComputedStyle(element);
        var overflowY = style.overflowY;
        if ((overflowY === 'auto' || overflowY === 'scroll') &&
            element.scrollHeight > element.clientHeight + 4) {
          return element;
        }
        element = element.parentElement;
      }
    } catch (e) { /* ignore */ }
    return null;
  }

  /**
   * Close the reel viewer.
   *
   * Instagram's viewer shows a back arrow rather than a labelled Close, so both
   * are tried. `history.back()` is the fallback only: when the viewer is an
   * overlay rather than a route, going back would leave the conversation.
   */
  function closeViewer() {
    var labels = ['Close', 'close', 'Back', 'back'];
    for (var i = 0; i < labels.length; i++) {
      try {
        var control = document.querySelector('[aria-label="' + labels[i] + '"]');
        if (control && typeof control.click === 'function') {
          control.click();
          return;
        }
      } catch (e) { /* ignore */ }
    }
    try { history.back(); } catch (e) { /* ignore */ }
  }

  // Reachable from Kotlin, so the system back button closes the reel first
  // instead of needing two presses.
  window.__ENOUGH_CLOSE__ = function () {
    try { closeViewer(); return true; } catch (e) { return false; }
  };

  function freezeScroller(video) {
    var element = scrollerFor(video);
    if (!element || element === frozenScroller) return;
    thawScroller();
    frozenScroller = element;
    frozenOverflow = element.style.overflowY;
    element.style.setProperty('overflow-y', 'hidden', 'important');
  }

  function thawScroller() {
    if (!frozenScroller) return;
    try { frozenScroller.style.overflowY = frozenOverflow; } catch (e) { /* ignore */ }
    frozenScroller = null;
  }

  /** Nearest scrollable ancestor of the touched node, if any. */
  var lastScrollTarget = null;
  var lastScrollResult = null;

  function scrollableFrom(target) {
    // A drag produces dozens of touchmove events against the same node. Walking
    // getComputedStyle on each one is a measurable jank source.
    if (target === lastScrollTarget) return lastScrollResult;
    lastScrollTarget = target;
    lastScrollResult = computeScrollable(target);
    return lastScrollResult;
  }

  function computeScrollable(target) {
    try {
      var element = target;
      var guard = 0;
      while (element && element !== document.body && element !== document.documentElement && guard++ < 12) {
        var style = window.getComputedStyle(element);
        var overflowY = style.overflowY;
        if ((overflowY === 'auto' || overflowY === 'scroll') &&
            element.scrollHeight > element.clientHeight + 4) {
          return element;
        }
        element = element.parentElement;
      }
    } catch (e) { /* ignore */ }
    return null;
  }

  /**
   * A non-passive `touchmove` listener on `document` makes the compositor wait
   * for the handler on *every* scroll, even when the handler does nothing. So it
   * only exists while a reel is open — the rest of the app scrolls at full speed.
   */
  var freezeListening = false;

  function onFreezeTouchMove(event) {
    if (!freezeActive) return;

    // Drags only: taps never reach this handler, which is what keeps the reel's
    // own controls usable. And a drag inside a real panel — the comment sheet,
    // the share sheet — is scrolling that panel, not the pager, so it must be
    // allowed or comments cannot be read past the fold.
    var scroller = scrollableFrom(event.target);
    if (scroller && scroller !== frozenScroller) return;

    event.preventDefault();
  }

  function listenForFreeze() {
    if (freezeListening) return;
    try {
      document.addEventListener('touchmove', onFreezeTouchMove, { passive: false });
      freezeListening = true;
    } catch (e) {
      E.fail('freeze-listen', e);
    }
  }

  function stopListeningForFreeze() {
    if (!freezeListening) return;
    try {
      document.removeEventListener('touchmove', onFreezeTouchMove);
      freezeListening = false;
    } catch (e) { /* ignore */ }
  }

  try {
    if (typeof setInterval !== 'undefined') {
      setInterval(function () {
        try {
          if (!E.onDirectRoute()) {
            if (freezeActive) {
              E.post('reelOverlay', { open: false });
              stopListeningForFreeze();
            }
            freezeActive = false;
            thawScroller();
            return;
          }

          var videos = fullScreenVideos();
          if (!videos.length) {
            if (freezeActive) {
              E.post('reelOverlay', { open: false });
              stopListeningForFreeze();
            }
            freezeActive = false;
            thawScroller();
            return;
          }

          if (!freezeActive) {
            E.post('reelOverlay', { open: true });
            listenForFreeze();
          }
          freezeActive = true;
          for (var i = 0; i < videos.length; i++) {
            fitVideo(videos[i]);
            freezeScroller(videos[i]);
          }

          if (!freezeLogged) {
            freezeLogged = true;
            E.logEvent('freeze', 'on');
          }
        } catch (e) { /* ignore */ }
      }, 400);
    }
  } catch (e) {
    E.fail('freeze-watch', e);
  }

  E.log('runtime ready', E.version);
})();
