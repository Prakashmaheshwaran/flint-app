#!/usr/bin/env python3
"""Run every device-free suite in an isolated, bounded process tree."""
import inspect
import os
from pathlib import Path
import resource
import shutil
import subprocess
import sys
import tempfile

from selftest_processes import ProcessRegistry, REGISTRY_ENV


def ensure_cleanup_python():
    """CLT-first PATH must not silently select Apple's older Python launcher."""
    if (getattr(shutil.rmtree, "avoids_symlink_attacks", False)
            and "dir_fd" in inspect.signature(shutil.rmtree).parameters):
        return
    seen = {Path(sys.executable).resolve()}
    probe = ("import inspect, shutil, sys; sys.exit(0 if "
             "getattr(shutil.rmtree, 'avoids_symlink_attacks', False) and "
             "'dir_fd' in inspect.signature(shutil.rmtree).parameters else 1)")
    for directory in os.environ.get("PATH", "").split(os.pathsep):
        candidate = Path(os.path.abspath(directory or os.curdir)) / "python3"
        resolved = candidate.resolve()
        if resolved in seen or not candidate.is_file() or not os.access(candidate, os.X_OK):
            continue
        seen.add(resolved)
        try:
            supported = subprocess.run([str(candidate), "-B", "-c", probe], timeout=3,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
        except (OSError, subprocess.TimeoutExpired):
            continue
        if supported:
            print(f"Selftest: {sys.executable} lacks fd-safe cleanup; using {candidate}",
                  file=sys.stderr, flush=True)
            os.execve(str(candidate), [str(candidate), "-B", *sys.argv],
                      {**os.environ, "PYTHONDONTWRITEBYTECODE": "1"})
    print("No compatible python3 on PATH: selftest requires symlink-safe shutil.rmtree(dir_fd). "
          "Install a current Python or put it on PATH; cleanup safeguards remain enabled.", file=sys.stderr)
    sys.exit(2)


ROOT = Path(__file__).resolve().parents[1]
SUITES = [
    ["/bin/bash", "scripts/flint-env-selftest.sh"],
    ["/bin/bash", "scripts/orca-setup-selftest.sh"],
    ["/bin/bash", "scripts/android-verify-selftest.sh"],
    ["/bin/bash", "scripts/check-release-version-selftest.sh"],
    # Each discovered file gets its own deadline, including the device and
    # watchdog followups. New regression suites cannot silently miss this gate.
    *[[sys.executable, "-B", "-m", "unittest", "discover", "-s", "scripts", "-p", path.name]
      for path in sorted((ROOT / "scripts").glob("test_*.py"))],
    [sys.executable, "-B", "scripts/agent/selftest.py"],
]


def main():
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
    resource.setrlimit(resource.RLIMIT_FSIZE, (1024 * 1024, 1024 * 1024))
    temporary = tempfile.TemporaryDirectory(prefix="flint-selftest-")
    safe_to_remove = False
    try:
        home = Path(temporary.name)
        # Deliberately do not inherit Git, agent identities, build options, or
        # test override variables from the caller.
        env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": str(home), "TMPDIR": str(home),
               "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_NOSYSTEM": "1",
               "GIT_CONFIG_COUNT": "1", "GIT_CONFIG_KEY_0": "core.hooksPath",
               "GIT_CONFIG_VALUE_0": "/dev/null", "PYTHONDONTWRITEBYTECODE": "1"}
        for name in ("CONFIG", "CACHE", "DATA", "STATE", "RUNTIME"):
            directory = home / name.lower()
            directory.mkdir()
            env[f"XDG_{name}_" + ("DIR" if name == "RUNTIME" else "HOME")] = str(directory)
            directory.chmod(0o700)
        for index, command in enumerate(SUITES):
            registry = ProcessRegistry(home / f"processes-{index}.json")
            print("SELFTEST " + " ".join(command), flush=True)
            with tempfile.TemporaryFile(dir=home) as log:
                child = registry.spawn(command, owner="suite", cwd=ROOT,
                                       env={**env, REGISTRY_ENV: str(registry.path)},
                                       stdout=log, stderr=subprocess.STDOUT)
                try:
                    status = child.wait(timeout=120)
                except subprocess.TimeoutExpired:
                    status = 124
                finally:
                    # Seal registration, freeze all registered trees (including
                    # inner run_cmd sessions), and verify exit before teardown.
                    registry.close()
                    child.wait(timeout=2)
                safe_to_remove = True
                log.seek(0)
                print(log.read(1024 * 1024).decode(errors="replace"), end="", flush=True)
                if status:
                    print(f"Selftest failed (exit {status}); process/log limits remained active.", file=sys.stderr)
                    return 1
                safe_to_remove = False
        safe_to_remove = True
    except (OSError, RuntimeError, subprocess.SubprocessError) as exc:
        print(f"Selftest supervision failed: {exc}", file=sys.stderr)
        return 1
    finally:
        if safe_to_remove:
            temporary.cleanup()
        else:
            # TemporaryDirectory's finalizer must not erase evidence or paths
            # that a process we could not stop may still be using.
            temporary._finalizer.detach()
            print(f"Selftest fixtures retained: {temporary.name}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    ensure_cleanup_python()
    sys.exit(main())
