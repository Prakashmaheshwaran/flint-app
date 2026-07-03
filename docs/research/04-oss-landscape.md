# Open-Source Landscape: Screen-Time / Focus / App-Blocker Apps (iOS & Android, 2023–2026)

## Summary of findings

The OSS landscape splits cleanly by platform because the **blocking mechanism is dictated by the OS**, not by app design:

- **iOS**: Everything funnels through Apple's **Screen Time / Family Controls stack** (`FamilyControls`, `ManagedSettings`, `ManagedSettingsUI`, `DeviceActivity`). There is no other sanctioned way to block apps. This requires the special `com.apple.developer.family-controls` entitlement (manual Apple approval, days-to-weeks), works only on physical devices, and forces you to work with **opaque tokens** (you can't read app names/bundle IDs). The standout OSS project is **Foqos**; the standout reusable library is **react-native-device-activity**.
- **Android**: There is no official "block this app" API. Every OSS project converges on the same pattern: **`AccessibilityService` (real-time, via `TYPE_WINDOW_STATE_CHANGED`) and/or `UsageStatsManager` (polled, ~500ms-delayed) + a foreground service + a full-screen overlay or `GLOBAL_ACTION_HOME` redirect.** A minority use a launcher-replacement model (Olauncher, Escape Launcher) to add friction rather than hard blocks. Standouts: **curbox**, **Reef**, **foqos-android**, **Olauncher / Escape Launcher**.

The most important cross-platform building block is **expo-app-blocker** / **react-native-device-activity**, which expose both mechanisms behind one API.

## Notable projects

### iOS

**Foqos** — https://github.com/awaseem/foqos (also forks: Niwreg, Klouckup, Ghussy/core-foqus)

- Swift + SwiftUI, SwiftData (persistence), CoreNFC, WidgetKit, App Intents (Shortcuts), Live Activities. MIT license.
- ~552 stars, 98 forks, 788 commits, 73 releases (v2.0.5), actively maintained. The most prominent OSS iOS app blocker; explicitly markets itself as a free alternative to Brick / Opal / Unpluq / Blok.
- **Mechanism**: Family Controls / Screen Time to apply shields. Differentiator is the **unlock friction model**: a session is started/stopped by tapping an NFC tag or scanning a QR code (QR deep link `https://foqos.app/profile/<UUID>`), optionally requiring the *same physical* tag to unblock — a software approximation of the hardware "Brick" approach.
- **Reuse/learn**: strategy pattern for blocking modes (manual / timer / NFC / QR), deep-linking for QR, Live Activities for lock-screen status, App Intents for automation, profile model. There is a separate **Family Foqos** (family-foqos.app) variant.

**ScreenBreak** — https://github.com/christianp-622/ScreenBreak

- 100% Swift / SwiftUI. ~118 stars, 29 forks, 55 commits; moderate interest, not actively developed.
- Cleanest *teaching* implementation of the full iOS 16 Screen Time stack: `DeviceActivity` for per-app/category analytics (pickups, notifications, screen time), `FamilyActivityPicker` → persisted `FamilyActivitySelection`, `ManagedSettings` to apply/lift shields on a timer. Includes a DeviceActivityMonitor extension (multiple shield intervals), a custom branded Shield Configuration extension, and a Widget extension via App Groups.
- **Reuse/learn**: the canonical extension layout (main app + monitor extension + shield-config extension + widget), App-Group cross-process communication, token→metric mapping.

**screen-time-api-agent-skill** — https://github.com/Siddhu7007/screen-time-api-agent-skill

- Not a library — an MIT "agent skill" / playbook (Python + Shell scaffolding) documenting authorization patterns, token selection, policy enforcement, custom shield architecture, DeviceActivity scheduling, and **App Review checklists** for shipping Screen Time apps. Useful as an implementation reference / de-risking checklist.

### Android

**curbox** — https://github.com/nethical6/curbox (supersedes the discontinued **DigiPaws**)

