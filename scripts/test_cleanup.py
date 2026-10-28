"""Device-free cleanup contracts, using a temporary Git repo, HOME and SDK.

PATH contains ONLY the fixture bin. Device tools are executable fake files, never
symlinks to real tools; required non-device utilities are explicitly linked.
Set FLINT_CLEANUP_SELFTEST_KEEP=1 to retain fixtures and exact fake call logs.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import tempfile
import time
import unittest


SOURCE = Path(__file__).resolve().parent
OWN = "22222222-2222-2222-2222-222222222222"
UNOWNED = "11111111-1111-1111-1111-111111111111"
LEGACY = "33333333-3333-3333-3333-333333333333"
DEVICE_TOOLS = ("xcrun", "xcodebuild", "xcodegen", "adb", "emulator", "avdmanager", "orca")

STUB = r'''
import json, os, pathlib, sys, time
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
state_path = pathlib.Path(os.environ["FLINT_STUB_STATE"])
state = json.loads(state_path.read_text())
assert pathlib.Path(os.environ["FLINT_STUB_LOG"]).stat().st_size < 262144, "fake call log size cap"
with open(os.environ["FLINT_STUB_LOG"], "a") as log:
    log.write(json.dumps([str(pathlib.Path(sys.argv[0]).resolve()), *args]) + "\n")
if state.get("delay_tool") == name:
    time.sleep(10)
if state.get("fail_tool") == name:
    sys.exit(1)
def save():
    state_path.write_text(json.dumps(state))
if name == "xcrun":
    if args == ["simctl", "list", "devices", "--json"]:
        print(json.dumps({"devices": {"fake-runtime": state["sims"]}}))
    elif len(args) == 3 and args[:2] in (["simctl", "shutdown"], ["simctl", "delete"]):
        assert args[2] not in ("all", "unavailable"), "blanket device action forbidden"
        device = next(d for d in state["sims"] if d["udid"] == args[2])
        if args[1] == "delete": state["sims"].remove(device)
        else: device["state"] = "Shutdown"
        save()
    else:
        sys.exit(88)
elif name == "orca":
    if args[:2] == ["emulator", "list"]:
        # Global unowned device intentionally leaks into the supposedly scoped list.
        print(json.dumps({"ok": True, "result": {"running": True, "devices": [{"udid": state["unowned"]}]}}))
    elif args[:2] == ["emulator", "kill"]:
        assert "--device" in args and "--worktree" in args
        print(json.dumps({"ok": True, "result": {"ok": True}}))
    elif args[:2] == ["worktree", "list"]:
        print(json.dumps({"ok": True, "result": {"worktrees": [{"path": p} for p in state.get("worktrees", [])]}}))
    else:
        sys.exit(88)
elif name == "adb":
    assert args[:1] == ["-s"] and args[1].startswith("emulator-")
    avd = state["android"].get(args[1])
    if not avd: sys.exit(1)
    if args[2:] == ["emu", "avd", "name"]:
        print(avd + "\nOK")
    elif args[2:] == ["emu", "kill"]:
        del state["android"][args[1]]
        save()
    else:
        sys.exit(88)
else:
    sys.exit(88)  # cleanup must not invoke builds, AVD managers, or emulator boot
'''


class CleanupTests(unittest.TestCase):
    def setUp(self):
        self.temp = Path(tempfile.mkdtemp(prefix="flint-cleanup-selftest-")).resolve()
        self.root = self.temp / "checkout with spaces"
        self.root.mkdir()
        self.bin = self.temp / "fake-bin"
        self.bin.mkdir()
        self.home = self.temp / "home"
        self.home.mkdir()
        self.sdk = self.temp / "sdk"
        (self.sdk / "platform-tools").mkdir(parents=True)
        (self.sdk / "emulator").mkdir()
        self.state_file = self.temp / "state.json"
        self.log_file = self.temp / "calls.jsonl"
        self.log_file.touch()
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("ORCA_", "CODEX_", "CLAUDE_", "GIT_", "ANDROID_", "FLINT_"))}
        self.env.update({"HOME": str(self.home), "PATH": str(self.bin),
                         "ANDROID_HOME": str(self.sdk), "ANDROID_SDK_ROOT": str(self.sdk),
                         "ORCA_CLI_COMMAND": str(self.bin / "orca"),
                         "FLINT_STUB_STATE": str(self.state_file), "FLINT_STUB_LOG": str(self.log_file),
                         "GIT_CONFIG_NOSYSTEM": "1", "PYTHONDONTWRITEBYTECODE": "1"})
        for name in ("git", "du", "bash", "dirname", "python3"):
            tool = sys.executable if name == "python3" else shutil.which(name)
            self.assertTrue(tool, name)
            (self.bin / name).symlink_to(tool)
        for name in DEVICE_TOOLS:
            tool = self.bin / name
            tool.write_text(f"#!{sys.executable}\n" + STUB)
            tool.chmod(0o755)
            self.assertEqual(shutil.which(name, path=self.env["PATH"]), str(tool))
            self.assertFalse(tool.is_symlink())
        for name in ("clean-worktree.py", "clean-env.sh", "clean-devices.sh", "clean-session.sh", "flint-devices.sh", "orca/archive.sh"):
            target = self.root / "scripts" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(SOURCE / name, target)
        (self.root / "ios-app").mkdir()
        (self.root / "ios-app/project.yml").write_text("name: Flint\n")
        (self.root / "android-app").mkdir()
        (self.root / "android-app/settings.gradle.kts").write_text('rootProject.name = "Flint"\n')
        self.git("init", "-q")
        self.git("add", ".")
        self.git("-c", "user.name=Selftest", "-c", "user.email=test@example.invalid",
                 "-c", "core.hooksPath=/dev/null", "commit", "-qm", "fixture")
        self.prefix = "flint-wt-" + hashlib.sha256(str(self.root.resolve()).encode()).hexdigest()[:12] + "-"
        self.state = {"sims": [{"udid": OWN, "name": self.prefix + "selftest", "state": "Booted"},
                                {"udid": UNOWNED, "name": "iPhone shared", "state": "Booted"},
                                {"udid": LEGACY, "name": "flint-review-61", "state": "Shutdown"}],
                      "unowned": UNOWNED,
                      "android": {"emulator-5554": "flint-test", "emulator-5556": "flint-opal-reference"}}
        self.save_state()

    def tearDown(self):
        # Every logged device invocation must be a real file inside this fake bin.
        for call in self.calls():
            self.assertEqual(Path(call[0]).parent, self.bin)
        if os.environ.get("FLINT_CLEANUP_SELFTEST_KEEP") == "1":
            print(f"Preserved cleanup fixture: {self.temp}")
        else:
            shutil.rmtree(self.temp)

    def save_state(self):
        self.state_file.write_text(json.dumps(self.state))

    def git(self, *args, root=None):
        return subprocess.check_output([str(self.bin / "git"), *args], cwd=root or self.root,
                                       env=self.env, text=True, stderr=subprocess.STDOUT).strip()

    def call(self, *args, root=None, env=None, check=True, input=None):
        result = subprocess.run([sys.executable, str(self.root / "scripts/clean-worktree.py"),
                                 "--root", str(root or self.root), *args],
                                cwd=self.root, env=env or self.env, text=True, input=input,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=15)
        if check:
            self.assertEqual(result.returncode, 0, result.stdout)
        return result

    def calls(self):
        return [json.loads(line) for line in self.log_file.read_text().splitlines()]

    def mutations(self):
        return [c for c in self.calls() if (Path(c[0]).name == "orca" and c[1:3] == ["emulator", "kill"])
                or (Path(c[0]).name == "xcrun" and len(c) > 2 and c[2] in ("shutdown", "delete"))
                or (Path(c[0]).name == "adb" and c[-2:] == ["emu", "kill"])]

    def claim(self, platform="ios", ident=OWN, *flags, root=None, session="session-a"):
        self.call("claim", platform, ident, "--session", session, *flags, root=root)

    def ledger(self, root=None):
        return json.loads(self.call("list", "--json", root=root).stdout)

    def test_only_claimed_created_simulator_is_deleted_and_second_run_is_noop(self):
        self.claim("ios", OWN, "--created", "--booted")
        self.call("devices")
        self.assertEqual([c[-1] for c in self.mutations() if Path(c[0]).name == "xcrun"], [OWN, OWN])
        self.assertFalse(any(UNOWNED in c or LEGACY in c for c in self.mutations()))
        count = len(self.calls())
        self.call("devices")
        self.assertEqual(len(self.calls()), count)
        self.assertEqual(self.ledger(), [])

    def test_borrowed_booted_simulator_only_releases_helper(self):
        self.claim("ios", UNOWNED)
        self.call("devices")
        self.assertEqual([Path(c[0]).name for c in self.mutations()], ["orca"])
        self.assertEqual(len(json.loads(self.state_file.read_text())["sims"]), 3)

    def test_booted_but_not_created_simulator_is_shutdown_not_deleted(self):
        self.claim("ios", UNOWNED, "--booted")
        self.call("devices")
        self.assertEqual([c[2] for c in self.mutations() if Path(c[0]).name == "xcrun"], ["shutdown"])

    def test_creation_requires_worktree_name_prefix(self):
        result = self.call("claim", "ios", UNOWNED, "--created", check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.mutations(), [])

    def test_session_cleanup_preserves_same_worktree_peer_and_provenance(self):
        self.claim("ios", OWN, "--created", "--booted", session="session-a")
        self.claim("ios", OWN, session="session-b")
        self.call("devices", "--session", "session-a")
        self.assertEqual(self.mutations(), [])
        self.assertEqual([e["claimant"] for e in self.ledger()], ["session-b"])
        self.call("devices", "--session", "session-b")
        self.assertTrue(any(c[1:] == ["simctl", "delete", OWN] for c in self.mutations()))

    def test_sibling_worktree_claim_prevents_any_device_action(self):
        sibling = self.temp / "sibling"
        self.git("worktree", "add", "--detach", str(sibling), "HEAD")
        self.claim("ios", UNOWNED, "--booted")
        self.claim("ios", UNOWNED, root=sibling, session="other-agent")
        self.call("devices")
        self.assertEqual(self.mutations(), [])
        self.assertEqual(self.ledger(), [])
        self.assertEqual(len(self.ledger(sibling)), 1)

    def test_invalid_sibling_ledger_fails_closed(self):
        sibling = self.temp / "sibling"
        self.git("worktree", "add", "--detach", str(sibling), "HEAD")
        path = Path(self.git("rev-parse", "--git-path", "flint-cleanup/devices.json", root=sibling))
        path.parent.mkdir()
        path.write_text("{broken")
        self.claim("ios", OWN, "--created")
        self.assertNotEqual(self.call("devices", check=False).returncode, 0)
        self.assertEqual(self.mutations(), [])

    def test_android_avd_data_preserved_and_serial_reuse_rejected(self):
        data = self.home / ".android/avd/flint-test.avd/userdata.img"
        data.parent.mkdir(parents=True)
        data.write_bytes(b"preserve me")
        self.claim("android", "emulator-5554", "--booted", "--avd", "flint-test")
        self.state["android"]["emulator-5554"] = "a-different-avd"
        self.save_state()
        self.call("devices")
        self.assertEqual(self.mutations(), [])
        self.state["android"]["emulator-5554"] = "flint-test"
        self.save_state()
        self.call("devices")
        self.assertTrue(any(c[1:] == ["-s", "emulator-5554", "emu", "kill"] for c in self.mutations()))
        self.assertTrue(data.exists())
        self.assertEqual(json.loads(self.state_file.read_text())["android"]["emulator-5556"], "flint-opal-reference")

    def test_device_dry_run_keeps_claims_and_devices(self):
        self.claim("ios", OWN, "--created")
        out = self.call("devices", "--dry-run").stdout
        self.assertIn("WOULD", out)
        self.assertEqual(len(self.ledger()), 1)
        self.assertEqual(self.mutations(), [])

    def test_clean_removes_native_artifacts_without_gradle_or_shared_caches(self):
        paths = ["ios-app/build", "ios-app/DerivedData", "ios-app/Flint.xcodeproj",
                 "ios-app/Packages/FlintCore/.build", "ios-app/Packages/FlintCore/.swiftpm",
                 "ios-app/result.xcresult", "android-app/.gradle", "android-app/.kotlin",
                 "android-app/app/.cxx", "android-app/feature/test/build", "android-app/app/build",
                 "scripts/__pycache__", ".build"]
        for name in paths:
            path = self.root / name
            path.mkdir(parents=True)
            (path / "artifact").write_bytes(b"build" * 100)
        config = self.root / "android-app/local.properties"
        config.write_text("sdk.dir=fixture\n")
        shared = self.home / ".gradle/caches/keep"
        shared.parent.mkdir(parents=True)
        shared.write_text("keep\n")
        self.call("artifacts")
        self.call("artifacts")
        self.assertTrue(all(not (self.root / p).exists() for p in paths))
        self.assertTrue(config.exists() and shared.exists())
        self.assertEqual(self.calls(), [])

    def test_clean_refuses_symlinks_tracked_sources_and_external_override(self):
        outside = self.temp / "outside"
        outside.mkdir()
        (outside / "keep").write_text("keep")
        (self.root / "ios-app/DerivedData").symlink_to(outside)
        self.assertNotEqual(self.call("artifacts", check=False).returncode, 0)
        self.assertTrue((outside / "keep").exists())
        (self.root / "ios-app/DerivedData").unlink()
        self.assertNotEqual(self.call("artifacts", "--derived-data", str(outside), check=False).returncode, 0)
        tracked = self.root / "android-app/module/build/source.txt"
        tracked.parent.mkdir(parents=True)
        tracked.write_text("source")
        self.git("add", str(tracked))
        self.assertNotEqual(self.call("artifacts", check=False).returncode, 0)
        self.assertTrue(tracked.exists())

    def test_orphan_default_is_dry_run_and_live_checkout_missing_project_is_kept(self):
        host = self.home / "Library/Developer/Xcode/DerivedData"
        for name, workspace in {"Flint-stale": self.temp / "gone/ios-app/Flint.xcodeproj/project.xcworkspace",
                                "Flint-live": self.root / "ios-app/Flint.xcodeproj/project.xcworkspace"}.items():
            path = host / name
            path.mkdir(parents=True)
            with (path / "info.plist").open("wb") as stream:
                plistlib.dump({"WorkspacePath": str(workspace)}, stream)
            (path / "data").write_bytes(b"x" * 4096)
        out = self.call("orphans").stdout
        self.assertIn("DRY RUN", out)
        self.assertIn("WOULD REMOVE", out)
        self.assertIn("WOULD DELETE simulator flint-review-61", out)
        self.assertTrue((host / "Flint-stale").exists())
        self.assertEqual(self.mutations(), [])
        self.call("orphans", "--confirm")
        self.assertFalse((host / "Flint-stale").exists())
        self.assertTrue((host / "Flint-live").exists())
        self.assertEqual([c[-1] for c in self.mutations()], [LEGACY])

    def test_orphan_booted_or_claimed_legacy_simulator_is_kept(self):
        self.claim("ios", LEGACY)
        self.call("orphans", "--confirm")
        self.assertEqual(self.mutations(), [])
        self.call("release", "ios", LEGACY, "--session", "session-a")
        self.state["sims"][2]["state"] = "Booted"
        self.save_state()
        self.call("orphans", "--confirm")
        self.assertEqual(self.mutations(), [])

    def test_archive_targets_env_worktree_from_different_cwd_and_releases_all_sessions(self):
        self.claim("ios", OWN, "--created", session="session-a")
        self.claim("android", "emulator-5554", "--booted", "--avd", "flint-test", session="session-b")
        (self.root / "ios-app/DerivedData").mkdir()
        env = dict(self.env, ORCA_WORKTREE_PATH=str(self.root), CODEX_THREAD_ID="someone-else")
        result = subprocess.run(["/bin/bash", str(self.root / "scripts/orca/archive.sh")], cwd=self.temp,
                                env=env, text=True, capture_output=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.ledger(), [])
        self.assertFalse((self.root / "ios-app/DerivedData").exists())

    def test_archive_exits_zero_with_missing_tools_or_missing_worktree(self):
        self.claim("ios", OWN, "--created")
        for name in DEVICE_TOOLS:
            (self.bin / name).unlink()
        (self.bin / "python3").unlink()
        env = dict(self.env, ORCA_WORKTREE_PATH=str(self.root))
        for target in (self.root, self.temp / "already-removed"):
            env["ORCA_WORKTREE_PATH"] = str(target)
            result = subprocess.run(["/bin/bash", str(self.root / "scripts/orca/archive.sh")],
                                    env=env, cwd=self.temp, text=True, capture_output=True, timeout=5)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_session_queue_is_scoped_and_never_cleans_build_outputs(self):
        self.claim("ios", OWN, "--created", session="session-a")
        self.claim("android", "emulator-5554", "--avd", "flint-test", session="session-b")
        artifact = self.root / "ios-app/DerivedData"
        artifact.mkdir()
        event = json.dumps({"hook_event_name": "SessionEnd", "cwd": str(self.root), "session_id": "session-a"})
        self.call("session-end", input=event)
        for _ in range(100):
            entries = self.ledger()
            if [e["claimant"] for e in entries] == ["session-b"]:
                break
            time.sleep(0.03)
        self.assertEqual([e["claimant"] for e in entries], ["session-b"])
        self.assertFalse(any("emulator-5554" in c for c in self.mutations()))
        self.assertTrue(artifact.exists())
        log = self.root / ".git/flint-cleanup/session-cleanup.log"
        self.assertIn("RUN", log.read_text())

    def test_stop_event_and_unknown_session_do_not_queue(self):
        self.claim("ios", OWN, "--created")
        for event, session in (("Stop", "session-a"), ("SessionEnd", "unknown")):
            self.call("session-end", input=json.dumps({"hook_event_name": event, "session_id": session, "cwd": str(self.root)}))
        self.assertEqual(self.mutations(), [])

    def test_claude_session_start_appends_only_to_supplied_env_file(self):
        target = self.temp / "claude-session.env"
        target.write_text("export EXISTING=value\n")
        session = "claude's session"
        event = json.dumps({"hook_event_name": "SessionStart", "session_id": session, "cwd": str(self.root)})
        self.call("session-start", input=event)  # no env file: no-op
        self.assertEqual(target.read_text(), "export EXISTING=value\n")
        self.call("session-start", input=event, env=dict(self.env, CLAUDE_ENV_FILE=str(target)))
        env = dict(self.env)
        env["FLINT_DEVICE_SESSION_ID"] = session
        self.call("claim", "ios", OWN, env=env)
        self.assertEqual(self.ledger()[0]["claimant"], session)
        readback = subprocess.check_output(["/bin/bash", "-c", 'source "$1"; printf %s "$FLINT_DEVICE_SESSION_ID"',
                                           "selftest", str(target)], env=self.env, text=True)
        self.assertEqual(readback, session)
        self.assertTrue(target.read_text().startswith("export EXISTING=value\n"))

    def test_session_device_job_missing_tools_exits_zero_and_keeps_claim(self):
        self.claim("ios", OWN, "--created")
        for name in DEVICE_TOOLS:
            (self.bin / name).unlink()
        self.call("session-devices", "--session", "session-a")
        self.assertEqual(len(self.ledger()), 1)
        self.assertEqual(self.mutations(), [])

    def test_lock_contention_and_deadline_fail_closed_without_stale_lock(self):
        self.claim("ios", OWN, "--created")
        with (self.root / ".git/flint-device-claims.lock").open("w") as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.call("devices", check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("another worktree", result.stdout)
        self.assertEqual(self.mutations(), [])
        self.state["delay_tool"] = "orca"
        self.save_state()
        start = time.monotonic()
        self.call("--deadline", "1", "session-devices", "--session", "session-a")
        self.assertLess(time.monotonic() - start, 3)
        self.assertEqual(self.mutations(), [])
        self.assertEqual(len(self.ledger()), 1)


if __name__ == "__main__":
    unittest.main()
