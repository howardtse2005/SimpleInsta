# SimpleInsta

**Instagram, but it ends.**

A ~1.6 MB Android app that shows you your feed, your messages, and the reels people actually sent you — and then stops. No Explore. No Reels tab. No "just one more."

> **Status: builds and is unit-tested; not yet run against live Instagram.** See [Status](#status).



---

## What it does

Three surfaces, one of which doesn't exist:

| Tab          | What it is                                                                                           |
| ------------ | ---------------------------------------------------------------------------------------------------- |
| **Feed**     | Instagram's web feed with pagination capped and ads/suggested posts stripped. It ends.               |
| **Messages** | Instagram's web DMs, untouched. Read, reply, react.                                                  |
| **Queue**    | The reels a human sent you, played one at a time with **Prev / Next buttons and no vertical swipe**. |

When the queue is empty you get a screen that says *"You're all caught up."* That is the whole product.

---

## Status

Verified on this machine:

- `assembleDebug` and `assembleRelease` (R8 full mode) both succeed.
- Release APK: **1.65 MB**. Debug: 11.3 MB (unminified, normal).
- 18/18 Kotlin unit tests pass (`WatermarkTest`, `UrlGateTest`).
- 28/28 policy-engine tests pass against synthetic payloads.
- The assembled 33 KB policy bundle parses *and executes* in a V8 context.
- Manifest: minSdk 26, targetSdk 34, only `INTERNET` + `ACCESS_NETWORK_STATE`, launcher + `instagram.com` deep links.

**Fixed after the first device run.** Instagram's web app fires
`intent://instagram.com/_n/mainfeed` to hand off to the native app. The URL check matched on *host*,
so an intent URL looked like a legitimate Instagram URL, the WebView tried to load it, and the user
got `net::ERR_UNKNOWN_URL_SCHEME`. Three changes: `UrlGate` now classifies by **scheme** and refuses
every non-`http(s)` navigation; `00_runtime.js` neutralises the handoff at the click and
`window.open` level so it never reaches the browser; and `onReceivedError` restores the last good
page (debounced) if one slips through anyway. `/_n/` is also blocked natively.

**Fixed after the second device run.** Two reports — "I can still scroll after the reel my friend
sent" and "the homepage still shows reels from people I don't follow" — turned out to be four
separate defects:

1. **Reel links were never intercepted.** Tapping a shared reel opened Instagram's own pager, which
   continues into recommendations when the sent reel ends. The click firewall only knew about
   `intent://`. It now catches `/reel/<code>` and `/reels/<code>`, posts `openReel`, and the queue
   opens at that reel. `/reel/` is also blocked natively, and a modal guard catches the case where
   Instagram opens the reel in a dialog without changing the URL.
2. **Only arrays named `edges` were filtered.** Feed containers named `items` or `medias` were
   untouched. Any array holding media-shaped objects is now a candidate.
3. **There was no "not following" signal.** `friendship_status.following === false` is now the
   strongest strip marker, alongside `social_context` / `inventory_source` / `connection_id`.
4. **Only GraphQL responses were inspected**, and every request counted as a feed page. Inspection
   now covers `/api/v1/` on the feed route, and a page only counts when it actually contained posts.

The app now also **checks whether the policy attached** and says so: a red "Limits aren't running"
strip appears if `window.__SIMPLEINSTA__` is missing after a page load, or if no feed response was ever
seen. That failure mode is silent by nature — every symptom looks like "it just doesn't work" — so it
is worth surfacing rather than guessing at.

**The bug that made all of the above a no-op.** The debug sheet reported `policy: attached` but
`feed: no feed response yet`. Instagram issues same-origin requests as **relative** URLs
(`fetch('/graphql/query')`), and the inspector required the string to contain `instagram.com`. So
every response was skipped: no feed filtering, no pagination cap, no DM reel extraction, and an empty
queue. `E.resolve()` now resolves against `location.href` before classifying. This is why the debug
sheet exists — it took one screenshot to find a bug that four rounds of guessing had missed.

**Fixed after the third device run.** With the URL fix in place the policy finally read responses, and
immediately reported `kept 0 · removed 0 · pages 0 · ended` on Instagram's **logged-out landing
page**. Three consequences:

1. **"No posts" was read as "end of feed."** That reported caught-up and moved the watermark to *now*,
   which then made the real feed look entirely stale — so after signing in, the feed would end after
   one page. `E.hasFeedShape()` now gates the end-of-feed logic: only payloads containing a
   `page_info` connection may drive the watermark or the cap. Cleaning still runs on everything,
   because suggested modules often arrive without pagination.
2. **The watermark clamp.** `Watermark.advance()` treats a value more than 60 s in the future as
   corruption and resets it, and a one-time `limits_version` migration clears watermarks written by
   older builds, so an existing install heals on upgrade instead of needing a reset button.
3. **"Limits aren't running" was crying wolf.** Health now distinguishes three states — logged out,
   read feed data and got nothing, or never saw feed data — and only the middle one degrades. A
   logged-out page gets a plain "Sign in to Instagram" chip instead of a red alarm.

**Fixed after the fourth device run.** The log said `tap: (not an anchor)`, and the viewer was a
full-screen overlay that never changed the URL. Both defences — the click firewall and the router
guard — were therefore blind: the reel bubble is not a link, so there is nothing to intercept.

So the app stopped trying to detect the *tap* and started detecting the *result*: **a video filling
the viewport inside DMs is a reel pager.** A transparent, touch-consuming layer is laid over it
(`#enough-shield`). Swipes never reach Instagram, so it cannot scroll; the only exits are
**Open in Queue** and **Done**.

This is the one guarantee in the app that does not depend on Instagram's markup. It depends on the
video being big, which is the definition of the thing we are trying to stop. The trade-off is
explicit: while the shield is up, you cannot interact with Instagram's reel viewer at all. That is the
point — but it does mean replying to a reel has to happen after closing it.

Health was also counting failures from the Messages tab, which is why `HIDE_ONLY` persisted. It now
only degrades on the feed route.

**UI pass.** With the behaviour settled, four changes:

- **Navigation is three icons** — home, messages, profile — with no labels. The profile slot is the
  queue, badged with a count when reels are waiting. `contentDescription` keeps it readable to
  TalkBack.
- **The chrome mirrors Instagram's theme**, not the system's. There is no theme flag to read, so the
  page's own background luminance decides: body, then root, then `prefers-color-scheme` as a last
  resort. Checking `body` alone is a trap — a transparent body computes as black and would flip the
  app to dark on a white page.
- **Reels play with sound.** WebView mutes media that starts without a user gesture, and the shield
  deliberately removes that gesture. Fixed in two places: `mediaPlaybackRequiresUserGesture = false`
  on the WebView, and an explicit `muted = false` on any full-screen video.
- **The shield is now invisible** apart from a single ×. Full-screen videos get `object-fit: cover`
  so they fill the screen rather than letterboxing. This crops slightly at the edges; the alternative
  is bars, and bars were the complaint.

**Correcting the UI pass.** Three of those were wrong, and the first was a plain mistake:

- **There were two nav bars.** I added a Compose bar instead of fixing the one already inside the
  page. The Compose bar is gone; navigation is Instagram's own. The plane sat off-centre because
  hiding the Explore and Reels *anchors* left their wrappers occupying a flex slot — `collapseNavGaps`
  now hides a wrapper only when it holds nothing else clickable, so the remaining three spread evenly.
  The queue, which that bar cannot reach, gets a small "N reels waiting" pill that appears only when
  it has something in it.
- **Dark mode followed the wrong thing.** It mirrored Instagram's page, so a phone in dark mode got a
  light app whenever the Instagram session was on light. System dark now wins; Instagram's own theme
  is only consulted when the system is light. There is also a `values-night` launch theme, so there is
  no white flash before Compose draws.
- **"Limits aren't running" is gone.** It was a diagnostic that had done its job and was firing on
  the wrong tab. The status stays in the debug sheet.
- **The touch-eating shield was the wrong trade.** It stopped scrolling by making the reel
  non-interactive, which killed like, comment, share, save, follow and tap-to-pause. Replaced with a
  **drag-only freeze**: `touchmove` is cancelled, and taps never trigger `touchmove`, so every control
  keeps working while the pager cannot move. The pager's own scroll container is also pinned to
  `overflow-y: hidden` as a second line.
- **Proportion needed the container, not the video.** `object-fit: cover` did nothing because the
  video already filled its box — the box was narrower than the screen. `fillViewport` grows the
  container chain to the viewport first, then cover fills it.

**What actually fixed the cropped rail.** Not the video at all. The reel viewer sizes its container by
*height*, so a 9:16 video ends up wider than the screen; that widens the document, and anything
anchored to the right — the like/comment rail — is pushed off the edge. Two attempts at resizing the
video made it worse (`innerWidth` ancestors, then a `scale()` transform). The fix is to clamp
overflow: `html, body { overflow-x: hidden }` plus `video { max-width: 100vw }`. Edge-to-edge framing
is no longer attempted; Instagram's own framing is left alone.

**Posting.** The blocker was not a route rule — it was a missing callback. Instagram's create flow
opens a file picker, and a WebView with no `onShowFileChooser` handler silently does nothing, which is
indistinguishable from "posting is broken". `WebHost` now forwards the chooser to the activity through
`rememberLauncherForActivityResult`. The `/_n/` route block was also narrowed to just `/_n/mainfeed`;
blocking the whole namespace could catch ordinary web routes.

**Back button.** One press now closes the reel. The page reports overlay state (`reelOverlay`), and
`BackHandler` asks the page to close itself before falling through to history navigation. Previously
the press went straight to `goBack()`, which did nothing visible, so it took two taps.

**Fourth pass — navigation traps and the discard dialog.**

- **"Discard" did nothing.** Two causes, both ours. `onReceivedError` treated *any* failed non-web
  navigation as an app handoff and reloaded the last good page, so a teardown that failed put the
  create view straight back. And `shouldOverrideUrlLoading` swallowed every non-web scheme, including
  `about:` and `blob:` — which is exactly how an SPA unmounts a view. Both are now scoped: recovery
  only chases real handoffs, and `about:`/`blob:` are handed back to the WebView.
- **Back was a trap.** `BackHandler` had a `tab == MESSAGES` branch that only flipped a variable
  without navigating, so the first press did nothing and the second fell through. Removed. The rule
  is now: close the reel, else exit if you are at the feed root, else `goBack()`, else go home.
- **Rail still clipped.** The container is sized by *height*, so a 9:16 reel computes a width wider
  than the screen. `overflow-x: hidden` stopped the page scrolling but still clipped. The container
  chain now gets `max-width: 100vw` — a maximum, not a fixed width, so nothing else moves.
- **Nav rendered late.** `layoutNav` ran on a 4-second timer, so the first render was whatever
  Instagram produced. It now runs at 150/400/900/1600/2600/4000 ms, then settles into the slow watch.

**Fifth pass — false positives, self-inflicted errors, and a deterministic back.**

The debug sheet settled three arguments at once:

```
feed: payloads 4 · kept 0 · pages 0
feed.shape: data,extensions > get_slide_mailbox_for_iris_subscription
```

- **A DM mailbox was being counted as a feed.** `hasFeedShape` accepted any object with a
  `page_info`, and Instagram's mailbox queries carry one. So four DM payloads were counted as feed
  pages containing zero posts — which reads exactly like "the filter is broken". It now requires a
  real connection: `page_info` **and** an `edges`/`items` array.
- **"Something went wrong, reload page"** is Instagram's own error boundary, and the most likely
  trigger was ours: the freeze set `overflow-y: hidden` on a React-managed scroll container. That is
  gone. The `touchmove` blocker is what actually stops the pager; the scroller is now only *located*,
  never modified.
- **Back is a ladder, not history.** `goBack()` was silently dropped while a page was still loading,
  which is why it sometimes took two presses. The handler now walks thread → inbox → feed → exit,
  using the in-page router first (instant) and falling back to a reload after 600 ms if Instagram
  ignores the synthetic navigation.
- **Rail width, fifth attempt.** Only the video's *immediate* parent is widened to `100vw` now —
  deeper ancestors are what pushed the rail off-screen before. A `reel.geom` log records the actual
  video, frame, and viewport dimensions, so the next report is data rather than another guess.

**Sixth pass — the measurement ended the guessing.**

```
reel.geom: video 390x645  frame 390x645  viewport 390x714
```

The video is **exactly** the viewport width. There is no letterboxing left to remove: the remaining
69 px is Instagram's reply bar. What is left is a genuine trade-off with no third option — `cover`
fills the screen and crops the edges, `contain` shows the whole frame and leaves bars. Both have been
requested at different points, so it is now a setting (**long-press "SimpleInsta" → Reels: fit screen /
whole frame**) rather than something to keep guessing at. Changing it reloads the page, because the
policy is injected at document start.

- **"You're all caught up" now only appears on the feed.** Over a DM reply bar it read as a stray
  toast, which is exactly how it looked.
- **Scroll performance.** The non-passive `touchmove` listener was registered on `document` for the
  whole session, which makes the compositor wait for the handler on *every* scroll even when it does
  nothing. It now attaches only while a reel is open. Nav layout runs at 150/700/1800/4000 ms then
  every 6 s; theme polling at 6 s.
- `LIMITS_VERSION` bumped to 4, clearing the `HIDE_ONLY` that the DM-mailbox false positive had set.

**Seventh pass — the rail, found in the box model.**

The video was full-bleed and the rail was still clipped, which ruled the video out entirely. Two
things were fighting:

1. **`box-sizing`.** Forcing `width: 100vw` on the frame while it was still `content-box` made its
   *total* width `100vw + padding`. Everything anchored to that right edge therefore sat exactly
   `padding` past the screen — which is precisely the "half an icon" symptom. The frame is now
   `border-box` with its horizontal padding zeroed before the width is applied.
2. **Containing block.** The rail is `right: 0` of whatever block Instagram chose. If that block is
   wider than the screen, every right-anchored control is clipped by the difference, so the app root
   is now clamped too.

`reel.geom` gained the numbers that were missing — left offsets and viewport widths:

```
reel.geom: video 390x645@0 frame 390x645@0 inner 390 client 390 scroll 390
```

Sizes alone could not distinguish "video too small" from "overlay shifted right"; `@left`,
`client` and `scroll` can.

**Eighth pass — audio, and a measurement that rules things out.**

```
reel.geom: video 390x645@0 frame 390x645@0 inner 390 client 390 scroll 390
```

Everything is exactly 390, starting at 0, and `scroll == client`. Nothing overflows and nothing is
offset, so the clip is not a width or offset problem at all — it is inside Instagram's layout, most
likely a viewport that resolves to a scale other than 1.

- **The mute button was fighting the app.** `fitVideo` ran on a 1.5 s timer and, on every tick,
  re-unmuted the video and called `play()` on *every* full-screen element. Pressing mute was undone
  within a second, and because the pager keeps neighbours mounted, several videos started at once —
  that is the stacked audio. Both are gone: unmute happens once per element, and the app never calls
  `play()`. Playback belongs to Instagram.
- **`feedPayloads` now means "payloads that held posts."** Structural detection of a feed response
  was leaky by nature — the DM mailbox matched every test we could write — so counting is tied to the
  only signal that cannot lie: `kept > 0`.
- **Viewport forced to 1:1** (`width=device-width, initial-scale=1, maximum-scale=1`), re-asserted
  every 5 s in case Instagram rewrites it.
- **`reel.overflow`** logs up to four elements whose right edge passes the viewport, so a clipped
  rail names itself instead of being inferred from a screenshot.

**Ninth pass — the rail was flush because I removed its margin.**

```
visual 390x715@1.00   reel.overflow: none / 16..711
```

Scale is exactly 1.00, so zoom was not the cause either. What the screenshot showed was simpler: the
rail sits *flush* against the right edge with no inset, and its counts are clipped by the screen — in
**both** fit modes, which rules out `object-fit` entirely.

The margin was Instagram's own horizontal padding on the video's frame, and an earlier revision zeroed
it (`padding-left/right: 0`) while forcing the width. `border-box` already makes the width inclusive,
so removing the padding bought nothing and cost the rail its inset. The padding is now left alone.

The general lesson: every `!important` override on someone else's layout is a claim that you know what
that property is for. Here it was a margin.

**Tenth pass — the overlay box itself was 503 px wide.**

```
reel.overflow: 16..711 16..519
```

`16..519` is a box **503 px wide starting at x=16** in a 390 px viewport — 129 px past the screen. The
reel viewer sizes its overlay by the video's aspect ratio at full height, and a 4:5-ish reel at 645 px
tall computes 503 px wide. Anything anchored to that box's right edge lands off-screen; the rail was
never the problem, its container was.

`reel.overflow` now clamps as well as reports: any element whose right edge passes the viewport by
200 px or less gets `max-width` set to the space actually available, once, with `border-box`. Genuinely
wide carousels (`16..711`) are left alone — they live inside their own horizontal scroller and
squashing them would break that instead.

**Eleventh pass — the rail was never in the clamped box.**

After the clamp, `reel.overflow` reported only `16..711` and the icons were still cut, which proves the
rail is not a child of the 519 box. Two changes:

- The 200 px cutoff is gone; every offender is clamped. Protecting carousels was a reasonable guess
  that cost two rounds.
- A rail-specific pass: a tall (≥120 px), narrow (10–90 px) box flush against the right edge is nudged
  in with `translateX(-12px)`. This runs even when nothing overflows, because the rail's own counts
  were sitting hard against the screen edge with no inset.

`reel.rail: inset 1` / `not found` is logged so the heuristic's success is visible rather than assumed.
If it reports `not found`, the rail's dimensions differ from the guess and the next step is to measure
it rather than widen the criteria.

**Twelfth pass — the feed works, and two pieces of my own surgery are gone.**

The log finally reads:

```
feed: payloads 1 · kept 27 · removed 0 · pages 1 · ended
```

27 posts kept, one page, ended. The feed filter works end to end — the relative-URL fix, the shape gate
and the watermark repair together.

- **"You're all caught up" is deleted**, not scoped. It had been reduced to feed-only, but the request
  was to remove it and the watermark still advances without a chip to announce it.
- **The `max-width` clamp is gone.** Rewriting `max-width` across arbitrary boxes was layout surgery on
  React-owned nodes — the most plausible trigger for "Something went wrong" when opening a story — and
  it never fixed the rail. `reel.overflow` now reports without mutating.
- **The rail diagnostic was useless by construction.** It returned three `390x715@0..390` entries
  because full-bleed containers match "near the right edge" and appear first in DOM order. It now
  ignores anything ≥95% of viewport width, and the rail matcher accepts the inset Instagram actually
  applies (±26 px) instead of demanding flush.

**Thirteenth pass — the two constraints were separable after all.**

Restoring the frame padding fixed the rail but inset the video, leaving a black band down the right
side. That looked like a forced trade-off — padding for the rail, no padding for the video — but it
isn't, because the rail now gets its inset from its own `translateX` pass. So the padding is zeroed
again for an edge-to-edge video, and the rail keeps its margin independently. The two requirements
were only coupled while the rail's position depended on the frame's padding.

- **"I can scroll again, occasionally."** The pagination kill only applied to the page that *decided*
  the feed was over. A follow-up response could restore `has_next_page` and reopen the feed. The kill
  is now latched on `E.state.caughtUp`, so every later page is closed too. Regression test added.
- **"Something went wrong" on fast tab switches.** `shouldInspect` is route-gated again: only the feed
  and DM routes parse responses. Profile, explore and create now cost nothing, which shortens the
  window in which a quick switch can race an in-flight response. This is mitigation, not a proven
  cure — the error is Instagram's own boundary.

**Fourteenth pass — my two fixes were fighting each other.**

```
video 390x645@51  overflow: 51..441
reel.rail: inset 25
```

Both symptoms were self-inflicted:

- **The black band was `width: 100vw`.** Instagram centres the reel frame; forcing a full-viewport
  width on a centred box moved it to x=51 and pushed it 51 px past the right edge. `max-width` can
  only *shrink* a box — it cannot reposition one — so it is the only safe lever, and it is now the
  only one used.
- **The rail nudge matched 25 elements.** `reel.rail: inset 25` means twenty-five boxes were
  translated 12 px left, shifting the layout it was meant to repair. Removed entirely. The rail's
  inset is Instagram's own padding, which is left alone.

The video now fills the frame's padding box instead, via
`width: calc(100% + padLeft + padRight)` and a negative left margin. The padding stays because it is
the rail's margin; the video simply extends underneath it. No element is repositioned.

**Fifteenth pass — the geometry work is reverted.**

That padding-box approach also failed on device (black band persisted, icons clipped on first open).
Rather than a sixth variation, **every geometric override is gone**: no forced widths, no zeroed
padding, no ancestor clamping, no `translateX`, no `scale()`, no `calc()` fill. The reel renders
exactly as Instagram's web app builds it, and the only things left of ours are the fit toggle
(`object-fit`) and unmuting.

That is a retreat, and it should be recorded as one. Five separate approaches each produced a new
symptom, and each new symptom needed another override to mask it. The honest conclusion is that this
layout cannot be corrected from outside by guessing at boxes; it needs the DOM inspected directly.

**Feed filter.** `kept 27 · removed 0` is the real remaining bug: posts are being read, but none are
being dropped, which means none of the promoted markers match this build's payload. Rather than guess
at field names again, the policy now logs `feed.keys` and `feed.userkeys` once — the actual field list
of a real feed item and of its author. That single line decides whether a following-only filter is
possible without the private API.

**Sixteenth pass — the feed regression was mine, and the user named it.**

The report "the few previous versions didn't show strangers' posts" was the decisive clue: this was a
regression, not a missing feature. Two changes caused it, both made while chasing the reel layout:

1. **`meta.feed` gating.** `processFeed` only ran when the *current route* was `/`. A feed response
   arriving just after a navigation was skipped entirely — no filtering, no cap. The route gate in
   `shouldInspect` compounded it. Both are gone: the payload's shape decides now, via
   `hasFeedShape()`, not the URL bar. Regression test added.
2. **A mangled `processFeed`.** An earlier scripted edit inserted the diagnostics *inside*
   `if (!hasFeedShape)`, which also pushed the `feed` stats post under `if (changed)`. It parsed, so
   nothing complained, but the branch was wrong. The function is rewritten cleanly.

`feed.markers` now logs the four fields that decide promotion — `social_context`,
`inventory_source`, `connection_id`, `friendship_status` — so the next report explains itself.

**Seventeenth pass — the cursor pointed at a deleted post.**

Filtering worked, and then scrolling produced "Something went wrong". The cause is in the box model of
the API rather than the page: `page_info.end_cursor` still named the last item of the *original* page.
Once that item was spliced out, the next request asked for a post that no longer existed and Instagram
answered with its error screen.

`cleanContainers` now moves `end_cursor` onto the last surviving item's cursor whenever it removes
anything. Regression test added (`moves the cursor off a post it removed`).

The reload behaviour — strangers reappearing after a reload — is not yet explained. The likely
candidates are a cached response or a different feed endpoint on reload; `feed.markers` and
`feed.keys` will distinguish them on the next report.

**Eighteenth pass — the app was still poking the page while idle.**

Two loops were still mutating Instagram-owned nodes for the entire life of the page:

- `ensureViewport` rewrote the `<meta name="viewport">` tag every 5 seconds. If Instagram rewrote it
  back, the two fought in a loop, forcing a re-layout each round. Removed entirely — it was added for
  the reel layout and never helped.
- `layoutNav` re-applied `display: none` to nav children every 6 seconds. Continuous mutation of
  React-owned nodes is exactly what trips an error boundary while nothing is happening. It now runs a
  few times after load and then stops; if Instagram re-renders the nav later, the default layout is
  accepted rather than poked again.

**And a cursor fail-safe.** If the surviving items carry no usable cursor, sending the old one names a
post we deleted — which is what the idle prefetch was doing. Pagination is now closed in that case
instead. A shorter feed is a much better failure than a broken one.

**Nineteenth pass — back was being eaten by a stale flag.**

Two separate faults:

- The `inThread` / `inInbox` ladder had been reverted by a later edit, so back from Messages fell
  through to `goBack()` — which is silently dropped while a page is busy. Restored as a fixed ladder:
  thread → inbox → feed → exit.
- **The `reelOpen` branch was removed entirely.** It consumed every back press whenever the page
  believed a reel was open, and that belief could stick long after the reel closed. One stale boolean
  was enough to make the back button appear completely broken. The freeze still prevents scrolling;
  back now always navigates.

On the feed: the idle error is a side effect of closing pagination and is accepted. What still isn't
guaranteed is filtering after a reload — that depends on Instagram including a "not following"
marker in the payload, which it does not always do. `feed.markers` records the four candidate fields
when it happens.

**Not verified:** behavior against the real Instagram web app. There was no device or emulator available, so the M0 spike from `PLAN.md` §13 is still an open gate. The policy's structural assumptions (`page_info` / `edges` / `item_type`) are modeled on Instagram's documented GraphQL shapes, not captured from a live session.

**Before you trust it:** install it, sign in once, and watch the logcat line `[enough] runtime ready`. Then scroll the feed to the end once. If the feed keeps loading, the policy didn't attach and the app is currently just a browser.

---

## Build

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # 10 unit tests
```

Toolchain: AGP 8.5.2, Gradle 8.8, Kotlin 1.9.24, JDK 17+. `local.properties` must point at your SDK (`sdk.dir=...`); Android Studio writes it for you.

Dependencies are deliberately few: Compose (BOM 2024.06), AndroidX WebKit, Media3 ExoPlayer, core-ktx, lifecycle. No DI, no navigation library, no image loader, no analytics, no Room.

---

## Install and first run

1. `adb install -r app/build/outputs/apk/debug/app-debug.apk`
2. Open SimpleInsta. You'll be asked to sign in to Instagram **once** — username + password (2FA works). The session persists, so that's the last time. Google/Facebook sign-in won't work in an embedded WebView.
3. The official Instagram app can stay installed — SimpleInsta works either way. It's a weaker commitment device with the official app around, but nothing breaks. See [the bypass problem](#the-bypass-problem).
4. Set SimpleInsta as the default handler for `instagram.com` links so shared reels land here instead of bouncing to the official app. (With the official app installed, Android may ask which app to use.)
5. Open a DM thread once. That's what fills the queue — see [why](#why-the-queue-fills-lazily).

---

## How it works

Three levers, in order of strength.

**1. End the feed at the data layer.** Instagram's web client is React + Relay. Every "load more" is gated on `page_info.has_next_page`. The injected policy rewrites every paginated connection in the response to `has_next_page: false`, so Relay's `loadNext()` becomes a no-op and Instagram renders its own end-of-feed card. No CSS tricks, no scroll interception.

**2. Refuse routes natively.** `/reels` and `/explore` are blocked in `shouldOverrideUrlLoading`, before a navigation commits. This is the only layer a page script cannot undo.

**3. Play DM reels outside Instagram.** If a shared reel opens in Instagram's viewer, finishing it rolls straight into algorithmic recommendations — the exact transition we're removing. So the app never mounts that viewer. It reads reel payloads out of the DM thread JSON, hands them to a native Media3 player, and that player has buttons instead of a pager.

The key idea is not "block scrolling." It's **stop being a stream**: if the client never asks for page N+1, the product is finite by construction.

---

## Project layout

```
app/src/main/
├── assets/policy/            ← the actual product logic, in JS
│   ├── 00_runtime.js         fetch/XHR patches, structural walkers, bridge
│   ├── 10_feed.js            watermark + page cap + ad strip
│   ├── 20_dm.js              reel harvesting from thread payloads
│   ├── 30_dom.js             nav hiding (CSS only — see note)
│   └── 90_health.js          tells Kotlin whether the policy took effect
├── java/app/enough/ig/
│   ├── AppViewModel.kt       queue state, watermark, degradation ladder
│   ├── core/                 Reel, QueueState, Watermark (pure, tested)
│   ├── data/                 Store (SQLite), Prefs
│   ├── policy/PolicyBundle   assembles the script from assets
│   ├── web/                  UrlGate, UrlPolicy, Bridge, WebHost, PolicyInjector
│   └── ui/                   AppRoot, QueuePane, Theme
└── test/                     WatermarkTest

tools/policy-test/            Node harness for the JS policy
PLAN.md                       the design doc this implements
```

Roughly 1,600 lines of Kotlin and 400 of JavaScript.

---

## Tests

```bash
node tools/policy-test/run.mjs            # 28 assertions on the policy engine
node tools/policy-test/bundle-check.mjs   # the assembled bundle parses and runs
./gradlew testDebugUnitTest               # 16 assertions on watermark + URL gating
```

The policy tests drive real payload shapes through the engine and assert the *observable* consequences: the ad is gone, `has_next_page` is false, the reel message carries the right URL and sender, the DM payload is byte-identical afterwards.

They are worth keeping. They caught two real bugs during implementation — a guard that silently
skipped short shortcodes, and a timestamp filter that rejected non-epoch values — and `UrlGateTest`
now locks in the fix for the one bug that actually reached a device.

---

## What's implemented vs. what the plan asked for

Implemented as designed: finite feed with watermark, page-cap safety valve, promoted-post stripping, native route blocking, DM reel queue, native player with Prev/Next, caught-up states, health reporting, degradation ladder, WebView renderer-crash recovery, single-instance lifecycle.

**Deviations, with reasons:**

1. **The native pagination gate became JS-only.** `PLAN.md` §6.1 proposed `shouldInterceptRequest` as a hard second tier. It can't work: GraphQL pagination is a POST, and `shouldInterceptRequest` cannot read request bodies. A URL-only counter would block likes, comments, and login along with the feed. Enforcement lives in the JS response rewrite, which *can* see the body. Native enforcement remains on navigation, where it is precise.
2. **Room → `SQLiteOpenHelper`.** Three tables and one bounded LRU. Room would add a Kotlin symbol processor and a Kotlin/KSP version-coupling constraint for no benefit. Schema is identical.
3. **DOM hiding instead of node deletion.** `PLAN.md` §6.3 says "delete routes, don't hide buttons." That's right for *routes* and wrong for *React nodes*: removing a node React owns throws `Failed to removeChild` on the next reconciliation, which is the classic wrapper-app crash. Routes are blocked natively; DOM affordances are hidden with CSS.
4. **Remote policy bundle deferred.** Fetching and executing JS at runtime is a remote-code-execution surface. `PolicyBundle` is structured so a signed *data* override can be added later; shipping remote *code* is not worth it in v1.
5. **The queue fills lazily.** The DM inbox payload contains thread previews, not reel media, so reels are harvested when you open a thread. Doing it eagerly would mean calling Instagram's private endpoints with your session — exactly the thing that got Barinsta shut down. Consequence: the Queue tab is empty until you open a chat.
6. **One WebView, reload on tab switch.** Two WebViews would make tab switching instant and cost ~200–400 MB. The plan's memory budget wins.

---

## Known gaps

- **"Only people I follow" is structural, not an allowlist.** Ads, suggested posts, and anything Instagram marks `friendship_status.following: false` are stripped; a "Following" tab is selected when the web build offers one. A hard allowlist would need the private API. If Instagram serves suggested reels without any of those markers, some will survive — the debug sheet's `kept / removed / pages` line is how you tell whether the filter is running at all.
- **Stories still work** if you reach one via a DM reply. Only the tray is hidden. Blocking `/stories/` would break story replies.
- **No push notifications.** No service, no polling, no battery cost. Check messages when you open the app.
- **Voice notes and some web gaps.** Instagram's web DM is slightly behind native.
- **No image thumbnails in the player.** A failed video shows text, not a poster frame. Adding Coil would double the APK.
- **WebView login can be flagged.** Rare, but Instagram sometimes challenges unfamiliar web sessions. If that happens, sign in on a desktop browser first.

---

## The bypass problem

Any client-side restriction is bypassable by opening the official Instagram app. If it's still installed, the "caught up" screen is theatre — SimpleInsta still blocks scrolling whenever you use it, but the official app is one tap away.

This app is a **commitment device, not a jail.** Its job is to make the good path frictionless and the bad path slightly annoying. Uninstalling the official app is what makes it real; nothing in the code can do that for you. Keeping it installed is fine for personal use — you just get the weaker version.

That said, Instagram does not have to be uninstalled for the app to *work*. It is a **behavioural** requirement, not a technical one, and the two get conflated. So there is a door: when Instagram fires a handoff, a chip offers **Hold to open Instagram** (1.6 s). Tapping does nothing; only a held press leaves. The same control is in the debug sheet, and `allowAppHandoff` turns it off entirely.

The design bet is that a reflex tap and a 1.6-second hold are different decisions. If holding ever feels like a formality, that bet has failed and the app should be honest about it rather than pretending a confirm dialog is a wall.

---

## Ethics

This is not a scraper and not a modded client:

- No private APIs, no token extraction, no automated actions.
- No content stored — URLs and shortcodes only, and no media ever written to disk.
- No ads stripped for commercial benefit; there is no commercial anything.
- Distributed as a signed APK, not on Play (Play policy prohibits unauthorised modification of third-party services).

It still likely violates Instagram's terms of service, which prohibit unauthorised clients. Practical exposure is account-level, not legal. It is a personal tool, one account, human-speed usage.

---

## Where to go next

1. **Run M0 properly.** Install, sign in once, confirm `[enough] runtime ready` in logcat, then scroll to the end of the feed. Everything else is speculative until that works.


2. **Capture real fixtures.** Save one real feed response and one real thread response, anonymize, drop them into `tools/policy-test/`. That converts the synthetic suite into a real regression suite.
3. **Then** consider the optional enforcer from `PLAN.md` §11.3, which is the only feature that addresses the actual failure mode: you opening Instagram anyway.
