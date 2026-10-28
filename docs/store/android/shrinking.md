# Release optimization and evidence

Release enables R8 code optimization and resource shrinking; debug remains
unoptimized for development. Flint currently opts into the legacy AGP DSL
(`android.newDsl=false`), so `isMinifyEnabled` and `isShrinkResources` remain the
appropriate existing configuration surface. No AGP/Kotlin migration is bundled
with this change. The [current Android optimization guide](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
and [keep-rule guide](https://developer.android.com/topic/performance/app-optimization/keep-rules-overview)
were checked 2026-10-28; the latter explicitly retains legacy DSL support in AGP
9.3 and later.

## Retained boundaries

- Manifest-driven Flint Application, Activity, Service and BroadcastReceiver
  classes and their callbacks are kept explicitly, in addition to Android's
  default manifest-root rules. This includes Accessibility, the fallback FGS,
  block Activity and boot/time-change receivers.
- Core persisted models/enums and DataStore codecs are conservatively retained.
  Current serialization is explicit rather than reflective; persisted names and
  future serializer compatibility must still be reviewed when storage evolves.
- Accessibility's XML metadata resource has an explicit resource-shrinker keep.
  The final APK must retain the service, its metadata reference and XML flags.
- Firebase/connected-feature names are protected from renaming if accidental
  live code reaches release, preserving the descriptor exclusion scan. Unused
  code may still be removed. These packages remain debug-only dependencies.
- R8 assertions require design-time Compose tooling and Flint Preview methods
  to be discarded. The release has no tooling UI dependency.

Android CI retains `app/build/outputs/mapping/release/mapping.txt` as an artifact
named with the exact source SHA, for 90 days; missing output fails the upload
step. Mapping files are build artifacts and are never committed. The owner must
archive mapping plus source SHA and artifact hashes for each distributed version
beyond CI retention. Existing privacy/data exclusions still run on both APK and
AAB after optimization; passing them is not a complete privacy or package audit.

## Required runtime acceptance

Shrinking changes the executable, so JVM tests alone cannot certify it. Before
merging an optimization change, install the exact release APK on the reserved
disposable API35/36 emulator, verify its version/hash and launch, enable
Accessibility through its disclosed system flow, create a fictional app rule in
the release UI, then observe the target app shield in pixels and Accessibility
state. Retain a small evidence note and clean only owned device resources.
Recheck storage round trips and OS recovery/permission controls as appropriate.
Physical-device/OEM and signed Play split/update testing remain separate.

Lane I's current standalone E2E module is not an unchanged release substitute:
its exhausted-budget case seeds state through the debug-only SetBlocklistReceiver
and its setup expects baseline disclosure text. A release-targeted test suite
must remove that fixture dependency and follow current consent copy first.

If an optimization concern cannot be resolved with build and runtime evidence,
leave shrinking disabled and document the exact unresolved boundary. Do not
silence missing-class errors or broadly suppress warnings to obtain a green build.
