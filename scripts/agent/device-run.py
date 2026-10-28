#!/usr/bin/env python3
"""Bounded, exact-identity boot/attach transactions for the native run scripts.

Only an explicit successful boot (iOS), or our own emulator process group
(Android), plus a verifiable incarnation can grant shutdown rights. Orca's
attach response is identity evidence, never boot or helper ownership evidence.
"""
import json
import os
from pathlib import Path
import re
import resource
import shlex
import signal
import socket
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
LEDGER = HERE.parent / "flint-devices.sh"
OUTPUT_LIMIT = 1024 * 1024


def file_limit():
    soft, hard = resource.getrlimit(resource.RLIMIT_FSIZE)
    limit = min(OUTPUT_LIMIT, hard) if hard != resource.RLIM_INFINITY else OUTPUT_LIMIT
    if soft != resource.RLIM_INFINITY:
        limit = min(limit, soft)
    resource.setrlimit(resource.RLIMIT_FSIZE, (limit, hard))


def run(args, timeout=3, check=True):
    """Bound time, output and descendants; never leave a timed-out CLI alive."""
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        child = subprocess.Popen(args, stdout=out, stderr=err, start_new_session=True,
                                 preexec_fn=file_limit)
        try:
            child.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=2)
            raise ValueError(f"{Path(args[0]).name} {' '.join(args[1:3])} timed out after {timeout:g}s")
        out.seek(0)
        err.seek(0)
        result = subprocess.CompletedProcess(args, child.returncode,
                                             out.read(OUTPUT_LIMIT).decode(errors="replace"),
                                             err.read(OUTPUT_LIMIT).decode(errors="replace"))
    if check and result.returncode:
        raise ValueError(f"{Path(args[0]).name} failed ({result.returncode}): {result.stderr[-4000:].strip()}")
    return result


def deadline_setting():
    value = float(os.environ.get("FLINT_AGENT_DEVICE_TIMEOUT", "60"))
    if not 0.1 <= value <= 120:
        raise ValueError("FLINT_AGENT_DEVICE_TIMEOUT must be between 0.1 and 120 seconds")
    return value


def orca(*args, timeout=3):
    result = run([os.environ.get("ORCA_CLI_COMMAND", "orca"), "emulator", *args, "--json"], timeout)
    payload = json.loads(result.stdout)
    if not isinstance(payload, dict):
        raise ValueError(f"Orca {args[0]} returned malformed JSON")
    if payload.get("ok") is False:
        raise ValueError(f"Orca {args[0]} reported failure")
    return payload["result"]


def incarnation(platform, device, avd=None, group=None):
    args = [str(LEDGER), "incarnation", platform, device]
    if avd:
        args += ["--avd", avd]
    if group is not None:
        args += ["--process-group", str(group)]
    result = run(args, timeout=5, check=False)
    token = result.stdout.strip()
    return token if result.returncode == 0 and re.fullmatch(r"v1:[0-9a-f]{64}", token) else None


def claim(platform, device, avd=None, token=None, group=None, helper_pid=None):
    args = [str(LEDGER), "claim", platform, device]
    if avd:
        args += ["--avd", avd]
    if token:
        args += ["--booted", "--incarnation", token]
        if group is not None:
            args += ["--process-group", str(group)]
    if helper_pid is not None:
        args += ["--helper-pid", str(helper_pid)]
    result = run(args, timeout=8)
    print(result.stdout.strip(), file=sys.stderr)


def attach(platform, device, timeout):
    result = orca("attach", device, "--worktree", f"path:{Path.cwd()}", timeout=timeout)
    if not isinstance(result, dict):
        raise ValueError("Orca attach returned malformed identity; no claim/install/launch")
    info = result.get("info")
    if not isinstance(info, dict):
        raise ValueError("Orca attach returned malformed identity; no claim/install/launch")
    actual = info.get("deviceUdid")
    if result.get("attached") is not True or actual != device or info.get("backend") != platform:
        raise ValueError(
            f"Orca attach identity mismatch: expected {platform} {device!r}, "
            f"returned {info.get('backend')!r} {actual!r}. No claim/install/launch. "
            "Returned device/helper is preserved; scoped helper recovery requires review."
        )
    helper_pid = info.get("helperPid")
    if helper_pid is not None and (type(helper_pid) is not int or helper_pid <= 1):
        raise ValueError("Orca attach returned an invalid helper PID; no claim/install/launch")
    # The ledger independently verifies this PID, device and process start time.
    return helper_pid