- 100% Kotlin. GPL-3.0. ~970 stars, 114 forks, ~799 commits; active. On F-Droid.
- **Mechanism**: `AccessibilityService`. Notably **declares no INTERNET permission** (provably local/private). Most feature-rich OSS Android blocker: app blocking, website + URL-path blocking, **short-form blocking (Reels/Shorts)**, "Granular UI Hiding" (hide specific in-app elements, e.g. the comments tab while keeping the app usable), auto-redirect to alternatives, and multiple **unlock-friction** mechanisms (retype a sentence, scan a QR, timed unlocks).
- **Reuse/learn**: in-app element hiding via accessibility node inspection; the "no internet permission" privacy posture as a trust signal; unlock-friction patterns.

**Reef** — https://github.com/PranavPurwar/Reef

- 100% Kotlin, Jetpack Compose, Material 3 Expressive. MIT (with trademark restrictions on the name/icon). ~289 stars, 209 commits, v4.3.0 (June 2026); very active. On IzzyOnDroid; Weblate translations.
- **Mechanism**: accessibility-based blocking that sends you home (`GLOBAL_ACTION_HOME`) when a blocked app opens. Per-app daily limits with warnings, routine scheduling, Pomodoro + count-up focus, **Strict mode** (lock yourself into a session), auto-DND, "Mindful Launch" friction, analytics with charts.
- **Reuse/learn**: modern Compose / Material 3 reference architecture, focus-session state machine, per-app daily-limit accounting, routine scheduling.

**foqos-android** — https://github.com/nish261/foqos-android

- Kotlin, Jetpack Compose + Material 3, Room (SQLite), ML Kit Barcode Scanning, CameraX, WorkManager, Coroutines. MIT. v0.2.0 (Feb 2026), claims "100% feature parity with iOS"; very low traction (1★) but a clean, documented port.
- **Mechanism**: `AccessibilityService` monitoring `TYPE_WINDOW_STATE_CHANGED`, blocking via `GLOBAL_ACTION_HOME`; 7 blocking strategies combining NFC / QR / manual / timer triggers.
- **Reuse/learn**: directly maps Foqos's iOS NFC/QR trigger model onto the Android accessibility mechanism — the best single reference for a cross-platform Flint that wants behavioral parity.

**Olauncher** — https://github.com/tanujnotes/Olauncher (and forks)

- Kotlin, GPLv3, ~3.6k stars (most popular in the space), on Play Store + F-Droid. Active community; many forks (OlauncherCF, mLauncher).
- **Mechanism**: not a blocker — a **minimalist launcher** that reduces usage by removing icons/grids, with no data collection. Friction-by-design rather than enforcement.

**Escape Launcher** — https://github.com/GeorgeClensy/Escape-Launcher

- Kotlin, Jetpack Compose + Material 3. MIT. ~361 stars, released Jan 2026; solo-maintained, active. On F-Droid. minSDK 26 → targetSDK 36.
- **Mechanism**: launcher-replacement with an **"app open countdown"** (a pause/reconsider screen before launching a distracting app) + screen-time dashboard, app hiding, Android 15+ Private Space / work-app support.
- **Reuse/learn**: the open-countdown friction screen and the screen-time dashboard built on `UsageStatsManager`.

**Other Android OSS worth noting**: Zenith (Compose digital-wellbeing tracker), DigiPause / PranavPurwar (gamified screen-addiction modes), UltraFocus, PureShield, Zenlock, tempo (cheat-proof blocker surviving reinstalls), seenot-app (intent-aware content-level intervention, not just app-level).

### Cross-platform reusable libraries (the most important reuse targets)

**react-native-device-activity** (Kingstinct) — https://github.com/kingstinct/react-native-device-activity

- Swift (58%) + TypeScript (36%), Expo module. MIT. ~167 stars, 32 forks, 31 releases (v0.6.1, Feb 2026); actively maintained — the best-maintained OSS Screen Time wrapper.
- Wraps `FamilyControls` (selection), `DeviceActivity` / ActivityMonitor (scheduling), `ManagedSettings` (shields) + `ShieldConfiguration` / `ShieldAction`. Exposes modal + inline + **persisted** `FamilyActivityPicker` components, `configureActions(activityName, callbackName, actions)` for monitor callbacks (e.g. `intervalDidStart`), shield-action handlers, and UserDefaults / App-Group cross-process persistence patterns.
- **Reuse/learn**: if Flint touches RN/Expo, this is a drop-in. Even for native, its Swift extension code and callback wiring are a reference implementation.

