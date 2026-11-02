#!/usr/bin/env python3
"""Reject account code/network permissions in a release APK or Android App Bundle.

APK manifests use binary XML (UTF-8/UTF-16 string pools); AAB module manifests
use protobuf XML (UTF-8 strings). Inspect uncompressed entries directly, without
running bundletool or trusting a filename extension. Scan every bundle module,
not only base, so a future dynamic feature cannot hide excluded code/permissions.
This is a named-exclusion check, not a general network or privacy audit.
"""

import re
import sys
import zipfile
from pathlib import Path


def violations(path: Path) -> list[str]:
    try:
        with zipfile.ZipFile(path) as package:
            names = package.namelist()
            if len(names) != len(set(names)):
                return ["Release archive has duplicate entries"]
            is_apk = "AndroidManifest.xml" in names
            is_bundle = "base/manifest/AndroidManifest.xml" in names
            if is_apk == is_bundle:
                return ["Release archive must contain exactly one APK or AAB manifest layout"]
            if is_apk:
                manifest_names = ["AndroidManifest.xml"]
                dex_names = [name for name in names if re.fullmatch(r"classes\d*\.dex", name)]
                required_dex = "classes.dex"
            else:
                if "BundleConfig.pb" not in names:
                    return ["AAB has no BundleConfig.pb"]
                manifest_names = [name for name in names if re.fullmatch(r"[^/]+/manifest/AndroidManifest\.xml", name)]
                dex_names = [name for name in names if re.fullmatch(r"[^/]+/dex/classes\d*\.dex", name)]
                required_dex = "base/dex/classes.dex"
            if required_dex not in dex_names:
                return [f"Release has no primary compiled classes: {required_dex}"]
            problems = []
            for name in manifest_names:
                manifest = package.read(name)
                if not manifest:
                    problems.append(f"{name} is empty")
                for permission in ("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"):
                    if any(permission.encode(encoding) in manifest for encoding in ("utf-8", "utf-16-le")):
                        problems.append(f"{name} requests {permission}")
            forbidden = (b"Lcom/google/firebase/", b"Lcom/flint/peakfocus/feature/connected/")
            for name in dex_names:
                dex = package.read(name)
                if not dex:
                    problems.append(f"{name} is empty")
                for prefix in forbidden:
                    if prefix in dex:
                        problems.append(f"{name} contains excluded account code: {prefix.decode()}")
            return problems
    except (OSError, KeyError, RuntimeError, zipfile.BadZipFile) as error:
        return [f"Cannot inspect release package: {error}"]


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: verify_local_first_apk.py <release.apk|release.aab>")
    problems = violations(Path(sys.argv[1]))
    if problems:
        sys.exit("\n".join(problems))
    print("Release package verified: no connected account code or network permissions.")