def ios_state(device):
    payload = json.loads(run(["xcrun", "simctl", "list", "devices", "available", "--json"]).stdout)
    matches = [d for devices in payload["devices"].values() for d in devices
               if d.get("udid") == device and d.get("isAvailable", True)]
    if len(matches) != 1 or matches[0].get("state") not in ("Shutdown", "Booted"):
        raise ValueError(f"Simulator {device} is unavailable or not in a settled state")
    return matches[0]["state"]


def rollback(platform, device, token, avd=None, group=None):
    if not token or incarnation(platform, device, avd, group) != token:
        print(f"Preserved {platform} {device}: no matching boot ownership proof for rollback.", file=sys.stderr)
        return
    # Rollback shares cleanup's claim lock and peer checks. A matching boot
    # token alone cannot authorize powering off a device another worker uses.
    args = [str(LEDGER), "rollback", platform, device, "--incarnation", token]
    if avd:
        args += ["--avd", avd]
    if group is not None:
        args += ["--process-group", str(group)]
    run(args, timeout=10)
    print(f"Rolled back only our verified {platform} boot: {device}; Orca helpers were not killed.", file=sys.stderr)


def ios(device):
    timeout = deadline_setting()
    token = None
    own_boot = False
    try:
        # This observation is deliberately after xcodebuild, not before it.
        if ios_state(device) == "Shutdown":
            boot = run(["xcrun", "simctl", "boot", device], timeout=10, check=False)
            if boot.returncode == 0:
                own_boot = True
                token = incarnation("ios", device)
            elif ios_state(device) != "Booted":
                raise ValueError(f"Could not boot simulator {device}: {boot.stderr[-2000:].strip()}")
            # Another actor won a failed boot: use it, but acquire no shutdown rights.
        run(["xcrun", "simctl", "bootstatus", device, "-b"], timeout=timeout)
        if token and incarnation("ios", device) != token:
            token = None
        helper_pid = attach("ios", device, timeout)
        claim("ios", device, token=token, helper_pid=helper_pid)
        if own_boot and not token:
            print("Boot incarnation unavailable or changed; claimed use only, without shutdown rights.", file=sys.stderr)
    except (ValueError, OSError, KeyError, TypeError) as exc:
        if own_boot:
            try:
                rollback("ios", device, token)
            except (ValueError, OSError) as rollback_error:
                print(f"Rollback deferred: {rollback_error}", file=sys.stderr)
        raise exc


def adb_path():
    return str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")


def avd_name(serial):
    if not re.fullmatch(r"emulator-[0-9]+", serial):
        raise ValueError(f"Expected an Android emulator serial, got {serial!r}")
    result = run([adb_path(), "-s", serial, "emu", "avd", "name"], timeout=2)
    lines = result.stdout.strip().splitlines()
    if len(lines) != 2 or lines[1].strip() != "OK":
        raise ValueError(f"Cannot verify AVD identity for {serial}")
    return lines[0].strip()


def properties(path):
    if path.stat().st_size > 128 * 1024:
        raise ValueError(f"AVD metadata is too large: {path}")
    result = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.lstrip().startswith(("#", ";")) and "=" in line:
            key, value = line.split("=", 1)
            result[key.strip()] = value.strip()
    return result


def preflight(name):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", name) or name in (".", ".."):
        raise ValueError(f"Invalid AVD name {name!r}; use an exact local AVD name")
    user = Path(os.environ.get("ANDROID_USER_HOME", str(Path.home() / ".android")))
    emulator_home = Path(os.environ.get("ANDROID_EMULATOR_HOME", str(user)))
    avds = Path(os.environ.get("ANDROID_AVD_HOME", str(emulator_home / "avd")))
    pointer = avds / f"{name}.ini"
    config = avds / f"{name}.avd/config.ini"
    if pointer.is_file():
        values = properties(pointer)
        if values.get("path"):
            config = Path(values["path"]) / "config.ini"
        elif values.get("path.rel"):
            config = user / values["path.rel"] / "config.ini"
    if not config.is_file():
        raise ValueError(f"AVD {name!r} has no config.ini at {config}; create or repair it in Android Device Manager")
    values = properties(config)
    if values.get("AvdId", name) != name:
        raise ValueError(f"AVD config identity mismatch for {name!r}: {config}")
    image = values.get("image.sysdir.1", "").replace("\\", "/")
    sdk = Path(os.environ["ANDROID_HOME"]).resolve()
    image_path = (sdk / image).resolve()
    try:
        relative = image_path.relative_to(sdk)
    except ValueError:
        raise ValueError(f"AVD image.sysdir.1 must name a system image under resolved SDK {sdk}: {image!r}") from None
    if len(relative.parts) != 4 or relative.parts[0] != "system-images" or not all(
            re.fullmatch(r"[A-Za-z0-9_.-]+", p) for p in relative.parts[1:]):
        raise ValueError(f"Invalid image.sysdir.1 in {config}: {image!r}; select an installed system image")
    package = ";".join(relative.parts)
    metadata = image_path / "package.xml"
    valid = False
    try:
        if metadata.stat().st_size < 128 * 1024:
            tree = ET.parse(metadata)
            valid = any(e.tag.rsplit("}", 1)[-1] == "localPackage" and e.get("path") == package
                        for e in tree.iter()) and (image_path / "system.img").is_file()
    except (OSError, ET.ParseError):
        pass
    if not valid:
        install = shlex.join([str(sdk / "cmdline-tools/latest/bin/sdkmanager"), f"--sdk_root={sdk}", package])
        raise ValueError(f"AVD {name!r} needs missing/incomplete image package {package!r} under {sdk}. Install it with: {install}")


