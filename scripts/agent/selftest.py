#!/usr/bin/env python3
"""Device-free integration tests: real temporary Git history, fake native/Orca/gh tools."""
import json
import importlib.util
import os
from pathlib import Path
import resource
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from selftest_processes import ProcessRegistry, REGISTRY_ENV


def real_tool(name):
    found = shutil.which(name)
    # macOS system/CLT make paths are xcrun launchers, not the actual tools.
    # Pin their binaries so an isolated DEVELOPER_DIR cannot select live tools.
    native = Path('/Library/Developer/CommandLineTools/usr/bin') / ('gnumake' if name == 'make' else name)
    launchers = {Path(f'/usr/bin/{name}')}
    if name == 'make':
        launchers.add(Path('/Library/Developer/CommandLineTools/usr/bin/make'))
    return str(native) if found and Path(found).resolve() in launchers and native.is_file() else found


REAL_GIT = real_tool('git')
REAL_MAKE = real_tool('make')
FILE_LIMIT = 1024 * 1024
_soft, _hard = resource.getrlimit(resource.RLIMIT_FSIZE)
_limit = min(FILE_LIMIT, _hard) if _hard != resource.RLIM_INFINITY else FILE_LIMIT
if _soft != resource.RLIM_INFINITY:
    _limit = min(_limit, _soft)
# Applied to this process and every fixture subprocess, including imported use.
resource.setrlimit(resource.RLIMIT_FSIZE, (_limit, _hard))

