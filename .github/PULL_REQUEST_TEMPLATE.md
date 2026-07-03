## What & why

<!-- The user-facing change; link an issue if one exists. -->

## Platform

- [ ] iOS
- [ ] Android
- [ ] Repo / docs / CI

## Verification (honest-docs rule)

<!-- Paste what you actually ran. Never claim device-verified behavior that was only built
     or simulated — say what's verified and how. -->

- [ ] iOS: `make ios-build` green (plus `xcodebuild test` if logic changed)
- [ ] Android: `make android` and `make android-test` green
- [ ] Docs updated if behavior changed
- [ ] No telemetry, analytics, network calls, or accounts introduced (hard rule)
- [ ] No hard-coded palette/brand values — design tokens only (`design/`)
- [ ] iOS: `.xcodeproj` untouched — changes go through `ios-app/project.yml` (XcodeGen)
