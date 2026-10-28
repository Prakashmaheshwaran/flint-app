"""Offline coverage for retry scope, cache validation and setup timeouts."""
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

import ci_android_emulator as ci


def package(tag="localPackage", revision="7", path=ci.PACKAGE):
    return (f'<{tag} path="{path}"><revision><major>{revision}</major></revision>'
            f'<channelRef ref="channel-0"/></{tag}>')


class EmulatorSetupTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.image = self.root / "image"
        self.image.mkdir()
        self.results = patch.object(ci, "RESULTS", self.root)
        self.results.start()
        self.addCleanup(self.results.stop)

    def write_image(self, revision="7"):
        (self.image / "package.xml").write_text(f"<repository>{package(revision=revision)}</repository>")
        for name in ("system.img", "vendor.img", "ramdisk.img", "kernel-ranchu"):
            (self.image / name).write_bytes(b"fixture")

    def test_selects_exact_stable_package_not_order(self):
        data = f'<repository>{package("remotePackage", path="wrong")}{package("remotePackage")}</repository>'
        self.assertEqual(ci.revision(ci.remote_package(data)), "7.0.0")
        for invalid in ("<repository/>", f'<repository>{package("remotePackage") * 2}</repository>',
                        data.replace('ref="channel-0"', 'ref="channel-1"')):
            with self.assertRaises(ValueError):
                ci.remote_package(invalid)
        with self.assertRaises(ValueError):
            ci.revision(ET.fromstring('<package><revision><preview>1</preview></revision></package>'))

    def test_cache_requires_exact_revision_and_nonempty_payloads(self):
        self.write_image()
        self.assertTrue(ci.installed(self.image, "7.0.0"))
        self.assertFalse(ci.installed(self.image, "8.0.0"))
        (self.image / "vendor.img").write_bytes(b"")
        self.assertFalse(ci.installed(self.image, "7.0.0"))
        (self.image / "package.xml").write_text("partial download")
        self.assertFalse(ci.installed(self.image, "7.0.0"))

    def test_verified_cache_does_not_invoke_installer(self):
        self.write_image()
        with patch.object(ci, "command") as run:
            ci.install_image("sdkmanager", self.image, "7.0.0", run=run)
            run.assert_not_called()

    def test_download_failure_then_verified_install_stops_after_second(self):
        calls, delays = [], []

        def run(args, log):
            calls.append((args, log))
            if len(calls) == 1:
                return 1
            self.write_image()
            return 0

        ci.install_image("sdkmanager", self.image, "7.0.0", run=run, sleep=delays.append)
        self.assertEqual(len(calls), 2)
        self.assertEqual(delays, [5])
        self.assertEqual(calls[0][0], ["sdkmanager", "--install", ci.PACKAGE, "--channel=0"])

    def test_success_exit_without_verified_package_exhausts_three_attempts(self):
        for status in (0, 1, 124):
            with self.subTest(status=status):
                calls, delays = [], []

                def run(args, log):
                    calls.append(args)
                    return status

                with self.assertRaisesRegex(RuntimeError, "no emulator/tests started"):
                    ci.install_image("sdkmanager", self.image, "7.0.0", run=run, sleep=delays.append)
                self.assertEqual(len(calls), 3)
                self.assertEqual(delays, [5, 10])
                self.assertTrue(all(args[1:] == ["--install", ci.PACKAGE, "--channel=0"] for args in calls))

    def test_command_timeout_retains_progress_and_stops_child(self):
        log = self.root / "timeout.log"
        status = ci.command([sys.executable, "-c", "import time; print('partial ZIP', flush=True); time.sleep(30)"],
                            log, timeout=0.5)
        self.assertEqual(status, 124)
        self.assertIn("partial ZIP", log.read_text())

    def test_local_cli_fails_before_network_or_sdk_mutation(self):
        with patch.dict("os.environ", {"GITHUB_ACTIONS": "false"}), patch.object(sys, "argv", ["ci", "prepare"]):
            with self.assertRaisesRegex(SystemExit, "only for disposable"):
                ci.main()


if __name__ == "__main__":
    unittest.main()
