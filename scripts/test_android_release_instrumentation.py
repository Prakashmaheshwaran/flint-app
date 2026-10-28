"""Release smoke must reject unsafe device contexts before app installation."""
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class ReleaseRunnerSafetyTests(unittest.TestCase):
    def fixture(self, *, disposable="1", serial="emulator-5554", outer_token=None):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "scripts").mkdir()
            (root / "bin").mkdir()
            shutil.copy(Path(__file__).with_name("android-release-instrumentation.sh"), root / "scripts")
            (root / "scripts/flint-env.sh").write_text("")
            adb = root / "bin/adb"
            adb.write_text(f"#!{sys.executable}\n" + '''import os,pathlib,sys
root=pathlib.Path(os.environ['FIXTURE_ROOT'])
with (root/'calls').open('a') as log: log.write(' '.join(sys.argv[1:])+'\\n')
if sys.argv[3:5]==['shell','getprop']: print('1')
elif sys.argv[3:]==['shell','cat','/proc/sys/kernel/random/boot_id']:
 print('22222222-2222-2222-2222-222222222222')
else: raise SystemExit('Unexpected mutation during preflight')
''')
            adb.chmod(0o755)
            token = "33333333-3333-3333-3333-333333333333"
            env = {**os.environ, "PATH": str(root / "bin") + os.pathsep + os.environ["PATH"],
                   "FIXTURE_ROOT": str(root), "FLINT_E2E_DISPOSABLE": disposable,
                   "FLINT_E2E_OUTER_RUN_TOKEN": token if outer_token is None else outer_token}
            result = subprocess.run(["bash", "scripts/android-release-instrumentation.sh", serial,
                                     "11111111-1111-1111-1111-111111111111", token],
                                    cwd=root, env=env, capture_output=True, text=True, timeout=10)
            calls = (root / "calls").read_text().splitlines() if (root / "calls").exists() else []
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
        self.assertEqual(len(calls), 3)
        self.assertFalse(any("install" in call or "instrument" in call for call in calls))


if __name__ == "__main__":
    unittest.main()
