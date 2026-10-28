"""Task K regressions: disposable checkouts and fake devices only.

Reuse AgentCommands by composition, so unittest discovery does not rerun its
existing tests. Faults below extend only this fixture's fake executable.
"""
import importlib.util
import json
import os
from pathlib import Path
import shlex
import shutil
import sys
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("device_followup_harness", ROOT / "scripts/agent/selftest.py")
agent = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent)
UDID = "11111111-2222-3333-4444-555555555555"
OTHER_UDID = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"
PACKAGE = "system-images;android-35;google_apis;arm64-v8a"
SPRINGBOARD = ["launchctl", "print", "system/com.apple.SpringBoard"]

FAULTS = r'''
if name == 'xcrun' and args[:2] == ['simctl', 'boot'] and os.environ.get('FOLLOWUP_BOOT_TIMEOUT'):
    pathlib.Path('booted-' + args[2]).touch()
    time.sleep(30)
    sys.exit(0)
if name == 'xcrun' and args[:3] == ['simctl', 'list', 'devices'] and os.environ.get('FOLLOWUP_DISAPPEAR') and pathlib.Path('attached').exists():
    print(json.dumps({'devices': {'runtime': []}}))
    sys.exit(0)
if name == 'xcrun' and args[:3] == ['simctl', 'list', 'devices'] and os.environ.get('FOLLOWUP_MALFORMED_INVENTORY') and pathlib.Path('attached').exists():
    print(json.dumps({'devices': {'runtime': [None]}}))
    sys.exit(0)
if name == 'xcrun' and args[:2] == ['simctl', 'spawn'] and args[3:] == ['launchctl', 'print', 'system/com.apple.SpringBoard']:
    counter = pathlib.Path('springboard-polls')
    count = int(counter.read_text()) + 1 if counter.exists() else 1
    counter.write_text(str(count))
    lost_after_launch = os.environ.get('FOLLOWUP_SPRINGBOARD_AFTER_LAUNCH') and pathlib.Path('launch-attempts').exists()
    if os.environ.get('FOLLOWUP_SPRINGBOARD_NEVER') or lost_after_launch or count <= int(os.environ.get('FOLLOWUP_SPRINGBOARD_DELAY', '0')):
        print('state = waiting\npid = 0')
        sys.exit(0)
    print('state = running\npid = ' + os.environ.get('FOLLOWUP_SPRINGBOARD_PID', '43211'))
    sys.exit(0)
if name == 'xcrun' and args[:2] == ['simctl', 'launch']:
    counter = pathlib.Path('launch-attempts')
    count = int(counter.read_text()) + 1 if counter.exists() else 1
    counter.write_text(str(count))
    mode = os.environ.get('FOLLOWUP_LAUNCH', '')
    if mode == 'twice' or (mode == 'once' and count == 1) or mode in ('other', 'other-code'):
        domain = 'NSPOSIXErrorDomain' if mode == 'other' else 'FBSOpenApplicationServiceErrorDomain'
        code = 6 if mode == 'other-code' else 5
        print('An error was encountered processing the command (domain=' + domain + ', code=' + str(code) + '):', file=sys.stderr)
        print('Simulator device failed to launch com.flint.peakfocus.', file=sys.stderr)
        sys.exit(code)
    print('com.flint.peakfocus: 43212')
    sys.exit(0)
if name == 'ps' and args == ['-axo', 'pid=,pgid=,lstart=,command='] and os.environ.get('AVD_BOOTED'):
    changed = ((os.environ.get('FOLLOWUP_RESTART_AFTER_BUILD') and pathlib.Path('built-android').exists()) or
               (os.environ.get('FOLLOWUP_RESTART_AFTER_ATTACH') and pathlib.Path('attached').exists()) or
               (os.environ.get('FOLLOWUP_RESTART_AFTER_INSTALL') and pathlib.Path('installed').exists()))
    if not (os.environ.get('FOLLOWUP_MISSING_PROCESS') and pathlib.Path('built-android').exists()):
        pid = '502' if changed and not os.environ.get('FOLLOWUP_REUSE_PID') else '501'
        start = '02:00:00' if changed else '01:00:00'
        print(pid + ' ' + pid + ' Tue Oct 27 ' + start + ' 2026 /fake/emulator -avd flint-test -port 5554')
    sys.exit(0)
'''


