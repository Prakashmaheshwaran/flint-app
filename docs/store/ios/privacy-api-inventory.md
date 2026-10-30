# iOS privacy manifest inventory

Source inventory refreshed 2026-10-30 at `ce441b60f5cfd5d6680f9c532e977757cd3d9cdb`.
Re-audit after adding storage, networking, SDKs or
required-reason APIs. These declarations describe Flint code; they do not certify
Apple account disclosures or signed-device behavior.
The final integration refresh includes B2 (`a0ecb02`), which added the clock
calls discussed below.

## Required-reason APIs

| Bundle | API and purpose | Declaration |
| --- | --- | --- |
| `com.flint.peakfocus` | Private onboarding, focus-completion acknowledgement, reminder preference, Live Activity preference/dismissal/request bookkeeping in `FlintApp.swift`, `FocusHistoryModel.swift`, `FlintCompletionNotifications.swift` and glance observation/settings | UserDefaults, `CA92.1` |
| Host, `.monitor`, `.shield-config`, `.shield-action` | `FlintGroupStore` and `UserDefaults.flintGroup` in `Packages/FlintCore/Sources/FlintCore/{FlintGroupStore,FlintModel}.swift`; selections, rules, session, PIN, pass and counters shared only inside `group.com.flint.peakfocus` | UserDefaults, `1C8F.1` |
| All six bundles | Focus history (`Packages/FlintCore/Sources/FlintCore/FlintHistoryStore.swift`) reads file size (`attributesOfItem`), `contentModificationDateKey` and `creationDateKey` for its own files under `group.com.flint.peakfocus/FlintHistory` (bounded reads, temp-file reclaim, quarantine pruning). FlintCore is linked into every bundle; the monitor publishes history there | FileTimestamp, `C617.1` |
| All six bundles | Cross-process lock deadlines in `Packages/FlintCore/Sources/FlintCore/FlintRuleStoreLock.swift` and `FlintSessionTransitionLock.swift` read `ProcessInfo.systemUptime` only to measure elapsed wait time against a local timeout. The value and anything derived from it never leave the device. FlintCore is linked into every bundle | SystemBootTime, `35F9.1` |
| `.report` | FlintCore is linked for brand/UI types. Its shared-suite defaults implementation remains in the optimized Release report binary, although report source makes no defaults call | UserDefaults, `1C8F.1` |
| `.widgets` | Display-only protection and focus-history reads inside the shared App Group; no protection mutations or report export | UserDefaults, `1C8F.1` |

Shared defaults are also used by `FlintOpenLimit.swift` and
`FlintWebRestrictions.swift`. The monitor consumes the shared rules/session state;
shield configuration reads remaining credits; shield action reads/writes credits.
The shared protection ledger persists `FlintLedger/ledger.json`, lock/recovery files
and the `flint.ledger.initializedAt` marker. Retained-store receipt state uses
`flint.protection.retainedStoreEnds.v1`. These are local enforcement/migration data,
separate from focus-history clearing; global recovery, explicit credits, scoped breaks, generation/tombstone/start replay,
trusted rule/credit clocks and Safari authority snapshots are now wired in source. Their
local archives/frozen selections/operation outboxes remain separate from focus-history
clearing. No source integration establishes physical-device delivery. Private reminder preferences are distinct from OS notification
authorization, and Live Activity defaults off. These purposes do not add a manifest
category beyond the declarations above.
The report's `ActivitySummary`, `TotalActivityReport` and `TotalActivityView`
keep system-provided report data in the report process; the report declaration
covers retained shared-library code, not persistence or export of report data.
An optimized simulator Release build was inspected with `nm -a` on 2026-10-28
and retained `NSUserDefaults`, `FlintGroupStore` and shared config load/save
implementations in the report executable. The report therefore declares the
same App Group reason as the other extensions rather than relying on a
source-call-only inventory. Reinspect the final signed archive as well.
All six bundles have their own manifest and no tracking, tracking domains or
collected data types. No third-party Swift SDK dependency is declared in
`project.yml`; FlintCore is a local static Swift package.

