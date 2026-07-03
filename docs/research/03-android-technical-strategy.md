# 03 — Android Technical Strategy: Flint's Blocker

> **Status:** Definitive implementation strategy for the Android blocker (2025–2026).
> **Scope:** Foreground-app + website blocking, Google Play policy compliance, permissions, OEM/battery survival, and the `android-app/` module layout (Kotlin + Jetpack Compose).
> **Principle:** Flint is 100% free and open source. Every blocking capability Opal paywalls ships free in Flint. This document is the engineering plan to match Opal's Android enforcement quality without the paywall.

---

## 1. The mechanism in one paragraph

Android gives third-party apps **no native "block this app" API**. Every credible blocker (Opal included) composes the same primitives:

1. **Detect** what is in the foreground.
2. **Decide** whether it is on the blocklist / over a limit.
3. **Enforce** by covering or evicting the offending app.

Flint uses **two parallel detection paths** feeding **one enforcement layer**:

- **AccessibilityService** — real-time, event-driven foreground detection and the *only* way to read a browser's URL for website blocking. This is the **primary** enforcement path and is how Opal blocks on Android 14+.
- **UsageStatsManager** — polling-based foreground detection plus the canonical source for **aggregate daily screen-time** (limits, stats, reports). This is the **fallback** detection path and the **sole** source for time-budget logic.
- **Enforcement** — either an **AccessibilityService overlay** (preferred — needs no extra permission) or a **`SYSTEM_ALERT_WINDOW` overlay / full-screen blocking Activity** plus `GLOBAL_ACTION_HOME`, hosted by a **foreground service** for liveness.

Website blocking is **not possible via UsageStatsManager** (it sees only the browser package, never the URL). Flint reads the URL from the AccessibilityService node tree (address bar / web content nodes). A local-VPN DNS sinkhole is a documented alternative but is **out of scope for v1** (see §9).

---

## 2. Detection: which API does what

| Capability | AccessibilityService | UsageStatsManager |
|---|---|---|
| Foreground package | Instant via `TYPE_WINDOW_STATE_CHANGED` → `event.packageName` | Polled via `queryEvents()` → most recent `ACTIVITY_RESUMED` |
| Latency | ~0 ms (event-driven) | Poll interval (~0.7–1 s lag) |
| Website / URL detection | **Yes** — read address bar / web nodes | **No** — only the browser package is visible |
| Aggregate daily screen time | No | **Yes** — `queryUsageStats()` (limits, reports) |
| Can it block by itself | Overlay + `GLOBAL_ACTION_HOME`/`BACK` | **No** — observe only, needs separate enforcement |
| Grant friction | High (manual Accessibility toggle, restricted-setting unlock) | Medium (manual Usage-access toggle) |
| Play policy risk | **High** (AccessibilityService API policy) | Low (sensitive special-access, no declaration) |
| OEM kill / auto-disable risk | Silently disabled on reboot/idle by some OEMs | Service kill; some OEMs reset the grant after OTA |

**Design consequence:** Use AccessibilityService as the hard-block trigger and the URL source. Use UsageStatsManager for time budgets and as a redundant detector when the AccessibilityService is disabled (Advanced Protection, OEM kill, user turned it off). Both feed the same enforcement decision so coverage degrades gracefully instead of failing outright.

### 2.1 UsageStatsManager poll loop (the fallback)

- A foreground service polls `queryEvents(now - window, now)` on a short rolling window (last ~5–10 s) every ~0.7–1 s.
- Walk the events; the most recent `ACTIVITY_RESUMED` (== legacy `MOVE_TO_FOREGROUND`) reveals the current front package. `ACTIVITY_PAUSED` (== `MOVE_TO_BACKGROUND`) reveals it left.
- **Constant aliasing (confirmed):** `MOVE_TO_FOREGROUND`→`ACTIVITY_RESUMED` and `MOVE_TO_BACKGROUND`→`ACTIVITY_PAUSED` were renamed in **API 29** and share identical numeric values (`1` and `2`). They are true aliases — handling either is equivalent. Prefer the `ACTIVITY_*` names on API 29+.
- **Limit logic:** `queryUsageStats(INTERVAL_DAILY, ...)` gives per-package `totalTimeInForeground`. Buckets are slightly delayed/coarse — fine for daily limits, not for sub-second enforcement.

