/*
 * Policy engine test harness.
 *
 * Runs the five policy files in a synthetic DOM/location, then drives them with
 * captured-shape Instagram payloads. This is the regression suite that catches
 * "Meta changed the shape" before it reaches a phone.
 *
 *   node tools/policy-test/run.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const POLICY_DIR = path.resolve(here, '../../app/src/main/assets/policy');
const FILES = ['00_runtime.js', '10_feed.js', '20_dm.js', '30_dom.js', '90_health.js'];

let passed = 0;
let failed = 0;

function check(name, fn) {
  try {
    fn();
    passed++;
    console.log(`  ok   ${name}`);
  } catch (e) {
    failed++;
    console.log(`  FAIL ${name}`);
    console.log(`       ${e.message}`);
  }
}

function assert(cond, msg) {
  if (!cond) throw new Error(msg);
}

function assertEqual(actual, expected, msg) {
  if (actual !== expected) throw new Error(`${msg} (expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)})`);
}

function fakeDocument() {
  const listeners = {};
  return {
    head: null,
    documentElement: null,
    visibilityState: 'visible',
    querySelector: () => null,
    querySelectorAll: () => [],
    createElement: () => ({
      setAttribute() {},
      set textContent(v) { this._t = v; },
      get textContent() { return this._t; },
      appendChild() {},
    }),
    addEventListener(type, fn) {
      (listeners[type] = listeners[type] || []).push(fn);
    },
    __dispatch(type, event) {
      (listeners[type] || []).forEach((fn) => fn(event));
    },
  };
}

function boot({ config = {}, pathname = '/' } = {}) {
  const messages = [];
  const timers = [];

  const ctx = {
    console,
    JSON,
    Math,
    Object,
    Array,
    String,
    Number,
    Boolean,
    Set,
    Map,
    Date,
    Promise,
    Error,
    Response,
    URL,
    URLSearchParams,
    setTimeout: (fn) => { timers.push(fn); return timers.length; },
    clearTimeout: () => {},
    location: {
      pathname,
      href: 'https://www.instagram.com' + pathname,
      origin: 'https://www.instagram.com',
      search: '',
    },
    document: fakeDocument(),
    enoughBridge: { postMessage: (raw) => messages.push(JSON.parse(raw)) },
    open: () => 'opened',
  };
  ctx.window = ctx;
  ctx.globalThis = ctx;
  ctx.window.__ENOUGH_CONFIG__ = { policyVersion: 'test', ...config };

  vm.createContext(ctx);
  const src = FILES.map((f) => fs.readFileSync(path.join(POLICY_DIR, f), 'utf8')).join('\n;\n');
  vm.runInContext(src, ctx, { filename: 'policy-bundle.js' });

  return { E: ctx.window.__ENOUGH__, messages, ctx };
}

/* ------------------------------------------------------------------ *
 * Fixtures — shapes only, no real content.
 * ------------------------------------------------------------------ */

// Timestamps must look like real epoch seconds: the policy rejects small
// numbers so that `0` and clock-relative fields are never mistaken for dates.
function post(code, takenAt, extra = {}) {
  return {
    node: {
      __typename: 'XDTMediaDict',
      pk: `pk_${code}`,
      code,
      taken_at: takenAt,
      user: { pk: '1', username: 'friend' },
      image_versions2: { candidates: [{ url: 'https://cdn/x.jpg' }] },
      ...extra,
    },
    cursor: `c_${code}`,
  };
}

function feedPayload({ edges, hasNext = true, cursor = 'CURSOR' }) {
  return {
    data: {
      xdt_api__v1__feed__timeline__connection: {
        edges,
        page_info: { has_next_page: hasNext, end_cursor: hasNext ? cursor : null },
      },
    },
    status: 'ok',
  };
}

function dmPayload() {
  return {
    thread: {
      thread_id: 't1',
      users: [
        { pk: '1', username: 'me' },
        { pk: '2', username: 'alice' },
      ],
      items: [
        {
          item_id: 'i1',
          item_type: 'text',
          user_id: '2',
          timestamp: 1700000000000,
          text: 'look at this',
        },
        {
          item_id: 'i2',
          item_type: 'reel_share',
          user_id: '2',
          timestamp: 1700000100000,
          reel_share: {
            media: {
              pk: 'm1',
              code: 'Cabc12345',
              taken_at: 1699000000,
              video_duration: 12.5,
              user: { pk: '9', username: 'creator' },
              video_versions: [{ url: 'https://cdn/v1.mp4' }],
              image_versions2: { candidates: [{ url: 'https://cdn/t1.jpg' }] },
            },
          },
        },
      ],
    },
  };
}

