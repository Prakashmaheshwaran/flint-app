# Opal Feature Inventory — Product Spec Source of Truth

> Research doc for **Flint — Peak Focus**, a 100% free, open-source alternative to Opal (opal.so / opalapp.com).
>
> **Mission constraint:** *Everything Opal paywalls behind "Opal Pro" must be free in Flint.* This document is the exhaustive inventory of Opal's feature surface, the source of truth for the Flint product spec. Every feature lists Opal's tier, platform, and a **Flint plan** describing how Flint delivers it for free and the technical mechanism (iOS Screen Time / Family Controls API, Android Accessibility + UsageStats, or backend).

---

## Executive Summary

Opal is a screen-time / focus app for iOS (mature) and Android (early, in-development). Its entire blocking engine is built on top of the platform OS primitives — on iOS, Apple's **Screen Time / Family Controls** stack (`FamilyControls`, `ManagedSettings`, `DeviceActivity`), and on Android, the **AccessibilityService** API (Android 14+) plus usage stats. Opal itself does not invent enforcement; it orchestrates OS shields, usage thresholds, and break-difficulty logic, then wraps them in scheduling, gamification, and accountability features.

**How Opal makes money (the paywall map).** Opal's free tier is deliberately limited so that the genuinely useful "stick to it" behaviors cost money. The paywalled surface that Flint must liberate includes:

- **Recurring / Smart Schedules** — free tier is capped (official FAQ: 3 recurring sessions; some sources say 1). Unlimited recurring schedules require Pro.
- **Advance scheduling beyond 24 hours** — free is limited to 24h ahead; >24h (a precondition for true weekly recurrence) is Pro.
- **Deep Focus / "No way, I'm hardcore" break level** — the non-bypassable accountability mode, the single most valuable feature, is Pro-only.
- **Allow List ("brick phone" / whitelist mode)** — Pro-only.
- **Emergency Pass** — the controlled escape hatch for hardcore mode — Pro-only.
- **Sleep Mode "Full Assist" / Morning Assist "Full Assist"** — gated behind the Pro escape mechanism.

**What this means for Flint.** Flint's competitive wedge is simple: ship the *whole* engine for free. The hard part is not the paywall (we remove it) — it is faithfully reimplementing the OS-level enforcement and anti-bypass guarantees that make blocking actually stick. On iOS this is well-trodden: the Screen Time / Family Controls APIs expose everything Opal uses (shields, schedules, usage thresholds, opaque app tokens via `FamilyActivityPicker`). The main constraints Flint inherits from iOS are the same ones Opal hits: opaque app tokens (you cannot enumerate apps yourself), unreliable third-party-browser domain tracking (only Safari is uniformly tracked), and the requirement that the user grant Screen Time permission and manage the system "Always Allowed" list.

**Platform reality.** iOS is the lead platform with full feature parity. Android (for both Opal and Flint) is harder and less complete: AccessibilityService-based enforcement, Android 14+ only for reliable blocking, and several features (Open Limits, Allow List, Focus Filter sync, Sleep Mode, Emergency Pass) absent on Opal's Android build. Flint should target iOS feature-complete first, then bring Android to parity using AccessibilityService + UsageStatsManager.

**Flint v1 recommendation (detailed at the end):** ship the core blocking loop (Sessions: Block Now + Schedule), Block List + Allow List, all three break-difficulty levels **including hardcore for free**, Time Limits, Open Limits, the foolproof anti-bypass suite, and a free Emergency Pass — all on iOS via Screen Time. Defer gamification, Sleep/Morning Assist content (soundscapes/meditations), and full Android parity.

---

## Legend

- **Tier** — Opal's pricing tier for the feature: `free`, `paid` (Opal Pro), or `unknown` (not explicitly documented; inferred where noted).
- **Platform** — `iOS`, `Android`, or `both`.
- **Flint plan** — always **FREE in Flint** + the technical mechanism: **iOS = Screen Time / Family Controls API**, **Android = AccessibilityService + UsageStatsManager**, **backend = server-side** (sync, accountability, cross-device). The whole point of Flint is that nothing here is paywalled.
- **Flint status** — where the implementation actually stands (July 2026). Markers: **implemented — verified earlier** (exercised before the most recent merges — Android end-to-end on an emulator, iOS in the Simulator + unit tests; the Simulator cannot grant Family Controls or apply shields, so no iOS row claims proven enforcement — a physical-device pass is still pending across the board); **implemented — verification pending** (merged, but the fresh macOS compile pass and/or Android emulator re-run has not happened yet); **partial** (a subset shipped, gap noted inline); **not yet** (not implemented). Detailed, candid per-platform state: [`ios-app/README.md`](../../ios-app/README.md), [`android-app/README.md`](../../android-app/README.md).

