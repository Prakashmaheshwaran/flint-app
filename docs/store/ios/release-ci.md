# Release simulator gate

The iOS workflow builds the host and all five extensions with
`-configuration Release`, without signing, in a separate derived-data directory.
It scans each bundle's Mach-O executable, dylibs and frameworks for symbols and strings, requiring all
exact shipping bundle IDs from XcodeGen's resolved `project.yml` and failing on
missing/duplicate/unexpected bundles, tool errors or known authorization,
session, SwiftUI and report preview fixtures. The report's `#Preview` is
explicitly guarded by `#if DEBUG`; production report rendering is unchanged.
To conserve hosted macOS capacity, the extra Release job runs for ready PRs,
workflow dispatches and the standing `integration/v1` PR, including when that
integration PR is a draft. Marking a PR ready triggers the Release job.
The same Release job also verifies privacy resources in all six bundles.
Ordinary store Markdown edits do not trigger iOS CI; executable Python verifier
changes do.

The common inventory reader uses the already-required `xcodegen dump --type json`
command, checks that every declared iOS app extension is embedded in the host,
and excludes test targets. Its device-free regression checks are:

```sh
python3 -m unittest discover -s docs/store/ios -p 'test_*.py'
```

```sh
python3 docs/store/ios/verify-release-binaries.py ios-app/build/ReleaseDerivedData/Build/Products/Release-iphonesimulator/Flint.app
```

To verify scanner sensitivity with a Debug control build:

```sh
python3 docs/store/ios/verify-release-binaries.py --expect-fixtures ios-app/DerivedData/Build/Products/Debug-iphonesimulator/Flint.app
```

The control must find authorization/session launch fixtures in the host and
the report's preview sample. Running the ordinary Release check against that
Debug build must fail. The injected authorization status/request closures used
by the real provider are production implementation and are intentionally
retained; the `AuthorizationPreview` class and preview launch arguments are not.

This is a regression check for known fixture names and preview generators,
not proof of all possible debug code or signed-device functionality. Add new
fixture names to the scanner when introducing them. Human archive validation
must rerun this check against the signed Release app before TestFlight.
