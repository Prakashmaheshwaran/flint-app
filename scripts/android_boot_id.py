#!/usr/bin/env python3
"""Read/compare one exact emulator's boot ID without confusing adb loss with reboot.

Exit 0: verified (and matching, when expected); 3: changed; 2: unverifiable.
Only exit 0 permits the caller to collect evidence or clean up owned grants.
"""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import time

BOOT_ID = re.compile(r"[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")


def read_boot_id(serial, record):
    deadline = time.monotonic() + 20
    for attempt in range(1, 6):
        try:
            for command in (["wait-for-device"], ["shell", "cat", "/proc/sys/kernel/random/boot_id"]):
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(command, 0)
                reply = subprocess.run(["adb", "-s", serial, *command], check=True,
                                       capture_output=True, text=True, timeout=min(2, remaining))
            boot_id = reply.stdout.strip()
            if BOOT_ID.fullmatch(boot_id):
                return boot_id
            record(attempt=attempt, result="unreadable", detail="empty or malformed boot ID")
        except (OSError, subprocess.SubprocessError) as exc:
            record(attempt=attempt, result="unreadable", detail=str(exc))
        if attempt < 5:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            time.sleep(min(0.5, remaining))
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("serial")
    parser.add_argument("--expected")
    parser.add_argument("--context", required=True)
    parser.add_argument("--log", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-[0-9]+", args.serial):
        parser.error("an exact emulator serial is required")
    if args.expected is not None and not BOOT_ID.fullmatch(args.expected):
        parser.error("expected boot ID must be nonempty and valid")
    args.log.parent.mkdir(parents=True, exist_ok=True)

    def record(**event):
        with args.log.open("a") as output:
            output.write(json.dumps(dict(serial=args.serial, context=args.context, **event)) + "\n")

    boot_id = read_boot_id(args.serial, record)
    if boot_id is None:
        result, status, message = "unverifiable", 2, "Boot incarnation unverifiable"
    elif args.expected is not None and boot_id != args.expected:
        result, status, message = "changed", 3, "Emulator incarnation changed"
    else:
        result, status, message = "same" if args.expected else "verified", 0, "Boot incarnation verified"
    record(result=result, boot_id=boot_id, expected=args.expected)
    print(f"{message} ({args.context}, {args.serial}).", file=sys.stderr)
    if status == 0:
        print(boot_id)
    return status


if __name__ == "__main__":
    sys.exit(main())
