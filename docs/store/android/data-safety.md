# Release data inventory and Data safety draft

Scope: Android local-only release, package `com.flint.peakfocus`, with lane C's
Settings/uninstall-guard and Settings-text-read removal in PR105 (`a34cbba`).
Settings screen content is not read for guarding. Lane J reconciled this inventory on
2026-10-28 against merged `integration/v1` `32a8d6b`, including F history, K126 and C139.
Recheck the exact submission commit/AAB before signing the form. Debug-only
Firebase/connected sandbox data is outside this release and must remain excluded.

| Data read or created | Purpose and source anchor | Location and retention | Off-device collection / sharing |
| --- | --- | --- | --- |
| Launchable app package names, labels and icons | Picker (`feature-blocklist/InstalledApps.kt`), scoped launcher queries in app manifest | OS-owned package inventory; picker/cache in memory; selected package identifiers in saved rules/groups | None |
| Foreground package/class and transition timing | Accessibility service or `UsagePoller`; app blocking and open counts | Current detector/coordinator state in memory; derived counters/discipline state in preferences | None |
| Browser address-bar URL; normalized domain | `FlintAccessibilityService.readBrowserWindow` and versioned `BrowserAddressBars` matrix; website rules. Supported-browser window-content changes trigger a throttled address-bar re-read after same-tab navigation, only while website rules are active | Read in memory for a decision; no raw URL/history persistence in these callers. User-entered domains persist in rules/groups | None |
| Website coverage observation (supported browser, unreadable address bar, unsupported web handler) | `WebCoverageTracker`, `WebsiteCoverageHealth`, `PermissionHealthChecker`; honest local health status. Package-scoped http(s) VIEW queries identify unsupported handlers; no broad package inventory query | Process-local state only; miss count and degradation time reset with the browser session/process. No URL stored in the health model | None |
| Android app-usage events and daily duration | `UsageStatsSource`, `UsageQuery`, `DailyLimitTracker`; local reports/budgets | Original usage history owned/retained by Android; queried results and aggregates in memory. Clearing Flint does not erase Android's history | None |
| Named rules/groups, selected apps/domains, schedules, limits and protection level | `FlintPreferences`, `DataStoreStores`, `PrefsCodec` | Private `datastore/flint_preferences.preferences_pb`: `block_rules_v1`, `app_groups_v1`, `time_limits_v1`, `open_limits_v1`, `default_break_level_v1`; until edited/deleted or app storage cleared | None |
| Break/cooldown, pass-spend and open-count state; temporary access ledger | `DataStoreStores`, `AccessDataStores`, `AccessLedgerCodec`, C136 durable grants | Same DataStore: legacy break/pass/open state and `temporary_access_v2`; consumed state, boot-bound active grants and operation receipts persist before release. Runtime caches also exist. ADR-012 rolling/global/explicit-credit runtime remains a separate integration gate | None |
| Editor commitment snapshots, deferred changes, Harder waits/time anchors and migration archives | C139 `DataStoreEditorCommitments`, `HarderWaitStore`, `ProtectionClock` | Same private DataStore: `editor_commitments_v1`, `editor_commitments_version`, `editor_migration_rules_v1`, `editor_migration_time_limits_v1`, `harder_wait_ledger_v1`; raw legacy archives are local recovery material, not cleared by history deletion | None |
| Legacy selected apps/schedule and daily limits | `core-data/BlocklistStore`, `LimitStore` | Private SharedPreferences `flint_blocklist` (`blocked_packages`, `schedule_on`, `schedule_start`, `schedule_end`) and `flint_limits` (package-keyed minutes); until changed/cleared | None |
| Setup completion and notification-prompt preferences | `MainActivity` | `flint_onboarding` and `flint_permissions` SharedPreferences; until storage cleared | None |
| Permission/service/clock health, boot state | Permissions, resilience, service gates and clock guard | System settings plus local runtime state; any lane C persisted additions must be inventoried at final merge | None |
| Focus lifecycle history and derived Insights | F89/F95/F129 `FocusHistoryBridge`, `FocusHistoryStore`, `HistoryReconciler` | Private `files/focus-history/events.v1.jsonl`, `metadata.v1.json`, bounded `quarantine/`/temporary recovery files. Session/operation IDs, chosen name, tier/origin/times/outcome and available metrics; no selected package/app/domain/token identifiers in the event projection. Rolling 400-day target, 1 MiB compaction trigger, 5 MiB main-log hard cap and record limits; active sessions retained. Clear history preserves active sessions and bounded recovery copies | None; recheck final artifact |
| Planned-end notification schedule/delivery bookkeeping | F97 `FocusCompletionNotifier`, `FocusNotificationState` | `files/focus-history/notification-state.v1.json` stores scheduled/delivered session IDs/times and recoverable alarm state. Inexact alarms and OS notification copies are separate from history clearing | None |
| Widget and ongoing-session display projection (merged K126) | `app/glance/GlanceState.kt`, `GlanceSurfaceBridge.kt`, `OngoingSessionNotification.kt`, `GlanceReadProjection.kt`: source/session name, tier, logical-end estimate, next-session name/start, F's focused-today text/note and observed check/date | App projection is in memory, without a durable protection snapshot; launcher/widget and system notification copies can outlive the app process until refreshed or removed. Notifications request private lock-screen visibility. Public protection publishers/service delivery remain pending; missing/stale data is unavailable | No developer network transfer; review OS surface delivery and applicable user-initiated sharing exceptions in the final form |