**expo-app-blocker** (eylonshm) — https://github.com/eylonshm/expo-app-blocker

- Swift + TS, Expo module. MIT. ~33 stars, 87 commits, 11 releases; active. The only OSS lib that **unifies both platforms** behind one API.
- iOS: `FamilyControls + ManagedSettings + DeviceActivity`. Android: `UsageStatsManager + Foreground Service + System Overlay`. APIs: `setBlockConfiguration()` / `setBlockedApps()`, `temporaryUnlock(minutes)` (usage-budget unlock), `FamilyActivityPickerView` (inline) + `presentFamilyActivityPicker()` (modal), `BlockedAppsNativeList`, customizable shield (title / subtitle / buttons / colors / blur).
- **Documented limitations to inherit**: iOS physical-device only, opaque tokens (no app names), Android ~500ms detection delay before overlay, both need manual permission grants, iOS shield customization limited to preset options.

**flutter_screentime** — referenced under the digital-wellbeing topic (Kotlin host, ~96★): a Flutter bridge to iOS Screen Time + Android equivalents, if Flint is Flutter-based.

## Comparison table

| Project | Platform | Stack | Blocking mechanism | License | Maintenance | Key reuse |
|---|---|---|---|---|---|---|
| Foqos (awaseem) | iOS | Swift, SwiftUI, SwiftData, CoreNFC, App Intents, Live Activities | Family Controls shields; NFC/QR to start/stop sessions | MIT | Active (552★, v2.0.5) | NFC/QR friction model, strategy pattern, Live Activities, App Intents |
| ScreenBreak | iOS | Swift, SwiftUI | DeviceActivity analytics + ManagedSettings shields | (repo) | Stale-ish (118★) | Full extension layout, App-Group IPC, token→metric mapping |
| screen-time-api-agent-skill | iOS | Docs / Python | N/A (reference) | MIT | New | Auth/token/shield patterns + App Review checklist |
| react-native-device-activity | iOS (RN/Expo) | Swift + TS | FamilyControls + DeviceActivity + ManagedSettings | MIT | Active (167★, v0.6.1) | Best-maintained Screen Time wrapper; pickers, monitor callbacks |
| expo-app-blocker | iOS + Android | Swift + TS (Expo) | iOS Screen Time; Android UsageStats + FG service + overlay | MIT | Active (33★) | Only unified cross-platform API; temporaryUnlock budget |
| curbox | Android | Kotlin | AccessibilityService (no INTERNET perm) | GPL-3.0 | Active (970★) | In-app element hiding, short-form blocking, unlock friction, privacy posture |
| Reef | Android | Kotlin, Compose, M3 | AccessibilityService → GLOBAL_ACTION_HOME | MIT* | Very active (289★, v4.3.0) | Compose architecture, per-app limits, strict mode, routines |
| foqos-android | Android | Kotlin, Compose, Room, ML Kit, CameraX | AccessibilityService (TYPE_WINDOW_STATE_CHANGED) + NFC/QR | MIT | New (v0.2.0) | Cross-platform parity with iOS Foqos; NFC/QR on Android |
| Olauncher | Android | Kotlin | Launcher replacement (friction, no hard block) | GPLv3 | Active (3.6k★) | Minimalist-launcher friction model |
| Escape Launcher | Android | Kotlin, Compose, M3 | Launcher + "app open countdown" pause screen | MIT | Active (361★) | Open-countdown friction, UsageStats dashboard |

\*Reef MIT has name/icon trademark restrictions.

## What Flint should borrow

1. **Don't reinvent the OS layer — wrap it.** On iOS there is exactly one path (FamilyControls / ManagedSettings / DeviceActivity). Study **ScreenBreak** for the native extension layout and **react-native-device-activity** for production-grade wrapper code and monitor-callback wiring. If Flint is cross-platform, **expo-app-blocker** is the closest thing to a turnkey foundation.