Focus history (#90, lane F) added file-metadata reads inside the App Group
container, declared above as FileTimestamp `C617.1` in all six manifests; values
are used only to size, order and reclaim Flint's own history files and never
leave the device. Apart from that, the **historical** 2026-10-28 production Swift-source search
(host, extensions and FlintCore, excluding Tests) found no other direct file
timestamp API (`stat`/`fstat`/`lstat`, `getattrlist`, `creationDate`,
`modificationDate`, related URL resource keys),
system boot-time API (`systemUptime`, `mach_absolute_time`), disk-space API
(`statfs`/`statvfs`, volume capacity keys, `systemFreeSize`/`systemSize`), or
active-keyboard API (`activeInputModes`). `Date`, calendar arithmetic and timers
do not themselves justify declaring boot-time API use. No unused reason codes
are declared. Recheck the final archive and Apple's privacy report before upload;
platform implementation details are not application API calls.

### History metadata and optimized binary retention

Historical inspection: on 2026-10-28, lane K inspected an unsigned optimized simulator Release build
containing merged history PR #90 and the read-only reader from PR #132
(`f5ca108`). All five current executables plus the proposed widget executable
retain `_fstat`, Foundation's `attributesOfItem` and `FlintHistoryStore` reader
code. This supports the FileTimestamp declaration in every current bundle;
the widget's separate target also needs it and is now merged as the sixth bundle.
The report extension still keeps
DeviceActivity report data inside its process; retained history metadata APIs
do not authorize report export.

[Per-bundle symbol checks and executable hashes](../../verification/ios-history-privacy-2026-10-28/release-retention.json)
record the evidence and its source-provenance limitation: the build included
uncommitted display wiring, so its post-build diff hash is not an exact build
attestation. This inspection proves binary retention, not signed-device behavior
or App Store acceptance. Rerun the inventory against the final signed archive.

Apple's current [required-reason list](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype),
re-read on 2026-10-28, explicitly lists `fstat` and file-attribute APIs.
`C617.1` permits metadata access for files inside app and App Group containers,
which matches local bounded history reads, cleanup and quarantine ordering.
Keep the file-descriptor and size checks; removing safety checks is unnecessary.

### B2 clock and boot-session identity refresh

`FlintSessionTiming.swift` now calls
`clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW)` for elapsed session timing and
`sysctlbyname("kern.bootsessionuuid", ...)` for a same-boot comparison token.
These are actual production calls and were reviewed separately from `Date` and
timer scheduling. The anchors and boot token stay in local shared session state;
they are not sent to a service or used to identify a person.

Apple's current `NSPrivacyAccessedAPICategorySystemBootTime` list names
`ProcessInfo.systemUptime` and `mach_absolute_time()`. It does not list
`clock_gettime_nsec_np` or `sysctlbyname`; neither appears in the other current
required-reason categories. On that source-to-list mapping, these B2 calls do
not add a declared category or change the bundle manifests. This is an inference
from the published list, not a promise about future policy or archive validation.
If an implementation switches to a listed API, elapsed session timing must be
reviewed against Apple's `35F9.1` reason before shipping.

**2026-10-30 refresh.** Later lock work (`FlintRuleStoreLock`, `FlintSessionTransitionLock`)
calls the listed `ProcessInfo.systemUptime` for elapsed lock-wait deadlines. All six
manifests now declare SystemBootTime `35F9.1`, which covers measuring elapsed time between
in-app events and timer calculations, with no boot-time value sent off-device. The
`clock_gettime_nsec_np`/`sysctlbyname` analysis above is unchanged. Re-run this inventory
and `verify-privacy-bundles.py` against the final signed archive.

This list was re-read on **2026-10-28** in Apple's
[API type documentation](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)
and its [official DocC data](https://developer.apple.com/tutorials/data/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype.json)
(`primaryContentSections` → `possibleValues`). The Markdown rendering omits
those value sections, so it is insufficient by itself for checking the list.
Recheck the final signed archive and Apple's privacy report before upload.

### New lock-deadline API gate (2026-10-30)

The current production [rule lock](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintRuleStoreLock.swift)
and [session transition lock](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionTransitionLock.swift)
call `ProcessInfo.processInfo.systemUptime` to bound local lock acquisition. This is a
listed SystemBootTime API, unlike the separate B2 `clock_gettime_nsec_np`/`sysctlbyname`
source-to-list inference above. The six source manifests currently contain UserDefaults
and FileTimestamp only: the old inventory and current equality verifier do not establish
complete declarations for these later calls.

Apple's [official category/reason data](https://developer.apple.com/tutorials/data/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype.json),
re-read 2026-10-30, lists `systemUptime`; `35F9.1` covers local elapsed-event/timer uses.
The lock-deadline use is a candidate match, requiring source/binary and declaration
review in every retaining bundle before release. Do not claim this docs-only refresh
added a manifest reason or inspected a final signed archive. The gap was routed to the
coordinator; its final disposition belongs in the ledger.

## Collection and tracking

The inspected iOS source has no app-owned URLSession/network client, analytics,
advertising, account or telemetry SDK. Preferences and blocking configuration
stay in local defaults. Apple's DeviceActivity report stays in its extension;
it is not exported into the host or to a server. The user-triggered GitHub link
in Settings opens an external destination, whose operator has its own policy.
Apple's collection definition concerns off-device transfer accessible beyond
the real-time request; local-only processing supports an empty
`NSPrivacyCollectedDataTypes` and draft "Data Not Collected" answers. Reconfirm
against the final integrated build and lane E's `docs/privacy-policy.md`.

## Apple sources

Accessed **2026-10-28**, using current official Apple Developer documentation:

- [Required-reason API categories and approved reasons](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype): `CA92.1` covers private app defaults; `1C8F.1` covers defaults shared by members of the same App Group. Neither permits accessing other apps' defaults outside the group.
- [Required-reason API overview](https://developer.apple.com/documentation/bundleresources/describing-use-of-required-reason-api).
- [Privacy manifest format](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files).
- [App privacy details and collection definition](https://developer.apple.com/app-store/app-privacy-details/).

## Built-bundle check

CI runs this after building the host and its five embedded extensions:

```sh
python3 docs/store/ios/verify-privacy-bundles.py ios-app/DerivedData/Build/Products/Debug-iphonesimulator/Flint.app
```

For a human-created signed archive, pass
`<archive>.xcarchive/Products/Applications/Flint.app` instead. The check requires
the exact shipping IDs derived from XcodeGen's resolved `project.yml`, a
root-level manifest in each bundle, equality with source,
and the intended tracking/collection/API declarations. It does not validate
distribution entitlements, Apple's server-side validation or future API usage.
Missing, duplicate and unexpected bundles fail; adding an extension to the spec
also adds it to the privacy and Release binary checks without a Python ID list.