---

## Area 1 — Core Blocking Engine (Sessions, Block/Allow Lists, Website Blocking)

### 1.1 Blocking Sessions ("Blocks")

| Field | Value |
|---|---|
| **Name** | Blocking Sessions (Blocks) |
| **Description** | The central blocking primitive. A Session/Block blocks chosen apps and websites for a scheduled or immediate period. Created from the Blocks/Home tab via "+ New Block", then "Block Now" (immediate timer) or "Create Schedule" (recurring/scheduled). User customizes name, time, days, blocked apps/websites, and break/protection level. To stop/edit: open the active Session, wait through a brief countdown, then Edit / Break / Stop (Stop gated by protection level). |
| **Opal tier** | free |
| **Platform** | both |
| **Flint plan** | **FREE.** iOS: `ManagedSettings` shields + `DeviceActivity` schedule, app/site list from `FamilyActivityPicker` (opaque tokens). Android: AccessibilityService monitors foreground app and overlays a block screen. Sessions are an independent layer that stacks with Time Limits / Open Limits. |
| **Flint status** | **Implemented on both.** iOS Block Now + Schedules verified earlier (Simulator + unit tests; on-device enforcement pass pending). Android core block + schedule gating emulator-verified earlier; the integrated app (new engine + authoring UI) awaits an emulator re-run. |

### 1.2 Block Now (instant focus timer / Pomodoro)

| Field | Value |
|---|---|
| **Name** | Block Now (instant focus timer / Pomodoro) |
| **Description** | One-tap immediate focus session for a set duration. Wireable to Apple Shortcuts ("Start Pomodoro") and Siri, with configurable focus length, breaks, and repeats. |
| **Opal tier** | free |
| **Platform** | both |
| **Flint plan** | **FREE.** iOS: immediate `ManagedSettings` shield + local timer; expose an App Intent ("Start Flint Session") for Shortcuts/Siri parity. Android: AccessibilityService + foreground service timer. |
| **Flint status** | **Implemented (iOS) — verified earlier:** timed shield, live countdown, stop gated by break level; a "Start Pomodoro" intent ships via 3.2 (device pass pending). Android: **partial** — unscheduled manual rules act as immediate blocks in the engine, but a dedicated one-tap timed-session UI is not built yet. |

### 1.3 Scheduled Sessions (single, up to 24h ahead)

| Field | Value |
|---|---|
| **Name** | Scheduled Sessions (single, ≤24h ahead) |
| **Description** | Schedule a Session to start at a specific time on chosen days. Opal free accounts can schedule only up to 24 hours in advance. |
| **Opal tier** | free |
| **Platform** | both |
| **Flint plan** | **FREE, with the 24h cap removed.** iOS: time-based `DeviceActivitySchedule`. Android: scheduled via `AlarmManager`/`WorkManager` waking the enforcement service. No advance-time cap in Flint. |
| **Flint status** | **Implemented on both — no 24h cap.** iOS verified earlier (Simulator + tests; device pass pending). Android schedule gating emulator-verified earlier; the new schedule-authoring UI is merged, emulator re-run pending. |

### 1.4 Recurring Sessions / Smart Schedules (weekly, >24h ahead)

| Field | Value |
|---|---|
| **Name** | Recurring Sessions / Smart Schedules |
| **Description** | Sessions that recur weekly and start more than 24h ahead — daily working hours, sleep, morning routine, gym, study blocks. Ships with a preset routine library ("Laser Focus", "Rise and Shine" 6–9 AM, Reading Time, Gym Time, Weekend Limit). Configured via "Create Schedule" with day-of-week ("On these days") + a time window. |
| **Opal tier** | **paid** (free capped at ~3 recurring per official FAQ; some sources say 1) |
| **Platform** | both |
| **Flint plan** | **FREE and UNLIMITED — this is a headline anti-paywall win.** iOS: repeating `DeviceActivitySchedule` entries, one per recurring rule, no count cap. Android: recurring `AlarmManager`/`WorkManager` triggers. Ship a free preset routine library mirroring Opal's. |
| **Flint status** | **Implemented — unlimited recurring rules on both platforms** (no count cap); same verification state as 1.3. The Opal-style preset *routine* library: **Android implemented** — the five templates above as one-tap prefilled drafts in the Blocklist overview (schedule + break level prefilled, user picks their own apps; JVM-tested, emulator pass pending); **iOS not yet** (saved app groups exist — see 1.10). |

