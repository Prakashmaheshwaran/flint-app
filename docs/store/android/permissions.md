# Permission rationale and FGS declaration drafts

Prepared 2026-10-28; verify against the final merged release AAB. No permission
grant, exemption or Play eligibility is claimed by this document.

| Permission / capability | User benefit and implementation | Disclosure / submission treatment |
| --- | --- | --- |
| Accessibility service, protected by `BIND_ACCESSIBILITY_SERVICE` | Foreground app and supported-browser address-bar detection; local rule comparison and accessibility overlay | Dedicated affirmative disclosure before system grant, service description, listing text, declaration/video. No disability-tool claim; no Settings/uninstall interception in submission build. Binding permission protects the service; it is not a runtime permission requested by Flint. |
| `PACKAGE_USAGE_STATS` | On-device usage summaries, daily budgets and app-only fallback detector | Explain Usage Access in context; user grants in special-access Settings. No collection/upload of usage history. Denial reduces reports/budgets/fallback; do not imply the primary Accessibility path provides every usage feature. |
| `SYSTEM_ALERT_WINDOW` | Show the fallback app-blocking screen above another app when Accessibility is unavailable | User-controlled special access. No ad overlays or concealment. A fallback full-screen Activity is best effort when overlay is absent; do not promise equal background-launch reliability. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | `UsageStatsForegroundService` performs continuous foreground-app fallback evaluation with an ongoing notification | Declare `specialUse`, manifest subtype, Console explanation and demo. Start restrictions still apply; neither overlay permission nor a persistent notification guarantees arbitrary background starts. |
| `POST_NOTIFICATIONS` | Mandatory Path B FGS visibility (`flint_enforcement`), optional Path A ongoing status (`flint_ongoing_session`) and planned-focus-end reminders (`focus_sessions`) | Runtime request on API 33+. Optional; denial does not itself prevent starting an FGS. Explain notification-drawer vs Task Manager visibility accurately. Include all three channels and widgets/OS copies in final demo/inventory; delivery is not completion or enforcement proof. |
| `RECEIVE_BOOT_COMPLETED` | Best-effort restoration of user-configured protection state | Does not enable Accessibility or bypass FGS restrictions; reboot/OEM acceptance is lane C/hardware work. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Request reduced battery restrictions for timely user-selected focus protection | Optional, user-controlled. Owner must justify direct-exemption eligibility under current power policy; exemption does not guarantee background survival. Do not claim automatic exemption or all-OEM coverage. |
| Scoped launcher `<queries>` | Show launchable apps in the user's block picker | No `QUERY_ALL_PACKAGES` request/declaration. Do not expand scope for parity marketing. |
| Scoped web-handler `<queries>`: `ACTION_VIEW` + `BROWSABLE`, `http` / `https` | Identify a foreground link handler outside the known browser matrix so website coverage can be reported honestly | Package-scoped queries and local OS app labels; no broad installed-package enumeration, URL history or transmission. Browser-category resolution only classifies an already visible handler. |
| `WAKE_LOCK` (normal, library manifest) | WorkManager/library background work in the merged release graph | Observed in K126's retained release-smoke APK; no user special-access grant. Inventory the final merged manifest rather than only Flint's source manifest. |
| `com.flint.peakfocus.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (internal signature) | AndroidX non-exported receiver compatibility protection | Internal signature permission, not a user data-access prompt. Recheck declaration/use and library components in the final signed APK/AAB. |
| No `INTERNET` / `ACCESS_NETWORK_STATE` | Preserve local-only release | Verify packaged APK and AAB; Firebase/feature-connected remain debug-only. |
| No exact alarm / full-screen-intent notification / device owner | None requested in release | No exact-alarm or full-screen-intent exception needed. The fallback Activity is not a notification full-screen intent. Do not claim device-admin/enterprise capabilities. |

The two normal/internal entries were observed in the actual K126
[release-smoke artifact](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40778992967),
tested merge `5560ea984422c6a8c3252a371e65801fbe5d1b60`, APK SHA-256
`c80b72aec6475cdef11ff9f2e73b891243118d17ac271726a5c4191282de98d6`.
That artifact also contains WorkManager components; “no additional Flint enforcement
foreground service” does not mean no library services. This is prior-artifact evidence,
not a final signed-candidate manifest attestation.

## Proposed specialUse FGS explanation

Flint is a user-configured focus app. When the user enables Usage Access and the
Accessibility detector is unavailable, the foreground service checks the current
app and evaluates the user's local app rules and usage limits. Delaying or
interrupting those checks can let a distracting app remain visible after a rule
starts or a budget is reached. WorkManager and deferred jobs cannot provide a
continuous timely foreground-app detector. This is not media playback,
location, health, data sync, connected-device or a short fixed-duration task, so
the requested type is specialUse. The service supplies an ongoing notification;
Android controls its visibility when notification permission is denied. Flint
does not request full-screen-intent notification permission.

This explains the requested use case; it does not prove Play will accept
specialUse. The manifest subtype, corrected in lane C PR93, states: “Detects which
app is in front to enforce the user's own on-device app blocks, schedules and
daily app limits. Website blocking is not part of this service.” The owner must
show that actual app-only behavior and use matching Console declaration text.

Record the user enabling the fallback while Flint is visible, the ongoing
notification/Task Manager entry, opening a rule-selected app and seeing its
block, denial/revocation and stopping the fallback when the primary detector
takes over. Explain interruption impact without claiming guaranteed restart.
Show any final user stop control and verify that OS stop/revoke is honored.

## Usage / overlay / battery justifications

Usage Access is needed to read local foreground usage for daily budgets/reports
and fallback app detection; ordinary app APIs cannot expose another app's usage.
Overlay access lets the optional fallback cover a matching app with Flint's
user-selected block screen. No sensitive-content scraping is needed by that
path. Battery exemption is requested only to reduce restrictions that can defer
the core focus checks; declining must leave settings and available functionality
usable. Direct exemption eligibility is a separate owner/policy gate, not
established by this rationale.

Official sources accessed 2026-10-28:

- [Foreground service types / specialUse](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Play FGS declarations and video](https://support.google.com/googleplay/android-developer/answer/13392821)
- [Doze, exemptions and acceptable use cases](https://developer.android.com/training/monitoring-device-state/doze-standby)
- [Notification permission and FGS](https://developer.android.com/develop/ui/compose/notifications/notification-permission)
- [Sensitive permissions policy](https://support.google.com/googleplay/android-developer/answer/16558241)
