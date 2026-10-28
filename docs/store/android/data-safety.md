# Release data inventory and Data safety draft

Scope: Android local-only release, package `com.flint.peakfocus`, with lane C's
Settings/uninstall-guard and Settings-text-read removal in PR105 (`a34cbba`).
Settings screen content is not read for guarding. Snapshot prepared 2026-10-28. Recheck the exact
submission commit/AAB and lane F additions before signing the form. Debug-only
Firebase/connected sandbox data is outside this release and must remain excluded.

| Data read or created | Purpose and source anchor | Location and retention | Off-device collection / sharing |
| --- | --- | --- | --- |
| Launchable app package names, labels and icons | Picker (`feature-blocklist/InstalledApps.kt`), scoped launcher queries in app manifest | OS-owned package inventory; picker/cache in memory; selected package identifiers in saved rules/groups | None |
| Foreground package/class and transition timing | Accessibility service or `UsagePoller`; app blocking and open counts | Current detector/coordinator state in memory; derived counters/discipline state in preferences | None |
| Browser address-bar URL; normalized domain | `FlintAccessibilityService.readUrl` and engine browser mappings; website rules | Read in memory for a decision; no raw URL/history persistence in these callers. User-entered domains persist in rules/groups | None |
| Android app-usage events and daily duration | `UsageStatsSource`, `UsageQuery`, `DailyLimitTracker`; local reports/budgets | Original usage history owned/retained by Android; queried results and aggregates in memory. Clearing Flint does not erase Android's history | None |
| Named rules/groups, selected apps/domains, schedules, limits and protection level | `FlintPreferences`, `DataStoreStores`, `PrefsCodec` | Private `datastore/flint_preferences.preferences_pb`: `block_rules_v1`, `app_groups_v1`, `time_limits_v1`, `open_limits_v1`, `default_break_level_v1`; until edited/deleted or app storage cleared | None |
| Break/cooldown, pass-spend and open-count state; temporary access ledger | `DataStoreStores`, `AccessDataStores`, `AccessLedgerCodec` | Same DataStore: `break_session_v1`, `emergency_pass_week_start_v1`, `emergency_pass_used_at_v1`, `open_counts_day_v1`, `open_counts_v1`, `temporary_access_v2`; state rolls/changes under its policy or is removed with storage. Runtime grants/caches may also be in memory; lane C must verify live durable wiring | None |
| Legacy selected apps/schedule and daily limits | `core-data/BlocklistStore`, `LimitStore` | Private SharedPreferences `flint_blocklist` (`blocked_packages`, `schedule_on`, `schedule_start`, `schedule_end`) and `flint_limits` (package-keyed minutes); until changed/cleared | None |
| Setup completion and notification-prompt preferences | `MainActivity` | `flint_onboarding` and `flint_permissions` SharedPreferences; until storage cleared | None |
| Permission/service/clock health, boot state | Permissions, resilience, service gates and clock guard | System settings plus local runtime state; any lane C persisted additions must be inventoried at final merge | None |
| Focus lifecycle history and derived Insights | Lane F integration | **Pending final lane F schema/path/retention verification**; add exact files/fields here before advertising or submitting history claims | None intended; verify final artifact |

The release has no account/profile identifiers, contacts, messages, photos,
microphone, camera, location, payments, ads identifiers or developer telemetry
SDK. Device package inventory, usage and URLs are still sensitive even when no
data is transmitted. Do not confuse “not collected” in the form with “not read.”

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
