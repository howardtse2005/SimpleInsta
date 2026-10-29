# SimpleInsta

**Instagram, but it ends.**

A ~1.6 MB Android app that shows you your feed, your messages, and the reels people actually sent you — and then stops. No Explore. No Reels tab. No "just one more."

> **Note: this is still in debug phase and bugs may be encountered.**



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
