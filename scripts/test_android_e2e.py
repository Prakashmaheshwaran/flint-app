"""Device-free guard and permission-ownership regression tests for the E2E runner."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class AndroidE2ESafetyTests(unittest.TestCase):
    def run_fixture(self, *, consent="1", serial="emulator-5554", mode="build-fails"):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "scripts").mkdir()
            (root / "android-app").mkdir()
            (root / "bin").mkdir()
            shutil.copy(Path(__file__).with_name("android-e2e.sh"), root / "scripts/android-e2e.sh")
            (root / "scripts/flint-env.sh").write_text("")
            adb = root / "bin/adb"
            adb.write_text(f"#!{sys.executable}\n" + r'''
import json, os, pathlib, sys
root = pathlib.Path(os.environ["FIXTURE_ROOT"])
args = sys.argv[3:]
with (root / "adb.jsonl").open("a") as f: f.write(json.dumps(args) + "\n")
mode = os.environ["FIXTURE_MODE"]
ran = (root / "ran").exists()
service = "com.flint.peakfocus/com.flint.peakfocus.blocking.accessibility.FlintAccessibilityService"
if args[:2] == ["shell", "getprop"]: print("1")
elif args == ["shell", "cat", "/proc/sys/kernel/random/boot_id"]:
    print("22222222-2222-2222-2222-222222222222" if ran and mode == "reboot" else "11111111-1111-1111-1111-111111111111")
elif args[:3] == ["shell", "cat", "/sdcard/Android/data/com.flint.peakfocus.test/files/owned-service.txt"]:
    if ran and mode in ("owned", "other-service"):
        print(next(x.split("=", 1)[1] for x in (root / "artifacts/android-e2e/device.txt").read_text().splitlines() if x.startswith("run_token=")))
    else: sys.exit(1)
elif args[:4] == ["shell", "settings", "get", "secure"]:
    if args[4] == "accessibility_enabled": print("0")
    elif not ran: print("other.app/.Service" if mode == "preexisting" else "null")
    elif mode == "other-service": print(service + ":other.app/.Service")
    else: print(service)
elif args[:2] == ["pull", "/sdcard/Android/data/com.flint.peakfocus.test/files/smoke"] and mode.startswith("summary"):
    token = next(x.split("=", 1)[1] for x in (root / "artifacts/android-e2e/device.txt").read_text().splitlines() if x.startswith("run_token="))
    smoke = root / "artifacts/android-e2e/smoke"
    smoke.mkdir()
    events = [{"runToken": token, "event": event} for event in ("setup-start", "anr-detected", "anr-dismissed", "launcher-ready")]
    events.append({"runToken": "stale-run", "event": "anr-dismissed"})
    contents = "".join(json.dumps(e) + "\n" for e in events)
    if mode == "summary-truncated":
        contents = 'malformed-stale-record\n' + contents + '{"runToken":'
    (smoke / "setup-events.jsonl").write_text(contents)
''')
            adb.chmod(0o755)
            gradle = root / "android-app/gradlew"
            gradle.write_text('#!/bin/bash\ntouch "$FIXTURE_ROOT/ran"\nexit 7\n')
            gradle.chmod(0o755)
            env = {**os.environ, "CI": "true", "FLINT_E2E_DISPOSABLE": consent,
                   "AVD": serial, "FIXTURE_ROOT": str(root), "FIXTURE_MODE": mode,
                   "GITHUB_STEP_SUMMARY": str(root / "summary.md"),
                   "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"]}
            result = subprocess.run(["bash", "scripts/android-e2e.sh"], cwd=root,
                                    env=env, capture_output=True, text=True, timeout=20)
            log = root / "adb.jsonl"
            calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
            return result, calls

    def test_missing_consent_or_phone_selector_never_calls_adb(self):
        for args in ({"consent": ""}, {"serial": "physical-phone"}):
            result, calls = self.run_fixture(**args)
            self.assertEqual(result.returncode, 2)
            self.assertEqual(calls, [])

    def test_existing_service_is_rejected_without_mutation(self):
        result, calls = self.run_fixture(mode="preexisting")
        self.assertEqual(result.returncode, 2)
        self.assertFalse(any(call[:3] == ["shell", "settings", "put"] for call in calls))

    def test_build_failure_without_ownership_does_not_touch_grants(self):
        result, calls = self.run_fixture()
        self.assertEqual(result.returncode, 7)
        self.assertFalse(any(call[:3] == ["shell", "settings", "put"] for call in calls))

    def test_owned_grant_is_removed_without_disabling_new_service(self):
        result, calls = self.run_fixture(mode="other-service")
        self.assertEqual(result.returncode, 7)
        mutations = [c for c in calls if c[:3] == ["shell", "settings", "put"]]
        self.assertEqual(mutations, [["shell", "settings", "put", "secure",
                                     "enabled_accessibility_services", "other.app/.Service"]])

    def test_changed_boot_is_not_collected_or_mutated(self):
        result, calls = self.run_fixture(mode="reboot")
        self.assertEqual(result.returncode, 7)
        self.assertFalse(any(c[0] in ("logcat", "pull", "exec-out") for c in calls))
        self.assertFalse(any(c[:3] == ["shell", "settings", "put"] for c in calls))

    def test_owned_only_grant_restores_global_flag(self):
        result, calls = self.run_fixture(mode="owned")
        self.assertEqual(result.returncode, 7)
        mutations = [c for c in calls if c[:3] == ["shell", "settings", "put"]]
        self.assertEqual(mutations[-1], ["shell", "settings", "put", "secure", "accessibility_enabled", "0"])

    def test_setup_summary_counts_current_run_and_preserves_failure_exit(self):
        result, _ = self.run_fixture(mode="summary")
        self.assertEqual(result.returncode, 7)
        summary = json.loads(next(line.removeprefix("Setup ANR evidence: ")
                                  for line in result.stdout.splitlines() if line.startswith("Setup ANR evidence: ")))
        self.assertEqual(summary["anr_detected"], 1)
        self.assertEqual(summary["anr_dismissed"], 1)
        self.assertEqual(summary["launcher_ready"], 1)
        self.assertTrue(summary["log_present"])

    def test_missing_setup_log_is_explicit_in_summary(self):
        result, _ = self.run_fixture()
        summary = json.loads(next(line.removeprefix("Setup ANR evidence: ")
                                  for line in result.stdout.splitlines() if line.startswith("Setup ANR evidence: ")))
        self.assertFalse(summary["log_present"])
        self.assertEqual(summary["anr_dismissed"], 0)

    def test_truncated_log_retains_valid_counts_and_marks_incomplete_evidence(self):
        result, _ = self.run_fixture(mode="summary-truncated")
        self.assertEqual(result.returncode, 7)
        summary = json.loads(next(line.removeprefix("Setup ANR evidence: ")
                                  for line in result.stdout.splitlines() if line.startswith("Setup ANR evidence: ")))
        self.assertEqual(summary["anr_dismissed"], 1)
        self.assertEqual(summary["malformed_records"], 2)
        self.assertEqual(summary["malformed_lines"], [1, 7])
        self.assertFalse(summary["evidence_complete"])