2. **On Android, default to `AccessibilityService` + foreground service + overlay / `GLOBAL_ACTION_HOME`** (the curbox / Reef / foqos-android consensus) for real-time hard blocking; use `UsageStatsManager` for analytics and limits. Re-check the accessibility permission on every service start — Android revokes it silently with no callback. Budget for the ~500ms detection latency.

3. **Copy Foqos's unlock-friction model**, not just on/off blocking. The proven differentiator (Brick / Unpluq research: ~78 min/day recovered with a physical tag vs ~54 min software-only) is making *unblocking* costly: NFC tag, QR scan, retype-a-sentence (curbox), timed delay, or strict mode (Reef). Software can approximate hardware friction.

4. **Adopt curbox's privacy posture as a feature**: ship Android with **no INTERNET permission** where possible and keep all data on-device — it's both a real privacy win and a strong, verifiable trust signal versus commercial competitors.

5. **Add a "pause/countdown" intervention layer** (Escape Launcher's app-open countdown; "Mindful Launch" in Reef) — cheap to build, high behavioral impact, and doesn't depend on hard enforcement.

6. **Plan for iOS realities early** (per riedel.wtf and the agent-skill checklist): apply for the `com.apple.developer.family-controls` entitlement immediately (long approval), design around **opaque tokens** (you cannot read app names, cannot reliably re-open the blocked app from a shield, tokens can silently change — FB14082790), test only on device, and note that users can revoke Screen Time permission with no third-party passcode protection (FB18794535). Budget around iOS 26 DeviceActivity regressions.

7. **Use modern Android scaffolding** from Reef / Escape Launcher (Kotlin + Jetpack Compose + Material 3, Room, WorkManager, Coroutines) and the foqos-android NFC/QR stack (ML Kit Barcode + CameraX) rather than building from scratch.

8. **Borrow content-granular blocking** (curbox's Reels/Shorts + in-app element hiding, seenot's intent-aware intervention) — blocking *inside* an allowed app is a meaningful differentiator over binary app-level blocks.

## Sources

- https://github.com/topics/digital-wellbeing
- https://github.com/topics/app-blocker
- https://github.com/topics/screen-time
- https://github.com/nethical6/curbox
- https://github.com/nethical6/digipaws
- https://f-droid.org/packages/nethical.digipaws/
- https://github.com/PranavPurwar/Reef
- https://github.com/awaseem/foqos
- https://github.com/Niwreg/foqos
- https://www.foqos.app/
- https://github.com/nish261/foqos-android
- https://github.com/christianp-622/ScreenBreak
- https://github.com/kingstinct/react-native-device-activity
- https://github.com/kingstinct/react-native-device-activity/blob/main/README.md
- https://github.com/eylonshm/expo-app-blocker
- https://libraries.io/npm/expo-app-blocker
- https://github.com/Siddhu7007/screen-time-api-agent-skill
- https://github.com/tanujnotes/Olauncher
- https://play.google.com/store/apps/details?id=app.olauncher
- https://github.com/GeorgeClensy/Escape-Launcher
- https://f-droid.org/packages/com.geecee.escapelauncher/
- https://riedel.wtf/state-of-the-screen-time-api-2024/
- https://medium.com/@juliusbrussee/a-developers-guide-to-apple-s-screen-time-apis-familycontrols-managedsettings-deviceactivity-e660147367d7
- https://medium.com/@jc_builds/building-a-powerful-ios-app-blocker-with-screen-time-apis-the-complete-guide-f6272bd00fc4
- https://developer.apple.com/documentation/xcode/configuring-family-controls
- https://developer.apple.com/forums/thread/819997
- https://github.com/ngdathd/ForegroundActivity
- https://cybernews.com/reviews/brick-phone-blocker-review/
- https://www.unpluq.com/pages/faq
- https://www.nbcnews.com/select/shopping/brick-phone-app-blocker-review-rcna259740
