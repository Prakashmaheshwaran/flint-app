#!/usr/bin/env python3
"""Resolve one available simulator name/UDID from simctl JSON; never select 'booted'."""
import json
import sys


def main():
    selector = sys.argv[1]
    devices = [
        device
        for runtime in json.load(sys.stdin)["devices"].values()
        for device in runtime
        if device.get("isAvailable", True)
        and selector in (device["name"], device["udid"])
    ]
    if len(devices) != 1:
        print(
            f"IOS_SIM={selector!r} matches {len(devices)} available simulators; "
            "set IOS_SIM to a unique simulator UDID (see orca emulator devices).",
            file=sys.stderr,
        )
        return 2
    if "--state" in sys.argv[2:]:
        print(f'{devices[0]["udid"]}\t{devices[0].get("state", "unknown")}')
    else:
        print(devices[0]["udid"])
    return 0


if __name__ == "__main__":
    sys.exit(main())
