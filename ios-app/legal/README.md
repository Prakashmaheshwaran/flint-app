# Bundled iOS legal documents

Flint's iOS app renders its privacy policy and open-source notices entirely from text
inside the app bundle. Nothing is fetched, cached from the web, or rendered in a
WebView, and no analytics or account is involved. The renderer supports a small
Markdown subset, so `[label](url)` links appear as plain, selectable text.

## What is generated

`generate_legal.py` writes two files into `ios-app/Flint/Resources/Legal/` (XcodeGen
copies both to the root of `Flint.app`):

- `privacy-policy.md`: a byte-for-byte copy of `docs/privacy-policy.md`. The generator
  never rewrites or reformats the policy; editing the canonical document and running
  `make ios-legal` is the only way it changes.
- `open-source-licenses.md`: the reviewed inventory from `components.json` plus the
  full upstream license text each component ships under, embedded verbatim in fenced
  blocks. Embedded lines are trimmed of trailing whitespace so the file stays
  byte-stable across editors.

`components.json` is the reviewed inventory, not a build input that can be inferred:

| field | meaning |
| --- | --- |
| `kind` | `font` (a bundled third-party binary) or `application` (Flint's own license) |
| `name` | display name used as the notices heading |
| `files` | repository-relative path and SHA-256 of every bundled file |
| `licenseFile` | repository-relative path to the full license text (`design/fonts/*-OFL.txt` for fonts) |
| `licenseFileSha256` | SHA-256 of that text |
| `license` | license name as printed in the notices |
| `source` | upstream repository URL |
| `changes` | how the bundled copy differs from upstream (fonts only) |
| `bundles` | exact bundle names that must contain the files (`Flint.app`, `DeviceActivityReportExtension.appex`) |
| `shippedIn` | human-readable "Shipped in" line |

Today it describes two SIL Open Font License 1.1 families (Hanken Grotesk, Shippori
Mincho) and Flint's own MIT license. Font provenance and modifications are recorded in
`design/fonts/README.md`, and the `*-OFL.txt` copies beside the fonts must stay
identical to `design/fonts/`.

## Regenerate after a source change

```sh
make ios-legal          # rewrite ios-app/Flint/Resources/Legal/
make ios-legal-check    # regenerate in memory, compare, and run the generator tests
```

Run `make ios-legal` after editing `docs/privacy-policy.md`, `LICENSE`, a font file, a
font license, or `components.json`, then commit the regenerated files. Generation runs
offline; it needs no Xcode, simulator, or network.

## Where the checks run

- `make ios-legal-check` (also part of `make verify-ios`) fails if either bundled
  document drifts from its sources, and runs `test_generate_legal.py`.
- `make verify-ios` additionally verifies the built Debug app:
  `--verify-bundle "$IOS_DERIVED_DATA/Build/Products/Debug-iphonesimulator/Flint.app"`.
- iOS CI (`.github/workflows/ios.yml`) runs `--check` plus the generator tests on
  checkout, then `--verify-bundle` on the Debug build and again on the Release build.
  Those jobs also trigger on changes to `docs/privacy-policy.md`, `design/fonts/**`
  and `LICENSE`.

`--verify-bundle` checks that both documents in the built app equal the committed
copies (and that the policy equals `docs/privacy-policy.md`), that every `.ttf`/`.otf`/
`.ttc`/`.woff`/`.woff2` in `Flint.app` and each `Flint.app/PlugIns/*.appex` is a
declared component file matched by name *and* SHA-256, that each font appears in
exactly the bundles its inventory entry declares, and that no bundle embeds third-party
code (`*.framework`, `*.xcframework`, `*.dylib`, or a non-empty `Frameworks/`
directory). Xcode's own Debug artifacts at a bundle root — `<CFBundleExecutable>.debug.dylib`
and `__preview.dylib` — are the only allowed dynamic libraries.

## Adding a reviewed component

1. Confirm the component actually ships: check `ios-app/project.yml` sources and the
   build's `PlugIns` output rather than assuming.
2. Review its license and add the full license text to the repository (fonts go in
   `design/fonts/`). Never infer a license from a name or URL.
3. Add an entry to `components.json` with real SHA-256 values
   (`shasum -a 256 <file>`), the source URL, the exact bundle names, and any
   modifications you made.
4. Update the intro paragraphs in `components.json` if the document's summary changes.
5. Run `make ios-legal`, review the rendered notices, and run `make ios-legal-check`.
6. Build and run `--verify-bundle` on the result.

Generation fails on an unreviewed font file, a changed font or license hash, a license
copy that differs from `design/fonts/`, or a license name other than the reviewed OFL
text, so a new binary cannot ship silently.

## What ships, and what does not

- No third-party libraries, XCFrameworks, or Swift packages. The only Swift package is
  the first-party, local `ios-app/Packages/FlintCore`; `project.yml` may reference only
  local `Packages/` paths. Generation fails on remote packages (`url:`, `github:`),
  `Package.resolved`, a `.package(` dependency in a local manifest, `carthage:`, and
  binary `framework:` dependencies, and on any vendored `*.framework`, `*.xcframework`,
  `*.a` or `*.dylib` in `ios-app`.
- Two OFL-licensed font families, bundled by the app and by the Insights report
  extension. Their license texts are included in the notices.
- Apple's system frameworks (Family Controls, Device Activity, Managed Settings,
  WidgetKit, SwiftUI, and the rest) are provided by iOS itself, not bundled with
  Flint. Swift compiler runtime support code is part of Apple's Swift runtime, which
  is distributed under the Apache License 2.0 with the Swift Runtime Library
  Exception; that license does not require attribution in binaries compiled with it.
  Flint therefore lists no Apple component.
- The policy and notices are plain text resources: no network access, no WebView, no
  embedded browser. Links in the documents render as plain selectable text and are not
  opened automatically.
