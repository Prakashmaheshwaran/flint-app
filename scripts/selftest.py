#!/usr/bin/env python3
"""Run every device-free suite in an isolated, bounded process tree."""
import os
from pathlib import Path
import resource
import signal
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SUITES = [
    ["/bin/bash", "scripts/flint-env-selftest.sh"],
    ["/bin/bash", "scripts/orca-setup-selftest.sh"],
    ["/bin/bash", "scripts/android-verify-selftest.sh"],
    ["/bin/bash", "scripts/check-release-version-selftest.sh"],
    [sys.executable, "-B", "-m", "unittest", "discover", "-s", "scripts", "-p", "test_*.py"],
    [sys.executable, "-B", "scripts/agent/selftest.py"],
]


def main():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_FSIZE, (1024 * 1024, 1024 * 1024))
    with tempfile.TemporaryDirectory(prefix="flint-selftest-") as temporary:
        home = Path(temporary)
        # Deliberately do not inherit Git, agent identities, build options, or
        # test override variables from the caller.
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(home),
               "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1",
               "GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "core.hooksPath",
               "GIT_CONFIG_VALUE_0": "/dev/null", "PYTHONDONTWRITEBYTECODE": "1"}
        for name in ("CONFIG", "CACHE", "DATA", "STATE", "RUNTIME"):
            directory = home / name.lower()
            directory.mkdir()
            env[f"XDG_{name}_" + ("DIR" if name == "RUNTIME" else "HOME")] = str(directory)
            directory.chmod(0o700)
        for command in SUITES:
            print("SELFTEST " + " ".join(command), flush=True)
            with tempfile.TemporaryFile() as log:
                child = subprocess.Popen(command, cwd=ROOT, env=env, stdout=log,
                                         stderr=subprocess.STDOUT, start_new_session=True)
                try:
                    status = child.wait(timeout=120)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait(timeout=5)
                    status = 124
                log.seek(0)
                print(log.read().decode(errors="replace"), end="", flush=True)
                if status:
                    print(f"Selftest failed (exit {status}); process/log limits remained active.", file=sys.stderr)
                    return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
