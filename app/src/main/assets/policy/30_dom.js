/*
 * 30_dom.js — remove entry points, and tidy what is left.
 *
 * Deliberately CSS-only for the hiding. Deleting React-managed nodes causes
 * "Failed to execute 'removeChild'" crashes when React reconciles, which is the
 * classic wrapper-app failure. Routes are blocked natively instead
 * (see UrlPolicy.kt); this file only makes the affordances disappear.
 */
(function () {
  var E = window.__SIMPLEINSTA__;
  if (!E) return;

  var RULES = [
    /* Explore: the algorithmic grid. */
    'a[href="/explore/"]',
    'a[href^="/explore/"]',

    /* Reels: the vertical pager. Singular /reel/<code>/ is left alone so
       shared singles can still be opened. */
    'a[href="/reels/"]',
    'a[href^="/reels/"]',

  ];

  // The stories-tray rule was removed. `div[role="group"]:has(> div >
  // a[href^="/stories/"])` matches any group containing a story link — which
  // includes the story *viewer* itself, not just the tray. Hiding the viewer's
  // container is a very plausible cause of "Something went wrong" when opening a
  // friend's story. The tray is cosmetic; the viewer is not.

  var CSS = RULES.join(',\n') + ' { display: none !important; }\n' +
    /* The reel viewer sizes its container by height, so a 9:16 video can end up
       wider than the screen. That widens the document and pushes anything
       anchored to the right — the like/comment rail — off the edge. Clamping
       overflow is what actually keeps the rail on screen. */
    'html, body { overflow-x: hidden !important; max-width: 100% !important; }\n' +
    'video { max-width: 100vw !important; }\n' +
    /* The rail sits at `right: 0` of whatever containing block Instagram chose.
       If that block is wider than the screen, every right-anchored control is
       clipped by exactly the difference. Clamping the app root is the only place
       that shrinks the containing block for all of them at once. */
    '#react-root, #react-root > div, #mount_0_0_9Y, body > div:first-child ' +
      '{ max-width: 100vw !important; }\n';

  function install() {
    try {
      if (document.querySelector('style[data-simpleinsta]')) return;
      var style = document.createElement('style');
      style.setAttribute('data-simpleinsta', '1');
      style.textContent = CSS;
      var parent = document.head || document.documentElement;
      if (parent) parent.appendChild(style);
    } catch (e) {
      E.fail('dom-install', e);
    }
  }

  /**
   * Lay the bottom nav out ourselves.
   *
   * Collapsing the hidden entries' wrappers was not enough — one slot stayed
   * empty, so the paper plane sat a third of the way from the right instead of
   * in the middle. Guessing at Instagram's flex rules is a losing game, so this
   * takes the container over: hide every child that holds no usable link, then
   * distribute what is left evenly. Three items, space-evenly, plane in the
   * middle by arithmetic rather than by hope.
   */
  function bottomHomeLink() {
    var links = document.querySelectorAll('a[href="/"]');
    var best = null;
    var lowest = -1;
    for (var i = 0; i < links.length; i++) {
      var rect = links[i].getBoundingClientRect();
      if (rect.top > lowest) {
        lowest = rect.top;
        best = links[i];
      }
    }
    return best;
  }

  function linksOf(node) {
    if (!node) return [];
    if (node.tagName === 'A') return [node];
    if (!node.querySelectorAll) return [];
    return Array.prototype.slice.call(node.querySelectorAll('a[href]'));
  }

  function layoutNav() {
    try {
      var home = bottomHomeLink();
      if (!home) return;

      var nav = home.parentElement;
      var guard = 0;
      while (nav && nav.querySelectorAll && nav.querySelectorAll('a[href]').length < 3 && guard++ < 4) {
        nav = nav.parentElement;
      }
      if (!nav || !nav.children) return;

      var children = Array.prototype.slice.call(nav.children);
      var kept = 0;

      for (var i = 0; i < children.length; i++) {
        var child = children[i];
        var links = linksOf(child);
        var useful = false;

        for (var j = 0; j < links.length; j++) {
          var href = links[j].getAttribute('href') || '';
          if (href.indexOf('/explore') === 0 || href.indexOf('/reels') === 0) continue;
          useful = true;
        }

        // Never hide something interactive. The Create entry is a button, not a
        // link, and hiding it is indistinguishable from "posting is broken".
        var interactive = child.querySelector &&
          child.querySelector('button, [role="button"], input, [contenteditable]');

        if (useful || interactive) {
          kept++;
        } else {
          child.style.setProperty('display', 'none', 'important');
        }
      }

      if (kept >= 3 && nav.getAttribute('data-simpleinsta-nav') !== '1') {
        nav.setAttribute('data-simpleinsta-nav', '1');
        nav.style.setProperty('display', 'flex', 'important');
        nav.style.setProperty('justify-content', 'space-evenly', 'important');
        nav.style.setProperty('align-items', 'center', 'important');
      }
    } catch (e) {
      E.fail('nav-layout', e);
    }
  }

  /**
   * Hide the "Suggested posts" heading.
   *
   * The JSON filter removes items Instagram labels as promoted, but the section
   * header is rendered markup, not payload, so it survives. This hides the
   * header block only — deliberately not its parent's parent, which would take
   * the whole feed with it.
   */
  function hideSuggestedLabels() {
    try {
      var labels = ['Suggested posts', 'Suggested Posts', 'Suggested for you'];
      var nodes = document.querySelectorAll('span, h2, h3, div');

      for (var i = 0; i < nodes.length && i < 900; i++) {
        var el = nodes[i];
        if (el.getAttribute('data-si-sug') === '1') continue;

        var text = (el.textContent || '').trim();
        if (labels.indexOf(text) === -1) continue;

        el.setAttribute('data-si-sug', '1');
        el.style.setProperty('display', 'none', 'important');

        var parent = el.parentElement;
        if (parent && (parent.textContent || '').trim().length <= text.length + 24) {
          parent.style.setProperty('display', 'none', 'important');
        }
      }
    } catch (e) {
      E.fail('suggested-hide', e);
    }
  }

  install();
  layoutNav();
  hideSuggestedLabels();

  if (!document.head) {
    document.addEventListener('DOMContentLoaded', install, { once: true });
  }

  // React re-renders the nav on every route change, so the gaps come back.
  // The nav renders a beat after the document, so a single late pass means the
  // paper plane sits off-centre until the next tick. Run early and often for the
  // first few seconds, then settle into a slow watch.
  if (typeof setTimeout !== 'undefined') {
    [150, 700, 1800, 4000].forEach(function (delay) {
      setTimeout(layoutNav, delay);
    });
  }

  if (typeof setTimeout !== 'undefined') {
    setTimeout(hideSuggestedLabels, 2500);
    setTimeout(hideSuggestedLabels, 6000);
  }

  var lastSweep = 0;
  if (typeof document !== 'undefined' && document.addEventListener) {
    document.addEventListener('scroll', function () {
      var now = Date.now();
      if (now - lastSweep < 2000) return;
      lastSweep = now;
      hideSuggestedLabels();
    }, true);
  }

  // No interval. This used to re-hide React-owned nav nodes every six seconds
  // for the life of the page, which is exactly the kind of continuous mutation
  // that trips Instagram's error boundary while the user is just sitting there.
  // A few passes after load are enough; if Instagram re-renders the nav later we
  // accept the default layout rather than keep poking it.
})();
