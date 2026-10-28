<div align="center">

<img src="design/app-icon.svg" width="88" height="88" alt="Flint logo" />

# Flint — Peak Focus

**Free native focus tools, built in the open.**

App blocking, recurring schedules, Time/Open Limits and local focus history for
iOS and Android. All protection tiers are free. Release builds are local-first,
require no account and include no analytics SDK.

</div>

## v1 status

The [capability acceptance ledger](docs/acceptance/v1-capability-ledger.md) is the
current evidence index for `integration/v1`. It separates merged implementation,
unit tests, Android hosted emulator flows, iOS UI fixtures and physical-device
acceptance. **No physical-device enforcement verification is claimed.**

Merged v1 work includes session/recovery fixes, Android safety-floor and durable-grant
work, local history for Today/Complete/Insights, optional planned-focus-end reminders,
read-only widgets/glance surfaces, release-code artifact gates and store-package drafts.
Some protection producers still need integration; a widget can correctly report
unavailable. The ledger records exact scope and outstanding behavior.

Hardcore is a finite commitment with rules for early changes in Flint, expiry and an
available Emergency Pass. OS emergency and permission-recovery controls remain reachable.
Android does not intercept Settings, uninstall or Accessibility disable. iOS removal
restriction requests are device-wide and authorization-dependent; code assigning a
setting is not proof of its effect. [ADR-012](docs/architecture/protection-contract.md)
defines the intended per-cause rights and seven-days-after-use pass policy; the ledger
states which live paths have adopted them. Do not infer full contract compliance from
a persisted tier label or a pure policy test.

Recurring weekday schedules have no Flint-imposed count cap. iOS still has a finite,
undocumented registration pool and surfaces validation failures/refusals. Arbitrary
future date-specific starts and a full repeated Pomodoro chain are not implemented at
this checkpoint. Open-credit semantics and any remaining live migration gaps are
listed in the ledger.

## Get Flint

- **Android development preview:** artifacts are available from the
  [release page](https://github.com/Prakashmaheshwaran/flint-app/releases/latest).
  New `flint-*-android-development-preview.apk` files use release code with development
  signing; older releases used `flint-*-android-debug.apk`. Different certificates
  cannot update one another, and uninstalling can erase local rules/history.
  See [APK/AAB and signing](docs/store/android/bundles.md).
- **Google Play and App Store:** submission and published availability are not established
  by this integration branch. Signing, hardware acceptance, uploads and review remain
  [human-owned gates](docs/store/README.md).

## How it works

Flint is two native apps:

- **iOS 16+:** Swift/SwiftUI with FamilyControls, ManagedSettings and DeviceActivity.
  The host, four Screen Time extensions and a WidgetKit extension share only their
  declared resources. Simulator builds/UI fixtures do not prove real authorization,
  shields, report delivery or callback timing.
- **Android 6+ (API 23 minimum, target API 36):** Kotlin/Compose, AccessibilityService
  foreground/supported-browser observation, UsageStats fallback and overlay/Activity
  enforcement. Path B has app-only coverage. Stock/OEM background survival and the
  full supported-OS matrix remain device acceptance.

Local session history drives focus totals; screen usage is reported separately and is
never converted into fabricated focus or time saved. History, rules and other local data
are described in the [privacy policy draft](docs/privacy-policy.md). Notifications and
widgets can expose chosen session names and local summaries on OS surfaces.

The optional **Android debug connected sandbox** uses a local test backend for accounts,
groups and managed-policy authoring. Its client/Firebase account SDK and network
permissions are excluded from release artifacts; production managed enforcement is
inactive. A backend directory exists without changing the no-account release posture.

## Building

```sh
make help
make env
make ios-gen       # generate ios-app/Flint.xcodeproj from project.yml
make ios-build     # unsigned simulator build
make verify-ios    # native unit/build/privacy/Release gates
make android       # debug APK
make verify-android
make android-release
make android-bundle
```

iOS requires macOS, full Xcode 15.3+/Swift 5.10+ and XcodeGen. Configure signing privately
through the [iOS release runbook](docs/store/ios/signing-and-release.md); never hand-edit
the generated Xcode project. Android requires JDK 21 and the SDK resolved by
`scripts/flint-env.sh`; `make env` prints current paths. See the
[iOS README](ios-app/README.md), [Android README](android-app/README.md) and
[contribution rules](AGENTS.md) for platform gates and device ownership.

## Documentation and contribution

Start with [docs](docs/README.md), the [v1 plan](docs/roadmap/v1-build-plan.md),
[acceptance ledger](docs/acceptance/v1-capability-ledger.md) and
[physical-device checklist](docs/acceptance/device-acceptance-plan.md).
Dated research and verification remain historical evidence, rather than a current
feature promise. See [CONTRIBUTING](CONTRIBUTING.md), [CODE_OF_CONDUCT](CODE_OF_CONDUCT.md)
and [SECURITY](SECURITY.md).

[MIT](LICENSE). Forks need their own identifiers and authorized signing/entitlement setup.
Flint is not affiliated with or endorsed by Opal; historical comparison research is linked
for provenance and has not been refreshed as current competitor pricing or availability.
