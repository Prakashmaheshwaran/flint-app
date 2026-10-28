#!/usr/bin/env python3
"""Reject known Flint DEBUG fixture symbols/strings in all five executables."""
import argparse
import plistlib
import re
import subprocess
from pathlib import Path


FIXTURES = re.compile(
    r"AuthorizationPreview|AuthorizationFixture|FlintUIPreview|FlintUI_Previews|"
    r"PreviewRegistry|__preview|preview-thunk|__designTime"
)


def verify(app: Path, expect_fixtures: bool = False) -> None:
    bundles = [app, *sorted((app / "PlugIns").glob("*.appex"))]
    if len(bundles) != 5:
        raise SystemExit(f"Expected host + four extensions, found {bundles}")
    ids = set()
    for bundle in bundles:
        with (bundle / "Info.plist").open("rb") as stream:
            info = plistlib.load(stream)
        identifier = info["CFBundleIdentifier"]
        ids.add(identifier)
        executable = bundle / info["CFBundleExecutable"]
        if not executable.is_file():
            raise SystemExit(f"Missing executable: {executable}")
        # Modern Xcode Debug builds can put code in Flint.debug.dylib while
        # CFBundleExecutable points to a launcher stub. Scan every Mach-O in
        # this bundle, including dylibs/frameworks, with each PlugIn handled
        # separately below. Recognize both endiannesses and fat headers.
        magic = {bytes.fromhex(value) for value in [
            "feedface", "cefaedfe", "feedfacf", "cffaedfe",
            "cafebabe", "bebafeca", "cafebabf", "bfbafeca",
        ]}
        binaries = []
        for path in sorted(bundle.rglob("*")):
            if not path.is_file() or "PlugIns" in path.relative_to(bundle).parts:
                continue
            with path.open("rb") as stream:
                if stream.read(4) in magic:
                    binaries.append(path)
        if executable not in binaries:
            raise SystemExit(f"Executable is not Mach-O: {executable}")
        symbols, strings = "", ""
        for binary in binaries:
            # Parse every architecture. Tool failures must never look empty.
            subprocess.run(["xcrun", "lipo", "-info", str(binary)], check=True, capture_output=True)
            symbols += subprocess.run(["xcrun", "nm", "-a", str(binary)],
                                      check=True, capture_output=True, text=True).stdout
            strings += subprocess.run(["/usr/bin/strings", "-a", str(binary)],
                                      check=True, capture_output=True, text=True).stdout
        matches = sorted(set(FIXTURES.findall(symbols + "\n" + strings)))
        # Known report preview sample; its display name must not ship either.
        if identifier.endswith(".report") and "Instagram" in strings:
            matches.append("report preview sample: Instagram")
        if not expect_fixtures and matches:
            raise SystemExit(f"DEBUG fixtures in {identifier}: {matches}")
        if expect_fixtures and identifier == "com.flint.peakfocus":
            if "FlintUIPreviewSession" not in strings or "AuthorizationPreview" not in symbols + strings:
                raise SystemExit("Debug control did not find authorization/session fixtures")
        if expect_fixtures and identifier.endswith(".report") and "Instagram" not in strings:
            raise SystemExit("Debug control did not find report preview sample")
        print(f"Scanned {identifier}: {matches if expect_fixtures else 'no known DEBUG fixtures'}")
    expected = {"com.flint.peakfocus" + suffix for suffix in ["", ".monitor", ".report", ".shield-action", ".shield-config"]}
    if ids != expected:
        raise SystemExit(f"Unexpected bundle IDs: {ids}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("app", type=Path)
    parser.add_argument("--expect-fixtures", action="store_true", help="Check the scanner against a Debug control build")
    args = parser.parse_args()
    verify(args.app, args.expect_fixtures)
