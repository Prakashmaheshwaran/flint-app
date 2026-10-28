#!/usr/bin/env python3
"""Dump only Flint's process logs; use SDK adb if Orca's JSON format is unavailable."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

PACKAGE = "com.flint.peakfocus"


def capture(args):
    return subprocess.check_output(args, text=True).strip()


def process_ids(output):
    if not isinstance(output, str):
        raise ValueError("Unrecognized Orca pidof result; expected a string")
    values = output.split()
    if not values or not all(value.isdecimal() for value in values):
        raise ValueError("Flint is not running, or its process IDs could not be read")
    return set(values)


def orca_logs(cli, target, lines):
    """Keep Orca response-format knowledge isolated here for version changes."""
    scope = ["--device", target, "--worktree", f"path:{Path.cwd()}", "--json"]
    response = json.loads(capture([cli, "emulator", "exec", "--command", f"pidof {PACKAGE}", *scope]))
    if not isinstance(response, dict) or response.get('ok') is not True:
        raise ValueError("Orca pidof reported failure")
    # Captured 1.4.241 exec replies put the raw shell string in result.
    pids = process_ids(response["result"])
    response = json.loads(capture([cli, "emulator", "logcat", "--lines", str(lines), *scope]))
    if not isinstance(response, dict) or response.get('ok') is not True:
        raise ValueError("Orca logcat reported failure")
    entries = response["result"]
    if not isinstance(entries, list) or any(not isinstance(entry, dict) for entry in entries):
        raise ValueError("Unrecognized Orca logcat entries")
    # Actual 1.4.241 entries expose timestamp/level/tag/message, without PID or
    # raw threadtime. A tag/message match cannot establish process ownership.
    # Use the existing explicitly targeted SDK adb --pid path in that case.
    if any('pid' not in entry or not str(entry['pid']).isdecimal() for entry in entries):
        raise ValueError("Orca logcat omits process IDs; cannot safely select Flint logs")
    return [
        entry.get("raw") or f'{entry.get("timestamp", "")} {entry.get("level", "")}/{entry.get("tag", "")}({entry["pid"]}): {entry["message"]}'
        for entry in entries
        if str(entry["pid"]) in pids
    ]


def adb_logs(target, lines):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or str(Path.home() / "Library/Android/sdk")
    sdk_adb = Path(sdk) / "platform-tools/adb"
    adb = str(sdk_adb) if sdk_adb.is_file() else shutil.which("adb")
    if not adb:
        raise ValueError(f"adb not found at {sdk_adb}; run make env")
    connected = [line.split()[0] for line in capture([adb, "devices"]).splitlines()[1:] if line.endswith("\tdevice")]
    if target in connected:
        serial = target
    else:
        matches = []
        for candidate in connected:
            if candidate.startswith("emulator-"):
                name = capture([adb, "-s", candidate, "emu", "avd", "name"]).splitlines()
                if name and name[0] == target:
                    matches.append(candidate)
        if len(matches) != 1:
            raise ValueError(f"AVD={target!r} matches {len(matches)} booted emulators; attach it with make android-run")
        serial = matches[0]
    pids = process_ids(capture([adb, "-s", serial, "shell", "pidof", PACKAGE]))
    output = []
    for pid in sorted(pids):
        output.extend(capture([adb, "-s", serial, "logcat", f"--pid={pid}", "-d", "-t", str(lines)]).splitlines())
    return output


def main():
    target = os.environ.get("AVD", "flint-test")
    lines = int(os.environ.get("LOG_LINES", "500"))
    if lines < 1:
        raise ValueError("LOG_LINES must be positive")
    try:
        output = orca_logs(os.environ.get("ORCA_CLI_COMMAND", "orca"), target, lines)
    except (subprocess.SubprocessError, OSError, KeyError, TypeError, ValueError) as exc:
        print(f"Orca logs unavailable ({exc}); trying SDK adb with an explicit device and PID.", file=sys.stderr)
        output = adb_logs(target, lines)
    print(f"Flint logs: device={target} package={PACKAGE}")
    print("\n".join(output) if output else "No recent Flint process logs.")


if __name__ == "__main__":
    try:
        main()
    except (subprocess.SubprocessError, OSError, KeyError, TypeError, ValueError) as exc:
        print(f"Cannot read Flint logs: {exc}", file=sys.stderr)
        sys.exit(1)
