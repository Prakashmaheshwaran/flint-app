#!/usr/bin/env python3
"""Read one Android device's ID/state from `orca emulator devices --json`."""
import json
import sys


def resolve(payload, selector):
    result = payload["result"]
    devices = result if isinstance(result, list) else result["devices"]
    matches = [
        device for device in devices
        if device.get("platform", "android") == "android"
        and selector in (device.get("id"), device.get("name"), device.get("avdName"))
    ]
    if len(matches) != 1:
        raise ValueError(f"AVD={selector!r} matches {len(matches)} Android devices; use a unique AVD name or serial")
    device = matches[0]
    state = device["state"].lower()
    if state not in ("booted", "shutdown"):
        raise ValueError(f"Device {selector!r} is in state {state!r}; wait for it to settle")
    return device["id"], state


if __name__ == "__main__":
    try:
        print("\t".join(resolve(json.load(sys.stdin), sys.argv[1])))
    except (KeyError, TypeError, ValueError) as exc:
        print(f"Cannot resolve Android device state: {exc}", file=sys.stderr)
        sys.exit(2)