### 2.2 What we deliberately do NOT use

- **`ActivityManager.getRunningTasks` / `getRunningAppProcesses`** — since **API 21** these return **only the caller's own process**. `GET_TASKS` is deprecated; `REAL_GET_TASKS` is signature-level (unavailable to third parties). Unusable for blocking; do not build on it.

---

## 3. Enforcement: covering / evicting the blocked app

Two enforcement variants, chosen by **which detector fired**:

**A. AccessibilityService-triggered (preferred).**
When the a11y service detects a blocked package or URL, it acts in-process:
- Add an **accessibility overlay window** to draw Flint's block screen. This floats above the status/nav bars and system dialogs and needs **no `SYSTEM_ALERT_WINDOW` grant**.
- And/or call `performGlobalAction(GLOBAL_ACTION_HOME)` / `GLOBAL_ACTION_BACK` to evict the app.

This path is preferred because it has the lowest latency, the strongest overlay, and the fewest permissions.

**B. UsageStatsManager-triggered (fallback).**
When only the poll loop is available, the foreground service must draw the block UI itself:
- Use `WindowManager` with **`TYPE_APPLICATION_OVERLAY`** (the only legal overlay type since Android 8 / API 26), gated on `Settings.canDrawOverlays()`; or
- Launch a **full-screen blocking Activity** with `FLAG_ACTIVITY_NEW_TASK`.

**Overlay limitations to design around:**
- `TYPE_APPLICATION_OVERLAY` windows draw **below** critical system UI (status bar, some system dialogs); the system can dim/hide them. A determined user can sometimes reach Settings/the shade *behind* the overlay.
- Overlays are dismissed by going Home — so the blocker must **re-trigger continuously** (the overlay is a cover, not a kill). Pair it with `GLOBAL_ACTION_HOME` (a11y) or a Home intent / `moveTaskToBack`.
- Android 12 added a user "Hide overlay windows" control.
- **Background activity launch is restricted on Android 10+** — a background full-screen Activity launch is unreliable. Launch the blocking Activity from the **a11y service** or from an active foreground service; do **not** rely on `USE_FULL_SCREEN_INTENT` (see §5.3).

---

## 4. Google Play AccessibilityService policy compliance (the make-or-break section)

This is the single most likely cause of rejection or removal. Get it exactly right.

### 4.1 `isAccessibilityTool` — Flint is NOT eligible (confirmed)

Per the Play Console **AccessibilityService API policy** (`support.google.com/.../answer/10964491`), only these qualify to declare `android:accessibilityFlags`/`isAccessibilityTool="true"`:

- screen readers,
- switch-based input,
- voice-based input,
- Braille-based access,
- genuine cognitive / multi-disability tools.

The policy text **explicitly names "monitoring apps"** (alongside antivirus, automation tools, assistants, cleaners, password managers, launchers) as **NOT eligible**. A focus/blocker app is a monitoring app. **Flint must not declare `isAccessibilityTool="true"`.**

### 4.2 Ineligibility does NOT bar Flint from Play (verifier correction)

> **Prefer the verifier here.** The research framed AccessibilityService as the "highest-risk path" in a way that overstates ineligibility as near-fatal. Correction: **being ineligible for `isAccessibilityTool` does not bar you from Play.** It only removes the lighter "tool" disclosure exemption and puts Flint on the **prominent-disclosure + affirmative-consent** path. **Opal** (`com.withopal.opal`) is live on Google Play and its own support docs state it uses the AccessibilityService API to manage blocked apps on Android 14+. The compliant route is well-trodden — execute the disclosure flow correctly and shipping is routine, not exceptional.

### 4.3 The required prominent disclosure + consent flow (confirmed)

For a non-eligible app using AccessibilityService, the disclosure must:

1. Be **in-app** — *not* satisfied by the Play listing, website, or privacy policy alone.
2. Be **shown during normal usage**, not buried in a settings sub-menu.
3. **Describe the data accessed via the AccessibilityService API** and **how it is used and shared.**
4. **Require affirmative user action** (an explicit accept) to consent — **before** Flint sends the user to enable the service.

