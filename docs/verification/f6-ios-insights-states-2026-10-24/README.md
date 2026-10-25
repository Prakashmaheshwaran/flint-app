# F6 — iOS Insights unavailable and empty states

## Behavior

Insights explains missing Screen Time authorization in both Today and 7 days. It uses the app's shared AuthorizationModel for the existing individual authorization request, inline failure/retry feedback and a check-access action. It rechecks access and the current calendar range on entry and when the app becomes active.

Simulator shows a physical-device explanation alongside the permission state. The existing `-FlintUIPreview` approved-UI flag also leads to an explicit simulator-unavailable state, rather than a blank report or sample totals.

On an approved physical device, the same `Total Activity` context and daily filter remain: today or the preceding six days plus today, all users, iPhone/iPad. Report help offers an explicit reload which recreates the SwiftUI report, without guaranteeing that Apple will return data.

Only the report extension evaluates returned usage. No day segments show “No activity reported”; returned segments with zero usage show “No screen time recorded.” Positive duration (including sub-minute usage), per-day/per-app activity, or pickups keep the activity view visible. Empty states and report content scroll, and the host header stacks at accessibility text sizes.

## API and privacy boundary

The installed SDK exposes `DeviceActivityReport.init(_:filter:)` and its view body, but no report-ready/error callback. `makeConfiguration(representing:)` asynchronously returns a configuration inside the extension; it does not expose a throwing failure to the host. See Apple's [report documentation](https://developer.apple.com/documentation/deviceactivity/deviceactivityreport) and [configuration API](https://developer.apple.com/documentation/deviceactivity/deviceactivityreportscene/makeconfiguration(representing:)).

There is no timer-based failure claim, fabricated total, or empty overlay over real report content. If Apple's report service never supplies a configuration, the host cannot reliably distinguish waiting from failure; Report help remains available. Usage and app names stay inside the extension. No persistence, telemetry, account, network collection or host-report data bridge is added.

## Verification

Worktree: `/Users/prakash/.codex/worktrees/f6-insights-recovery/flint`.
Branch: `codex/f6-ios-insights-states`.
Native UI source revision: `fd135b4bf6168ca500285725dccdfc8b1bc59d27`.
Final report source revision: `3328601af3ca440cfa67ef725d0ba0281f3c1c9e`.

Xcode 27.0 (27A266a), shared iPhone 18 Pro Simulator running iOS 27.0 (24A434). Heavy builds and device operations used the shared serialized wrapper, with parallel simulator testing disabled. The native UI sequence freshly installed F6's own app build, passed all three cases at Large and all three at Accessibility Extra Extra Extra Large, terminated Flint and restored Large. Read-only checks confirmed no PIN before or after the sequence; no PIN state was changed.

`make verify-ios` passed all 70 tests at the native UI revision, then passed all 70 again on final F6 source `3328601`. The final report fixtures come from F6's own `Test-Flint-2026.10.25_01-57-51--0400.xcresult`. Visual review confirmed that both the complete report heading and peak-day sentence render at maximum text size. The report content keeps its intrinsic height inside its scroll container.

The sole iOS file changed after native UI verification is `TotalActivityView.swift`, for report-extension vertical sizing; the host authorization screens and their dependencies are unchanged. The final report gate independently confirmed absent PIN keys before and after its run, restored Large with a successful readback, and released the shared slot. Its source also includes F5 through `851ce84`.

The [manifest](manifest.json) records exact source revisions, executable checksums, test summaries, result bundle paths and screenshot checksums. [Attachment provenance](evidence-index.json), [UI harness](harness/Tests/InsightsUITests.swift) and compressed [logs](logs/) accompany the screenshots. Full local result bundles remain under `/tmp/flint-fixes-20261024/F6/`.

The unit tests compile the actual Insights state/range and report model/view sources. They check authorization selection/revocation, simulator exclusion, calendar-day/DST ranges, absent versus zero segments, positive/sub-minute/partial usage preservation, and render fixtures at standard/maximum accessibility text sizes. F5's model tests cover request exclusion, failure/retry and externally refreshed authorization.

