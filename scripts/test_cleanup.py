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
import resource
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


limit = 1048576
soft, hard = resource.getrlimit(resource.RLIMIT_FSIZE)
resource.setrlimit(resource.RLIMIT_FSIZE, (min(soft, limit) if soft >= 0 else limit,
                                         min(hard, limit) if hard >= 0 else limit))


SOURCE = Path(__file__).resolve().parent
OWN = "22222222-2222-2222-2222-222222222222"
UNOWNED = "11111111-1111-1111-1111-111111111111"
LEGACY = "33333333-3333-3333-3333-333333333333"
DEVICE_TOOLS = ("xcrun", "xcodebuild", "xcodegen", "adb", "emulator", "avdmanager", "orca", "ps")


def bounded(argv, *, env, cwd=None, timeout=15, input=None):
    # RLIMIT_FSIZE bounds regular files, not PIPE memory. Put child output in a
    # capped file so a broken fake cannot fill coordinator memory before timeout.
    with tempfile.TemporaryFile() as output:
        result = subprocess.run(argv, env=env, cwd=cwd, input=input, text=True,
                                stdout=output, stderr=subprocess.STDOUT, timeout=timeout)
        output.seek(0)
        result.stdout = output.read(1048576).decode(errors='replace')
        result.stderr = ''
        return result

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
    elif len(args) == 5 and args[:2] == ["simctl", "spawn"] and args[3:] == ["launchctl", "managerpid"]:
        device = next(d for d in state["sims"] if d["udid"] == args[2])
        if device['state'] != 'Booted': sys.exit(1)
        print(state.get('ios_pid', 601))
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
        print(json.dumps({"ok": True, "result": state.get('helper', {"running": True, "devices": [{"udid": state["unowned"]}]})}))
    elif args[:2] == ["emulator", "kill"]:
        assert "--device" in args and "--worktree" in args
        print(json.dumps({"ok": True, "result": {"ok": True}}))
        state.pop('helper', None)
        save()
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
    elif args[2:] == ["shell", "cat", "/proc/sys/kernel/random/boot_id"]:
        print(state.get('android_boot_id', 'aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'))
    elif args[2:] == ["emu", "kill"]:
        del state["android"][args[1]]
        save()
    else:
        sys.exit(88)
