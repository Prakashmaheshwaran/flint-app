"""Review regressions. Only disposable checkouts and fake tools are executed."""
import json
import os
from pathlib import Path
import resource
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(os.environ.get("FLINT_SAFETY_SOURCE_ROOT", Path(__file__).resolve().parents[1]))
resource.setrlimit(resource.RLIMIT_FSIZE, (1024 * 1024, 1024 * 1024))


class WorkspaceSafety(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="flint-safety-")
        self.addCleanup(self.tmp.cleanup)
        self.base = Path(self.tmp.name)
        self.root = self.base / "checkout"
        self.root.mkdir()
        shutil.copy(SOURCE / "Makefile", self.root / "Makefile")
        shutil.copytree(SOURCE / "scripts", self.root / "scripts", ignore=shutil.ignore_patterns("__pycache__"))
        (self.root / "android-app").mkdir()
        (self.root / "android-app/settings.gradle.kts").touch()
        (self.root / "ios-app").mkdir()
        self.home = self.base / "home"
        self.home.mkdir()
        self.bin = self.base / "bin"
        self.bin.mkdir()
        self.env = {"HOME": str(self.home), "XDG_CONFIG_HOME": str(self.home / "config"),
                    "XDG_CACHE_HOME": str(self.home / "cache"), "XDG_DATA_HOME": str(self.home / "data"),
                    "PATH": str(self.bin) + ":/usr/bin:/bin:/usr/sbin:/sbin",
                    "JAVA_HOME": str(self.base / "jdk"),
                    "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1",
                    "PYTHONDONTWRITEBYTECODE": "1"}
        (self.bin / "python3").symlink_to(sys.executable)
        for tool in ("xcrun", "xcodebuild", "xcodegen", "adb", "emulator", "orca"):
            self.script(self.bin / tool, "#!/bin/sh\necho 'unexpected live-tool route' >&2\nexit 91\n")
        self.env["ORCA_CLI_COMMAND"] = str(self.bin / "orca")

    def script(self, path, contents):
        path.write_text(contents)
        path.chmod(0o755)

    def run_cmd(self, args, *, env=None, timeout=20):
        return subprocess.run(args, cwd=self.root, env=env or self.env, text=True,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)

    def probe(self, **extra):
        return self.run_cmd(["/bin/bash", "-c", '. scripts/flint-env.sh || exit; printf "%s" "$ANDROID_HOME"'],
                            env={**self.env, **extra}, timeout=2)

    def test_h3_checkout_metacharacters_are_data(self):
        for name in ('back`touch injected`', 'dollar$(touch injected)', 'quotes " and \' space'):
            with self.subTest(name=name):
                (self.root / "injected").unlink(missing_ok=True)
                moved = self.base / name
                self.root.rename(moved)
                self.root = moved
                result = self.run_cmd(["/usr/bin/make", "--no-print-directory", "help"])
                self.assertFalse((self.root / "injected").exists(), result.stdout)
                self.assertEqual(result.returncode, 0, result.stdout)

    def test_h3_make_environment_and_cli_values_are_literal(self):
        self.script(self.root / "scripts/clean-worktree.py",
                    "import json,sys\nfrom pathlib import Path\nPath('argv.json').write_text(json.dumps(sys.argv[1:]))\n")
        for value in ('ios-app/Data`touch injected-env`', 'ios-app/Data$(touch injected-env)',
                      'ios-app/quotes " and \' space', 'ios-app/$HOME/$$literal'):
            for mode in ("environment", "command-line"):
                with self.subTest(value=value, mode=mode):
                    (self.root / "injected-env").unlink(missing_ok=True)
                    args = ["/usr/bin/make", "--no-print-directory", "clean"]
                    env = {**self.env, "IOS_DERIVED_DATA": value}
                    if mode == "command-line":
                        args.append("IOS_DERIVED_DATA=" + value)
                        env.pop("IOS_DERIVED_DATA")
                    result = self.run_cmd(args, env=env)
                    self.assertFalse((self.root / "injected-env").exists(), result.stdout)
                    self.assertEqual(result.returncode, 0, result.stdout)
                    self.assertEqual(json.loads((self.root / "argv.json").read_text()), ["artifacts", "--derived-data", value])

    def test_h3_native_and_selector_argv_are_literal(self):
        record = ('#!' + sys.executable + '\nimport json,os,sys\nfrom pathlib import Path\n'
                  'Path("record.json").write_text(json.dumps({"argv":sys.argv[1:],"sim":os.environ["IOS_SIM"],"avd":os.environ["AVD"]}))\n')
        self.script(self.bin / "xcodegen", "#!/bin/sh\nexit 0\n")
        self.script(self.bin / "xcodebuild", record)
        value = 'data `touch injected` $(touch injected) " \' space'
        result = self.run_cmd(["/usr/bin/make", "--no-print-directory", "ios-build"],
                              env={**self.env, "IOS_DERIVED_DATA": value, "IOS_SIM": value, "AVD": value})
        self.assertEqual(result.returncode, 0, result.stdout)
        record = json.loads((self.root / "record.json").read_text())
        self.assertEqual(record["argv"][record["argv"].index("-derivedDataPath") + 1], value)
        self.assertEqual(record["sim"], value)
        self.assertEqual(record["avd"], value)
        self.assertFalse((self.root / "injected").exists())

    def test_m5_relative_and_nonexistent_root_terminate(self):
        (self.root / "relative").mkdir()
        for value in ("relative", "relative-no-checkout", "./relative", "/nonexistent/flint-root"):
            with self.subTest(value=value):
                result = self.probe(FLINT_ENV_ROOT=value)
                self.assertIn(result.returncode, (0, 1), result.stdout)

    def test_l1_unicode_escape_is_decoded_or_rejected(self):
        (self.root / "android-app/local.properties").write_text("sdk.dir=/SDK\\u0020folder\n")
        result = self.probe()
        self.assertTrue((result.returncode == 0 and result.stdout == "/SDK folder") or
                        (result.returncode != 0 and "unsupported" in result.stdout.lower()), result.stdout)

    def test_l1_setup_rejects_control_characters_without_writing(self):
        sdk = self.base / "SDK\nfolder"
        sdk.mkdir()
        self.script(self.bin / "make", "#!/bin/sh\nexit 0\n")
        self.script(self.bin / "xcodegen", "#!/bin/sh\nexit 0\n")
        result = self.run_cmd(["/bin/bash", "scripts/orca/setup.sh"], env={**self.env, "ANDROID_HOME": str(sdk)})
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertIn("control", result.stdout.lower())
        self.assertFalse((self.root / "android-app/local.properties").exists())

    def test_m4_setup_suite_ignores_caller_git_hooks(self):
        hooks = self.base / "hooks"
        hooks.mkdir()
        sentinel = self.base / "caller-hook-ran"
        self.script(hooks / "pre-commit", '#!/bin/sh\ntouch "$HOOK_SENTINEL"\n')
        config = self.home / ".gitconfig"
        config.write_text('[core]\n hooksPath = "' + str(hooks) + '"\n')
        env = {**self.env, "GIT_CONFIG_GLOBAL": str(config), "HOOK_SENTINEL": str(sentinel)}
        result = self.run_cmd(["/bin/bash", "scripts/orca-setup-selftest.sh"], env=env, timeout=30)
        self.assertFalse(sentinel.exists(), "caller pre-commit hook executed")
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_l2_zsh_shim_repairs_each_path_independently(self):
        template = SOURCE / "scripts/flint-zshenv.example"
        # At the reviewed base the only copy was in the bootstrap report.
        if template.exists():
            block = template.read_text()
        else:
            report = (SOURCE / "docs/verification/workspace-bootstrap-2026-10-27.md").read_text()
            start = report.index("# >>> Flint toolchain paths")
            block = report[start:report.index("```", start)]
        shim = self.base / "shim"
        shim.write_text(block)
        for initial in ("/sdk/platform-tools:/usr/bin:/bin", "/sdk/emulator:/usr/bin:/bin", "/usr/bin:/bin"):
            result = self.run_cmd(["/bin/zsh", "-f", "-c", '. "$1"; . "$1"; print -r -- "$PATH"', "zsh", str(shim)],
                                  env={**self.env, "ANDROID_HOME": "/sdk", "PATH": initial})
            self.assertEqual(result.returncode, 0, result.stdout)
            entries = result.stdout.strip().split(":")
            self.assertEqual(entries.count("/sdk/platform-tools"), 1)
            self.assertEqual(entries.count("/sdk/emulator"), 1)

    def test_l2_report_detects_each_missing_shell_path(self):
        result = self.run_cmd(["/bin/bash", "scripts/flint-env-report.sh"],
                              env={**self.env, "ANDROID_HOME": "/sdk", "PATH": "/sdk/platform-tools:/usr/bin:/bin"})
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("shell PATH lacked /sdk/emulator", result.stdout)
        self.assertNotIn("shell PATH lacked /sdk/platform-tools", result.stdout)

    def test_l3_selftest_does_not_create_bytecode(self):
        # Run the real Make target/runner with tiny suites, avoiding recursion.
        for name in ("flint-env-selftest.sh", "orca-setup-selftest.sh", "android-verify-selftest.sh", "check-release-version-selftest.sh"):
            (self.root / "scripts" / name).write_text("exit 0\n")
        (self.root / "scripts/agent/selftest.py").write_text("pass\n")
        for path in (self.root / "scripts").glob("test_*.py"):
            path.unlink()
        (self.root / "scripts/test_probe.py").write_text("import unittest\nclass Probe(unittest.TestCase):\n def test_ok(self): pass\n")
        env = dict(self.env)
        env.pop("PYTHONDONTWRITEBYTECODE")
        result = self.run_cmd(["/usr/bin/make", "--no-print-directory", "selftest"], env=env)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertEqual(list(self.root.rglob("__pycache__")), [])


if __name__ == "__main__":
    unittest.main()