Concretely, Flint must show a dedicated, unavoidable consent screen *before* the "Enable Accessibility" hand-off, with copy like: *"To block apps in real time, Flint uses Android's Accessibility service to detect which app is in the foreground and (for website blocking) read the web address shown in your browser. Flint does this only to enforce the blocks you set up. This data stays on your device and is never sent off-device or sold."* Plus an explicit **Accept & continue** button.

### 4.4 Play listing + service-description string must match

- The **Play Data safety** form and store listing must accurately describe the AccessibilityService use.
- The `<accessibility-service>` config's `android:description` string (shown by the OS on the Accessibility enable screen) must accurately and plainly describe the use.
- Reading **screen content (URLs)** heightens review scrutiny — keep the disclosure copy explicit about URL reading and keep all processing on-device.

### 4.5 Declaration checklist (what to ship)

- [ ] `BIND_ACCESSIBILITY_SERVICE` on the service in the manifest.
- [ ] An `<accessibility-service>` config XML referenced via `android.accessibilityservice` meta-data, with an accurate `android:description`.
- [ ] **No** `android:isAccessibilityTool="true"`.
- [ ] In-app prominent disclosure screen with affirmative consent, shown before the enable hand-off.
- [ ] Play Data safety form filled to match.
- [ ] Privacy policy URL (required, but **not** a substitute for the in-app disclosure).

---

## 5. Permissions & special access (full inventory)

### 5.1 `PACKAGE_USAGE_STATS` (Usage Access)
- **Type:** `signature|privileged|development` — **not** grantable by the runtime permission dialog (confirmed).
- **Grant flow:** user toggles it manually under **Settings > Apps > Special app access > Usage access**. Open it with `ACTION_USAGE_ACCESS_SETTINGS`.
- **Verify with:** `AppOpsManager.checkOpNoThrow(OPSTR_GET_USAGE_STATS, uid, packageName)` → `MODE_ALLOWED`. (Holding the manifest permission alone does not mean access is granted.)
- **Play:** sensitive special-access; **no** accessibility-style declaration required, but justify it clearly in-app.
- **Pitfall:** some OEMs reset this grant after an OTA update — re-verify on launch and re-prompt.

### 5.2 `SYSTEM_ALERT_WINDOW` (Draw over other apps)
- **Type:** special access since Android 6 (API 23).
- **Grant flow:** `ACTION_MANAGE_OVERLAY_PERMISSION`; verify with `Settings.canDrawOverlays()`.
- **Note:** auto-grant for Play installs no longer applies to modern targets — treat as a **manual** grant.
- **Only needed for enforcement path B** (UsageStats-triggered overlay). Path A (a11y overlay) does not need it.

### 5.3 `USE_FULL_SCREEN_INTENT` — DO NOT depend on it (verifier-confirmed hazard)
- On **Android 14+** this is restricted to **calling/alarm** apps, and **Play auto-revokes** it from non-qualifying apps. Flint is neither — do not architect the block screen around a full-screen intent. Launch the blocking Activity from the a11y service or an active foreground service instead.

### 5.4 Foreground service type (Android 14+) — required
- Android 14 **mandates** a `foregroundServiceType` declaration. Declare an appropriate type (e.g. `specialUse`) with a justification; `specialUse` requires a Play Console justification at submission.
- **Android 15:** restricts which FGS types may start from `BOOT_COMPLETED` — verify the chosen type can auto-start on boot, or re-arm via an alternative (see §6).

### 5.5 Android 15 SYSTEM_ALERT_WINDOW + FGS tightening (verifier-confirmed showstopper for path B)
> For apps **targeting Android 15**, holding `SYSTEM_ALERT_WINDOW` alone **no longer** lets you start a foreground service from the background. You must **first** have a **visible `TYPE_APPLICATION_OVERLAY` window**; otherwise the system throws **`ForegroundServiceStartNotAllowedException`**. This directly constrains the UsageStats-poll + overlay pattern (path B): show the overlay window first, then (re)start the FGS — or keep the FGS persistently alive so it never needs a background start. **Design path B assuming this restriction is in force.**

### 5.6 `BOOT_COMPLETED`
- Needed to re-arm enforcement after reboot. Subject to the Android 15 FGS-from-boot restriction (§5.4). Many OEMs also strip boot receivers from "optimized" apps — treat boot re-arm as best-effort and back it with a re-check on next app open.

---

