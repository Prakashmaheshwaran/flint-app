#!/usr/bin/env python3
"""Device-free integration tests: real temporary Git history, fake native/Orca/gh tools."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
REAL_GIT = shutil.which("git")
REAL_MAKE = shutil.which("make")

SHIM = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ["EVENTS"], "a") as f:
    f.write(json.dumps([name, args]) + "\n")
with open(os.environ["BINARY_PATHS"], "a") as f:
    f.write(json.dumps({"tool": name, "resolved": str(pathlib.Path(sys.argv[0]).resolve())}) + "\n")
if set(args) & {"delete", "shutdown", "erase", "-wipe-data", "--wipe-data"}:
    sys.exit("unexpected destructive device command in agent selftest")
if name == os.environ.get("FAIL_TOOL") or (name == "fake-make" and args[0] == os.environ.get("FAIL_TARGET")):
    sys.exit(7)
if name == "xcrun" and args[:4] == ["simctl", "list", "devices", "available"]:
    print(os.environ["SIM_DEVICES"])
elif name == "xcodebuild" and not os.environ.get("NO_APP"):
    d = pathlib.Path(args[args.index("-derivedDataPath") + 1])
    (d / "Build/Products/Debug-iphonesimulator/Flint.app").mkdir(parents=True)
elif name == "fake-make" and args == ["android"]:
    p = pathlib.Path("android-app/app/build/outputs/apk/debug/app-debug.apk")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.touch()
elif name == "orca":
    verb = args[1]
    if verb == "devices":
        count = sum(1 for line in pathlib.Path(os.environ["EVENTS"]).read_text().splitlines()
                    if json.loads(line) == ["orca", ["emulator", "devices", "--json"]])
        booted = count > 1 or os.environ.get("AVD_BOOTED")
        print(json.dumps({"result": {"devices": [
            {"id": "emulator-5554" if booted else "flint-test", "name": "flint-test", "platform": "android", "state": "booted" if booted else "shutdown"},
            {"id": "emulator-5556", "name": "other-avd", "platform": "android", "state": "booted"}]}}))
    elif verb == "exec":
        print(json.dumps({"result": {"stdout": "123\n"}}))
    elif verb == "logcat":
        print(json.dumps({"result": {"entries": [
            {"pid": 999, "tag": "Other", "message": "unrelated secret"},
            {"pid": 123, "tag": "Flint", "message": "app event"}]}}))
    else:
        print(json.dumps({"result": {"device": {"id": "emulator-5554"}}}))
elif name == "adb":
    if args == ["devices"]:
        print("List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice")
    elif args[2:] == ["emu", "avd", "name"]:
        print("flint-test\nOK" if args[1] == "emulator-5554" else "other-avd\nOK")
    elif args[2:4] == ["shell", "pidof"]:
        print("123")
    elif "logcat" in args:
        print("app event via adb")
elif name == "git":
    if args == ["branch", "--show-current"]:
        print("agent-test")
    elif args[:1] == ["rev-list"]:
        print("2\t3")
    elif args[:2] == ["merge-base", "--is-ancestor"]:
        sys.exit(int(os.environ.get("ANCESTOR_RC", "0")))
'''


class AgentCommands(unittest.TestCase):
    def setUp(self):
        temp = tempfile.mkdtemp(prefix="flint-agent-selftest-")
        if os.environ.get("FLINT_AGENT_SELFTEST_KEEP") == "1":
            print(f"Preserving selftest evidence: {temp}", flush=True)
        else:
            self.addCleanup(shutil.rmtree, temp)
        self.root = Path(temp).resolve() / "project with spaces"
        self.root.mkdir()
        shutil.copy(ROOT / "Makefile", self.root / "Makefile")
        shutil.copytree(ROOT / "scripts", self.root / "scripts", ignore=shutil.ignore_patterns("__pycache__"))
        (self.root / "ios-app").mkdir()
        (self.root / "android-app").mkdir()
        self.bin = self.root / "fake-bin"
        self.bin.mkdir()
        self.events = self.root / "events.jsonl"
        self.shim = self.bin / "shim"
        self.shim.write_text(SHIM)
        self.shim.chmod(0o755)
        # Exercise the integration contract without running the real ledger.
        ledger = self.root / "scripts/flint-devices.sh"
        ledger.unlink(missing_ok=True)
        ledger.symlink_to(self.shim)
        for name in ("orca", "xcrun", "simctl", "xcodebuild", "xcodegen", "gh", "fake-make", "adb", "emulator", "avdmanager"):
            (self.bin / name).symlink_to(self.shim.name)
        self.env = os.environ.copy()
        for key in ("ORCA_CLI_COMMAND", "MAKEFLAGS", "MFLAGS", "MAKELEVEL"):
            self.env.pop(key, None)
        self.env.update(
            PATH=f"{self.bin}{os.pathsep}{os.environ['PATH']}",
            EVENTS=str(self.events),
            BINARY_PATHS=str(self.root / "binary-paths.jsonl"),
            FLINT_MAKE=str(self.bin / "fake-make"),
            SIM_DEVICES=json.dumps({"devices": {"runtime": [
                {"name": "iPhone 17", "udid": "TEST-UDID", "isAvailable": True, "state": "Shutdown"},
                {"name": "Other phone", "udid": "OTHER-UDID", "isAvailable": True, "state": "Booted"},
            ]}}),
        )
        # A merged shared env wrapper may prepend the SDK tools to PATH. Pin those
        # paths and Orca itself to fakes so this suite cannot reach a real device.
        sdk = self.root / "fake-sdk"
        (sdk / "platform-tools").mkdir(parents=True)
        (sdk / "platform-tools/adb").symlink_to(self.shim)
        self.env.update(
            ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
            ORCA_CLI_COMMAND=str(self.bin / "orca"),
            ORCA_ROOT_PATH=str(self.root), ORCA_WORKTREE_PATH=str(self.root),
        )

    def run_cmd(self, args, **env):
        return subprocess.run(args, cwd=self.root, env={**self.env, **env}, text=True, capture_output=True)

    def tearDown(self):
        paths = self.root / "binary-paths.jsonl"
        if paths.exists():
            for line in paths.read_text().splitlines():
                call = json.loads(line)
                self.assertEqual(call["resolved"], str(self.shim), f"non-fake tool: {call}")

    def make(self, target, *args, **env):
        return self.run_cmd([REAL_MAKE, "--no-print-directory", target, f"MAKE={self.bin / 'fake-make'}", *args], **env)

    def calls(self, name=None):
        calls = [json.loads(line) for line in self.events.read_text().splitlines()] if self.events.exists() else []
        return [args for tool, args in calls if tool == name] if name else calls

    def assert_ok(self, result):
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def git(self, *args):
        result = self.run_cmd([REAL_GIT, *args])
        self.assert_ok(result)
        return result.stdout.strip()

    def init_repo(self):
        self.git("init", "-q")
        self.git("config", "user.name", "Selftest")
        self.git("config", "user.email", "selftest@example.invalid")
        (self.root / "tracked.txt").write_text("base\n")
        (self.root / ".gitignore").write_text("fake-bin/\nevents.jsonl\n")
        self.git("add", ".")
        self.git("commit", "-qm", "base")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

    def change(self, path, content="changed\n"):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)

    def gates(self, dry=True, **env):
        return self.run_cmd(["bash", "scripts/agent/gates.sh"], DRY_RUN="1" if dry else "0", **env)

    def test_gate_mapping_and_dry_run(self):
        self.init_repo()
        scenarios = [
            (["ios-app/Foo.swift"], ["verify-ios"]),
            (["android-app/Foo.kt"], ["verify-android"]),
            (["scripts/new.sh"], ["selftest"]),
            (["Makefile"], ["selftest"]),
            (["ios-app/Foo.swift", "android-app/Foo.kt"], ["verify-ios", "verify-android"]),
            (["ios-app/Foo.swift", "android-app/Foo.kt", "scripts/new.sh"], ["verify-ios", "verify-android", "selftest"]),
            (["docs/notes.md"], []),
        ]
        for paths, expected in scenarios:
            with self.subTest(paths=paths):
                for path in paths:
                    self.change(path)
                result = self.gates()
                self.assert_ok(result)
                planned = [line.removeprefix("PLAN: make ") for line in result.stdout.splitlines() if line.startswith("PLAN:")]
                self.assertEqual(planned, expected)
                self.assertEqual(self.calls("fake-make"), [])
                self.git("reset", "--hard", "-q", "HEAD")
                for path in paths:
                    if path != "Makefile":
                        (self.root / path).unlink()

    def test_gate_includes_commits_staging_untracked_deletions_and_renames(self):
        self.init_repo()
        self.change("ios-app/committed.swift")
        self.git("add", "ios-app")
        self.git("commit", "-qm", "ios")
        self.change("android-app/staged.kt")
        self.git("add", "android-app")
        self.change("scripts/untracked\nfile.sh")
        result = self.gates()
        self.assert_ok(result)
        for target in ("verify-ios", "verify-android", "selftest"):
            self.assertIn(f"PLAN: make {target}", result.stdout)
        self.git("commit", "-qm", "android")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")
        (self.root / "ios-app/committed.swift").unlink()
        self.git("mv", "android-app/staged.kt", "renamed.kt")
        result = self.gates()
        self.assertIn("PLAN: make verify-ios", result.stdout)
        self.assertIn("PLAN: make verify-android", result.stdout)

    def test_gate_preserves_staged_then_reversed_worktree_change(self):
        self.init_repo()
        original = (self.root / "Makefile").read_text()
        self.change("Makefile", original + "\n# staged edit\n")
        self.git("add", "Makefile")
        self.change("Makefile", original)
        result = self.gates()
        self.assert_ok(result)
        self.assertIn("PLAN: make selftest", result.stdout)

    def test_gate_failures_do_not_skip_remaining_gates(self):
        self.init_repo()
        for path in ("ios-app/Foo.swift", "android-app/Foo.kt", "scripts/new.sh"):
            self.change(path)
        result = self.gates(dry=False, FAIL_TARGET="verify-ios")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls("fake-make"), [["verify-ios"], ["verify-android"], ["selftest"]])
        self.assertIn("ran=3 passed=2 failed=1 skipped=0", result.stdout)

    def test_gate_missing_main_fails_without_running_tools(self):
        self.init_repo()
        self.git("update-ref", "-d", "refs/remotes/origin/main")
        result = self.gates(dry=False)
        self.assertEqual(result.returncode, 2)
        self.assertIn("fetch origin/main", result.stderr)
        self.assertEqual(self.calls("fake-make"), [])

    def test_ios_run_composes_scoped_build_install_and_launch(self):
        result = self.make("ios-run", "IOS_DERIVED_DATA=ios-app/Custom Data", "IOS_SIM=TEST-UDID")
        self.assert_ok(result)
        build = self.calls("xcodebuild")[0]
        self.assertEqual(build[build.index("-derivedDataPath") + 1], "ios-app/Custom Data")
        self.assertIn("CODE_SIGNING_ALLOWED=NO", build)
        self.assertEqual(self.calls("xcodegen"), [["generate"]])
        self.assertEqual(self.calls("orca"), [["emulator", "attach", "TEST-UDID", "--worktree", f"path:{self.root}", "--json"]])
        self.assertEqual(self.calls("flint-devices.sh"), [["claim", "ios", "TEST-UDID", "--booted"]])
        calls = self.calls("xcrun")
        self.assertIn(["simctl", "install", "TEST-UDID", "ios-app/Custom Data/Build/Products/Debug-iphonesimulator/Flint.app"], calls)
        self.assertIn(["simctl", "launch", "TEST-UDID", "com.flint.peakfocus"], calls)
        self.assertFalse(any("booted" in args for _, args in self.calls()))
        self.assertIn("bundle=com.flint.peakfocus", result.stdout)

    def test_ios_build_failure_never_attaches(self):
        result = self.make("ios-run", FAIL_TOOL="xcodebuild")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls("orca"), [])

    def test_ios_claim_does_not_own_shutdown_for_an_already_booted_device(self):
        result = self.make("ios-run", "IOS_SIM=OTHER-UDID")
        self.assert_ok(result)
        self.assertEqual(self.calls("flint-devices.sh"), [["claim", "ios", "OTHER-UDID"]])

    def test_missing_app_prevents_attach(self):
        result = self.make("ios-run", NO_APP="1")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Built app not found", result.stderr)
        self.assertEqual(self.calls("orca"), [])

    def test_ios_missing_or_ambiguous_device_never_builds(self):
        for devices in ([], [{"name": "iPhone 17", "udid": "A"}, {"name": "iPhone 17", "udid": "B"}]):
            with self.subTest(devices=devices):
                result = self.make("ios-run", SIM_DEVICES=json.dumps({"devices": {"runtime": devices}}))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("set IOS_SIM to a unique simulator UDID", result.stderr)
                self.assertEqual(self.calls("xcodebuild"), [])
                self.assertEqual(self.calls("orca"), [])

    def test_android_run_composes_attach_build_reinstall_launch(self):
        result = self.make("android-run", "AVD=flint-test")
        self.assert_ok(result)
        scope = ["--device", "emulator-5554", "--worktree", f"path:{self.root}", "--json"]
        self.assertEqual(self.calls("orca"), [
            ["emulator", "devices", "--json"],
            ["emulator", "attach", "flint-test", "--worktree", f"path:{self.root}", "--json"],
            ["emulator", "devices", "--json"],
            ["emulator", "install", str(self.root / "android-app/app/build/outputs/apk/debug/app-debug.apk"), "--reinstall", *scope],
            ["emulator", "launch", "com.flint.peakfocus", *scope],
        ])
        self.assertEqual(self.calls("fake-make"), [["android"]])
        self.assertEqual(self.calls("flint-devices.sh"), [["claim", "android", "emulator-5554", "--avd", "flint-test", "--booted"]])
        self.assertEqual([name for name, _ in self.calls()], ["orca", "orca", "orca", "flint-devices.sh", "fake-make", "orca", "orca"])

    def test_android_running_device_claim_does_not_own_shutdown(self):
        result = self.make("android-run", AVD_BOOTED="1")
        self.assert_ok(result)
        self.assertEqual(self.calls("flint-devices.sh"), [["claim", "android", "emulator-5554", "--avd", "flint-test"]])

    def test_android_attach_or_build_failure_stops_before_install(self):
        for failing in ("orca", "fake-make"):
            with self.subTest(failing=failing):
                result = self.make("android-run", FAIL_TOOL=failing)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(args[1] in ("install", "launch") for args in self.calls("orca")))

    def test_ios_logs_use_exact_device_and_process_without_boot(self):
        result = self.make("logs-ios")
        self.assert_ok(result)
        self.assertEqual(self.calls("xcrun")[-1], ["simctl", "spawn", "TEST-UDID", "log", "stream", "--level", "debug", "--style", "compact", "--predicate", 'process == "Flint"'])
        self.assertFalse(any("bootstatus" in args for _, args in self.calls()))

    def test_android_logs_filter_pids_and_sdk_fallback(self):
        result = self.make("logs-android")
        self.assert_ok(result)
        self.assertIn("app event", result.stdout)
        self.assertNotIn("unrelated secret", result.stdout)
        result = self.make("logs-android", FAIL_TOOL="orca", ANDROID_HOME=str(self.root / "absent-sdk"))
        self.assert_ok(result)
        self.assertIn("app event via adb", result.stdout)
        self.assertIn(["-s", "emulator-5554", "logcat", "--pid=123", "-d", "-t", "500"], self.calls("adb"))

    def test_read_only_status_and_ancestry_commands(self):
        (self.bin / "git").symlink_to(self.shim.name)
        result = self.make("pr-status")
        self.assert_ok(result)
        self.assertEqual(self.calls("gh"), [["pr", "view", "agent-test"], ["pr", "checks", "agent-test"], ["run", "list", "--branch", "agent-test", "--limit", "10"]])
        for rc, answer in (("0", "yes"), ("1", "no")):
            result = self.make("ancestry", ANCESTOR_RC=rc)
            self.assert_ok(result)
            self.assertIn("ahead=3 behind=2", result.stdout)
            self.assertIn(f"ancestor of HEAD: {answer}", result.stdout)
        self.assertIn(["fetch", "--no-tags", "origin", "main"], self.calls("git"))
        self.assertFalse(any(set(args) & {"push", "merge", "checkout", "reset"} for args in self.calls("git")))

    def test_failed_pr_lookup_still_reports_ci_and_returns_failure(self):
        (self.bin / "git").symlink_to(self.shim.name)
        result = self.make("pr-status", FAIL_TOOL="gh")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.calls("gh")), 3)

    def test_direct_script_sources_shared_environment_when_present(self):
        (self.bin / "git").symlink_to(self.shim.name)
        (self.root / "scripts/flint-env.sh").write_text('export SHARED_ENV_LOADED=yes\n')
        # A sentinel in a fake gh proves the source affected the script's subprocess.
        gh = self.bin / "gh"
        gh.unlink()
        gh.write_text('#!/usr/bin/env bash\n[[ ${SHARED_ENV_LOADED:-} == yes ]]\n')
        gh.chmod(0o755)
        self.assert_ok(self.run_cmd(["bash", "scripts/agent/pr-status.sh"]))


if __name__ == "__main__":
    unittest.main(verbosity=2)
