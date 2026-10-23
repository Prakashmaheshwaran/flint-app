#!/usr/bin/env python3
"""Reject account code or network permissions in a packaged local-first release APK."""

import re
import sys
import zipfile
from pathlib import Path


def violations(path: Path) -> list[str]:
    try:
        with zipfile.ZipFile(path) as apk:
            manifest = apk.read("AndroidManifest.xml")
            dex_names = [name for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
            if not dex_names:
                return ["APK has no compiled classes"]
            problems = []
            for permission in ("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"):
                if any(permission.encode(encoding) in manifest for encoding in ("utf-8", "utf-16-le")):
                    problems.append(f"Release requests {permission}")
            forbidden = (b"Lcom/google/firebase/", b"Lcom/flint/peakfocus/feature/connected/")
            for name in dex_names:
                dex = apk.read(name)
                for prefix in forbidden:
                    if prefix in dex:
                        problems.append(f"{name} contains excluded account code: {prefix.decode()}")
            return problems
    except (OSError, KeyError, zipfile.BadZipFile) as error:
        return [f"Cannot inspect release APK: {error}"]


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("usage: verify_local_first_apk.py <release.apk>")
    problems = violations(Path(sys.argv[1]))
    if problems:
        sys.exit("\n".join(problems))
    print("Release APK verified: no connected account code or network permissions.")
