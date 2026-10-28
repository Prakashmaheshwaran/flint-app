# iOS privacy manifest inventory

Source inventory: 2026-10-28. Re-audit after adding storage, networking, SDKs or
required-reason APIs. These declarations describe Flint code; they do not certify
Apple account disclosures or signed-device behavior.
The final integration refresh includes B2 (`a0ecb02`), which added the clock
calls discussed below.

## Required-reason APIs

| Bundle | API and purpose | Declaration |
| --- | --- | --- |
| `com.flint.peakfocus` | `@AppStorage("flint.onboarded")` in `Flint/FlintApp.swift`, private onboarding preference | UserDefaults, `CA92.1` |
| Host, `.monitor`, `.shield-config`, `.shield-action` | `FlintGroupStore` and `UserDefaults.flintGroup` in `Packages/FlintCore/Sources/FlintCore/{FlintGroupStore,FlintModel}.swift`; selections, rules, session, PIN, pass and counters shared only inside `group.com.flint.peakfocus` | UserDefaults, `1C8F.1` |
| `.report` | FlintCore is linked for brand/UI types. Its shared-suite defaults implementation remains in the optimized Release report binary, although report source makes no defaults call | UserDefaults, `1C8F.1` |

Shared defaults are also used by `FlintOpenLimit.swift` and
`FlintWebRestrictions.swift`. The monitor consumes the shared rules/session state;
shield configuration reads remaining credits; shield action reads/writes credits.
The report's `ActivitySummary`, `TotalActivityReport` and `TotalActivityView`
keep system-provided report data in the report process; the report declaration
covers retained shared-library code, not persistence or export of report data.
An optimized simulator Release build was inspected with `nm -a` on 2026-10-28
and retained `NSUserDefaults`, `FlintGroupStore` and shared config load/save
implementations in the report executable. The report therefore declares the
same App Group reason as the other extensions rather than relying on a
source-call-only inventory. Reinspect the final signed archive as well.
All five bundles have their own manifest and no tracking, tracking domains or
collected data types. No third-party Swift SDK dependency is declared in
`project.yml`; FlintCore is a local static Swift package.

The 2026-10-28 production Swift-source search (host, extensions and FlintCore,
excluding Tests) found no direct file timestamp API (`stat`/`fstat`/`lstat`,
`getattrlist`, `creationDate`, `modificationDate`, related URL resource keys),
system boot-time API (`systemUptime`, `mach_absolute_time`), disk-space API
(`statfs`/`statvfs`, volume capacity keys, `systemFreeSize`/`systemSize`), or
active-keyboard API (`activeInputModes`). `Date`, calendar arithmetic and timers
do not themselves justify declaring boot-time API use. No unused reason codes
are declared. Recheck the final archive and Apple's privacy report before upload;
platform implementation details are not application API calls.

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
not add a declared category or change the five manifests. This is an inference
from the published list, not a promise about future policy or archive validation.
If an implementation switches to a listed API, elapsed session timing must be
reviewed against Apple's `35F9.1` reason before shipping.

This list was re-read on **2026-10-28** in Apple's
[API type documentation](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype)
and its [official DocC data](https://developer.apple.com/tutorials/data/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitype.json)
(`primaryContentSections` → `possibleValues`). The Markdown rendering omits
those value sections, so it is insufficient by itself for checking the list.
Recheck the final signed archive and Apple's privacy report before upload.

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

CI runs this after building the host and four extensions:

```sh
python3 docs/store/ios/verify-privacy-bundles.py ios-app/DerivedData/Build/Products/Debug-iphonesimulator/Flint.app
```

For a human-created signed archive, pass
`<archive>.xcarchive/Products/Applications/Flint.app` instead. The check requires
the exact five IDs, a root-level manifest in each bundle, equality with source,
and the intended tracking/collection/API declarations. It does not validate
distribution entitlements, Apple's server-side validation or future API usage.