def android_device(selector, name):
    result = orca("devices")
    devices = result if isinstance(result, list) else result["devices"]
    matches = [d for d in devices if d.get("platform") == "android"
               and selector in (d.get("id"), d.get("name"), d.get("avdName"))]
    if len(matches) != 1:
        raise ValueError(f"AVD={selector!r} matches {len(matches)} Android devices; use a unique AVD name or serial")
    device = matches[0]
    if device.get("avdName", device.get("name")) != name:
        raise ValueError(f"Android inventory identity mismatch for {selector!r}")
    state = device.get("state", "").lower()
    if state not in ("shutdown", "booted"):
        raise ValueError(f"AVD {name!r} is in state {state!r}; wait for it to settle")
    return device["id"], state


def verify_android(serial, name):
    if avd_name(serial) != name:
        raise ValueError(f"Android identity mismatch: expected {serial} / {name!r}; no claim/install/launch")
    if run([adb_path(), "-s", serial, "get-state"], timeout=2).stdout.strip() != "device":
        raise ValueError(f"Android emulator {serial} is not ready")


def reserve_port():
    for port in range(5554, 5683, 2):
        sockets = [socket.socket(), socket.socket()]
        try:
            sockets[0].bind(("127.0.0.1", port))
            sockets[1].bind(("127.0.0.1", port + 1))
            return port, sockets
        except OSError:
            for sock in sockets:
                sock.close()
    raise ValueError("No free Android console/adb port pair in 5554..5683")


def android(selector):
    timeout = deadline_setting()
    name = avd_name(selector) if selector.startswith("emulator-") else selector
    # No Orca discovery or attach before this fast, entirely local prerequisite check.
    preflight(name)
    serial, state = android_device(selector, name)
    token = None
    child = None
    group = None
    try:
        if state == "shutdown":
            emulator = Path(os.environ["ANDROID_HOME"]) / "emulator/emulator"
            if not os.access(emulator, os.X_OK):
                raise ValueError(f"Missing emulator executable: {emulator}; install the SDK emulator package")
            port, reservations = reserve_port()
            serial = f"emulator-{port}"
            for sock in reservations:
                sock.close()
            # The post-start incarnation check binds serial/AVD to this new group;
            # a competing process taking the released port never earns our claim.
            child = subprocess.Popen([str(emulator), "-avd", name, "-port", str(port), "-no-snapshot-save"],
                                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                     start_new_session=True)
            group = child.pid
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if child.poll() is not None:
                    raise ValueError(f"Emulator for {name!r} exited during startup ({child.returncode})")
                try:
                    verify_android(serial, name)
                    token = incarnation("android", serial, name, group)
                    if token:
                        break
                except (ValueError, OSError):
                    pass
                time.sleep(min(0.2, max(0, deadline - time.monotonic())))
            if not token:
                raise ValueError(f"AVD {name!r} startup timed out after {timeout:g}s or boot ownership could not be verified")
        verify_android(serial, name)
        helper_pid = attach("android", serial, timeout)
        verify_android(serial, name)
        claim("android", serial, name, token, group, helper_pid)
        print(f"{serial}\t{name}")
    except (ValueError, OSError, KeyError, TypeError) as exc:
        if token:
            try:
                rollback("android", serial, token, name, group)
            except (ValueError, OSError) as rollback_error:
                print(f"Rollback deferred: {rollback_error}", file=sys.stderr)
        elif child is not None and child.poll() is None:
            # No serial mutation: stop only the still-live child group we started.
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=2)
        raise exc


def main():
    try:
        if sys.argv[1] == "ios":
            ios(sys.argv[2])
        elif sys.argv[1] == "android":
            android(sys.argv[2])
        elif sys.argv[1] == "verify-android":
            verify_android(sys.argv[2], sys.argv[3])
        else:
            raise ValueError("Unknown device-run operation")
    except (ValueError, OSError, KeyError, TypeError) as exc:
        print(f"Device run stopped: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