/* ------------------------------------------------------------------ *
 * Feed
 * ------------------------------------------------------------------ */

console.log('\nfeed');

check('inspects relative urls, not just absolute ones', () => {
  // The on-device no-op: Instagram fetches '/graphql/query', the raw string
  // never contained "instagram.com", so nothing was ever inspected.
  const { E } = boot({ pathname: '/' });
  assert(E.shouldInspect('/graphql/query'), 'relative graphql must be inspected');
  assert(E.shouldInspect('/api/v1/feed/timeline/'), 'relative api must be inspected');
  assert(E.shouldInspect('https://www.instagram.com/graphql/query'), 'absolute still works');
});


check('strips promoted posts and ends at the watermark', () => {
  const { E, messages } = boot({ config: { watermark: 1700000500, feedPageCap: 5 } });
  const payload = feedPayload({
    edges: [
      post('Cnewpost001', 1700001000),
      post('Cadpost0001', 1700000800, { ad_id: '123' }),
      post('Coldpost001', 1700000100),
    ],
  });

  const changed = E.onResponse('https://www.instagram.com/graphql/query', payload, { feed: true });
  const conn = payload.data.xdt_api__v1__feed__timeline__connection;

  assertEqual(conn.edges.length, 2, 'ad should be removed');
  assertEqual(conn.page_info.has_next_page, false, 'pagination should be killed at watermark');
  assertEqual(changed, true, 'mutation must be reported so the body is re-serialised');

  const caught = messages.find((m) => m.type === 'caughtUp');
  assert(caught, 'should report caughtUp');
  assertEqual(caught.reason, 'watermark', 'reason should be watermark');
});

check('keeps pagination alive when everything is newer than the watermark', () => {
  const { E } = boot({ config: { watermark: 1700000500, feedPageCap: 5 } });
  const payload = feedPayload({ edges: [post('Cnewpost001', 1700001000), post('Cnewpost002', 1700000900)] });

  E.onResponse('https://www.instagram.com/graphql/query', payload, { feed: true });

  assertEqual(
    payload.data.xdt_api__v1__feed__timeline__connection.page_info.has_next_page,
    true,
    'must not kill pagination early'
  );
});

check('ends the feed at the page cap even with fresh content', () => {
  const { E, messages } = boot({ config: { watermark: 0, feedPageCap: 2 } });

  E.onResponse('https://www.instagram.com/graphql/query', feedPayload({ edges: [post('Cpageone001', 1700001000)] }), { feed: true });
  assert(!messages.find((m) => m.type === 'caughtUp'), 'page 1 must not end the feed');

  const second = feedPayload({ edges: [post('Cpagetwo001', 1700000900)] });
  E.onResponse('https://www.instagram.com/graphql/query', second, { feed: true });

  assertEqual(
    second.data.xdt_api__v1__feed__timeline__connection.page_info.has_next_page,
    false,
    'page 2 should hit the cap'
  );
  assertEqual(messages.find((m) => m.type === 'caughtUp').reason, 'cap', 'reason should be cap');
});

check('ignores comment connections (no code field)', () => {
  const { E } = boot({ config: { watermark: 1700000500, feedPageCap: 5 } });
  const payload = {
    data: {
      comments: {
        edges: [{ node: { pk: 'c1', text: 'nice', user: { username: 'x' } } }],
        page_info: { has_next_page: true, end_cursor: 'C' },
      },
    },
  };

  const changed = E.onResponse('https://www.instagram.com/graphql/query', payload, { feed: true });

  assertEqual(changed, false, 'comment nodes are not posts, nothing to change');
  assertEqual(payload.data.comments.page_info.has_next_page, true, 'comment pagination untouched');
});

