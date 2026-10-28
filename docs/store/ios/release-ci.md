# Release simulator gate

The iOS workflow builds the host and all four extensions with
`-configuration Release`, without signing, in a separate derived-data directory.
It scans each bundle's Mach-O executable, dylibs and frameworks for symbols and strings, requiring all
five exact bundle IDs and failing on tool errors or known authorization,
session, SwiftUI and report preview fixtures. The report's `#Preview` is
explicitly guarded by `#if DEBUG`; production report rendering is unchanged.

```sh
python3 docs/store/ios/verify-release-binaries.py ios-app/DerivedDataRelease/Build/Products/Release-iphonesimulator/Flint.app
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
