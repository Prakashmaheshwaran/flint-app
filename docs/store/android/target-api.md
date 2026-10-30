# Android target API readiness

Policy checked **2026-10-28**. Google Play requires new mobile apps and updates
to target **Android 16 / API 36 or higher** from **2026-08-31**. Existing-app
availability has a different API 35 threshold; that does not make an API 35 new
submission eligible. Flint targets and compiles against 36 and retains minSdk 23.
AGP 9.3.2 is unchanged. No extension or Console action was requested.

Official sources (accessed 2026-10-28):

- [Play target API submission requirements](https://support.google.com/googleplay/android-developer/answer/11926878)
- [Android 16 target behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16)
- [Foreground service types and specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Foreground service background-start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Alarm scheduling](https://developer.android.com/develop/background-work/services/alarms)
- [Scoped package visibility](https://developer.android.com/training/package-visibility/declaring)

## Compatibility assessment

This target change preserves enforcement behavior; detector/liveness changes
belong to lane C. Source inspection and JVM/build checks do not prove Android 16
device behavior. Complete the device checks below before store submission.

| Surface | Current implementation and API 36 disposition | Device acceptance still required |
| --- | --- | --- |
| Edge-to-edge / insets | `MainActivity.enableEdgeToEdge`, Scaffold `safeDrawing` and consumed content insets; `BlockActivity.enableEdgeToEdge` and shared block content `safeDrawingPadding`. No edge-to-edge opt-out attribute exists. API 36 removes that opt-out. | Gesture and 3-button navigation, display cutout, IME, large font, rotated/tablet window; consent and block actions remain reachable. |
| Predictive Back | Main Activity enables it and uses AndroidX dispatch. The app manifest explicitly keeps `BlockActivity` opted out with `enableOnBackInvokedCallback=false`; its dispatcher callback nudges and intentionally does not exit. Preserve this activity-specific behavior: API 36 otherwise bypasses legacy Back paths by default. | Back gesture/button on the fallback block Activity still nudges without opening the blocked app; ordinary navigation still returns correctly. |
| Foreground service | `UsageStatsForegroundService` is nonexported, declares `specialUse` and a subtype property, and passes `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` on API 34+. Both FGS permissions are present. No new FGS type or permission is needed for this SDK change. Play eligibility requires separate owner declaration/review. | Start with Usage Access while Flint is visible; revoke/restore Accessibility; deny notifications; background and reboot behavior on API 36/OEMs. FGS presence is not permission to launch from arbitrary background contexts. |
| Alarm scheduling | Inexact `AlarmManager.setAndAllowWhileIdle` supports planned-focus-end reminders; `AlarmManager.set` supports widget refresh. No exact scheduling or exact-alarm permission is requested. Neither alarm delivery, widget refresh nor an in-process countdown proves enforcement/completion timing. | Reminder/refresh delivery, stationary activation, expiry, overnight/Doze/reboot remain physical/OEM acceptance gates. |
| Package visibility | Launcher intent `<queries>` scope is retained. Narrow `ACTION_VIEW` + `BROWSABLE` queries for `http` / `https` identify foreground web handlers for website-coverage health. `QUERY_ALL_PACKAGES` is absent. | Picker contains supported launchable apps and safe system-app exclusions; known-browser vs unsupported-link-handler health is accurate; no broad package-enumeration claim. |
| Notifications | Existing runtime request is guarded to API 33+, explained in setup, and not an enforcement prerequisite. FGS must still provide a notification when permission is denied; Android may show it only in Task Manager. | Deny, allow, revoke and channel-disabled states display accurate health; no claim that denial stops the service. |
| Scheduling / adaptive windows | No `scheduleAtFixedRate` use was found. Compose layouts have no fixed orientation/resizability opt-out. API 36 large-screen layout rules still require visual verification. | Rotation/window resize preserves setup and active screens; reevaluation remains timely. |

## Reproduce

Install `platforms;android-36` with the resolved SDK's `sdkmanager` (stop for the
owner if a new license is requested), then run `make verify-android` under the
shared `flint-heavy android` semaphore. Inspect the built APK's merged manifest
for minSdk 23 / targetSdk 36, the activity-specific Back flag, specialUse service,
scoped queries and absence of network/exact-alarm/broad-visibility permissions.
The packaged local-first scan remains mandatory. Record API 36 device evidence
separately; an API 35 emulator is not proof of API 36 behavior.