check('drops posts from accounts you do not follow', () => {
  const { E } = boot({ config: { watermark: 0, feedPageCap: 5 } });
  const payload = feedPayload({
    edges: [
      post('Cfollowed01', 1700001000),
      post('Cstranger01', 1700000900, { user: { pk: '9', username: 'stranger', friendship_status: { following: false } } }),
    ],
  });

  E.onResponse('https://www.instagram.com/graphql/query', payload, { feed: true });

  const edges = payload.data.xdt_api__v1__feed__timeline__connection.edges;
  assertEqual(edges.length, 1, 'the non-followed post should be gone');
  assertEqual(edges[0].node.code, 'Cfollowed01', 'the followed post stays');
});

check('drops suggested posts by social context', () => {
  const { E } = boot({ config: { watermark: 0, feedPageCap: 5 } });
  const payload = feedPayload({
    edges: [
      post('Corganic001', 1700001000),
      post('Csuggested1', 1700000900, { social_context: 'Suggested for you' }),
    ],
  });

  E.onResponse('https://www.instagram.com/graphql/query', payload, { feed: true });

  assertEqual(payload.data.xdt_api__v1__feed__timeline__connection.edges.length, 1, 'suggested removed');
});

check('ignores payloads that are not feeds at all', () => {
  // The logged-out landing page returns JSON on the feed route. Treating it as
  // "end of feed" poisoned the watermark and hid the real feed after sign-in.
  const { E, messages } = boot({ config: { watermark: 0, feedPageCap: 2 } });
  const payload = { data: { landing: { title: 'Instagram', locale: 'en' } } };

  const changed = E.onResponse('/graphql/query', payload, { feed: true });

  assertEqual(changed, false, 'nothing to change');
  assert(!messages.find((m) => m.type === 'caughtUp'), 'must not claim caught up');
  assertEqual(E.state.feedPayloads, 0, 'not counted as a feed payload');
});

check('does not declare exhaustion before any post is seen', () => {
  const { E, messages } = boot({ config: { watermark: 0, feedPageCap: 2 } });
  const payload = feedPayload({ edges: [], hasNext: false });

  E.onResponse('/graphql/query', payload, { feed: true });

  assert(!messages.find((m) => m.type === 'caughtUp'), 'no posts yet: not exhausted');
  // `feedPayloads` now means "payloads that actually held posts", because
  // structural detection of a feed response was leaky by nature.
  assertEqual(E.state.feedPayloads, 0, 'no posts means no feed payload');
});

check('closes pagination when it cannot repair the cursor', () => {
  // Edges without a cursor: sending the old one would name a deleted post, which
  // is what produced the error while idle on the feed.
  const { E } = boot({ config: { watermark: 0, feedPageCap: 5 } });
  const payload = {
    data: {
      xdt_api__v1__feed__timeline__connection: {
        edges: [
          { node: post('Ckeep000001', 1700001000).node },
          { node: post('Cgone000001', 1700000900, { ad_id: '3' }).node },
        ],
        page_info: { has_next_page: true, end_cursor: 'STALE' },
      },
    },
  };

  E.onResponse('/graphql/query', payload, { feed: true });

  const conn = payload.data.xdt_api__v1__feed__timeline__connection;
  assertEqual(conn.edges.length, 1, 'ad removed');
  assertEqual(conn.page_info.has_next_page, false, 'pagination closed instead of a dead cursor');
});

check('moves the cursor off a post it removed', () => {
  // Removing the last item while leaving end_cursor pointing at it made the next
  // page request invalid — the "Something went wrong" screen on scroll.
  const { E } = boot({ config: { watermark: 0, feedPageCap: 5 } });
  const payload = feedPayload({
    edges: [
      post('Ckeepfirst01', 1700001000),
      post('Cremovedlast', 1700000900, { ad_id: '9' }),
    ],
  });

  E.onResponse('/graphql/query', payload, { feed: true });

  const conn = payload.data.xdt_api__v1__feed__timeline__connection;
  assertEqual(conn.edges.length, 1, 'ad removed');
  assertEqual(conn.page_info.end_cursor, 'c_Ckeepfirst01', 'cursor follows the new last post');
});