## 6. Battery, OEM, and reliability pitfalls

Enforcement is only as good as the process staying alive. The hostile actors are **OEM power managers** and **two emerging OS-level restrictions.**

### 6.1 OEM power managers (Samsung, Xiaomi/MIUI/HyperOS, Huawei, OnePlus, Oppo/realme)
- Aggressively kill background **and** foreground services, and **silently disable AccessibilityService on reboot/idle** (confirmed in direction by the verifier).
- **Mitigations:**
  - Run a **persistent foreground service** with an ongoing notification (lowest kill priority).
  - Request **battery-optimization exemption** via `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission) and verify with `PowerManager.isIgnoringBatteryOptimizations()`.
  - Detect the OEM and deep-link the user to the relevant **auto-start / protected-app** screen (MIUI Autostart, Samsung "Never sleeping apps", etc.) with guided copy. There is no single API for this — maintain a per-OEM intent map (the [dontkillmyapp.com](https://dontkillmyapp.com) data set is the reference).
  - On every app open, **re-verify** that the AccessibilityService and Usage-access grants are still live; if not, surface a clear re-enable prompt.

### 6.2 Self-healing via `ApplicationExitInfo` (API 30+)
- Use `ActivityManager.getHistoricalProcessExitReasons(null, 0, n)` (no permission needed for your own package) to learn **why** the service was last killed: `REASON_LOW_MEMORY`, `REASON_USER_REQUESTED`, `REASON_DEPENDENCY_DIED`, ANR, or generic `REASON_OTHER`/`REASON_SIGNALED` (how OEM kills usually surface).
- **Read-only** — it cannot prevent kills, only report them after the fact. Use it to: detect that enforcement lapsed, prompt the user to whitelist Flint, and log telemetry (local) on why enforcement broke. This is diagnostics, not a blocking primitive.

### 6.3 Advanced Protection Mode — a11y auto-revocation (verifier-confirmed, plan for it)
- **Android 17** (the feature surfaced/expanded in **Android 17.2 betas, 2026**) adds an **Advanced Protection Mode** that, when the user enables it, **blocks granting and auto-revokes the AccessibilityService API** from apps not declared `isAccessibilityTool` (screen reader/switch/voice/Braille). Flint is ineligible (§4.1), so a user who turns this on **loses a11y-based blocking.**
- **It is opt-in (user-enabled), NOT on by default.** Do not treat it as the common case.
- **Mitigation:** the **UsageStatsManager fallback path** (§2.1 + enforcement path B) is the answer — when a11y is revoked, Flint degrades to poll-based detection + `SYSTEM_ALERT_WINDOW` overlay. Detect a11y revocation and (a) keep enforcing via the fallback, (b) inform the user that website blocking and instant hard-blocking are reduced while Advanced Protection is on.

### 6.4 Android 13+ "restricted settings" (sideload only)
- On **Android 13+**, sideloaded apps cannot enable AccessibilityService until the user clears a hidden per-app **"Allow restricted settings"** toggle. This affects **sideloaded / direct-APK** distribution; **Play installs are unaffected.** Since Flint will be open source and may be distributed via APK/F-Droid as well as Play, ship guided in-app instructions for the restricted-setting unlock.

### 6.5 Polling cost
- The UsageStats poll loop drains battery and the FGS is kill-prone. Keep the poll interval ~0.7–1 s only while a11y is unavailable; when the AccessibilityService is active, **suspend or slow the poll** (a11y events are the primary trigger) to save battery.

---

## 7. Minimum API level & target

- **`minSdk = 23` (Android 6.0).** Rationale: `SYSTEM_ALERT_WINDOW` manage-overlay flow and runtime special-access model both stabilize at API 23. `TYPE_APPLICATION_OVERLAY` (API 26) and `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` (API 29) are gated with version checks. `UsageStatsManager` exists from API 21 / `queryEvents` from API 22, so API 23 is comfortably above the floor. `ApplicationExitInfo` (API 30) is feature-gated. (If a smaller, simpler v1 is preferred, `minSdk = 26` removes the most legacy overlay branching — but 23 maximizes device reach for a free app and costs only a few `Build.VERSION` checks.)
- **`targetSdk` = latest stable (35 / Android 15 at time of writing).** Targeting current is **mandatory** for Play and forces Flint to honor §5.4 (FGS type) and §5.5 (Android 15 overlay-before-FGS rule). Re-test against Android 16/17 betas for the Advanced Protection behavior (§6.3).

---

## 8. Recommended module layout for `android-app/`

Kotlin + Jetpack Compose, single-Activity UI, multi-module Gradle. The enforcement engine is isolated from the UI so it can run headless and survive process restarts.

```
android-app/
├── settings.gradle.kts
├── build.gradle.kts                         # version catalogs, plugins
├── gradle/libs.versions.toml                # Compose, Hilt, Room, coroutines, etc.
│
├── app/                                      # thin shell: Application, DI graph, nav host
│   └── src/main/kotlin/dev/flint/
│       ├── FlintApplication.kt
│       └── MainActivity.kt                   # single-Activity Compose host
│
├── core/
│   ├── core-model/                           # Blocklist, BlockRule, Schedule, AppInfo, UsageBucket
│   ├── core-data/                            # Room DB + repositories (blocklists, limits, sessions)
│   ├── core-datastore/                       # Preferences DataStore (settings, consent flags)
│   └── core-common/                          # coroutine dispatchers, time, OEM detection, Build gating
│
├── feature/                                  # Compose UI, one module per screen group
│   ├── feature-onboarding/                   # permission walkthrough + PROMINENT DISCLOSURE/consent (§4.3)
│   ├── feature-blocklist/                    # pick apps/sites to block, schedules, limits
│   ├── feature-stats/                        # screen-time reports from UsageStatsManager
│   ├── feature-blockscreen/                  # the Compose "blocked" lock UI (hosted in overlay or Activity)
│   └── feature-settings/                     # battery exemption, OEM auto-start guidance, re-enable prompts
│
├── blocking/                                 # THE ENFORCEMENT ENGINE (UI-independent, headless-capable)
│   ├── blocking-engine/                      # pure-Kotlin decision core: foreground+url -> Allow/Block
│   │   └── BlockDecisionEngine.kt            # given (package, url, time-budget, schedule) -> verdict
│   ├── blocking-accessibility/               # path A
│   │   ├── FlintAccessibilityService.kt      # TYPE_WINDOW_STATE_CHANGED, URL node reads, a11y overlay, GLOBAL_ACTION_HOME
│   │   └── res/xml/accessibility_service_config.xml   # NO isAccessibilityTool; accurate android:description
│   ├── blocking-usagestats/                  # path B detection + time budgets
│   │   ├── UsageStatsForegroundService.kt    # FGS, poll loop (~0.7–1s), foregroundServiceType=specialUse
│   │   ├── UsagePoller.kt                     # queryEvents window walk -> current package
│   │   └── DailyLimitTracker.kt              # queryUsageStats aggregate -> over/under budget
│   ├── blocking-overlay/                      # path B enforcement
│   │   ├── OverlayController.kt              # WindowManager + TYPE_APPLICATION_OVERLAY (canDrawOverlays gate)
│   │   └── BlockActivity.kt                  # full-screen fallback (FLAG_ACTIVITY_NEW_TASK)
│   └── blocking-resilience/                   # liveness + self-healing
│       ├── BootReceiver.kt                   # re-arm on BOOT_COMPLETED (Android 15 FGS-from-boot aware)
│       ├── ExitReasonReporter.kt             # ApplicationExitInfo diagnostics (API 30+)
│       └── PermissionHealthChecker.kt        # re-verify a11y / usage / overlay / battery grants each launch
│
└── permissions/                              # special-access plumbing, decoupled from UI
    └── permissions-special/
        ├── UsageAccess.kt                    # ACTION_USAGE_ACCESS_SETTINGS + AppOpsManager verify
        ├── OverlayPermission.kt              # ACTION_MANAGE_OVERLAY_PERMISSION + canDrawOverlays
        ├── AccessibilityPermission.kt        # enable hand-off (gated behind consent), enabled-state check
        ├── BatteryOptimization.kt            # ignore-battery-optimizations + isIgnoringBatteryOptimizations
        └── OemAutoStart.kt                   # per-OEM auto-start/protected-app intent map (dontkillmyapp data)
