/*
 * 90_health.js — tell Kotlin what the page actually looks like.
 *
 * Two jobs: report whether the policy took effect, and mirror Instagram's own
 * theme so the chrome above and below the WebView does not disagree with it.
 *
 * The important distinction in the health report is between "we looked at feed
 * data and found nothing usable" (a real failure) and "we never saw feed data at
 * all" (probably logged out, or not on the feed).
 */
(function () {
  var E = window.__SIMPLEINSTA__;
  if (!E) return;

  function isLoggedOut() {
    try {
      if (location.pathname.indexOf('/accounts/login') === 0) return true;
      if (document.querySelector('input[name="username"]')) return true;
      return false;
    } catch (e) {
      return false;
    }
  }

  function backgroundLuminance(element) {
    try {
      if (!element) return null;

      var background = window.getComputedStyle(element).backgroundColor || '';
      var match = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?\)/.exec(background);
      if (!match) return null;

      // A transparent body is not "black"; it means the colour lives elsewhere.
      var alpha = match[4] === undefined ? 1 : Number(match[4]);
      if (!alpha) return null;

      return (
        (0.299 * Number(match[1]) + 0.587 * Number(match[2]) + 0.114 * Number(match[3])) / 255
      );
    } catch (e) {
      return null;
    }
  }

  /**
   * Instagram does not expose a theme flag, but it does paint the page. Read the
   * body, then the root, then fall back to the media query the page itself would
   * honour. Checking `body` alone is a trap: a transparent body reads as black
   * and would flip the app to dark on a white page.
   */
  function detectDark() {
    var luminance = backgroundLuminance(document.body);
    if (luminance === null) luminance = backgroundLuminance(document.documentElement);
    if (luminance !== null) return luminance < 0.5;

    try {
      return window.matchMedia('(prefers-color-scheme: dark)').matches;
    } catch (e) {
      return null;
    }
  }

  var lastDark = null;

  function reportTheme() {
    try {
      var dark = detectDark();
      if (dark === null || dark === lastDark) return;
      lastDark = dark;
      E.post('theme', { dark: dark });
    } catch (e) { /* ignore */ }
  }

  function report(phase) {
    try {
      E.post('health', {
        phase: phase,
        path: location.pathname,
        pages: E.state.pages,
        payloads: E.state.feedPayloads,
        feedSeen: E.state.feedSeen,
        caughtUp: E.state.caughtUp,
        reels: E.state.reelsSent,
        loggedOut: isLoggedOut(),
        css: !!document.querySelector('style[data-simpleinsta]'),
        err: E.state.lastError || ''
      });
    } catch (e) { /* ignore */ }
  }

  setTimeout(function () {
    report('load');
    reportTheme();
  }, 3000);

  setTimeout(reportTheme, 1200);

  if (typeof setInterval !== 'undefined') {
    // Cheap: only posts when the answer actually changes.
    setInterval(reportTheme, 6000);
  }

  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') report('hide');
    else reportTheme();
  });
})();