check('filters the feed even when the route has moved on', () => {
  // The regression: gating feed processing on `location.pathname === '/'` meant
  // a feed response landing just after a navigation was skipped, so suggested
  // posts came straight through.
  const { E } = boot({ pathname: '/direct/t/123/', config: { watermark: 0, feedPageCap: 5 } });
  const payload = feedPayload({
    edges: [
      post('Corganic0001', 1700001000),
      post('Csuggested01', 1700000900, { ad_id: '7' }),
    ],
  });

  E.onResponse('/graphql/query', payload, { feed: true });

  assertEqual(
    payload.data.xdt_api__v1__feed__timeline__connection.edges.length,
    1,
    'the ad is dropped regardless of the current route'
  );
});

check('keeps pagination closed after the feed has ended', () => {
  // The occasional "I can scroll again": only the page that decided the feed was
  // over got rewritten, so a follow-up response could restore has_next_page.
  const { E } = boot({ config: { watermark: 0, feedPageCap: 1 } });

  const first = feedPayload({ edges: [post('Cpageone0001', 1700001000)] });
  E.onResponse('/graphql/query', first, { feed: true });

  const later = feedPayload({ edges: [post('Cpagetwo0001', 1700000900)] });
  E.onResponse('/graphql/query', later, { feed: true });

  const conn = later.data.xdt_api__v1__feed__timeline__connection;
  assertEqual(conn.page_info.has_next_page, false, 'later pages must stay closed');
});

check('declares exhaustion once posts have been seen', () => {
  const { E, messages } = boot({ config: { watermark: 0, feedPageCap: 5 } });

  E.onResponse('/graphql/query', feedPayload({ edges: [post('Cseenpost01', 1700001000)] }), { feed: true });
  E.onResponse('/graphql/query', feedPayload({ edges: [], hasNext: false }), { feed: true });

  const caught = messages.find((m) => m.type === 'caughtUp');
  assert(caught, 'should report caught up');
  assertEqual(caught.reason, 'exhausted', 'reason');
});

check('cleans containers that are not called edges', () => {
  const { E } = boot({ config: { watermark: 0, feedPageCap: 5 } });
  const payload = {
    data: {
      items: [
        post('Citemnew001', 1700001000).node,
        post('Citemad0001', 1700000900, { ad_id: '42' }).node,
      ],
    },
  };

  E.onResponse('https://www.instagram.com/graphql/query', payload, { feed: true });

  assertEqual(payload.data.items.length, 1, 'ad removed from an `items` container');
  assertEqual(payload.data.items[0].code, 'Citemnew001', 'organic post stays');
});

/* ------------------------------------------------------------------ *
 * Direct messages
 * ------------------------------------------------------------------ */

console.log('\ndm');

check('harvests a shared reel out of a thread payload', () => {
  const { E, messages } = boot({});

  E.onResponse('https://www.instagram.com/api/v1/direct_v2/threads/t1/', dmPayload(), { feed: false });

  const msg = messages.find((m) => m.type === 'reels');
  assert(msg, 'should emit a reels message');
  assertEqual(msg.items.length, 1, 'one video item');
  assertEqual(msg.items[0].shortcode, 'Cabc12345', 'shortcode');
  assertEqual(msg.items[0].videoUrl, 'https://cdn/v1.mp4', 'video url');
  assertEqual(msg.items[0].author, 'creator', 'author');
  assertEqual(msg.items[0].sender, 'alice', 'sender resolved from user_id');
  assertEqual(msg.items[0].sentAt, 1700000100, 'sentAt in seconds');
});

check('does not re-emit the same reel twice', () => {
  const { E, messages } = boot({});

  E.onResponse('https://www.instagram.com/api/v1/direct_v2/threads/t1/', dmPayload(), { feed: false });
  E.onResponse('https://www.instagram.com/api/v1/direct_v2/threads/t1/', dmPayload(), { feed: false });

  assertEqual(messages.filter((m) => m.type === 'reels').length, 1, 'dedupe by shortcode');
});

check('ignores text-only threads', () => {
  const { E, messages } = boot({});
  const payload = {
    thread: { items: [{ item_id: 'i1', item_type: 'text', user_id: '2', timestamp: 1700000000000, text: 'hi' }] },
  };

  E.onResponse('https://www.instagram.com/api/v1/direct_v2/threads/t1/', payload, { feed: false });

  assert(!messages.find((m) => m.type === 'reels'), 'nothing to harvest');
});

