"""Release smoke must reject unsafe device contexts before app installation."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class ReleaseRunnerSafetyTests(unittest.TestCase):
    def fixture(self, *, disposable="1", serial="emulator-5554", outer_token=None, mode="changed"):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "bin").mkdir()
            shutil.copy(Path(__file__).with_name("android-release-instrumentation.sh"), root / "scripts")
            shutil.copy(Path(__file__).with_name("android_boot_id.py"), root / "scripts")
            (root / "scripts/flint-env.sh").write_text("")
            (root / "scripts/verify_local_first_apk.py").write_text("")
            shutil.copy(Path(__file__).with_name("verify_instrumentation_result.py"), root / "scripts")
            for path, data in (("app/build/outputs/apk/release/app-release.apk", b"release"),
                               ("e2e/build/outputs/apk/debug/e2e-debug.apk", b"test")):
                apk = root / "android-app" / path
                apk.parent.mkdir(parents=True, exist_ok=True)
                apk.write_bytes(data)
            gradle = root / "android-app/gradlew"
            gradle.write_text('#!/bin/bash\nexit 0\n')
            gradle.chmod(0o755)
            git = root / "bin/git"
            git.write_text('#!/bin/bash\necho fixture-commit\n')
            git.chmod(0o755)
            adb = root / "bin/adb"
            adb.write_text(f"#!{sys.executable}\n" + '''import os,pathlib,sys
root=pathlib.Path(os.environ['FIXTURE_ROOT'])
args=sys.argv[3:]
assert sys.argv[1:3] == ['-s', 'emulator-5554']
with (root/'calls').open('a') as log: log.write(' '.join(sys.argv[1:])+'\\n')
mode=os.environ['FIXTURE_MODE']
installed=(root/'installed').exists()
if args==['wait-for-device']:
 if installed and mode=='reconnect-wait' and not (root/'reconnected').exists():
  (root/'reconnected').touch()
  sys.exit('adb: device offline')
elif args[:2]==['shell','getprop']: print('1')
elif args==['shell','cat','/proc/sys/kernel/random/boot_id']:
 if installed and mode=='empty': sys.exit(0)
 if installed and mode=='failed': sys.exit('adb: device offline')
 if installed and mode=='reconnect-read' and not (root/'reconnected').exists():
  (root/'reconnected').touch()
  sys.exit('adb: device offline')
 print('22222222-2222-2222-2222-222222222222' if mode=='changed' or (installed and mode=='reboot') else '11111111-1111-1111-1111-111111111111')
elif args[:3]==['shell','settings','get']: print('null')
elif args[:2]==['install','-r']: (root/'installed').touch()
elif args[:3]==['shell','pm','path']:
 print('package:/' + ('test' if args[3].endswith('.test') else 'release') + '.apk')
elif args[0]=='pull': pathlib.Path(args[2]).write_bytes(b'test' if args[1]=='/test.apk' else b'release')
elif args[:3]==['shell','dumpsys','package']: print('release-package')
elif args[:3]==['shell','am','instrument']:
 tests=args[args.index('class')+1].split(',')
 for i, test in enumerate(tests,1):
  cls, method=test.split('#')
  for code in (1,0):
   for key,value in [('class',cls),('test',method),('current',i),('numtests',len(tests))]:
    print(f'INSTRUMENTATION_STATUS: {key}={value}')
   print(f'INSTRUMENTATION_STATUS_CODE: {code}')
 print('OK (2 tests)\\nINSTRUMENTATION_CODE: -1')
else: raise SystemExit('Unexpected adb command')
''')
            adb.chmod(0o755)
            token = "33333333-3333-3333-3333-333333333333"
            env = {**os.environ, "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"],
                   "FIXTURE_ROOT": str(root), "FLINT_E2E_DISPOSABLE": disposable,
                   "FIXTURE_MODE": mode,
                   "FLINT_E2E_OUTER_RUN_TOKEN": token if outer_token is None else outer_token}
            result = subprocess.run(["bash", "scripts/android-release-instrumentation.sh", serial,
                                     "11111111-1111-1111-1111-111111111111", token],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=10)
            calls = (root / "calls").read_text().splitlines() if (root / "calls").exists() else []
            evidence = root / "artifacts/android-e2e"
            result.evidence = {p.name: p.read_text() for p in evidence.glob("*.txt")}
            result.boot_events = (evidence / "boot-incarnation.jsonl").read_text() if (evidence / "boot-incarnation.jsonl").exists() else ""
            return result, calls

    def test_missing_consent_invalid_serial_and_missing_outer_cleanup_never_call_adb(self):
        for arguments in ({"disposable": ""}, {"serial": "physical-device"}, {"outer_token": ""}):
            with self.subTest(arguments=arguments):
                result, calls = self.fixture(**arguments)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(calls, [])

    def test_changed_boot_stops_before_install_build_or_instrumentation(self):
        result, calls = self.fixture()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(calls), 2)
        self.assertIn("Emulator incarnation changed", result.stderr)
        self.assertFalse(any("install" in call or "instrument" in call for call in calls))

    def test_post_install_reconnect_proceeds_through_provenance_and_instrumentation_once(self):
        for mode in ("reconnect-wait", "reconnect-read"):
            with self.subTest(mode=mode):
                result, calls = self.fixture(mode=mode)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn('"result": "unreadable"', result.boot_events)
                self.assertIn('"context": "release-provenance", "result": "same"', result.boot_events)
                self.assertEqual(sum("shell am instrument" in c for c in calls), 1)
                self.assertIn("release-instrumentation.txt", result.evidence)
                self.assertIn("test sha256=", result.evidence["apk-provenance.txt"])

    def test_post_install_changed_empty_or_failed_boot_never_collects_or_instruments(self):
        for mode, message in (("reboot", "Emulator incarnation changed"),
                              ("empty", "Boot incarnation unverifiable"),
                              ("failed", "Boot incarnation unverifiable")):
            with self.subTest(mode=mode):
                result, calls = self.fixture(mode=mode)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)
                self.assertEqual(sum(" install -r " in c for c in calls), 2)
                self.assertFalse(any("shell pm path" in c or " pull " in c or "shell am instrument" in c for c in calls))
                self.assertNotIn("release-instrumentation.txt", result.evidence)


if __name__ == "__main__":
    unittest.main()