### 1.5 Advance scheduling (>24 hours ahead)

| Field | Value |
|---|---|
| **Name** | Advance scheduling (>24h ahead) |
| **Description** | Opal free is limited to scheduling ≤24h in advance; Pro removes the limit (precondition for true weekly recurrence). |
| **Opal tier** | paid |
| **Platform** | iOS |
| **Flint plan** | **FREE — no advance-time cap.** iOS: arbitrary future `DeviceActivitySchedule` start dates. Backend optionally mirrors schedule definitions for cross-device sync. |
| **Flint status** | **Implemented (iOS)** — arbitrary future start dates, no cap; verified earlier (Simulator + tests; device pass pending). Android schedules carry no advance cap either. Cross-device mirroring: **not yet** (v1 is local-only). |

### 1.6 Block List (select apps/websites to block)

| Field | Value |
|---|---|
| **Name** | Block List |
| **Description** | Default mode: choose specific apps + websites to block during a Session; everything else stays usable. Selection via "Apps Blocked" opening Apple's Screen Time picker; soft cap (~49 individual items, more via whole-category selection). |
| **Opal tier** | free |
| **Platform** | both |
| **Flint plan** | **FREE.** iOS: `FamilyActivityPicker` returns opaque `ApplicationToken`/`ActivityCategoryToken`; store tokens (cannot enumerate apps ourselves — same constraint Opal has). Android: enumerate installed apps via `PackageManager` (Android can list packages, unlike iOS) and match foreground package name in the AccessibilityService — Flint can actually offer a richer, freely-typed app picker on Android. Third-party browsers (Chrome, Firefox, Ecosia, Opera GX) are not reliably represented in the iOS picker — document the limitation. |
| **Flint status** | **Implemented on both.** iOS `FamilyActivityPicker` token flow verified earlier (device pass pending). Android `PackageManager`-backed app picker + typed-domain input merged — emulator re-run pending. |

### 1.7 Allow List (whitelist / "brick phone" mode)

| Field | Value |
|---|---|
| **Name** | Allow List (whitelist / "brick phone" mode) |
| **Description** | Inverted blocking: blocks ALL apps + websites except the few the user explicitly allows — turns the device into a dumb/brick phone. Reusable Allow List presets apply to scheduled or immediate Sessions (bedtime, morning). Adult content always restricted; Private Browsing disabled during Allow List sessions. |
| **Opal tier** | **paid** (Opal Pro exclusive) |
| **Platform** | iOS (Opal); both for Flint |
| **Flint plan** | **FREE — major anti-paywall win.** iOS: shield everything except the allowed tokens (`ManagedSettings` shield-all + allow set). Must surface the system "Always Allowed" list since it supersedes our restrictions. Android: invert AccessibilityService logic — block any foreground package not on the allow set. |
| **Flint status** | **Implemented on both.** iOS ships Allow List in Schedules (shield-all-except); verified earlier in Simulator + tests, enforcement device-pass pending. Android allow-list mode lives in the rule model, decision engine, and rule editor — merged, emulator re-run pending. |

### 1.8 Website & domain blocking

| Field | Value |
|---|---|
| **Name** | Website & domain blocking |
| **Description** | Block websites/domains during Sessions. Opal does NOT let users type a custom domain on iOS — it relies on Apple's Screen Time list of frequently-used sites (workaround: visit a site in Safari ~10 min so iOS tracks it, then it appears in the picker). Allow List is the recommended path for true allow-only web control. A separate Chrome/desktop story implies a browser-extension/desktop component. |
| **Opal tier** | free |
| **Platform** | both |
| **Flint plan** | **FREE, and aim to beat Opal here.** iOS: `ManagedSettings` web-content filtering for Safari; investigate `webContentFilter` to support manually-typed domains where the API allows (Opal punts on this). Android: a Flint accessibility/VPN-style local filter or a Flint browser extension can block typed domains across Chrome/Firefox — Android is not limited to Safari-style tracking, so Flint can offer free custom-domain blocking that Opal cannot. Backend optionally hosts shared blocklists (e.g., social, adult, news categories). |
| **Flint status** | **Implemented on both — verification pending.** iOS web-domain shields ride the picker/monitor path (verified earlier); the newer Safari custom block/allow-domain restrictions are merged, macOS compile pass pending. Android typed-domain rules + AccessibilityService URL detection feed the engine (emulator re-run pending) — the freely-typed domain blocking Opal doesn't offer. Shared/category blocklists: **not yet**. |

