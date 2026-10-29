# SimpleInsta

**Instagram, but without doomscrolling.**

A ~1.6 MB Android app that shows you your feed, your messages, and the reels people actually sent you — and then stops. No Explore. No Reels tab. No doomscrolling.

> **Note: this is still in debug phase and bugs may be encountered.**



---

## What it does

| Tab          | What it is                                                                                           |
| ------------ | ---------------------------------------------------------------------------------------------------- |
| **Feed**     | Instagram's web feed with pagination capped and ads/suggested posts stripped. It ends.               |
| **Messages** | Instagram's web DMs, untouched. Read, reply, react. Only can open reels sent to you, no scrolling    |

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

---

## Ethics

This is not a scraper and not a modded client:

- No private APIs, no token extraction, no automated actions.
- No content stored — URLs and shortcodes only, and no media ever written to disk.
- No ads stripped for commercial benefit; there is no commercial anything.
- Distributed as a signed APK, not on Play (Play policy prohibits unauthorised modification of third-party services).

It still likely violates Instagram's terms of service, which prohibit unauthorised clients. Practical exposure is account-level, not legal. It is a personal tool, one account, human-speed usage.

---
