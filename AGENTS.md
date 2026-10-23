# Flint contribution rules

## Repository
- Flint is a native iOS and Android monorepo.
- Never hand-edit `ios-app/Flint.xcodeproj`; edit `project.yml` and run `make ios-gen`.
- Preserve existing user changes and inspect the PR/branch graph before consolidation.

## Verification
- iOS changes: run `make verify-ios` when the toolchain is available.
- Android changes: run `make verify-android`.
- `scripts/` or `Makefile` changes: run `make selftest`.
- Cross-platform changes: run the relevant gates for both platforms.
- Report unavailable simulator, Xcode, Gradle, or physical-device verification honestly.

## GitHub
- PR creation, review submission, merge, close, admin bypass, branch deletion, and release publication are external actions requiring explicit authorization.
- Verify CI and branch ancestry before merging.
- Never use admin bypass merely to avoid a failing or missing check.

## Product
- Preserve Flint’s local-first, no-account, no-analytics product posture.
- Treat Screen Time, accessibility, device-owner, and anti-removal behavior as security-sensitive.
