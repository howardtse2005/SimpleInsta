/*
 * 10_feed.js — make the home feed finite, and make it yours.
 *
 * Two independent stop conditions, whichever fires first:
 *   1. Watermark  — the page contains a post older than your last completed visit.
 *   2. Page cap   — we have already fetched N pages this session.
 *
 * Either way we flip every paginated connection to "no next page". Relay reads
 * pageInfo.hasNextPage, so loadNext() becomes a no-op and Instagram renders its
 * own end-of-feed card. We never touch scroll containers or gestures.
 *
 * Filtering note: the first version only looked at arrays named `edges`, which
 * missed feed containers called `items` or `medias`, and it had no way to tell
 * "you don't follow this person" — so suggested reels survived. Both are fixed
 * here: any array holding media-shaped objects is a candidate, and the promoted
 * check now reads friendship_status directly.
 */
(function () {
  var E = window.__SIMPLEINSTA__;
  if (!E) return;

  /** Does this node look like a feed post? */
  function looksLikePost(n) {
    if (!n || typeof n !== 'object') return false;
    if (typeof n.code !== 'string' || n.code.length < 5) return false;
    return !!(n.user || n.owner || n.image_versions2 || n.video_versions || n.carousel_media);
  }

  /**
   * Walk every array in the response. For each array that holds media-shaped
   * objects, drop promoted/suggested entries and collect the oldest timestamp.
   *
   * Structural on purpose: it does not matter whether the container is called
   * edges, items, medias, or feed_items.
   */
  function cleanContainers(json) {
    var removed = 0;
    var first = null;
    var kept = 0;
    var oldest = 0;

    E.walk(json, function (o) {
      for (var key in o) {
        if (!Object.prototype.hasOwnProperty.call(o, key)) continue;

        var arr = o[key];
        if (!Array.isArray(arr) || !arr.length) continue;

        // Cheap pre-check: only treat this as a media container if at least one
        // element looks like a post. Avoids touching comment/like arrays.
        var hasMedia = false;
        for (var i = 0; i < arr.length; i++) {
          var candidate = arr[i];
          if (looksLikePost(candidate && (candidate.node || candidate))) {
            hasMedia = true;
            break;
          }
        }
        if (!hasMedia) continue;

        var before = arr.length;

        for (var j = arr.length - 1; j >= 0; j--) {
          var element = arr[j];
          var node = element && (element.node || element);
          if (!looksLikePost(node)) continue;

          if (E.isPromoted(node)) {
            arr.splice(j, 1);
            removed++;
            continue;
          }

          if (!first) first = node;

          var ts = E.timestampOf(node);
          if (ts) oldest = oldest ? Math.min(oldest, ts) : ts;
          kept++;
        }

        // Keep the cursor honest.
        //
        // `page_info.end_cursor` still names the last item of the *original*
        // page. Once that item has been spliced out, the next page request asks
        // for something that no longer exists and Instagram answers with its
        // error screen — "Something went wrong" the moment you scroll.
        if (arr.length !== before && o.page_info && typeof o.page_info === 'object') {
          var last = arr[arr.length - 1];
          if (last && typeof last.cursor === 'string') {
            o.page_info.end_cursor = last.cursor;
          } else {
            // No usable cursor on the survivors. Sending the old one would name a
            // post we deleted, and Instagram answers that with its error screen —
            // which is what appeared while sitting idle on the feed, when the app
            // prefetches the next page on its own. Close pagination instead: a
            // shorter feed is a far better failure than a broken one.
            o.page_info.has_next_page = false;
          }
        }
      }
    });

    return { removed: removed, kept: kept, oldest: oldest, first: first };
  }

  /**
   * Best effort, zero API cost: if the web build offers a chronological
   * "Following" tab, select it once. It is the only authoritative way to say
   * "people I follow" without calling Instagram's private endpoints.
   */
  function tryFollowingTab() {
    if (E.state.followingTried) return;
    E.state.followingTried = true;

    try {
      var tabs = document.querySelectorAll('[role="tab"], [role="tablist"] a');
      for (var i = 0; i < tabs.length; i++) {
        var text = (tabs[i].textContent || '').trim().toLowerCase();
        if (text === 'following') {
          tabs[i].click();
          E.state.followingTab = true;
          E.post('followingTab', { found: true });
          return;
        }
      }
      E.post('followingTab', { found: false });
    } catch (e) {
      E.fail('following-tab', e);
    }
  }

  E.processFeed = function (json) {
    // Cleaning always runs: suggested modules often arrive without a page_info,
    // and stripping them is desirable regardless of shape.
    var result = cleanContainers(json);
    var changed = result.removed > 0;

    // One-off diagnostic. `kept 27 · removed 0` means posts are being read but
    // nothing is dropped, so none of the promoted markers exist in this build.
    // These lines name the fields that would decide it, instead of guessing at
    // names for a fourth time.
    if (result.first && !E.state.keysLogged) {
      E.state.keysLogged = true;
      try {
        var sample = result.first;
        var keys = [];
        for (var k in sample) {
          if (Object.prototype.hasOwnProperty.call(sample, k)) keys.push(k);
        }
        E.logEvent('feed.keys', keys.slice(0, 26).join(','));

        var author = sample.user || sample.owner || {};
        var userKeys = [];
        for (var u in author) {
          if (Object.prototype.hasOwnProperty.call(author, u)) userKeys.push(u);
        }
        E.logEvent('feed.userkeys', userKeys.slice(0, 22).join(','));

        E.logEvent('feed.markers',
          'social=' + String(sample.social_context || '').slice(0, 40) +
          ' source=' + String(sample.inventory_source || '').slice(0, 30) +
          ' conn=' + String(sample.connection_id || '').slice(0, 30) +
          ' follow=' + (author.friendship_status
            ? JSON.stringify(author.friendship_status).slice(0, 60)
            : 'absent'));
      } catch (e) { /* ignore */ }
    }

    // Only feed-shaped payloads may drive the watermark or the page cap. The
    // logged-out landing page answers with JSON on the same route, and treating
    // it as "end of feed" moved the watermark to now — which then hid the real
    // feed after signing in.
    if (!E.hasFeedShape(json)) {
      if (changed) {
        E.post('feed', {
          kept: result.kept,
          removed: result.removed,
          pages: E.state.pages,
          payloads: E.state.feedPayloads,
          caughtUp: false
        });
      }
      return changed;
    }

    // First few empty payloads: record what shape they actually had. This is the
    // difference between "we are looking at the wrong response" and "we are
    // looking at the right response and misreading it".
    if (result.kept === 0) {
      E.state.shapeLogs = (E.state.shapeLogs || 0) + 1;
      if (E.state.shapeLogs <= 3) E.logEvent('feed.shape', E.shapeOf(json));
    }

    // `feedPayloads` counts only payloads that actually held posts. Counting
    // "feed-shaped" ones was unfixably leaky — Instagram's DM mailbox answers
    // match every structural test we could write, and reported "payloads 4,
    // kept 0" for a page that had no feed on it at all.
    var hasPosts = result.kept > 0;
    if (hasPosts) {
      E.state.feedSeen = true;
      E.state.feedPayloads++;
      E.state.pages++;
    }

    if (result.oldest) {
      E.state.oldest = E.state.oldest ? Math.min(E.state.oldest, result.oldest) : result.oldest;
    }

    var watermark = E.cfg.watermark || 0;
    var cap = E.cfg.feedPageCap || 2;

    var hitWatermark = watermark > 0 && result.oldest > 0 && result.oldest <= watermark;
    var hitCap = hasPosts && E.state.pages >= cap;

    var reason = '';

    if (hitWatermark) reason = 'watermark';
    else if (hitCap) reason = 'cap';
    // Only exhausted if we have actually seen posts in this document. Otherwise
    // "no posts" just means "this wasn't the feed".
    else if (!hasPosts && E.state.feedSeen && !E.anyHasNext(json)) reason = 'exhausted';

    // `|| E.state.caughtUp` matters: once the feed has ended, every later page
    // must be killed too. Previously only the page that *decided* it was over
    // got rewritten, so a follow-up response could restore `has_next_page` and
    // the feed would start scrolling again — the occasional "I can scroll" bug.
    if (reason || E.state.caughtUp) {
      var touched = E.killPagination(json);
      if (touched > 0) changed = true;

      if (reason && !E.state.caughtUp) {
        E.state.caughtUp = true;
        E.post('caughtUp', {
          reason: reason,
          pages: E.state.pages,
          oldest: result.oldest
        });
      }
    }

    E.post('feed', {
      kept: result.kept,
      removed: result.removed,
      oldest: result.oldest,
      pages: E.state.pages,
      payloads: E.state.feedPayloads,
      caughtUp: !!reason
    });

    if (E.state.pages === 1) {
      setTimeout(tryFollowingTab, 1200);
    }

    return changed;
  };
})();