check('never mutates the DM payload', () => {
  const { E } = boot({});
  const payload = dmPayload();
  const before = JSON.stringify(payload);

  const changed = E.onResponse('https://www.instagram.com/api/v1/direct_v2/threads/t1/', payload, { feed: false });

  assertEqual(changed, false, 'thread must render exactly as Meta built it');
  assertEqual(JSON.stringify(payload), before, 'payload byte-identical');
});

/* ------------------------------------------------------------------ *
 * fetch interception
 * ------------------------------------------------------------------ */

console.log('\nfetch');

async function fetchTests() {
  check('rewrites the response body when the feed ends', async () => {
    const { ctx, E } = boot({ config: { watermark: 1700000500, feedPageCap: 5 } });

    const body = JSON.stringify(feedPayload({ edges: [post('Coldpost001', 1700000100)] }));
    ctx.fetch = async () =>
      new Response(body, { status: 200, headers: { 'content-type': 'application/json' } });

    // runtime captured window.fetch before we swapped it, so rebuild with the stub in place
    const rebuilt = boot({ config: { watermark: 1700000500, feedPageCap: 5 } });
    rebuilt.ctx.fetch = async () =>
      new Response(body, { status: 200, headers: { 'content-type': 'application/json' } });
    vm.runInContext(
      FILES.map((f) => fs.readFileSync(path.join(POLICY_DIR, f), 'utf8')).join('\n;\n')
        .replace(/if \(window\.__ENOUGH__\) return;/, ''),
      rebuilt.ctx,
      { filename: 'rebuild.js' }
    );

    const res = await rebuilt.ctx.fetch('/graphql/query', { body: '{"variables":{"after":"X"}}' });
    const json = await res.json();

    assertEqual(
      json.data.xdt_api__v1__feed__timeline__connection.page_info.has_next_page,
      false,
      'body must be rewritten on the way out'
    );
  });

  check('leaves mutation responses byte-identical', async () => {
    const body = JSON.stringify(
      feedPayload({ edges: [post('Cadmutation1', 1700001000, { ad_id: '9' })] })
    );

    const rebuilt = boot({ config: { watermark: 0, feedPageCap: 2 }, pathname: '/' });
    rebuilt.ctx.fetch = async () =>
      new Response(body, { status: 200, headers: { 'content-type': 'application/json' } });
    vm.runInContext(
      FILES.map((f) => fs.readFileSync(path.join(POLICY_DIR, f), 'utf8')).join('\n;\n')
        .replace(/if \(window\.__ENOUGH__\) return;/, ''),
      rebuilt.ctx,
      { filename: 'rebuild-mutation.js' }
    );

    const res = await rebuilt.ctx.fetch('/graphql/query', { body: '{"mutation":"like"}' });
    const json = await res.json();

    assertEqual(
      json.data.xdt_api__v1__feed__timeline__connection.edges.length,
      1,
      'a like must reach Instagram exactly as built'
    );
  });

  check('passes non-instagram requests through untouched', async () => {
    const { ctx } = boot({});
    let called = 0;
    ctx.fetch = async () => {
      called++;
      return new Response('{}', { headers: { 'content-type': 'application/json' } });
    };

    const rebuilt = boot({});
    rebuilt.ctx.fetch = ctx.fetch;
    vm.runInContext(
      FILES.map((f) => fs.readFileSync(path.join(POLICY_DIR, f), 'utf8')).join('\n;\n')
        .replace(/if \(window\.__ENOUGH__\) return;/, ''),
      rebuilt.ctx,
      { filename: 'rebuild2.js' }
    );

    await rebuilt.ctx.fetch('https://example.com/api', {});
    assertEqual(called, 1, 'should call through');
  });
}

/* ------------------------------------------------------------------ *
 * App handoff
 *
 * The bug that shipped and failed on device: intent://instagram.com/_n/mainfeed
 * reached the WebView and produced net::ERR_UNKNOWN_URL_SCHEME.
 * ------------------------------------------------------------------ */

console.log('\nhandoff');

check('neutralises an intent handoff on click', () => {
  const { ctx, messages } = boot({});
  let prevented = false;

  const anchor = {
    tagName: 'A',
    parentNode: null,
    getAttribute: (k) => (k === 'href' ? 'intent://instagram.com/_n/mainfeed#Intent;end' : null),
  };

  ctx.document.__dispatch('click', {
    target: anchor,
    preventDefault: () => { prevented = true; },
    stopPropagation: () => {},
  });

  assert(prevented, 'the click must be prevented');
  assert(messages.find((m) => m.type === 'blocked'), 'native should be told');
});