### 1.9 Safari / browser blocking & Private Browsing lockdown

| Field | Value |
|---|---|
| **Name** | Safari blocking & Private Browsing lockdown |
| **Description** | During active Sessions (and always during Allow List sessions), Safari Private Browsing is disabled so users can't evade web blocks via incognito; clearing browsing history is also prevented. Safari is the primary supported browser; combinable with iPhone Focus Filters. |
| **Opal tier** | free |
| **Platform** | iOS |
| **Flint plan** | **FREE.** iOS: `ManagedSettings` content/web restrictions toggle Private Browsing off and lock history for the session duration. Android: enforce via accessibility/extension where browser incognito is detectable. |
| **Flint status** | **Implemented (iOS) — merged, compile pass pending.** Session-scoped Safari restrictions; iOS ties Private Browsing + Clear History to web restrictions, so both lock down. The Simulator can't set web restrictions, so enforcement is additionally device-pass pending. Android incognito handling: **not yet**. |

### 1.10 App Groups / Blocking Groups + preset routines

| Field | Value |
|---|---|
| **Name** | App Groups / Blocking Groups + presets ("Gems"/templates) |
| **Description** | Save reusable groups of apps and reuse across Sessions. Ships a preset routine template library. (Opal also brands users as "Gems" with a gamified streaks/reward layer — gamification, not a blocking control.) |
| **Opal tier** | free |
| **Platform** | both |
| **Flint plan** | **FREE.** iOS: persist saved sets of Screen Time tokens; auto-switch by binding to system Focus Modes via Focus Filters (see 3.1). Android: persisted package-name sets. Backend syncs group definitions across devices. |
| **Flint status** | **Implemented (iOS) — verified earlier:** named reusable picker selections applied in one tap; saved groups also surface as a preset parameter in the Shortcuts intents (3.2). Preset routine template library: **Android implemented** (see 1.4; one-tap prefilled rule drafts), **iOS not yet**. Android named groups: **not yet** (rules persist individually). Cross-device sync: not in v1. |

---

## Area 2 — Limits & Protection (Time Limits, Open Limits, Difficulty, Anti-Bypass)

### 2.1 Time Limits (usage-based auto-block)

| Field | Value |
|---|---|
| **Name** | Time Limits |
| **Description** | Set a daily cumulative usage threshold ("Time Allowed") for selected apps/websites; once the combined limit is hit they auto-block for a configurable "Then Block Until" period. Limits are CUMULATIVE across the group (30 min shared by Instagram + TikTok), so per-app limits need separate entries. Configurable per day-of-week + reset difficulty. Known iOS bug: can trigger prematurely (fix via update + "Reload Blocks"). |
| **Opal tier** | free |
| **Platform** | both |
| **Flint plan** | **FREE.** iOS: `DeviceActivity` usage thresholds (`DeviceActivityEvent` with a time threshold) trigger the shield. Android: aggregate foreground time via `UsageStatsManager` / `queryUsageStats`, trigger overlay block at threshold. Independent stacking layer. Hardcore reset tier free in Flint. |
| **Flint status** | **Implemented on both.** iOS daily budgets via threshold events, incl. the free hard-reset tier — verified earlier (device pass pending). Android per-app daily limits emulator-verified earlier on the AccessibilityService path; the limit-authoring UI is merged (re-run pending); budgets on the usage-poll fallback path are **not yet** enforced. |

### 2.2 Open Limits (launch-count limit)