elif name == 'ps':
    if args == ['-axo', 'pid=,pgid=,lstart=,command=']:
        for process in state['android_processes']:
            print(process['pid'], process['pgid'], process['start'],
                  '/fake/sdk/emulator/qemu-system-aarch64 -avd', process['avd'], '-port', process['port'])
    elif args == ['-p', str(state.get('ios_pid', 601)), '-o', 'lstart=,comm=']:
        print(state.get('ios_start', 'Tue Oct 27 20:00:00 2026'), state.get('ios_command', '/fake/launchd_sim'))
    elif state.get('helper') and args == ['-p', str(state['helper']['pid']), '-o', 'lstart=,comm=']:
        print(state.get('helper_start', 'Tue Oct 27 20:00:00 2026'), '/fake/orca-helper')
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
                         "XDG_CONFIG_HOME": str(self.home / 'config'),
                         "XDG_CACHE_HOME": str(self.home / 'cache'),
                         "XDG_DATA_HOME": str(self.home / 'data'),
                         "ANDROID_HOME": str(self.sdk), "ANDROID_SDK_ROOT": str(self.sdk),
                         "ORCA_CLI_COMMAND": str(self.bin / "orca"),
                         "FLINT_STUB_STATE": str(self.state_file), "FLINT_STUB_LOG": str(self.log_file),
                         "GIT_CONFIG_NOSYSTEM": "1", "GIT_CONFIG_GLOBAL": "/dev/null",
                         "GIT_CONFIG_SYSTEM": "/dev/null", "PYTHONDONTWRITEBYTECODE": "1"})
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
        for device in self.state['sims']:
            device['lastBootedAt'] = '2026-10-27T20:00:00Z'
        self.state['android_processes'] = [dict(pid=501, pgid=501, start='Tue Oct 27 20:00:00 2026',
                                                avd='flint-test', port='5554')]
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
        result = bounded([str(self.bin / "git"), *args], cwd=root or self.root, env=self.env, timeout=10)
        result.check_returncode()
        return result.stdout.strip()

    def call(self, *args, root=None, env=None, check=True, input=None):
        result = bounded([sys.executable, str(self.root / "scripts/clean-worktree.py"),
                                 "--root", str(root or self.root), *args],
                         cwd=self.root, env=env or self.env, input=input, timeout=15)
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
        # The same regression fixture must exercise the vulnerable base source,
        # whose CLI had no token flag, without failing on argument parsing alone.
        if '--booted' in flags and '--incarnation' in (self.root / 'scripts/clean-worktree.py').read_text():
            observed = ['incarnation', platform, ident]
            if '--avd' in flags:
                observed += ['--avd', flags[flags.index('--avd') + 1]]
            token = self.call(*observed, root=root).stdout.strip()
            flags = (*flags, '--incarnation', token)
        self.call("claim", platform, ident, "--session", session, *flags, root=root)

    def ledger(self, root=None):
        return json.loads(self.call("list", "--json", root=root).stdout)

    def approve_orphans(self, *udids):
        self.plan_number = getattr(self, 'plan_number', 0) + 1
        plan = self.temp / f'orphan-plan-{self.plan_number}.json'
        flags = [flag for ident in udids for flag in ('--allow-legacy-udid', ident)]
        output = self.call('orphans', '--plan', str(plan), *flags).stdout
        digest = next(line.split('=', 1)[1] for line in output.splitlines() if line.startswith('PLAN_DIGEST='))
        return ['--confirm', '--plan', str(plan), '--plan-digest', digest]

    def orphan_artifact(self, name='Flint-stale'):
        path = self.home / 'Library/Developer/Xcode/DerivedData' / name
        path.mkdir(parents=True)
        with (path / 'info.plist').open('wb') as stream:
            plistlib.dump({'WorkspacePath': str(self.temp / 'gone/ios-app/Flint.xcodeproj/project.xcworkspace')}, stream)
        (path / 'data').write_text('orphan build')
        return path

    def swapping_du(self, parent, other, *, symlink=True):
        tool = self.bin / 'du'
        tool.unlink()
        action = ('p.symlink_to(' + repr(str(other)) + ', target_is_directory=True)' if symlink
                  else 'Path(' + repr(str(other)) + ').rename(p)')
        tool.write_text(f'#!{sys.executable}\nfrom pathlib import Path\np=Path({str(parent)!r})\n'
                        f'p.rename(p.with_name(p.name + "-saved"))\n{action}\nprint("4 ignored")\n')
        tool.chmod(0o755)

    def test_only_claimed_created_simulator_is_deleted_and_second_run_is_noop(self):
        self.claim("ios", OWN, "--created", "--booted")
        self.call("devices")
        self.assertEqual([c[-1] for c in self.mutations() if Path(c[0]).name == "xcrun"], [OWN, OWN])
        self.assertFalse(any(UNOWNED in c or LEGACY in c for c in self.mutations()))
        count = len(self.calls())
        self.call("devices")
        self.assertEqual(len(self.calls()), count)
        self.assertEqual(self.ledger(), [])

    def test_borrowed_booted_simulator_preserves_unproven_helper(self):
        self.claim("ios", UNOWNED)
        self.call("devices")
        self.assertEqual(self.mutations(), [])
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
        self.assertIn("KEEP name-only candidate flint-review-61", out)
        self.assertTrue((host / "Flint-stale").exists())
        self.assertEqual(self.mutations(), [])
        self.call("orphans", *self.approve_orphans())
        self.assertFalse((host / "Flint-stale").exists())
        self.assertTrue((host / "Flint-live").exists())
        self.assertEqual(self.mutations(), [])

    def test_orphan_booted_or_claimed_legacy_simulator_is_kept(self):
        self.claim("ios", LEGACY)
        self.call("orphans", *self.approve_orphans())
        self.assertEqual(self.mutations(), [])
        self.call("release", "ios", LEGACY, "--session", "session-a")
        self.state["sims"][2]["state"] = "Booted"
        self.save_state()
        self.call("orphans", *self.approve_orphans())
        self.assertEqual(self.mutations(), [])

    def test_archive_targets_env_worktree_from_different_cwd_and_releases_all_sessions(self):
        self.claim("ios", OWN, "--created", session="session-a")
        self.claim("android", "emulator-5554", "--booted", "--avd", "flint-test", session="session-b")
        (self.root / "ios-app/DerivedData").mkdir()
        env = dict(self.env, ORCA_WORKTREE_PATH=str(self.root), CODEX_THREAD_ID="someone-else")
        result = bounded(["/bin/bash", str(self.root / "scripts/orca/archive.sh")], cwd=self.temp,
                         env=env, timeout=15)
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
            result = bounded(["/bin/bash", str(self.root / "scripts/orca/archive.sh")],
                             env=env, cwd=self.temp, timeout=5)
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
        readback = bounded(["/bin/bash", "-c", 'source "$1"; printf %s "$FLINT_DEVICE_SESSION_ID"',
                            "selftest", str(target)], env=self.env, timeout=5).stdout
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

    def test_h1_dotdot_source_root_cannot_bypass_tracked_protection(self):
        source = self.root / 'android-app/settings.gradle.kts'
        result = self.call('artifacts', '--derived-data', 'ios-app/../android-app', check=False)
        self.assertTrue(source.exists(), 'tracked source deleted through dot-dot override')
        self.assertNotEqual(result.returncode, 0)

    def test_h1_git_alias_and_nondirectory_config_are_preserved(self):
        (self.root / 'git-alias').symlink_to(self.root / '.git', target_is_directory=True)
        config = self.root / 'android-app/local.properties'
        config.write_text('preserve private configuration')
        for target in ('git-alias/config', 'android-app/local.properties', '.git', 'ios-app', 'android-app', '.'):
            with self.subTest(target=target):
                self.assertNotEqual(self.call('artifacts', '--derived-data', target, check=False).returncode, 0)
                self.assertTrue(config.exists())
                self.assertTrue((self.root / '.git/config').exists())

    def test_h1_custom_directory_with_dot_spelling_is_removed(self):
        target = self.root / 'ios-app/custom-cache'
        target.mkdir()
        self.call('artifacts', '--derived-data', 'ios-app/./custom-cache')
        self.assertFalse(target.exists())

    def test_h2_parent_symlink_swap_during_size_probe_preserves_other_checkout(self):
        parent = self.root / 'ios-app/cache-parent'
        (parent / 'build').mkdir(parents=True)
        other = self.temp / 'other-checkout'
        (other / 'build').mkdir(parents=True)
        precious = other / 'build/precious'
        precious.write_text('unowned')
        self.swapping_du(parent, other)
        result = self.call('artifacts', check=False)
        self.assertTrue(precious.exists(), 'size probe redirected recursive deletion outside checkout')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue((parent.with_name('cache-parent-saved') / 'build').exists())

    def test_h2_leaf_inode_replacement_is_preserved(self):
        leaf = self.root / 'ios-app/build'
        leaf.mkdir()
        other = self.temp / 'replacement'
        other.mkdir()
        (other / 'precious').write_text('not the approved directory')
        self.swapping_du(leaf, other, symlink=False)
        self.assertNotEqual(self.call('artifacts', check=False).returncode, 0)
        self.assertTrue((leaf / 'precious').exists())
        self.assertTrue(leaf.with_name('build-saved').exists())

    def test_h2_orphan_parent_swap_preserves_other_host_artifact(self):
        target = self.orphan_artifact()
        # Run the same destructive reproduction against the initial CLI too.
        flags = self.approve_orphans() if '--plan-digest' in (self.root / 'scripts/clean-worktree.py').read_text() else ['--confirm']
        other = self.temp / 'other-host'
        (other / target.name).mkdir(parents=True)
        precious = other / target.name / 'precious'
        precious.write_text('unowned host data')
        self.swapping_du(target.parent, other)
        result = self.call('orphans', *flags, check=False)
        self.assertTrue(precious.exists(), 'orphan deletion followed swapped host ancestor')
        self.assertNotEqual(result.returncode, 0)

    def test_h2_rmtree_requires_local_symlink_safe_implementation(self):
        import importlib.util
        spec = importlib.util.spec_from_file_location('cleanup_fd_test', self.root / 'scripts/clean-worktree.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertTrue(shutil.rmtree.avoids_symlink_attacks)
        with mock.patch.object(shutil.rmtree, 'avoids_symlink_attacks', False):
            with self.assertRaises(module.Unsafe):
                with module.Directory(self.root):
                    self.fail('unsafe rmtree was accepted')
        def old_rmtree(path):
            self.fail('Python without rmtree dir_fd reached mutation')
        old_rmtree.avoids_symlink_attacks = True
        with mock.patch.object(shutil, 'rmtree', old_rmtree):
            with self.assertRaises(module.Unsafe):
                with module.Directory(self.root):
                    self.fail('rmtree without dir_fd was accepted')

    def test_m2_same_avd_same_serial_restart_does_not_inherit_poweroff(self):
        self.claim('android', 'emulator-5554', '--booted', '--avd', 'flint-test')
        self.state['android_processes'][0]['pid'] = 777
        self.state['android_processes'][0]['start'] = 'Tue Oct 27 21:00:00 2026'
        # Guest boot_id intentionally unchanged: Quick Boot can restore it.
        self.save_state()
        self.call('devices')
        self.assertEqual(self.mutations(), [], 'new emulator process inherited stale shutdown authority')
        self.assertIn('emulator-5554', json.loads(self.state_file.read_text())['android'])

    def test_m2_ios_reboot_does_not_inherit_poweroff(self):
        self.claim('ios', UNOWNED, '--booted')
        self.state['sims'][1]['lastBootedAt'] = '2026-10-27T21:00:00Z'
        self.save_state()
        self.call('devices')
        self.assertEqual(self.mutations(), [], 'new simulator boot inherited old poweroff authority')

    def test_m2_ios_managerpid_fallback_and_restart_without_lastbootedat(self):
        for device in self.state['sims']:
            device.pop('lastBootedAt')
        self.save_state()
        self.claim('ios', UNOWNED, '--booted')
        self.state['ios_start'] = 'Tue Oct 27 21:00:00 2026'
        self.save_state()
        self.call('devices')
        self.assertEqual(self.mutations(), [])

    def test_m2_ios_managerpid_fallback_cleans_own_boot(self):
        for device in self.state['sims']:
            device.pop('lastBootedAt')
        self.save_state()
        self.claim('ios', OWN, '--booted', '--created')
        self.call('devices')
        self.assertEqual([c[2] for c in self.mutations()], ['shutdown', 'delete'])

    def test_m2_unknown_incarnation_and_pid_group_mismatch_fail_closed(self):
        self.assertNotEqual(self.call('claim', 'ios', UNOWNED, '--booted', check=False).returncode, 0)
        for flags in (('--pid', '777'), ('--process-group', '777')):
            self.assertNotEqual(self.call('incarnation', 'android', 'emulator-5554', '--avd', 'flint-test', *flags,
                                          check=False).returncode, 0)
        good = self.call('incarnation', 'android', 'emulator-5554', '--avd', 'flint-test', '--process-group', '501').stdout
        plain = self.call('incarnation', 'android', 'emulator-5554', '--avd', 'flint-test').stdout
        self.assertEqual(good, plain)
        self.assertEqual(self.mutations(), [])

    def test_m2_claim_records_auditable_process_evidence(self):
        self.claim('android', 'emulator-5554', '--booted', '--avd', 'flint-test')
        evidence = self.ledger()[0]['incarnation_evidence']
        self.assertEqual(evidence, dict(platform='android', id='emulator-5554', avd='flint-test',
                                       kind='emulator-process', pid=501, process_group=501,
                                       start='Tue Oct 27 20:00:00 2026', boot_id='aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee'))

    def test_m2_rollback_preserves_claimed_or_restarted_device(self):
        token = self.call('incarnation', 'android', 'emulator-5554', '--avd', 'flint-test').stdout.strip()
        self.claim('android', 'emulator-5554', '--avd', 'flint-test', session='peer')
        args = ['rollback', 'android', 'emulator-5554', '--avd', 'flint-test', '--incarnation', token,
                '--process-group', '501']
        self.assertNotEqual(self.call(*args, check=False).returncode, 0)
        self.call('release', 'android', 'emulator-5554', '--session', 'peer')
        self.state['android_processes'][0]['start'] = 'Tue Oct 27 21:00:00 2026'
        self.save_state()
        self.assertNotEqual(self.call(*args, check=False).returncode, 0)
        self.assertEqual(self.mutations(), [])

    def test_m2_rollback_only_stops_exact_proven_boot(self):
        token = self.call('incarnation', 'ios', OWN).stdout.strip()
        self.call('rollback', 'ios', OWN, '--incarnation', token)
        self.assertEqual([c[1:] for c in self.mutations()], [['simctl', 'shutdown', OWN]])
        self.assertEqual(self.ledger(), [])

    def test_m2_missing_helper_does_not_block_proven_own_shutdown(self):
        self.state['helper'] = dict(running=True, device=UNOWNED, pid=701)
        self.save_state()
        self.claim('ios', UNOWNED, '--booted', '--helper-pid', '701')
        self.state.pop('helper')
        self.save_state()
        self.call('devices')
        self.assertEqual([c[1:] for c in self.mutations()], [['simctl', 'shutdown', UNOWNED]])

    def test_m2_reclaim_after_restart_never_ors_old_boot_rights(self):
        self.claim('ios', UNOWNED, '--booted')
        self.state['sims'][1]['lastBootedAt'] = '2026-10-27T21:00:00Z'
        self.save_state()
        self.claim('ios', UNOWNED)
        self.assertFalse(self.ledger()[0]['booted'])
        self.call('devices')
        self.assertEqual(self.mutations(), [])

    def test_m2_helper_exact_incarnation_is_released_without_poweroff(self):
        self.state['helper'] = dict(running=True, device=UNOWNED, pid=701)
        self.save_state()
        self.claim('ios', UNOWNED, '--helper-pid', '701')
        self.call('devices')
        self.assertEqual([Path(c[0]).name for c in self.mutations()], ['orca'])
        self.assertEqual(json.loads(self.state_file.read_text())['sims'][1]['state'], 'Booted')

    def test_m2_helper_reused_pid_and_missing_helper_preserve_booted_device(self):
        for missing in (False, True):
            with self.subTest(missing=missing):
                self.state['helper'] = dict(running=True, device=UNOWNED, pid=701)
                self.state['helper_start'] = 'Tue Oct 27 20:00:00 2026'
                self.save_state()
                self.claim('ios', UNOWNED, '--helper-pid', '701')
                if missing:
                    self.state.pop('helper')
                else:
                    self.state['helper_start'] = 'Tue Oct 27 21:00:00 2026'
                self.save_state()
                self.call('devices')
                self.assertEqual(self.mutations(), [])

    def test_m2_helper_peer_claim_blocks_release_then_transfers_proof(self):
        self.state['helper'] = dict(running=True, device=UNOWNED, pid=701)
        self.save_state()
        self.claim('ios', UNOWNED, '--helper-pid', '701')
        self.claim('ios', UNOWNED, session='peer')
        self.call('devices', '--session', 'session-a')
        self.assertEqual(self.mutations(), [])
        self.call('devices', '--session', 'peer')
        self.assertEqual([Path(c[0]).name for c in self.mutations()], ['orca'])

    def test_m3_bare_confirm_never_authorizes_name_only_delete(self):
        result = self.call('orphans', '--confirm', check=False)
        self.assertEqual(self.mutations(), [], 'legacy name alone authorized deletion')
        self.assertNotEqual(result.returncode, 0)

    def test_m3_exact_plan_deletes_only_explicit_legacy_udid(self):
        target = self.orphan_artifact()
        flags = self.approve_orphans(LEGACY)
        self.state['sims'].append(dict(udid='44444444-4444-4444-4444-444444444444', name='flint-review-99', state='Shutdown'))
        self.save_state()
        self.call('orphans', *flags)
        self.assertFalse(target.exists())
        self.assertEqual([c[-1] for c in self.mutations()], [LEGACY])
        self.assertTrue(any(d['name'] == 'flint-review-99' for d in json.loads(self.state_file.read_text())['sims']))

    def test_m3_plan_digest_tamper_and_wrong_approval_preserve_everything(self):
        target = self.orphan_artifact()
        flags = self.approve_orphans(LEGACY)
        wrong = flags[:-1] + ['0' * 64]
        self.assertNotEqual(self.call('orphans', *wrong, check=False).returncode, 0)
        path = Path(flags[2])
        plan = json.loads(path.read_text())
        plan['simulators'][0]['identity']['name'] = 'flint-review-999'
        path.write_text(json.dumps(plan))
        self.assertNotEqual(self.call('orphans', *flags, check=False).returncode, 0)
        self.assertTrue(target.exists())
        self.assertEqual(self.mutations(), [])

    def test_m3_changed_identity_or_new_claim_invalidates_plan_before_artifact_delete(self):
        target = self.orphan_artifact()
        flags = self.approve_orphans(LEGACY)
        self.state['sims'][2]['name'] = 'flint-review-62'
        self.save_state()
        self.assertNotEqual(self.call('orphans', *flags, check=False).returncode, 0)
        self.assertTrue(target.exists())
        self.state['sims'][2]['name'] = 'flint-review-61'
        self.save_state()
        self.claim('ios', LEGACY)
        self.assertNotEqual(self.call('orphans', *flags, check=False).returncode, 0)
        self.assertTrue(target.exists())
        self.assertEqual(self.mutations(), [])

    def test_m3_new_live_checkout_invalidates_saved_artifact_plan(self):
        target = self.orphan_artifact()
        flags = self.approve_orphans()
        (self.temp / 'gone').mkdir()
        self.assertNotEqual(self.call('orphans', *flags, check=False).returncode, 0)
        self.assertTrue(target.exists())

    def test_m3_preview_plan_never_overwrites_existing_file_or_symlink(self):
        source = self.root / 'ios-app/project.yml'
        original = source.read_bytes()
        alias = self.temp / 'plan-alias.json'
        alias.symlink_to(source)
        for path in (source, alias):
            with self.subTest(path=path.name):
                self.assertNotEqual(self.call('orphans', '--plan', str(path), check=False).returncode, 0)
                self.assertEqual(source.read_bytes(), original)
        flags = self.approve_orphans()
        plan = Path(flags[2])
        approved = plan.read_bytes()
        self.assertNotEqual(self.call('orphans', '--plan', str(plan), check=False).returncode, 0)
        self.assertEqual(plan.read_bytes(), approved)
        self.assertEqual(self.mutations(), [])

    def test_m4_cleanup_fixture_isolates_home_git_xdg_and_caps_subprocesses(self):
        self.assertEqual(self.env['GIT_CONFIG_GLOBAL'], '/dev/null')
        self.assertEqual(self.env['GIT_CONFIG_SYSTEM'], '/dev/null')
        for name in ('XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'XDG_DATA_HOME'):
            self.assertTrue(Path(self.env[name]).is_relative_to(self.home))
        self.assertLessEqual(resource.getrlimit(resource.RLIMIT_FSIZE)[0], 1048576)
        output = bounded([sys.executable, '-c', 'print("x" * 4000000)'], env=self.env, timeout=5)
        self.assertNotEqual(output.returncode, 0)
        self.assertLessEqual(len(output.stdout), 1048576)


if __name__ == "__main__":
    unittest.main()