check('leaves ordinary links alone', () => {
  const { ctx } = boot({});
  let prevented = false;

  const anchor = {
    tagName: 'A',
    parentNode: null,
    getAttribute: (k) => (k === 'href' ? 'https://www.instagram.com/direct/inbox/' : null),
  };

  ctx.document.__dispatch('click', {
    target: anchor,
    preventDefault: () => { prevented = true; },
    stopPropagation: () => {},
  });

  assertEqual(prevented, false, 'normal navigation must not be intercepted');
});

check('window open refuses intent urls', () => {
  const { ctx, messages } = boot({});
  const result = ctx.window.open('intent://instagram.com/_n/mainfeed#Intent;end');
  assertEqual(result, null, 'must not forward the handoff');
  assert(messages.find((m) => m.type === 'blocked'), 'native should be told');
});

check('window open still forwards normal urls', () => {
  const { ctx } = boot({});
  assertEqual(ctx.window.open('https://example.com'), 'opened', 'passthrough');
});

/* ------------------------------------------------------------------ *
 * Reel links
 *
 * Tapping a reel a friend sent used to open Instagram's own pager, which rolls
 * into recommendations when the sent reel ends. It must land in the queue.
 * ------------------------------------------------------------------ */

console.log('\nreel links');

function clickAnchor(ctx, href) {
  let prevented = false;
  const anchor = {
    tagName: 'A',
    parentNode: null,
    getAttribute: (k) => (k === 'href' ? href : null),
  };
  ctx.document.__dispatch('click', {
    target: anchor,
    preventDefault: () => { prevented = true; },
    stopPropagation: () => {},
  });
  return prevented;
}

check('routes a sent reel into the native queue', () => {
  const { ctx, messages } = boot({ pathname: '/direct/t/123/' });
  const prevented = clickAnchor(ctx, '/reel/Cabc12345/');

  assert(prevented, 'the pager navigation must be prevented');
  const msg = messages.find((m) => m.type === 'openReel');
  assert(msg, 'native should be asked to open the queue');
  assertEqual(msg.shortcode, 'Cabc12345', 'shortcode');
});

check('routes reels from an absolute url too', () => {
  const { ctx, messages } = boot({ pathname: '/direct/t/123/' });
  clickAnchor(ctx, 'https://www.instagram.com/reel/Cxyz98765/');
  const msg = messages.find((m) => m.type === 'openReel');
  assert(msg, 'absolute links must be caught as well');
  assertEqual(msg.shortcode, 'Cxyz98765', 'shortcode');
});

check('does not treat a music page as a reel', () => {
  const { ctx, messages } = boot({});
  const prevented = clickAnchor(ctx, '/reels/audio/123456789/');

  assertEqual(prevented, false, 'audio pages are not reels');
  assert(!messages.find((m) => m.type === 'openReel'), 'no queue jump');
});

check('leaves ordinary post links alone', () => {
  const { ctx } = boot({});
  const prevented = clickAnchor(ctx, '/p/Cabc12345/');
  assertEqual(prevented, false, 'posts are not reels');
});

/* ------------------------------------------------------------------ *
 * Robustness
 * ------------------------------------------------------------------ */

console.log('\nrobustness');

check('survives a malformed payload without throwing', () => {
  const { E } = boot({});
  E.onResponse('https://www.instagram.com/graphql/query', { data: { weird: [null, 1, 'x'] } }, { feed: true });
});

check('survives a deeply nested payload', () => {
  const { E } = boot({});
  let node = { code: 'Cdeep12345', taken_at: 500, user: { username: 'a' } };
  for (let i = 0; i < 500; i++) node = { child: node };
  E.onResponse('https://www.instagram.com/graphql/query', node, { feed: true });
});

check('reports health on demand', () => {
  const { E, messages } = boot({});
  E.post('health', { phase: 'test' });
  assert(messages.find((m) => m.type === 'health'), 'health message emitted');
});

/* ------------------------------------------------------------------ */

await fetchTests();

console.log(`\n${passed} passed, ${failed} failed\n`);
process.exit(failed ? 1 : 0);
