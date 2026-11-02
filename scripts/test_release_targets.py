"""Exercise release Make targets with fixture packages, never real Gradle/devices."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[1]


class ReleaseTargetsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="flint-release-targets-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.android = self.root / "android with spaces"
        self.android.mkdir()
        (self.root / "scripts").mkdir()
        shutil.copy2(ROOT / "Makefile", self.root / "Makefile")
        shutil.copy2(ROOT / "scripts/verify_local_first_apk.py", self.root / "scripts")
        self.executable(self.root / "scripts/flint-shell.sh", '#!/bin/bash\nexec /bin/bash "$@"\n')
        self.executable(self.root / "scripts/flint-env-report.sh", "#!/bin/bash\nexit 0\n")
        self.executable(self.android / "gradlew", '''#!/usr/bin/env python3
import json, os, sys, zipfile
from pathlib import Path
with open(os.environ["RELEASE_TEST_LOG"], "a") as log:
    log.write(json.dumps(sys.argv[1:]) + "\\n")
if os.environ.get("RELEASE_TEST_FAIL") == "1":
    sys.exit(23)
bundle = ":app:bundleRelease" in sys.argv
apk = ":app:assembleRelease" in sys.argv
if not bundle and not apk:
    sys.exit(0)
out = Path("app/build/outputs") / ("bundle/release/app-release.aab" if bundle else "apk/release/app-release.apk")
out.parent.mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(out, "w") as package:
    manifest = b"android.permission.INTERNET" if os.environ.get("RELEASE_TEST_NETWORK") == "1" else b"local manifest"
    package.writestr("base/manifest/AndroidManifest.xml" if bundle else "AndroidManifest.xml", manifest)
    package.writestr("base/dex/classes.dex" if bundle else "classes.dex", b"local classes")
    if bundle:
        package.writestr("BundleConfig.pb", b"bundle config")
''')
        self.log = self.root / "calls.jsonl"
        self.env = {**os.environ, "RELEASE_TEST_LOG": str(self.log)}

    def executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def make(self, target, **extra):
        return subprocess.run([os.environ.get("FLINT_MAKE", "make"), "--no-print-directory", target,
                               f"ANDROID_DIR={self.android}"], cwd=self.root, env={**self.env, **extra},
                              capture_output=True, text=True, timeout=15)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_apk_and_bundle_targets_build_and_inspect_their_exact_outputs(self):
        for target, expected in (("android-release", ":app:assembleRelease"), ("android-bundle", ":app:bundleRelease")):
            with self.subTest(target=target):
                result = self.make(target)
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn("Release package verified", result.stdout)
                self.assertEqual([expected], self.calls()[-1])

    def test_android_gate_includes_bundle_without_skipping_pure_jvm_tests(self):
        result = self.make("verify-android")
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual([["assembleDebug"], [":app:assembleRelease"], [":app:bundleRelease"], ["test"]], self.calls())
        self.assertEqual(2, result.stdout.count("Release package verified"))

    def test_failed_build_stops_before_inspecting_stale_output(self):
        self.assertEqual(0, self.make("android-bundle").returncode)
        result = self.make("android-bundle", RELEASE_TEST_FAIL="1")
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("Release package verified", result.stdout)

    def test_network_permission_rejects_apk_and_bundle_target(self):
        for target in ("android-release", "android-bundle"):
            with self.subTest(target=target):
                result = self.make(target, RELEASE_TEST_NETWORK="1")
                self.assertNotEqual(0, result.returncode)
                self.assertIn("android.permission.INTERNET", result.stderr)


if __name__ == "__main__":
    unittest.main()