```

**Layout rationale:**
- `blocking-engine` is **pure Kotlin, no Android deps** — both detection paths (a11y and usagestats) call the same `BlockDecisionEngine` so verdicts are identical regardless of which path fired. Trivially unit-testable.
- The two detection paths are **separate modules** so the app degrades cleanly: if a11y is revoked (Advanced Protection, OEM, user), `blocking-usagestats` + `blocking-overlay` keep enforcing.
- `blocking-resilience` centralizes the OEM/battery/self-healing concerns from §6 so they are not smeared across services.
- `feature-onboarding` **owns the prominent-disclosure consent screen** and must gate the a11y enable hand-off behind it (§4.3) — this is a compliance requirement, not a UX nicety.
- `permissions-special` keeps the brittle Settings-intent + verification code in one place, version-gated.

---

## 9. Website blocking — v1 decision

- **v1:** read the URL from the **AccessibilityService** node tree (address bar / web content nodes) and block matching sites with the same overlay/redirect as app blocking. Pros: no extra permission beyond a11y, no VPN slot consumed, matches Opal's approach. Cons: lost when a11y is revoked (§6.3); per-browser node-structure fragility; heightens Play review scrutiny (disclose URL reading explicitly, §4.4).
- **Deferred (v2 consideration):** a **local `VpnService` DNS sinkhole** for browser-agnostic, a11y-independent website blocking. Pros: works without a11y and across all browsers; survives Advanced Protection. Cons: consumes the single device VPN slot (conflicts with the user's real VPN), its own user-consent VPN dialog, and added complexity. **Out of scope for v1**; revisit if Advanced Protection adoption rises or if a11y URL reads prove too fragile across browsers.

---

## 10. Compliance & engineering checklist (ship gate)

- [ ] AccessibilityService: `BIND_ACCESSIBILITY_SERVICE`, config XML, accurate `android:description`, **no** `isAccessibilityTool`.
- [ ] In-app **prominent disclosure + affirmative consent** screen, shown during normal use, **before** the a11y enable hand-off; describes data accessed + use/sharing; on-device only.
- [ ] Play **Data safety** form matches the a11y/usage/URL-reading reality.
- [ ] `PACKAGE_USAGE_STATS`: `ACTION_USAGE_ACCESS_SETTINGS` flow + `AppOpsManager` verification; re-check post-OTA.
- [ ] `SYSTEM_ALERT_WINDOW`: manual grant flow + `canDrawOverlays()`; used only for path B.
- [ ] `foregroundServiceType` declared (Android 14+); justify `specialUse` at submission.
- [ ] **Android 15:** path B shows the `TYPE_APPLICATION_OVERLAY` window **before** any background FGS start (avoid `ForegroundServiceStartNotAllowedException`).
- [ ] **Do not** depend on `USE_FULL_SCREEN_INTENT` (auto-revoked for non-call/alarm apps on 14+).
- [ ] Battery-optimization exemption flow + per-OEM auto-start guidance.
- [ ] `ApplicationExitInfo` self-healing + per-launch permission health check.
- [ ] UsageStats fallback proven to enforce when AccessibilityService is disabled/revoked.
- [ ] `ACTIVITY_RESUMED`/`ACTIVITY_PAUSED` used on API 29+ (alias-aware on older).
- [ ] `minSdk = 23`, `targetSdk` = latest stable; tested against Android 16/17 betas for Advanced Protection.

---

### Verifier-driven corrections folded in (where research and verifier diverged)

1. **AccessibilityService ineligibility is not fatal.** The research's "highest-risk path" framing was softened: ineligibility for `isAccessibilityTool` does **not** bar Play distribution; the prominent-disclosure/consent path is the compliant, proven route (Opal ships this way). (§4.2)
2. **Android 15 overlay-before-FGS rule made a first-class design constraint** for enforcement path B, not a footnote. (§5.5)
3. **`USE_FULL_SCREEN_INTENT` explicitly excluded** from the architecture (auto-revoked for non-call/alarm apps on Android 14+). (§5.3)
4. **Advanced Protection (Android 17, opt-in)** treated as a degrade-gracefully case handled by the UsageStats fallback — **not** a default-on showstopper. (§6.3)
5. **Constant aliasing** (`MOVE_TO_FOREGROUND`==`ACTIVITY_RESUMED`) confirmed as true numeric aliases. (§2.1)
