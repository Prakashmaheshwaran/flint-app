# Owner signing, versioning and archive procedure

This is a scaffold and human runbook, not evidence of Apple membership,
capability approval, local certificates, signed hardware acceptance, an archive,
an App Store Connect record or a TestFlight build. No account action is automated.

## Local signing settings

Debug and Release use `ios-app/Config/Signing.xcconfig`, which optionally
includes `Signing.local.xcconfig` from the same directory. Copy the committed
`.example` to that **gitignored** filename and set the intended approved team.
No team ID, explicit signing identity, certificate, key, profile or account
credential belongs in `project.yml` or any tracked file. Keep signing material
in the owner's keychain/secure storage. The optional include permits unsigned
CI builds without any local signing file; CI disables signing explicitly.

Automatic signing is the committed default. If a team requires an explicit
identity, set it only in the local override. Confirm the effective settings for
every shipping target before archiving; never edit the generated Xcode project.
Do not put version overrides in this machine-local file.
The host explicitly inherits its identity so XcodeGen's application preset
cannot override the identity from the owner's local project configuration.

## Version policy

`MARKETING_VERSION` in `ios-app/project.yml` is the digits-and-dots semantic
version `X.Y.Z`; all five shipping targets inherit it. It must equal the numeric
core of the eventual repository release tag. `scripts/check-release-version.sh`
already checks this mapping, including prerelease tags such as `v1.0.0-rc.1`
mapping to iOS `1.0.0`. It also checks Android's full version name/code and the
changelog, so a repository release requires coordination with lane E.

`CURRENT_PROJECT_VERSION` is a positive integer, shared by the host and all
embedded extensions. Increment it in a reviewed commit before **every new
TestFlight upload**, including a rebuild of the same marketing version. Never
reuse an uploaded build number or override it on one target. The existing
version-check script does not enforce iOS build-number monotonicity: the owner
must compare against App Store Connect and the last uploaded build, then inspect
the archived `CFBundleVersion` and `CFBundleShortVersionString` in all five
bundles. The baseline `0.1.0` / `1` remains unchanged by this scaffold; it is not
an approved v1 upload version. No tag or release is created here.

Before a coordinated repository release, run `make release-check TAG=vX.Y.Z`
with the reviewed actual tag/version. A passing script is a consistency check,
not upload authorization or proof of capability/signing.

## Human prerequisites

1. Inspect the intended Apple Developer team and existing Family Controls
   distribution approval for `com.flint.peakfocus`, `.monitor`, `.shield-config`,
   `.shield-action` and `.report`. Check each ID separately; status is unknown.
   Confirm all five profiles contain Family Controls and the shared
   `group.com.flint.peakfocus` entitlement. Use the unsent request drafts in
   [review-notes.md](review-notes.md) when available; avoid duplicate requests.
2. Provision the intended signing identity and profiles in the owner's keychain
   and Xcode account configuration. Do not grant CI access to the Apple account.
3. Record the reviewed integration commit, green iOS Debug/Release CI,
   independent privacy review, final version/build and lane B/F acceptance.
   Complete signed physical iPhone/iPad testing; simulator CI does not prove
   Screen Time, extension callbacks, expiry, permission recovery or safe exit.

## Archive locally after the prerequisites

From the repository root, with the local override configured and the real
device provisioning already installed:

```sh
source scripts/flint-env.sh
make ios-gen
xcodebuild -project ios-app/Flint.xcodeproj -scheme Flint \
  -configuration Release -destination 'generic/platform=iOS' \
  -archivePath ios-app/build/Flint.xcarchive archive
```

This deliberately supplies neither `-allowProvisioningUpdates` nor an upload
option. If signing fails, stop and have the owner resolve the exact team/profile
problem. Do not bypass it by stripping entitlements or using simulator output.
Open the archive in Xcode Organizer and inspect the host plus all four embedded
extensions. Check exact bundle IDs, inherited version/build, signatures, App
Group/Family Controls entitlements, icon resources and privacy manifests.

```sh
python3 docs/store/ios/verify-privacy-bundles.py ios-app/build/Flint.xcarchive/Products/Applications/Flint.app
python3 docs/store/ios/verify-release-binaries.py ios-app/build/Flint.xcarchive/Products/Applications/Flint.app
```

For each `.app`/`.appex`, inspect `codesign -d --entitlements :- <bundle>` and
`security cms -D -i <bundle>/embedded.mobileprovision` locally. Do not paste
certificate/profile contents or personal identifiers into repository evidence.
Use Xcode's privacy report and validation to catch final integration/SDK issues;
source declarations and simulator checks do not replace these steps.

## Export and TestFlight remain separate owner actions

Use Organizer to prepare an App Store Connect **local export**. Inspect the
resulting export options and save them privately as
`ios-app/Config/ExportOptions.local.plist` (gitignored). Confirm its
`destination` is `export`, never `upload`, and review its method, team and all
five provisioning mappings before using the equivalent local-only CLI:

```sh
xcodebuild -exportArchive -archivePath ios-app/build/Flint.xcarchive \
  -exportPath ios-app/build/export \
  -exportOptionsPlist ios-app/Config/ExportOptions.local.plist
```

Export options vary by installed Xcode and signing method; use that Xcode's
`xcodebuild -help` and owner-generated options instead of a fabricated profile
map. An export plist with `destination=upload` performs an account action, so
do not reuse one without reviewing it. No upload command is supplied by CI.

The owner then confirms/creates the App Store Connect app record, final public
privacy/support URLs (lane E's `docs/privacy-policy.md` is source content, not
a deployed URL), App Privacy and age-rating answers, review contact/notes and
real screenshots. After explicit approval, the human validates/uploads the
reviewed build, checks Apple processing, enables the intended TestFlight testers
and verifies an actual TestFlight install on hardware. External beta review,
App Review submission and publication remain separate owner decisions. Record
the processed version/build and physical-device evidence; do not infer them
from this runbook or a successful unsigned CI job.

Official Apple references, accessed 2026-10-28:

- [Distribution and archives](https://developer.apple.com/documentation/xcode/distributing-your-app-for-beta-testing-and-releases).
- [Testing a Release build](https://developer.apple.com/documentation/xcode/testing-a-release-build).
- [Command-line archive/export syntax](https://developer.apple.com/library/archive/technotes/tn2339/_index.html).