The native cases exercise unauthorized Today/7 days, the actual Simulator authorization failure ("Couldn't communicate with a helper application.") and retry, check-access feedback and foreground refresh, plus the existing approved-UI preview's Simulator explanation. The preview flag simulates the host's authorization status; it neither enables Screen Time nor supplies usage.

| Native screen | Large | Maximum accessibility text |
| --- | --- | --- |
| Unauthorized, 7 days | [Image](screenshots/standard-01-unauthorized-week.png) | [Image](screenshots/accessibility-01-unauthorized-week.png) |
| Unauthorized, Today | [Image](screenshots/standard-02-unauthorized-today.png) | [Image](screenshots/accessibility-02-unauthorized-today.png) |
| Check-access feedback | [Image](screenshots/standard-03-check-access-feedback.png) | [Image](screenshots/accessibility-03-check-access-feedback.png) |
| Foreground refresh | [Image](screenshots/standard-04-resume-refresh.png) | [Image](screenshots/accessibility-04-resume-refresh.png) |
| Actual authorization error | [Image](screenshots/standard-05-authorization-error.png) | [Image](screenshots/accessibility-05-authorization-error.png) |
| Retry | [Image](screenshots/standard-06-retry-error.png) | [Image](screenshots/accessibility-06-retry-error.png) |
| Approved UI preview, 7 days | [Image](screenshots/standard-07-simulator-week-ui-preview.png) | [Image](screenshots/accessibility-07-simulator-week-ui-preview.png) |
| Approved UI preview, Today | [Image](screenshots/standard-08-simulator-today-ui-preview.png) | [Image](screenshots/accessibility-08-simulator-today-ui-preview.png) |

The report fixtures render the actual production `TotalActivityContent` at a width of 402 points and its natural height. The unhosted unit-test bundle uses the report font fallback. Fixtures are synthetic and outside Apple's report service; they do not represent personal usage, an authorization grant, or a running report extension. The test rejects plain-background images, and the exported images are visually reviewed.

| Report fixture | Large | Maximum accessibility text |
| --- | --- | --- |
| No returned day segments | [Image](screenshots/render-insights-report-no-data-large.png) | [Image](screenshots/render-insights-report-no-data-accessibility5.png) |
| Returned day with zero usage | [Image](screenshots/render-insights-report-zero-usage-large.png) | [Image](screenshots/render-insights-report-zero-usage-accessibility5.png) |
| Positive activity, synthetic 1h 30m | [Image](screenshots/render-insights-report-activity-fixture-large.png) | [Image](screenshots/render-insights-report-activity-fixture-accessibility5.png) |

At maximum text size the host content requires scrolling; screenshots capture individual viewports. XCTest verifies range selection, feedback availability/clearing, and reachable enabled authorization/retry controls. The standalone F6 screenshots retain the pre-existing shared tab-bar truncation and overlay; F4 owns that shell layout correction and must be integrated downstream. These screenshots do not claim the whole app's accessibility layout is complete.

Physical-device authorization transitions, real usage totals, report-service loading/failure and VoiceOver speech require separate device verification. Simulator coverage must not be described as usage enforcement verification.

## Integration

Stack: [F5 / #61](https://github.com/Prakashmaheshwaran/flint-app/pull/61) → [F6 / #65](https://github.com/Prakashmaheshwaran/flint-app/pull/65) → [F4 / #67](https://github.com/Prakashmaheshwaran/flint-app/pull/67). F5 through `851ce84` is included. F4 must include F6 through `3328601` for the final report-text sizing.

F6 passes RootView's existing AuthorizationModel into `StatsView(auth: auth)` (one tab-wiring line); preserve that call with F2's RootView work. It consumes F5's read-only error and request lifecycle without duplicating request state. No F6 authorization/PIN implementation, Today, Settings or shared design changes are added relative to F5.

`project.yml` adds test source paths for `InsightsState.swift`, `ActivitySummary.swift` and `TotalActivityView.swift`, alongside F5's AuthorizationModel source. Retain both lists when integrating other fixes. The Xcode project is generated, never hand-edited.
