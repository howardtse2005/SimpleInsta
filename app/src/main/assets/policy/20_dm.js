/*
 * 20_dm.js — harvest reels that humans sent, instead of letting Instagram
 * play them inside its own Reels pager.
 *
 * Why harvest instead of intercept the tap: if a shared reel opens in IG's
 * viewer, finishing it rolls straight into algorithmic recommendations. That
 * transition is the exact behaviour we are removing. So we read the thread
 * payload (the same data the bubble renders from) and hand the media to the
 * native player, which has Prev/Next buttons and no vertical swipe.
 */
(function () {
  var E = window.__SIMPLEINSTA__;
  if (!E) return;

  var MAX_ITEMS = 40;
  var MAX_SEEN = 5000;

  function collectUsers(json) {
    var map = {};
    E.walk(json, function (o) {
      if (!Array.isArray(o.users)) return;
      for (var i = 0; i < o.users.length; i++) {
        var u = o.users[i];
        if (u && u.pk != null && typeof u.username === 'string') {
          map[String(u.pk)] = u.username;
        }
      }
    });
    return map;
  }

  function firstVideoIn(node) {
    var found = null;
    E.walk(node, function (n) {
      if (found) return;
      if (E.isVideoMedia(n)) found = n;
    });
    return found;
  }

  E.processDirect = function (json) {
    // One cheap pass first: most payloads on this route are not DMs at all.
    if (!E.hasDirectShape(json)) return false;

    var users = collectUsers(json);
    var seen = E.__dmSeen;
    if (!seen) seen = E.__dmSeen = new Set();

    var out = [];

    E.walk(json, function (o) {
      if (out.length >= MAX_ITEMS) return;
      if (!o || typeof o !== 'object') return;
      if (!o.item_type) return;

      var media = firstVideoIn(o);
      if (!media) return;

      var code = media.code || media.shortcode;
      if (!code || seen.has(code)) return;

      var senderId = o.user_id != null ? String(o.user_id) : '';
      var versions = media.video_versions || [];
      var candidates = (media.image_versions2 && media.image_versions2.candidates) || [];
      var author = (media.user && media.user.username) ||
                   (media.owner && media.owner.username) || '';

      seen.add(code);
      out.push({
        mediaId: String(media.pk || media.id || code),
        shortcode: code,
        author: author,
        sender: users[senderId] || '',
        sentAt: typeof o.timestamp === 'number' ? Math.floor(o.timestamp / 1000) : 0,
        videoUrl: (versions[0] && versions[0].url) || '',
        thumbUrl: (candidates[0] && candidates[0].url) || '',
        durationMs: Math.round((media.video_duration || 0) * 1000)
      });
    });

    if (seen.size > MAX_SEEN) {
      E.__dmSeen = new Set();
    }

    if (!out.length) return false;

    E.state.reelsSent += out.length;
    E.post('reels', { items: out, count: out.length });

    // We never mutate the DM payload; the thread must render exactly as Meta built it.
    return false;
  };
})();