The release has no account/profile identifiers, contacts, messages, photos,
microphone, camera, location, payments, ads identifiers or developer telemetry
SDK. Device package inventory, usage and URLs are still sensitive even when no
data is transmitted. Do not confuse “not collected” in the form with “not read.”

The merged K projection has no selected package/app/domain identifiers in its display
model; user-chosen session names can still be sensitive. Notification surfaces are distinct:

- C mandatory fallback FGS: `flint_enforcement`, **Focus enforcement**, ID 1001.
- K optional Path A status: `flint_ongoing_session`, **Focus in progress**,
  tag `flint.ongoing.session` / ID 1002; only Open Flint, never a weakening action.
- F planned-end reminder: `focus_sessions`, **Focus end reminders**,
  per-session tag / ID 1, inexact alarm and planned-time wording.

K does not cancel/time out the mandatory FGS. Actual live cause/detector publication,
service customization, launcher pixels, lock-screen privacy and OEM delivery are separate
acceptance gates. [K126 CI](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40778992856)
and release smoke prove build/local-first/R8 boundaries, not those OS display behaviors.
The [capability ledger](../../acceptance/v1-capability-ledger.md) and
[single owner checklist](../README.md) govern the final submission refresh.

## Candidate Console answers

- **Does the app collect or share required user data types? No**, conditional on
  the exact release still using only local processing and having no transfers.
  Google's [Data safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469)
  excludes data processed only on-device from collection; on-device transfers to
  another app can still count as sharing. Audit outbound intents/SDKs as well as
  network permissions. The source has no release sync/export implementation.
- **Collected/shared data type entries: none** under that definition. Do not mark
  installed apps, browsing or usage as collected just because they are processed
  locally. Explain those local uses in the Accessibility declaration and policy.
- **Accounts: no account creation/login**. Cloud account-deletion flow is not
  applicable. Local deletion is supported through Android app storage controls
  and uninstall, described in the privacy policy.
- **Encryption in transit:** no developer transmission; follow the current form's
  not-applicable path. Do not claim end-to-end encryption or encrypted private
  preference files as an implemented feature.
- **Independent security review / Families / certifications:** do not claim them;
  select only programs actually completed by the owner.

The owner must attach the final AAB SHA-256, source SHA, dependency/permission
inspection and published policy URL to their submission record. `INTERNET`
absence plus DEX exclusions is useful evidence, not the entire Data safety form.
Official guidance accessed 2026-10-28; Console wording may change.