SHIM = r'''#!/usr/bin/env python3
import json, os, pathlib, sys, time
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ["EVENTS"], "a") as f:
    f.write(json.dumps([name, args]) + "\n")
with open(os.environ["BINARY_PATHS"], "a") as f:
    f.write(json.dumps({"tool": name, "resolved": str(pathlib.Path(sys.argv[0]).resolve())}) + "\n")
if set(args) & {"delete", "erase", "-wipe-data", "--wipe-data"}:
    sys.exit("unexpected destructive device command in agent selftest")
if name == os.environ.get("FAIL_TOOL") or (name == "fake-make" and args[0] == os.environ.get("FAIL_TARGET")):
    sys.exit(7)
if name == "xcrun" and args[:3] == ["simctl", "list", "devices"]:
    devices = json.loads(os.environ["SIM_DEVICES"])
    for rows in devices["devices"].values():
        for device in rows:
            if pathlib.Path('booted-' + device['udid']).exists():
                device.update(state='Booted', lastBootedAt='2026-10-28T01:00:00Z')
                if os.environ.get('NO_BOOT_EPOCH'):
                    device.pop('lastBootedAt')
    print(json.dumps(devices))
elif name == "xcodebuild" and not os.environ.get("NO_APP"):
    d = pathlib.Path(args[args.index("-derivedDataPath") + 1])
    (d / "Build/Products/Debug-iphonesimulator/Flint.app").mkdir(parents=True, exist_ok=True)
    if os.environ.get("OTHER_ACTOR_BOOTS_DURING_BUILD"):
        pathlib.Path('booted-11111111-2222-3333-4444-555555555555').touch()
elif name == 'xcrun' and args[:2] == ['simctl', 'boot']:
    pathlib.Path('booted-' + args[2]).touch()
    if os.environ.get('BOOT_RACE'):
        sys.exit(149)
elif name == 'xcrun' and args[:2] == ['simctl', 'shutdown']:
    pathlib.Path('booted-' + args[2]).unlink(missing_ok=True)
elif name == 'flint-devices.sh' and args[0] == 'incarnation':
    if not os.environ.get('NO_INCARNATION'):
        token = 'b' if os.environ.get('CHANGE_INCARNATION_AFTER_ATTACH') and pathlib.Path('attached').exists() else 'a'
        print('v1:' + token * 64)
elif name == 'flint-devices.sh' and args[0] == 'claim':
    entry = {'platform': args[1], 'id': args[2],
             'incarnation': 'v1:' + 'a' * 64}
    if '--avd' in args:
        entry['avd'] = args[args.index('--avd') + 1]
    pathlib.Path('fake-claims.json').write_text(json.dumps([entry]))
elif name == 'flint-devices.sh' and args == ['list', '--json']:
    print(pathlib.Path('fake-claims.json').read_text())
elif name == 'flint-devices.sh' and args[0] == 'rollback':
    if os.environ.get('PEER_CLAIM'):
        sys.exit('rollback refused: device/AVD is claimed; preserve peers')
    with open(os.environ['EVENTS'], 'a') as f:
        command = ['xcrun', ['simctl', 'shutdown', args[2]]] if args[1] == 'ios' else ['adb', ['-s', args[2], 'emu', 'kill']]
        f.write(json.dumps(command) + '\n')
elif name == 'emulator':
    pathlib.Path('emulator-group').write_text(str(os.getpid()))
    pathlib.Path('emulator-serial').write_text('emulator-' + args[args.index('-port') + 1])
    pathlib.Path('emulator-ready').touch()
    while True:
        time.sleep(1)
elif name == "fake-make" and args == ["android"]:
    p = pathlib.Path("android-app/app/build/outputs/apk/debug/app-debug.apk")
    p.parent.mkdir(parents=True, exist_ok=True)
    p.touch()
    pathlib.Path('built-android').touch()
elif name == "orca":
    verb = args[1]
    if verb == "devices":
        if os.environ.get('DEVICES_JSON_AFTER_ATTACH') and pathlib.Path('attached').exists():
            print(os.environ['DEVICES_JSON_AFTER_ATTACH'])
            sys.exit(0)
        if os.environ.get('DEVICES_JSON'):
            print(os.environ['DEVICES_JSON'])
            sys.exit(0)
        count = sum(1 for line in pathlib.Path(os.environ["EVENTS"]).read_text().splitlines()
                    if json.loads(line) == ["orca", ["emulator", "devices", "--json"]])
        booted = count > 1 or os.environ.get("AVD_BOOTED")
        fixture = 'devices-booted.json' if booted else 'devices-shutdown.json'
        payload = json.loads((pathlib.Path('scripts/agent/fixtures/orca-android-1.4.241') / fixture).read_text())
        print(json.dumps(payload))
    elif verb == 'attach':
        if os.environ.get('ATTACH_DELAY'):
            time.sleep(float(os.environ['ATTACH_DELAY']))
        pathlib.Path('attached').write_text(os.environ.get('ATTACH_DEVICE', args[2]))
        if os.environ.get('ATTACH_JSON'):
            print(os.environ['ATTACH_JSON'])
            sys.exit(int(os.environ.get('ATTACH_RETURN_CODE', '0')))
        else:
            ident = args[2]
            if ident == 'iPhone 17':
                ident = '11111111-2222-3333-4444-555555555555'
            if ident == 'flint-test':
                ident = 'emulator-5554'
            backend = 'android' if ident.startswith('emulator-') else 'ios'
            if backend == 'android':
                payload = json.loads(pathlib.Path('scripts/agent/fixtures/orca-android-1.4.241/attach.json').read_text())
                payload['result']['info']['deviceUdid'] = os.environ.get('ATTACH_DEVICE', ident)
                print(json.dumps(payload))
            else:
                print(json.dumps({'ok': True, 'result': {'attached': True, 'info': {
                    'deviceUdid': os.environ.get('ATTACH_DEVICE', ident), 'backend': backend, 'helperPid': 12345}}}))
    elif verb == 'list':
        print(json.dumps({'result': {'running': True, 'pid': 12345, 'device': pathlib.Path('attached').read_text()}}))
    elif verb == 'install':
        pathlib.Path('installed').touch()
    elif verb == "exec":
        print(pathlib.Path('scripts/agent/fixtures/orca-android-1.4.241/pidof.json').read_text())
    elif verb == "logcat":
        print(pathlib.Path('scripts/agent/fixtures/orca-android-1.4.241/logcat.json').read_text())
    else:
        print(json.dumps({"result": {"device": {"id": "emulator-5554"}}}))
elif name == "adb":
    if args == ["devices"]:
        print("List of devices attached\nemulator-5554\tdevice\nemulator-5556\tdevice")
    elif args[2:] == ["emu", "avd", "name"]:
        ours = pathlib.Path('emulator-serial').read_text() if pathlib.Path('emulator-serial').exists() else 'emulator-5554'
        value = os.environ.get('ACTUAL_AVD_NAME', 'flint-test' if args[1] == ours else 'other-avd')
        if os.environ.get('AVD_CHANGED_AFTER_INSTALL') and pathlib.Path('installed').exists():
            value = 'other-avd'
        if os.environ.get('AVD_CHANGED_AFTER_BUILD') and pathlib.Path('built-android').exists():
            value = 'other-avd'
        print(value + '\nOK')
    elif args[2:] == ['get-state']:
        print('device')
    elif args[2:] == ['shell', 'getprop', 'sys.boot_completed']:
        print(os.environ.get('BOOT_COMPLETED', '1'))
    elif args[2:] == ['shell', 'cat', '/proc/sys/kernel/random/boot_id']:
        print('11111111-2222-3333-4444-555555555555')
    elif args[2:] == ['emu', 'kill']:
        pass
    elif args[2:4] == ["shell", "pidof"]:
        print("123")
    elif "logcat" in args:
        print("app event via adb")
elif name == 'xcrun' and args[:2] == ['simctl', 'spawn'] and args[-2:] == ['launchctl', 'managerpid']:
    print('43210')
elif name == 'xcrun' and args[:2] == ['simctl', 'spawn'] and args[3:] == ['launchctl', 'print', 'system/com.apple.SpringBoard']:
    print('state = running\npid = 43211')
elif name == 'ps':
    if args == ['-axo', 'pid=,pgid=,lstart=,command=']:
        if pathlib.Path('emulator-group').exists():
            group = pathlib.Path('emulator-group').read_text()
            port = pathlib.Path('emulator-serial').read_text().removeprefix('emulator-')
            print(group + ' ' + group + ' Tue Oct 27 01:00:00 2026 /fake/emulator -avd flint-test -port ' + port)
        elif os.environ.get('AVD_BOOTED'):
            print('501 501 Tue Oct 27 01:00:00 2026 /fake/emulator -avd flint-test -port 5554')
    elif args == ['-p', '43210', '-o', 'lstart=,comm=']:
        print('Tue Oct 27 01:00:00 2026 /fake/launchd_sim')
    elif args == ['-p', '12345', '-o', 'lstart=,comm=']:
        print('Tue Oct 27 01:00:00 2026 /fake/serve-sim')
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
            self.addCleanup(self.remove_fixture, temp)
        self.active_commands = set()
        self.registry = ProcessRegistry(os.environ.get(REGISTRY_ENV, str(Path(temp) / 'processes.json')))
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
        for name in ("orca", "xcrun", "simctl", "xcodebuild", "xcodegen", "gh", "fake-make", "adb", "emulator", "avdmanager", "ps"):
            (self.bin / name).symlink_to(self.shim.name)
        self.home = Path(temp).resolve() / 'home'
        self.home.mkdir()
        self.env = {key: value for key, value in os.environ.items()
                    if not key.startswith(('GIT_', 'FLINT_', 'ANDROID_', 'ORCA_', 'XDG_'))
                    and key not in ('MAKEFLAGS', 'MFLAGS', 'MAKELEVEL', 'BASH_ENV', 'ENV', 'ZDOTDIR',
                                    'JAVA_HOME', 'DEVELOPER_DIR', 'XCODE_DEV', 'CODEX_THREAD_ID', 'CLAUDE_SESSION_ID',
                                    'IOS_DIR', 'IOS_SIM', 'IOS_DERIVED_DATA', 'AVD', 'DRY_RUN', 'CONFIRM',
                                    'ORPHAN_PLAN', 'ORPHAN_DIGEST', 'TAG')}
        self.env.update(
            HOME=str(self.home),
            # This is a private test-owned registry, deliberately forwarded even
            # through otherwise sanitized nested AgentCommands environments.
            **{REGISTRY_ENV: str(self.registry.path)},
            XDG_CONFIG_HOME=str(self.home / '.config'),
            XDG_CACHE_HOME=str(self.home / '.cache'),
            XDG_DATA_HOME=str(self.home / '.local/share'),
            XDG_STATE_HOME=str(self.home / '.local/state'),
            GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_SYSTEM=os.devnull, GIT_CONFIG_GLOBAL=os.devnull,
            GIT_CONFIG_COUNT='3', GIT_CONFIG_KEY_0='core.hooksPath', GIT_CONFIG_VALUE_0=os.devnull,
            GIT_CONFIG_KEY_1='init.templateDir', GIT_CONFIG_VALUE_1=str(self.home / 'empty-template'),
            GIT_CONFIG_KEY_2='commit.gpgsign', GIT_CONFIG_VALUE_2='false',
            PYTHONDONTWRITEBYTECODE='1',
            JAVA_HOME=str(self.home / 'fake-java'), DEVELOPER_DIR=str(self.home / 'fake-xcode'),
            PATH=os.pathsep.join((str(self.bin), str(Path(REAL_GIT).parent), str(Path(REAL_MAKE).parent), os.environ['PATH'])),
            EVENTS=str(self.events),
            BINARY_PATHS=str(self.root / "binary-paths.jsonl"),
            FLINT_MAKE=str(self.bin / "fake-make"),
            SIM_DEVICES=json.dumps({"devices": {"runtime": [
                {"name": "iPhone 17", "udid": "11111111-2222-3333-4444-555555555555", "isAvailable": True, "state": "Shutdown"},
                {"name": "Other phone", "udid": "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", "isAvailable": True, "state": "Booted"},
            ]}}),
        )
        # A merged shared env wrapper may prepend the SDK tools to PATH. Pin those
        # paths and Orca itself to fakes so this suite cannot reach a real device.
        sdk = self.root / "fake-sdk"
        (sdk / "platform-tools").mkdir(parents=True)
        (sdk / "platform-tools/adb").symlink_to(self.shim)
        (sdk / 'emulator').mkdir()
        (sdk / 'emulator/emulator').symlink_to(self.shim)
        self.image = sdk / 'system-images/android-35/google_apis/arm64-v8a'
        self.image.mkdir(parents=True)
        (self.image / 'system.img').touch()
        (self.image / 'package.xml').write_text('<repository><localPackage path="system-images;android-35;google_apis;arm64-v8a"/></repository>')
        self.avd_config = self.home / '.android/avd/flint-test.avd/config.ini'
        self.avd_config.parent.mkdir(parents=True)
        self.avd_config.write_text('AvdId=flint-test\nimage.sysdir.1=system-images/android-35/google_apis/arm64-v8a/\n')
        (self.home / 'empty-template').mkdir()
        self.env.update(
            ANDROID_HOME=str(sdk), ANDROID_SDK_ROOT=str(sdk),
            ORCA_CLI_COMMAND=str(self.bin / "orca"),
            ORCA_ROOT_PATH=str(self.root), ORCA_WORKTREE_PATH=str(self.root),
        )

    def run_cmd(self, args, command_timeout=20, **env):
        with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
            owner = uuid.uuid4().hex
            self.active_commands.add(owner)
            child = self.registry.spawn(args, owner=owner, cwd=self.root, env={**self.env, **env},
                                        stdout=out, stderr=err)
            try:
                code = child.wait(timeout=command_timeout)
            except subprocess.TimeoutExpired:
                code = 124
            finally:
                self.registry.finish(owner)
                child.wait(timeout=2)
                self.active_commands.discard(owner)
            out.seek(0)
            err.seek(0)
            return subprocess.CompletedProcess(args, code, out.read(FILE_LIMIT).decode(errors='replace'),
                                               err.read(FILE_LIMIT).decode(errors='replace'))

    def remove_fixture(self, temporary):
        if self.active_commands:
            raise RuntimeError(f'Selftest process termination unverified; fixture retained: {temporary}')
        shutil.rmtree(temporary)

    def tearDown(self):
        # run_cmd already stopped every verified descendant. A saved numeric
        # group alone must never authorize another signal after PID reuse.
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
        result = self.make("ios-run", "IOS_DERIVED_DATA=ios-app/Custom Data", "IOS_SIM=11111111-2222-3333-4444-555555555555")
        self.assert_ok(result)
        build = self.calls("xcodebuild")[0]
        self.assertEqual(build[build.index("-derivedDataPath") + 1], "ios-app/Custom Data")
        self.assertIn("CODE_SIGNING_ALLOWED=NO", build)
        self.assertEqual(self.calls("xcodegen"), [["generate"]])
        self.assertEqual(self.calls("orca"), [["emulator", "attach", "11111111-2222-3333-4444-555555555555", "--worktree", f"path:{self.root}", "--json"]])
        self.assertEqual([a for a in self.calls('flint-devices.sh') if a[0] == 'claim'],
                         [["claim", "ios", "11111111-2222-3333-4444-555555555555", "--booted", '--incarnation', 'v1:' + 'a' * 64, '--helper-pid', '12345']])
        calls = self.calls("xcrun")
        self.assertIn(["simctl", "install", "11111111-2222-3333-4444-555555555555", "ios-app/Custom Data/Build/Products/Debug-iphonesimulator/Flint.app"], calls)
        self.assertIn(["simctl", "launch", "11111111-2222-3333-4444-555555555555", "com.flint.peakfocus"], calls)
        self.assertFalse(any("booted" in args for _, args in self.calls()))
        self.assertIn("bundle=com.flint.peakfocus", result.stdout)

    def test_ios_build_failure_never_attaches(self):
        result = self.make("ios-run", FAIL_TOOL="xcodebuild")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.calls("orca"), [])

    def test_ios_claim_does_not_own_shutdown_for_an_already_booted_device(self):
        result = self.make("ios-run", "IOS_SIM=AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE")
        self.assert_ok(result)
        self.assertEqual(self.calls("flint-devices.sh"), [["claim", "ios", "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", '--helper-pid', '12345']])

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
        serial = (self.root / 'emulator-serial').read_text()
        scope[1] = serial
        self.assertEqual(self.calls("orca"), [
            ["emulator", "devices", "--json"],
            ["emulator", "attach", serial, "--worktree", f"path:{self.root}", "--json"],
            ["emulator", "install", str(self.root / "android-app/app/build/outputs/apk/debug/app-debug.apk"), "--reinstall", *scope],
            ["emulator", "launch", "com.flint.peakfocus", "--activity", ".MainActivity", *scope],
        ])
        self.assertEqual(self.calls("fake-make"), [["android"]])
        claim, = [a for a in self.calls('flint-devices.sh') if a[0] == 'claim']
        self.assertEqual(claim[:7], ['claim', 'android', serial, '--avd', 'flint-test', '--booted', '--incarnation'])
        self.assertEqual(claim[-2:], ['--process-group', (self.root / 'emulator-group').read_text()])
        self.assertNotIn('--helper-pid', claim)
        self.assertIn('-no-snapshot-save', self.calls('emulator')[0])

    def test_android_inventory_uses_captured_backend_array(self):
        sample = (ROOT / 'scripts/agent/fixtures/orca-android-1.4.241/devices-shutdown.json').read_text()
        result = self.make('android-run', DEVICES_JSON=sample)
        self.assert_ok(result)
        self.assertTrue(any(args[1] == 'launch' for args in self.calls('orca')))

    def test_android_unknown_inventory_never_boots_or_attaches(self):
        for payload in ({'devices': []}, [None], [{'platform': 'android', 'id': 'flint-test',
                                                 'name': 'flint-test', 'state': 'shutdown'}]):
            with self.subTest(payload=payload):
                result = self.make('android-run', DEVICES_JSON=json.dumps({'ok': True, 'result': payload}))
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(self.calls('emulator'), [])
                self.assertFalse(any(args[1] in ('attach', 'install', 'launch') for args in self.calls('orca')))

    def test_android_framework_readiness_precedes_attach(self):
        result = self.make('android-run', AVD_BOOTED='1', BOOT_COMPLETED='0')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('has not completed boot', result.stderr)
        self.assertFalse(any(args[1] in ('attach', 'install', 'launch') for args in self.calls('orca')))
        result = self.make('android-run', BOOT_COMPLETED='0', FLINT_AGENT_DEVICE_TIMEOUT='0.2')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(args[1] in ('attach', 'install', 'launch') for args in self.calls('orca')))

    def test_android_failed_attach_preserves_real_json_diagnostic(self):
        sample = (ROOT / 'scripts/agent/fixtures/orca-android-1.4.241/attach-not-ready.json').read_text()
        result = self.make('android-run', ATTACH_JSON=sample, ATTACH_RETURN_CODE='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('scrcpy server exited before the video stream started', result.stderr)
        self.assertFalse(any(args[1] in ('install', 'launch') for args in self.calls('orca')))

    def test_android_reused_stream_requires_fresh_android_inventory(self):
        sample = (ROOT / 'scripts/agent/fixtures/orca-android-1.4.241/attach-reused.json').read_text()
        self.assert_ok(self.make('android-run', AVD_BOOTED='1', ATTACH_JSON=sample))
        (self.root / 'events.jsonl').unlink()
        (self.root / 'attached').unlink()
        wrong = {'ok': True, 'result': [{'backend': 'ios', 'id': 'emulator-5554',
                                       'name': 'flint-test', 'state': 'booted'}]}
        result = self.make('android-run', AVD_BOOTED='1', ATTACH_JSON=sample,
                           DEVICES_JSON_AFTER_ATTACH=json.dumps(wrong))
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(args[1] in ('install', 'launch') for args in self.calls('orca')))

    def use_real_ledger(self):
        ledger = self.root / "scripts/flint-devices.sh"
        ledger.unlink()
        shutil.copy(ROOT / "scripts/flint-devices.sh", ledger)
        (self.root / "android-app/settings.gradle.kts").touch()
        (self.root / "ios-app/project.yml").touch()
        self.init_repo()
        for key in ("ORCA_TERMINAL_HANDLE", "ORCA_PANE_KEY", "CODEX_THREAD_ID", "CLAUDE_SESSION_ID"):
            self.env.pop(key, None)
        self.env["FLINT_DEVICE_SESSION_ID"] = "fixture-claude-session"

    def ledger_entries(self):
        result = self.run_cmd(["bash", "scripts/flint-devices.sh", "list", "--json"])
        self.assert_ok(result)
        return json.loads(result.stdout)

    def test_android_serial_claim_uses_real_avd_and_ending_session_identity(self):
        self.use_real_ledger()
        result = self.make("android-run", "AVD=emulator-5554", AVD_BOOTED="1")
        self.assert_ok(result)
        entry, = self.ledger_entries()
        self.assertEqual(entry["avd"], "flint-test")
        self.assertEqual(entry["claimant"], "fixture-claude-session")
        self.assertFalse(entry["booted"])
        self.assertFalse(entry["created"])
        self.assertIsNone(entry['helper'])

    def test_ios_real_ledger_inherits_orca_identity_and_only_cold_start_rights(self):
        self.use_real_ledger()
        ident = "11111111-2222-3333-4444-555555555555"
        devices = json.loads(self.env["SIM_DEVICES"])
        devices["devices"]["runtime"][0]["udid"] = ident
        self.env["SIM_DEVICES"] = json.dumps(devices)
        self.env["ORCA_TERMINAL_HANDLE"] = "fixture-orca-session"
        self.assert_ok(self.make("ios-run", f"IOS_SIM={ident}"))
        entry, = self.ledger_entries()
        self.assertEqual(entry["claimant"], "fixture-orca-session")
        self.assertTrue(entry["booted"])
        self.assertFalse(entry["created"])
        self.assertEqual(entry['helper']['pid'], 12345)

    def test_m1_ios_real_ledger_uses_launchd_fallback_without_boot_epoch(self):
        self.use_real_ledger()
        ident = '11111111-2222-3333-4444-555555555555'
        devices = json.loads(self.env['SIM_DEVICES'])
        devices['devices']['runtime'][0]['udid'] = ident
        self.assert_ok(self.make('ios-run', f'IOS_SIM={ident}', SIM_DEVICES=json.dumps(devices), NO_BOOT_EPOCH='1'))
        entry, = self.ledger_entries()
        self.assertTrue(entry['booted'])
        self.assertEqual(entry['incarnation_evidence']['kind'], 'launchd_sim-process')
        self.assertEqual(entry['helper']['pid'], 12345)

    def test_m1_android_real_ledger_binds_child_group_boot_and_helper(self):
        self.use_real_ledger()
        self.assert_ok(self.make('android-run'))
        entry, = self.ledger_entries()
        self.assertTrue(entry['booted'])
        self.assertEqual(entry['incarnation_evidence']['process_group'], int((self.root / 'emulator-group').read_text()))
        self.assertIsNone(entry['helper'])

    def test_android_running_device_claim_does_not_own_shutdown(self):
        result = self.make("android-run", AVD_BOOTED="1")
        self.assert_ok(result)
        self.assertEqual([args for args in self.calls("flint-devices.sh") if args[0] == 'claim'],
                         [["claim", "android", "emulator-5554", "--avd", "flint-test"]])

    def test_android_attach_or_build_failure_stops_before_install(self):
        for failing in ("orca", "fake-make"):
            with self.subTest(failing=failing):
                result = self.make("android-run", FAIL_TOOL=failing)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse(any(args[1] in ("install", "launch") for args in self.calls("orca")))

    def test_m1_ios_other_actor_boots_during_build_gets_use_only_claim(self):
        self.assert_ok(self.make('ios-run', OTHER_ACTOR_BOOTS_DURING_BUILD='1'))
        claims = [a for a in self.calls('flint-devices.sh') if a[0] == 'claim']
        self.assertEqual(claims, [['claim', 'ios', '11111111-2222-3333-4444-555555555555', '--helper-pid', '12345']])
        self.assertNotIn(['simctl', 'boot', '11111111-2222-3333-4444-555555555555'], self.calls('xcrun'))

    def test_m1_ios_failed_boot_race_gets_use_only_claim(self):
        self.assert_ok(self.make('ios-run', BOOT_RACE='1'))
        self.assertIn(['simctl', 'boot', '11111111-2222-3333-4444-555555555555'], self.calls('xcrun'))
        claims = [a for a in self.calls('flint-devices.sh') if a[0] == 'claim']
        self.assertEqual(claims, [['claim', 'ios', '11111111-2222-3333-4444-555555555555', '--helper-pid', '12345']])

    def test_m1_ios_cold_boot_requires_explicit_boot_and_incarnation(self):
        self.assert_ok(self.make('ios-run'))
        self.assertIn(['simctl', 'boot', '11111111-2222-3333-4444-555555555555'], self.calls('xcrun'))
        claim, = [a for a in self.calls('flint-devices.sh') if a[0] == 'claim']
        self.assertIn('--booted', claim)
        self.assertEqual(claim[claim.index('--incarnation') + 1], 'v1:' + 'a' * 64)

    def test_m1_ios_unknown_incarnation_never_grants_shutdown_rights(self):
        self.assert_ok(self.make('ios-run', NO_INCARNATION='1'))
        claim, = [a for a in self.calls('flint-devices.sh') if a[0] == 'claim']
        self.assertNotIn('--booted', claim)

    def test_e2e1_exact_name_attaches_resolved_udid(self):
        devices = json.loads(self.env['SIM_DEVICES'])
        devices['devices']['runtime'].insert(0, {'name': 'iPhone 17e', 'udid': 'BBBBBBBB-CCCC-DDDD-EEEE-FFFFFFFFFFFF', 'state': 'Shutdown'})
        self.assert_ok(self.make('ios-run', 'IOS_SIM=iPhone 17', SIM_DEVICES=json.dumps(devices)))
        attach, = [a for a in self.calls('orca') if a[1] == 'attach']
        self.assertEqual(attach[2], '11111111-2222-3333-4444-555555555555')

    def test_e2e1_mismatch_rolls_back_only_proven_own_boot(self):
        result = self.make('ios-run', ATTACH_DEVICE='AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('expected ios', result.stderr)
        self.assertIn('AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE', result.stderr)
        self.assertEqual([a for a in self.calls('flint-devices.sh') if a[0] == 'claim'], [])
        self.assertFalse(any(a[1] in ('install', 'launch') for a in self.calls('xcrun')))
        self.assertEqual([a for a in self.calls('xcrun') if a[1] == 'shutdown'],
                         [['simctl', 'shutdown', '11111111-2222-3333-4444-555555555555']])
        self.assertFalse(any(a[1] in ('kill', 'shutdown') for a in self.calls('orca')))

    def test_e2e1_mismatch_preserves_rebooted_or_shared_devices(self):
        result = self.make('ios-run', ATTACH_DEVICE='AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE', CHANGE_INCARNATION_AFTER_ATTACH='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(a[1] in ('shutdown', 'install', 'launch') for a in self.calls('xcrun')))
        self.assertFalse(any(a[1] in ('kill', 'shutdown') for a in self.calls('orca')))

    def test_e2e1_malformed_attach_never_claims_or_installs(self):
        for info in ({}, None, [], {'deviceUdid': '11111111-2222-3333-4444-555555555555', 'backend': 'ios', 'helperPid': True}):
            with self.subTest(info=info):
                result = self.make('ios-run', ATTACH_JSON=json.dumps({'ok': True, 'result': {'attached': True, 'info': info}}))
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn('Traceback', result.stderr)
                self.assertFalse(any(a[0] == 'claim' for a in self.calls('flint-devices.sh')))
                self.assertFalse(any(a[1] in ('install', 'launch') for a in self.calls('xcrun')))

    def test_e2e1_mismatch_rollback_preserves_peer_claim(self):
        result = self.make('ios-run', ATTACH_DEVICE='AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE', PEER_CLAIM='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('preserve peers', result.stderr)
        self.assertTrue(any(a[0] == 'rollback' for a in self.calls('flint-devices.sh')))
        self.assertFalse(any(a[1] in ('shutdown', 'install', 'launch') for a in self.calls('xcrun')))

    def test_e2e2_missing_image_fails_under_five_seconds_before_device_calls(self):
        shutil.rmtree(self.image)
        start = time.monotonic()
        result = self.make('android-run')
        self.assertLess(time.monotonic() - start, 5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('sdkmanager', result.stderr)
        self.assertIn('system-images;android-35;google_apis;arm64-v8a', result.stderr)
        self.assertIn('--sdk_root=', result.stderr)
        self.assertEqual(self.calls('orca'), [])
        self.assertEqual(self.calls('emulator'), [])
        self.assertEqual(self.calls('fake-make'), [])

    def test_e2e2_missing_or_invalid_avd_config_prevents_attach(self):
        self.avd_config.unlink()
        result = self.make('android-run')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('config.ini', result.stderr)
        self.assertEqual(self.calls('orca'), [])

    def test_e2e2_image_package_metadata_must_match_resolved_sdk(self):
        (self.image / 'package.xml').write_text('<localPackage path="system-images;android-34;google_apis;arm64-v8a"/>')
        result = self.make('android-run')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('missing/incomplete image package', result.stderr)
        self.assertEqual(self.calls('orca'), [])

    def test_e2e2_image_outside_resolved_sdk_is_rejected(self):
        self.avd_config.write_text('image.sysdir.1=/tmp/foreign-sdk/system-images/android-35/google_apis/arm64-v8a/\n')
        result = self.make('android-run')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('under resolved SDK', result.stderr)
        self.assertEqual(self.calls('orca'), [])

    def test_e2e2_attach_mismatch_rolls_back_only_own_serial(self):
        result = self.make('android-run', ATTACH_DEVICE='emulator-5998')
        self.assertNotEqual(result.returncode, 0)
        serial = (self.root / 'emulator-serial').read_text()
        self.assertIn(['-s', serial, 'emu', 'kill'], self.calls('adb'))
        self.assertNotIn(['-s', 'emulator-5998', 'emu', 'kill'], self.calls('adb'))
        self.assertFalse(any(a[0] == 'claim' for a in self.calls('flint-devices.sh')))
        self.assertFalse(any(a[1] in ('kill', 'shutdown', 'install', 'launch') for a in self.calls('orca')))

    def test_e2e2_attach_timeout_is_bounded_and_never_installs(self):
        start = time.monotonic()
        result = self.make('android-run', AVD_BOOTED='1', ATTACH_DELAY='10', FLINT_AGENT_DEVICE_TIMEOUT='0.1')
        self.assertLess(time.monotonic() - start, 5)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('timed out', result.stderr)
        self.assertFalse(any(a[0] == 'claim' for a in self.calls('flint-devices.sh')))
        self.assertFalse(any(a[1] in ('install', 'launch') for a in self.calls('orca')))

    def test_e2e2_cold_start_without_group_proof_never_claims(self):
        result = self.make('android-run', NO_INCARNATION='1', FLINT_AGENT_DEVICE_TIMEOUT='0.2')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(a[0] == 'claim' for a in self.calls('flint-devices.sh')))
        self.assertFalse(any(a[1] == 'attach' for a in self.calls('orca')))
        self.assertFalse(any(a[-2:] == ['emu', 'kill'] for a in self.calls('adb')))

    def test_e2e2_avd_identity_is_rechecked_after_build_before_install(self):
        result = self.make('android-run', AVD_BOOTED='1', AVD_CHANGED_AFTER_BUILD='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any(a[1] in ('install', 'launch') for a in self.calls('orca')))

    def test_e2e2_avd_identity_is_rechecked_before_launch(self):
        result = self.make('android-run', AVD_BOOTED='1', AVD_CHANGED_AFTER_INSTALL='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(any(a[1] == 'install' for a in self.calls('orca')))
        self.assertFalse(any(a[1] == 'launch' for a in self.calls('orca')))

    def test_m4_caller_home_git_config_and_hooks_are_not_inherited(self):
        caller = self.home / 'caller'
        hooks = caller / 'hooks'
        hooks.mkdir(parents=True)
        sentinel = caller / 'hook-executed'
        hook = hooks / 'pre-commit'
        hook.write_text('#!/bin/sh\ntouch ' + str(sentinel) + '\n')
        hook.chmod(0o755)
        config = caller / '.gitconfig'
        config.write_text(f'[core]\n hooksPath = "{hooks}"\n')
        spec = importlib.util.spec_from_file_location('candidate_agent_suite', ROOT / 'scripts/agent/selftest.py')
        candidate = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(candidate)
        case = candidate.AgentCommands('test_gate_mapping_and_dry_run')
        try:
            with mock.patch.dict(os.environ, {'HOME': str(caller), 'GIT_CONFIG_GLOBAL': str(config),
                                             'GIT_CONFIG_PARAMETERS': "'core.hooksPath=" + str(hooks) + "'"}):
                case.setUp()
            case.init_repo()
            self.assertFalse(sentinel.exists(), 'caller global Git hook executed')
            self.assertNotEqual(case.env['HOME'], str(caller))
            self.assertEqual(case.env['GIT_CONFIG_NOSYSTEM'], '1')
            self.assertEqual(case.env['GIT_CONFIG_GLOBAL'], os.devnull)
            self.assertNotIn('GIT_CONFIG_PARAMETERS', case.env)
            self.assertTrue(case.env['XDG_CONFIG_HOME'].startswith(case.env['HOME']))
        finally:
            case.doCleanups()

    def test_m4_subprocess_deadline_terminates_descendants(self):
        marker = self.root / 'escaped-child'
        descendant = 'import time; from pathlib import Path; time.sleep(0.7); Path(' + repr(str(marker)) + ').touch()'
        parent = 'import subprocess, sys, time; subprocess.Popen([sys.executable, "-c", ' + repr(descendant) + ']); time.sleep(30)'
        result = self.run_cmd([sys.executable, '-c', parent], command_timeout=0.1)
        self.assertEqual(result.returncode, 124)
        time.sleep(0.8)
        self.assertFalse(marker.exists(), 'timed-out subprocess descendant survived')

    def test_m4_file_cap_applies_process_wide(self):
        self.assertLessEqual(resource.getrlimit(resource.RLIMIT_FSIZE)[0], FILE_LIMIT)
        result = self.run_cmd([sys.executable, '-c', 'import resource; print(resource.getrlimit(resource.RLIMIT_FSIZE)[0])'])
        self.assert_ok(result)
        self.assertLessEqual(int(result.stdout.strip()), FILE_LIMIT)

    def test_m4_subprocess_output_is_capped(self):
        result = self.run_cmd([sys.executable, '-c', 'import os; os.write(1, b"x" * (4 * 1024 * 1024))'])
        self.assertLessEqual(len(result.stdout), FILE_LIMIT)

    def test_ios_logs_use_exact_device_and_process_without_boot(self):
        result = self.make("logs-ios")
        self.assert_ok(result)
        self.assertEqual(self.calls("xcrun")[-1], ["simctl", "spawn", "11111111-2222-3333-4444-555555555555", "log", "stream", "--level", "debug", "--style", "compact", "--predicate", 'process == "Flint"'])
        self.assertFalse(any("bootstatus" in args for _, args in self.calls()))

    def test_android_logs_reject_pidless_orca_entries_and_use_scoped_sdk(self):
        result = self.make("logs-android")
        self.assert_ok(result)
        self.assertIn("app event via adb", result.stdout)
        self.assertIn("Orca logcat omits process IDs", result.stderr)
        self.assertNotIn('AiAiEcho', result.stdout)
        self.assertIn(["-s", "emulator-5554", "logcat", "--pid=123", "-d", "-t", "500"], self.calls("adb"))
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
