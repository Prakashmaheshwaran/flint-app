# APK and Android App Bundle verification

`make android-release` assembles and verifies the release APK.
`make android-bundle` runs `:app:bundleRelease` and verifies
`android-app/app/build/outputs/bundle/release/app-release.aab`.
`make verify-android` includes debug, both release formats and all JVM tests.
Android PR CI builds/scans both formats; repository CI runs device-free script
selftests. These builds do not publish or upload anything to Google.

The verifier keeps its historical filename `scripts/verify_local_first_apk.py`
but accepts both formats. It recognizes the archive layout, requires the
primary manifest/classes and bundle configuration, and scans uncompressed data:

- APK: root binary XML manifest (UTF-8 or UTF-16 strings) and every root DEX.
- AAB: base protobuf XML manifest plus every feature-module manifest and DEX.
- Both: reject INTERNET/ACCESS_NETWORK_STATE permission strings, Firebase and
  Flint's connected-feature class prefixes, empty primary entries and duplicate
  archive names. Future dynamic modules cannot bypass the same exclusions.

This named-exclusion inspection is not a protobuf/DEX validity validator or a
complete outbound-intent/SDK privacy audit. AGP builds establish package format;
Play/bundletool validation and installed split behavior remain submission-device
checks. Never substitute a ZIP scan for actual signer or update validation.

Public release automation checks out the exact release tag, runs
`make release-check`, builds/tests both formats, scans both and uploads only to
the already authorized GitHub Release when that workflow is explicitly invoked
or a release is published. Artifacts are named `android-development-preview`
with APK/AAB extensions and SHA-256 sums. The workflow has no owner upload-key
configuration; those files are development-signed previews, not Play uploads.
This change does not run that publication workflow.

For the owner's intended Play bundle, first configure [upload signing](signing.md)
and use `FLINT_REQUIRE_UPLOAD_SIGNING=1 make android-bundle`. Verify the signer,
record source SHA/AAB SHA-256 and use the owner's Play testing process to validate
generated splits, install/update and retained local data. Signing a bundle does
not establish Play policy eligibility or authorization to upload it.

APK and AAB share the literal `versionCode`/`versionName` in app Gradle; keep the
existing `scripts/check-release-version.sh` policy: versionName equals tag without
`v`, versionCode strictly increases over the previous reachable release, iOS
marketing version matches the numeric core, and CHANGELOG includes that version.
No new release/tag/version is chosen by this packaging change.