class DeviceFollowups(unittest.TestCase):
    def setUp(self):
        self.h = agent.AgentCommands()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        self.addCleanup(self.h.tearDown)
        source = self.h.shim.read_text()
        self.assertIn("if set(args)", source)
        self.h.shim.write_text(source.replace("if set(args)", FAULTS + "\nif set(args)", 1))
        self.h.env["SIM_DEVICES"] = json.dumps({"devices": {"runtime": [
            {"name": "iPhone 17", "udid": UDID, "isAvailable": True, "state": "Booted"},
            {"name": "Other phone", "udid": OTHER_UDID, "isAvailable": True, "state": "Booted"},
        ]}})

    def ios(self, **env):
        return self.h.make("ios-run", f"IOS_SIM={UDID}", **env)

    def launches(self):
        return [args for args in self.h.calls("xcrun") if args[:2] == ["simctl", "launch"]]

    def assert_no_install_launch(self, platform="ios"):
        calls = self.h.calls("xcrun" if platform == "ios" else "orca")
        self.assertFalse(any(args[1] in ("install", "launch") for args in calls), calls)

    def test_n3_ambiguous_boot_timeout_warns_exact_udid_without_claim_or_shutdown(self):
        devices = json.loads(self.h.env["SIM_DEVICES"])
        devices["devices"]["runtime"][0]["state"] = "Shutdown"
        start = time.monotonic()
        result = self.ios(SIM_DEVICES=json.dumps(devices), FOLLOWUP_BOOT_TIMEOUT="1")
        self.assertLess(time.monotonic() - start, 15)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("timed out", result.stderr)
        self.assertTrue((self.h.root / ("booted-" + UDID)).exists())
        self.assertEqual(self.h.calls("flint-devices.sh"), [])
        self.assert_no_install_launch()
        self.assertFalse(any(args[1] == "shutdown" for args in self.h.calls("xcrun")))
        self.assertIn(UDID, result.stderr)
        self.assertIn("possibly booted, unclaimed", result.stderr)
        self.assertIn("review manually", result.stderr)

    def test_j1_missing_and_null_backend_accept_only_exact_verified_simulator(self):
        for absent in (True, False):
            with self.subTest(absent=absent):
                info = {"deviceUdid": UDID, "helperPid": 12345}
                if not absent:
                    info["backend"] = None
                result = self.ios(ATTACH_JSON=json.dumps({"result": {"attached": True, "info": info}}))
                self.h.assert_ok(result)
        claims = [a for a in self.h.calls("flint-devices.sh") if a[0] == "claim"]
        self.assertEqual(len(claims), 2)
        self.assertTrue(all("--booted" not in args for args in claims))
        self.assertEqual(len(self.launches()), 2)

    def test_j1_missing_backend_still_rejects_wrong_or_malformed_identity(self):
        for info in ({"deviceUdid": OTHER_UDID}, {"deviceUdid": [UDID]},
                     {"deviceUdid": UDID, "backend": "android"},
                     {"deviceUdid": UDID, "backend": ""},
                     {"deviceUdid": UDID, "helperPid": True}, None, []):
            with self.subTest(info=info):
                result = self.ios(ATTACH_JSON=json.dumps({"result": {"attached": True, "info": info}}))
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("Traceback", result.stderr)
                self.assert_no_install_launch()
        self.assertFalse(any(a[0] == "claim" for a in self.h.calls("flint-devices.sh")))

    def test_j1_missing_backend_requires_fresh_simctl_membership(self):
        result = self.ios(FOLLOWUP_DISAPPEAR="1", ATTACH_JSON=json.dumps({
            "result": {"attached": True, "info": {"deviceUdid": UDID}}}))
        self.assertNotEqual(result.returncode, 0)
        self.assert_no_install_launch()
        self.assertFalse(any(a[0] == "claim" for a in self.h.calls("flint-devices.sh")))
        calls = self.h.calls()
        attach_index = next(i for i, (tool, args) in enumerate(calls) if tool == "orca" and args[1] == "attach")
        self.assertTrue(any(tool == "xcrun" and args[:3] == ["simctl", "list", "devices"]
                            for tool, args in calls[attach_index + 1:]))

    def test_j1_rejects_non_uuid_even_if_inventory_and_backend_match(self):
        invalid = "NOT-A-SIMULATOR-UUID"
        devices = {"devices": {"runtime": [{"name": "iPhone 17", "udid": invalid, "state": "Booted"}]}}
        for backend in (None, "ios"):
            with self.subTest(backend=backend):
                result = self.h.make("ios-run", f"IOS_SIM={invalid}", SIM_DEVICES=json.dumps(devices),
                                     ATTACH_JSON=json.dumps({"result": {"attached": True, "info": {
                                         "deviceUdid": invalid, "backend": backend}}}))
                self.assertNotEqual(result.returncode, 0)
                self.assert_no_install_launch()

    def test_j1_malformed_fresh_inventory_fails_without_claim_or_traceback(self):
        result = self.ios(FOLLOWUP_MALFORMED_INVENTORY="1", ATTACH_JSON=json.dumps({
            "result": {"attached": True, "info": {"deviceUdid": UDID}}}))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("malformed simulator inventory", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assert_no_install_launch()
        self.assertFalse(any(a[0] == "claim" for a in self.h.calls("flint-devices.sh")))

    def test_j2_springboard_readiness_follows_bootstatus_and_precedes_install(self):
        self.h.assert_ok(self.ios(FOLLOWUP_SPRINGBOARD_DELAY="1"))
        calls = self.h.calls("xcrun")
        polls = [i for i, args in enumerate(calls) if args[3:] == SPRINGBOARD]
        self.assertEqual(len(polls), 2)
        self.assertLess(calls.index(["simctl", "bootstatus", UDID, "-b"]), polls[0])
        self.assertLess(polls[-1], next(i for i, args in enumerate(calls) if args[1] == "install"))

    def test_j2_unready_springboard_is_bounded_and_never_installs(self):
        start = time.monotonic()
        result = self.ios(FOLLOWUP_SPRINGBOARD_NEVER="1", FLINT_AGENT_DEVICE_TIMEOUT="0.3")
        self.assertLess(time.monotonic() - start, 5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SpringBoard", result.stderr)
        self.assert_no_install_launch()

    def test_j2_springboard_requires_positive_pid(self):
        result = self.ios(FOLLOWUP_SPRINGBOARD_PID="0", FLINT_AGENT_DEVICE_TIMEOUT="0.3")
        self.assertNotEqual(result.returncode, 0)
        self.assert_no_install_launch()

    def test_j2_code5_then_success_retries_once(self):
        result = self.ios(FOLLOWUP_LAUNCH="once")
        self.h.assert_ok(result)
        self.assertEqual(len(self.launches()), 2)
        self.assertEqual(self.launches()[0], self.launches()[1])
        self.assertIn("retry", result.stderr.lower())
        self.assertIn("FBSOpenApplicationServiceErrorDomain", result.stderr)

    def test_j2_code5_twice_fails_after_exactly_two_attempts(self):
        result = self.ios(FOLLOWUP_LAUNCH="twice")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.launches()), 2)
        self.assertIn("FBSOpenApplicationServiceErrorDomain", result.stderr)
        self.assertIn("still failed after one retry", result.stderr)
        self.assertNotIn("Flint launched", result.stdout)

    def test_j2_other_domain_code5_is_not_retried(self):
        result = self.ios(FOLLOWUP_LAUNCH="other")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.launches()), 1)
        self.assertIn("NSPOSIXErrorDomain", result.stderr)

    def test_j2_same_domain_other_code_is_not_retried(self):
        result = self.ios(FOLLOWUP_LAUNCH="other-code")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.launches()), 1)
        self.assertIn("FBSOpenApplicationServiceErrorDomain, code=6", result.stderr)

    def test_j2_readiness_loss_after_code5_aborts_retry_within_deadline(self):
        start = time.monotonic()
        result = self.ios(FOLLOWUP_LAUNCH="once", FOLLOWUP_SPRINGBOARD_AFTER_LAUNCH="1",
                          FLINT_AGENT_DEVICE_TIMEOUT="0.3")
        self.assertLess(time.monotonic() - start, 5)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.launches()), 1)
        self.assertIn("SpringBoard readiness timed out", result.stderr)
        self.assertNotIn("Flint launched", result.stdout)

    def test_j3_missing_image_hint_uses_executable_path_and_resolved_sdk(self):
        shutil.rmtree(self.h.image)
        sdkmanager = self.h.bin / "sdkmanager"
        sdkmanager.symlink_to(self.h.shim)
        result = self.h.make("android-run")
        self.assertNotEqual(result.returncode, 0)
        hint = result.stderr.split("Install it with: ", 1)[1].splitlines()[0]
        self.assertEqual(shlex.split(hint), [str(sdkmanager), f"--sdk_root={Path(self.h.env['ANDROID_HOME']).resolve()}", PACKAGE])
        self.assertTrue(os.access(shlex.split(hint)[0], os.X_OK))
        self.assertEqual(self.h.calls("orca"), [])
        self.assertEqual(self.h.calls("emulator"), [])

    def test_j3_absent_sdkmanager_prints_generic_guidance(self):
        shutil.rmtree(self.h.image)
        (self.h.bin / "python3").symlink_to(sys.executable)
        sdkmanager = self.h.bin / "sdkmanager"
        sdkmanager.write_text("not executable\n")
        result = self.h.make("android-run", PATH=f"{self.h.bin}:/usr/bin:/bin")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(PACKAGE, result.stderr)
        self.assertIn("Android SDK Manager", result.stderr)
        self.assertIn("sdkmanager", result.stderr)
        self.assertNotIn("Install it with:", result.stderr)
        self.assertNotIn("cmdline-tools/latest/bin/sdkmanager", result.stderr)
        self.assertEqual(self.h.calls("orca"), [])

    def android(self, **env):
        self.h.use_real_ledger()
        return self.h.make("android-run", "AVD=emulator-5554", AVD_BOOTED="1", **env)

    def test_android_same_avd_new_pid_after_build_aborts_install(self):
        result = self.android(FOLLOWUP_RESTART_AFTER_BUILD="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("incarnation", result.stderr)
        self.assert_no_install_launch("android")
        entry, = self.h.ledger_entries()
        self.assertEqual(entry["incarnation_evidence"]["pid"], 501)
        self.assertFalse(entry["booted"])

    def test_android_restart_during_attach_cannot_replace_selected_incarnation(self):
        result = self.android(FOLLOWUP_RESTART_AFTER_ATTACH="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("incarnation", result.stderr)
        self.assert_no_install_launch("android")
        self.assertEqual(self.h.calls("fake-make"), [])
        entry, = self.h.ledger_entries()
        self.assertEqual(entry["incarnation_evidence"]["pid"], 502)
        self.assertFalse(entry["booted"])

    def test_android_same_pid_new_start_after_build_aborts_install(self):
        result = self.android(FOLLOWUP_RESTART_AFTER_BUILD="1", FOLLOWUP_REUSE_PID="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("incarnation", result.stderr)
        self.assert_no_install_launch("android")

    def test_android_same_avd_new_incarnation_after_install_aborts_launch(self):
        result = self.android(FOLLOWUP_RESTART_AFTER_INSTALL="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("incarnation", result.stderr)
        self.assertTrue(any(args[1] == "install" for args in self.h.calls("orca")))
        self.assertFalse(any(args[1] == "launch" for args in self.h.calls("orca")))

    def test_android_missing_process_evidence_aborts_install(self):
        result = self.android(FOLLOWUP_MISSING_PROCESS="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("incarnation", result.stderr)
        self.assert_no_install_launch("android")

    def test_android_same_incarnation_runs_with_use_only_rights(self):
        self.h.assert_ok(self.android())
        entry, = self.h.ledger_entries()
        self.assertEqual(entry["incarnation_evidence"]["pid"], 501)
        self.assertFalse(entry["booted"])
        self.assertTrue(any(args[1] == "launch" for args in self.h.calls("orca")))


if __name__ == "__main__":
    unittest.main()
