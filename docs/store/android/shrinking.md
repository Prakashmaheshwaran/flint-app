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
- The release BuildConfig development-preview classification remains readable
  after optimization, even though runtime app code does not branch on it.
- Accessibility's XML metadata resource has an explicit resource-shrinker keep.
  The final APK must retain the service, its metadata reference and XML flags.
- Firebase/connected-feature classes and members are protected from renaming or
  optimization if accidental live code reaches release, preserving the descriptor
  exclusion scan even against helper-method inlining. Dead classes/members may
  still be removed. These packages remain debug-only dependencies.
- R8 assertions require design-time Compose tooling and Flint Preview methods
  to be discarded. The release has no tooling UI dependency.
- Glance/WorkManager constructs Room's `androidx.work.impl.WorkDatabase_Impl`
  reflectively. The exact generated class and public no-argument constructor are kept
  by `app/proguard-rules.pro`; do not replace this targeted rule with a broad keep.
  K126's earlier release startup failure and repaired
  [API 36 release smoke](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40778992967)
  are artifact-specific evidence, not proof of a later candidate or hardware delivery.

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

The separate `Android release smoke` workflow builds the release APK/AAB and
isolated self-instrumenting test APK before emulator boot. Its release mode
(`FLINT_E2E_DISPOSABLE=1 FLINT_E2E_VARIANT=release AVD=emulator-N bash scripts/android-e2e.sh`)
retains lane I's exact-boot, disposable-device and run-token grant cleanup guards.
It installs the release app directly, hashes both installed APKs against their
build inputs, then selects only the UI-authored rule/persistence/shield/break test.
It never invokes `connectedDebugAndroidTest`, which would install the debug app,
and excludes the budget test's debug-only receiver. The selected case grants
Accessibility through owned instrumentation setup; manual disclosure/system-grant
flow and OEM testing remain separate acceptance.

The runner retains source SHA, installed APKs/hashes, package metadata, raw
instrumentation output, screenshots, hierarchy, logcat and mapping artifacts.
Strict result parsing requires the exact single test to start and pass, a
successful runner summary and terminal code; adb shell exit zero alone cannot
certify runtime. Missing, failed, ignored, skipped or crashed tests fail the gate.
No assertion retries are performed. Mapping/runtime evidence retention is 90 days;
the owner must retain distributed-release evidence beyond that window.

If an optimization concern cannot be resolved with build and runtime evidence,
leave shrinking disabled and document the exact unresolved boundary. Do not
silence missing-class errors or broadly suppress warnings to obtain a green build.