| Field | Value |
|---|---|
| **Name** | Open Limits |
| **Description** | Cap the number of times per day specific apps/websites can be opened ("Opens Allowed"). After the quota, a shield appears; intentionally choosing "Use [app]" consumes an unlock (accidental taps don't). Lockout persists until next day or a configurable window. Separate layer from Sessions / Time Limits. |
| **Opal tier** | free (hardcore no-reset tier is Pro) |
| **Platform** | iOS (Opal — "coming soon" on Android) |
| **Flint plan** | **FREE, including the no-reset tier.** iOS: `DeviceActivityEvent` counting app-open events vs. a count threshold → shield. Android: count launch transitions via AccessibilityService foreground-app changes → overlay block. Flint can ship Open Limits on Android (Opal hasn't). |
| **Flint status** | **Implemented on both — verification pending.** iOS is user-reachable end-to-end in code: the enforcement engine (intentional opens counted at the shield; fails closed if state is unreadable) plus the config UI (Limits tab → Open Limits), host-app arming + day-boundary re-arm, and the "Use app (N left)" block-screen label — the config-UI/arming layer awaits the macOS compile pass, and grants enforce on a real device only. Known limit: apps blocked by another layer's *category* rule are undetectable to the open-limit layer (opaque tokens), so a tap there still charges an open. Android open-limit models, engine policy, persistence, and editor UI are merged (emulator re-run pending). |

### 2.3 Blocking strength / protection levels (break difficulty)

| Field | Value |
|---|---|
| **Name** | Protection levels (break difficulty) |
| **Description** | Each Session, Time Limit, and Open Limit has three intensity tiers: (1) "Yes, make it easy" — break/cancel freely; (2) "Yes, but make it harder" — extended waits/delays between breaks, reset only inside Opal; (3) "No way, I'm hardcore" — cannot break or stop early, no resets, one weekly emergency pass. |
| **Opal tier** | tiers 1–2 free; **tier 3 (hardcore) paid** |
| **Platform** | both |
| **Flint plan** | **ALL THREE FREE.** Enforced in Flint's own session-state logic on top of the OS shield (iOS Screen Time shield / Android overlay). The hardcore tier — the core accountability value — is free in Flint, including the friction delays and reset-gating. |
| **Flint status** | **Implemented on both — all three tiers free.** iOS verified earlier (Simulator + tests; device pass pending). Android Easy/Harder/Hardcore live in the pure-Kotlin engine with a break-level-aware block screen on both enforcement paths — merged, emulator re-run pending. |

### 2.4 Deep Focus (maximum / non-bypassable protection)

| Field | Value |
|---|---|
| **Name** | Deep Focus (maximum protection) |
| **Description** | Strongest mode: once started you cannot cancel, pause, or bypass until it ends. Equivalent to a session at "No way, I'm hardcore". Includes one weekly emergency pass on iOS. |
| **Opal tier** | **paid** (Opal Pro) |
| **Platform** | both |
| **Flint plan** | **FREE — flagship anti-paywall win.** iOS: session-state logic blocks early-stop, combined with foolproof features (block uninstall, lock Screen Time access, prevent clock change for the duration). Android: hardcore sessions run to completion; enforce via AccessibilityService + device-admin-free uninstall guards where possible. Free weekly emergency pass (see 2.6). |
| **Flint status** | **Implemented on both** as the free Hardcore tier (2.3) with the free weekly Emergency Pass as the exit (2.6); same verification state as 2.3. The deeper anti-bypass hardening (uninstall / time-change guards) is still **partial** — see 2.5. |

### 2.5 Foolproof / anti-bypass protections

| Field | Value |
|---|---|
| **Name** | Foolproof / anti-bypass suite |
| **Description** | (1) Screen Time Passcode (strongest — a 4-digit code, ideally held by a trusted person, prevents uninstalling Opal even after sessions end); (2) hardcore difficulty; (3) Lock Screen Time Access (prevents disabling mid-session, redirects Settings back to app); (4) App Uninstall Protection (blocks deleting apps or changing date/time during an active session); (5) Pin Code to open the app itself. |
| **Opal tier** | free (except hardcore difficulty, which is Pro) |
| **Platform** | both |
| **Flint plan** | **FREE (all five, including the underlying hardcore difficulty).** iOS: rely on Apple's Screen Time passcode + `ManagedSettings` restrictions (`denyAppRemoval`, restrict Screen Time settings access, prevent date/time change) for the session window; app-level PIN in Flint. Android: use `UsageStatsManager` + AccessibilityService to detect/redirect Settings access attempts and guard uninstall flows (device-admin optional). |
| **Flint status** | **Partial.** Shipped: iOS app-open PIN (salted hash, never stored plaintext — verified earlier) and a Settings screen surfacing the system Screen Time passcode as the real anti-uninstall guarantee; Android permission-health checks + exit diagnostics (merged, re-run pending). **Not yet:** session-scoped uninstall / date-time-change restrictions and Settings-redirect guards. |

### 2.6 Emergency Pass

| Field | Value |
|---|---|
| **Name** | Emergency Pass |
| **Description** | One-per-week "get out of jail" pass to end/break a hardcore (Deep Focus) block early — a controlled escape hatch for the most restrictive mode. Resets weekly. After use, recurring blocks re-initiate unless manually edited. |
| **Opal tier** | **paid** (Opal Pro) |
| **Platform** | iOS (Opal — "coming soon" on Android) |
| **Flint plan** | **FREE.** Pure app-state logic: a weekly-resettable token that, when spent, lifts the shield and allows an early stop of hardcore Sessions / Time Limits / Open Limits. iOS + Android from day one (Flint beats Opal's Android gap). Track the weekly reset locally; optionally mirror to backend for accountability/cross-device. |
| **Flint status** | **Implemented on both — free, weekly.** iOS pass ends a running Hardcore session early; verified earlier (device pass pending). Android weekly-pass policy is merged in the engine (emulator re-run pending) — Opal's Android build still lacks this. Backend mirroring: not in v1. |

---

## Area 3 — Scheduling Automation, Sleep/Morning, Cross-Device

### 3.1 iOS Focus Filter integration (auto start/stop with Focus modes)

| Field | Value |
|---|---|
| **Name** | iOS Focus Filter integration |
| **Description** | Add an Opal Focus Filter to any iOS Focus mode (Work, Sleep, Personal). When that Focus turns ON, the associated Block List/Session starts automatically; OFF stops it. Opal's official answer to context/location-based blocking (bind to a Focus, trigger the Focus by schedule/location via iOS). One-directional: ending Focus ends the session, but ending the session in-app does NOT turn off the Focus. Added in Opal v3.1.1. |
| **Opal tier** | unknown (not explicitly tagged) |
| **Platform** | iOS |
| **Flint plan** | **FREE.** iOS: implement the Focus Filter API (`SetFocusFilterIntent` / `AppIntents`) so a system Focus activates a Flint Block List. iOS 16+ only. No Android equivalent (Android has no system Focus Filter API — approximate via app-open/time triggers). |
| **Flint status** | **Implemented (iOS) — merged, verification pending.** A `SetFocusFilterIntent` attaches Flint to any system Focus; per-Focus preset + strictness are chosen in iOS Settings, with in-app defaults. macOS compile pass pending, and Focus filters only fire on a real device — device pass pending. |

### 3.2 Shortcuts / Automations triggers (DND, location, app-open, time)

| Field | Value |
|---|---|
| **Name** | Shortcuts / Automations triggers |
| **Description** | iOS Shortcuts Automations start/stop Sessions on contextual triggers via a "Start Session" action: (1) when DND turns on; (2) location-based ("Arrive" at a place, optionally time-bounded); (3) when a specific app opens; (4) launch a session + Apple Music focus playlist; (5) notify a contact before going offline. This is how Opal achieves location-based / context triggers — Opal has no location access itself; iOS recognizes the trigger and tells Opal to start. NFC-tag triggering also routes through Shortcuts. |
| **Opal tier** | unknown |
| **Platform** | iOS |
| **Flint plan** | **FREE.** iOS: expose App Intents ("Start Flint Session", "Stop Session", "Start Pomodoro") consumable by the Shortcuts app for DND/location/app-open/time/NFC automations. Android: approximate with Tasker-style intents + AccessibilityService app-open triggers; native location triggers via geofencing (`Geofence` API) since Android *does* grant apps location access. |
| **Flint status** | **Implemented (iOS) — merged, verification pending.** Start Session / Start Pomodoro / Stop Session App Intents with Siri phrases, auto-discovered from the app binary; saved app groups surface as a preset parameter. Compile pass pending; the sessions they start enforce on a real device only. Android automation intents / geofencing: **not yet**. |

### 3.3 Sleep Mode (bedtime downtime) + Morning Assist

| Field | Value |
|---|---|
| **Name** | Sleep Mode + Morning Assist |
| **Description** | Dedicated wind-down/wake-up downtime engine separate from Sessions/Limits. Set bedtime + wake schedule. Sleep Assist: Off / "Wind Down" (harder app access, you keep control) / "Full Assist" (blocks ALL apps except an allowlist; exit only via Emergency Pass). Morning Assist: Off / "Slow Uplift" (gentle reminders if phone used within 30 min of waking) / "Full Assist" (blocks all non-allowlisted apps for 1 hour after waking). Provides insights (last scroll time, apps used before sleep, morning usage) plus soundscapes, sleep stories, meditations (Box Breathing, Body Scan). |
| **Opal tier** | unknown (Full Assist relies on Pro Emergency Pass to exit, implying effective Pro dependency) |
| **Platform** | iOS |
| **Flint plan** | **FREE (including Full Assist + the free Emergency Pass to exit).** iOS: bedtime/wake `DeviceActivitySchedule` + Allow List shield (Flint's free Allow List, 1.7) for Full Assist; reuse the free Emergency Pass (2.6) as the exit. Usage insights from `DeviceActivityReport`. Android: scheduled overlay block via AccessibilityService. **Defer** the bundled soundscapes/sleep-stories/meditations content for v1 (content production, not a blocking control). |
| **Flint status** | **Implemented (iOS, blocking-only) — merged, verification pending.** Bedtime→wake windows on chosen nights; Sleep Assist Off / Wind Down / **free Full Assist**; optional enforced morning window; one saved app group allowed overnight — materialized as rules on the Schedules engine. Soundscapes/meditations deferred by design (Slow Uplift stores the choice but arms no shield, and says so). Compile + device passes pending. Android: **not yet**. |

### 3.4 Cross-device / desktop blocking (Focus Rules across phone + desktop)

| Field | Value |
|---|---|
| **Name** | Cross-device / desktop blocking ("Focus Rules") |
| **Description** | Block lists / Focus Rules apply across iPhone, iPad, and desktop (Mac app + Chrome extension), so a scheduled block extends beyond the phone to the computer browser. Tier split for desktop not explicitly documented. |
| **Opal tier** | unknown |
| **Platform** | iOS (+ iPad, Mac, Chrome) |
| **Flint plan** | **FREE.** Backend: a sync service stores Block Lists / schedules per account and pushes to all devices. iOS/iPadOS: Screen Time shields (shared via iCloud account). Desktop: a free Flint Chrome/Firefox extension + optional Mac helper enforcing the same blocklists. **Defer to post-v1** (requires backend + extension + Mac client). |
| **Flint status** | **Not yet implemented** — deliberately post-v1: v1 is local-only (no accounts, no backend), and this needs a sync service + browser extension + Mac helper. |

### 3.5 Android implementation & feature gaps

| Field | Value |
|---|---|
| **Name** | Android implementation & gaps |
| **Description** | Opal's Android app supports Sessions (Block Now + Schedule), recurring Smart Schedules, Time Limits (with day scheduling + "Then Block Until"), and the three break levels. It LACKS Open Limits, Allow List, Focus Mode/Filter integration, Sleep Mode, and a working Emergency Pass (hardcore runs to completion). Uses AccessibilityService (Android 14+); no root/device-admin. Blocking effectively limited to Android 14+. Still in development, no iOS/Mac parity. |
| **Opal tier** | unknown |
| **Platform** | Android |
| **Flint plan** | **FREE, and target full parity — a key differentiator.** Android: `AccessibilityService` (foreground-app detection + overlay block) + `UsageStatsManager` (time/launch counting) + `AlarmManager`/`WorkManager` (scheduling) + `Geofence` (location triggers). Flint should ship Open Limits, Allow List, and Emergency Pass on Android where Opal does not. Support below Android 14 best-effort. |
| **Flint status** | **Substantially implemented — verification pending.** The Android engine now carries break levels, Open Limits, the weekly Emergency Pass, allow-list mode, and typed-domain targets — several of which Opal's Android build lacks — plus DataStore persistence, an OEM/battery resilience layer (boot re-arm, exit diagnostics, permission health), rule/schedule/limit authoring, screen-time stats, a shared branded block screen across both detection paths, and a unified ISO weekday convention. Core blocking was emulator-verified earlier; the integrated app awaits an emulator re-run. `minSdk 23` (best-effort below Android 14, as planned). Sleep Mode / Focus-style automation: **not yet**. |

---

## Gamification & Rewards (noted, not a blocking control)

Opal brands its users as **"Gems"** and runs a gamified streaks/rewards layer earned by completing sessions. This is engagement, not enforcement. **Flint plan:** optional, free, local-first streaks/achievements; low priority for v1. No paywall. **Flint status:** not yet implemented, as planned — engagement, not enforcement.

---

## Cross-Cutting iOS Constraints (inherited by Flint)

These are platform limits both Opal and Flint must live with on iOS — document them in onboarding:

- **Opaque app tokens.** `FamilyActivityPicker` returns opaque tokens; an app cannot enumerate or programmatically add arbitrary apps. (Android *can* via `PackageManager` — Flint should exploit this for a better Android picker.)
- **Third-party browser domains.** iOS only uniformly tracks website usage via Safari; Chrome/Firefox/Ecosia/Opera GX domain blocking is unreliable through Screen Time. Flint can do better on Android and via a desktop extension.
- **"Always Allowed" supersedes everything.** The system Screen Time "Always Allowed" list overrides app restrictions; Flint must surface and explain it (especially before Allow List sessions).
- **Permission grant required.** The user must grant Family Controls / Screen Time authorization; Flint cannot enforce without it.
- **No app-level location.** Like Opal, Flint's iOS app gets location triggers only through Focus Filters / Shortcuts, not direct location access.

---

## Minimum-Viable Flint v1 Feature Set

Build the core blocking loop and the highest-value anti-paywall wins first, iOS-first via Screen Time / Family Controls. Everything below is **free** in Flint.

> **Progress (July 2026):** items 1–7, 9, and 11 are implemented — with the caveat that 7's iOS
> enforcement engine still lacks its config UI and shield arming (see 2.2). Items 8 and 10 are
> partial (app PIN shipped; uninstall/time-change guards and the preset routine library are not).
> Items 12–14 are implemented in the most recent merges. Verification is the honest caveat
> throughout: the newest iOS merges await a macOS compile pass, all iOS enforcement awaits an
> on-device pass, and the integrated Android app awaits an emulator re-run. Item 15 (Android
> parity) is substantially implemented pending that re-run; 16–17 remain open.

### v1 — Must ship (the core loop + anti-paywall wins)

1. **Sessions — Block Now** (1.2) — immediate focus timer; the simplest end-to-end vertical slice. *(iOS: ManagedSettings shield + timer.)*
2. **Sessions — Schedule, single + recurring, UNLIMITED, no 24h cap** (1.3–1.5) — removes Opal's two biggest scheduling paywalls at once. *(iOS: DeviceActivitySchedule.)*
3. **Block List** (1.6) — `FamilyActivityPicker` token selection.
4. **Allow List / brick-phone mode** (1.7) — Opal Pro-only; **free in Flint.** *(iOS: shield-all + allow set.)*
5. **All three break-difficulty levels, including HARDCORE / Deep Focus** (2.3, 2.4) — the flagship paywall removal and the reason users switch. *(App-state logic on top of the shield.)*
6. **Time Limits** (2.1) — usage-threshold auto-block. *(iOS: DeviceActivityEvent time threshold.)*
7. **Open Limits** (2.2) — launch-count limit. *(iOS: DeviceActivityEvent count threshold.)*
8. **Foolproof anti-bypass suite** (2.5) — uninstall protection, lock Screen Time access, app PIN; essential for hardcore mode to be credible. *(iOS: ManagedSettings restrictions + Screen Time passcode.)*
9. **Emergency Pass — free weekly** (2.6) — makes hardcore humane; trivial app-state logic. **Free in Flint.**
10. **App Groups + preset routine library** (1.10) — reusable saved sets + Opal-style templates.
11. **Website/Safari blocking + Private Browsing lockdown** (1.8, 1.9) — within iOS Screen Time limits.

### v1.1 — Fast follow

12. **iOS Focus Filter integration** (3.1) — `SetFocusFilterIntent`; high value, moderate effort.
13. **Shortcuts / Siri App Intents** (3.2) — "Start Session"/"Start Pomodoro" intents for automations.
14. **Sleep Mode + Morning Assist (blocking only, Full Assist free; no bundled audio content)** (3.3).

### v2 — Platform expansion (after iOS is feature-complete)

15. **Android parity** (3.5) — AccessibilityService + UsageStatsManager + WorkManager + Geofence; ship Open Limits, Allow List, and Emergency Pass on Android (Opal doesn't).
16. **Cross-device / desktop** (3.4) — backend sync + Flint browser extension + Mac helper.
17. **Gamification / streaks** — optional, local-first, free.

### Explicitly deferred / non-goals for v1

- Bundled soundscapes, sleep stories, guided meditations (content production, not enforcement).
- Custom typed-domain blocking on iOS third-party browsers (iOS API limitation; offer on Android + desktop extension instead).
- Full Mac desktop app (start with the browser extension).

---

*Sources: opalapp.com/help (Sessions, Schedule, Time Limits, Open Limits, Sleep Mode, Allow List, Foolproof, Focus Filters, Shortcuts, Why-pay-for-Opal, Android intro, How-Opal-differs-from-Screen-Time), opal.so, opalapp.com/screentime, App Store & Google Play listings, and third-party reviews (mindsightnow, makeuseof, timily, meetdaniel). Tiers marked "unknown" were not explicitly documented by Opal at time of research; inferences are noted inline.*
