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
import shutil
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
SIMCTL_QUERY_TIMEOUT = 30


class CommandTimeout(ValueError):
    """The client timed out; completion of a device operation is unknown."""


def file_limit():
    soft, hard = resource.getrlimit(resource.RLIMIT_FSIZE)
    limit = min(OUTPUT_LIMIT, hard) if hard != resource.RLIM_INFINITY else OUTPUT_LIMIT
    if soft != resource.RLIM_INFINITY:
        limit = min(limit, soft)
    resource.setrlimit(resource.RLIMIT_FSIZE, (limit, hard))


def run(args, timeout=None, check=True):
    """Bound time, output and descendants; never leave a timed-out CLI alive."""
    if timeout is None:
        # CoreSimulator queries can queue behind a peer boot. Mutating simctl
        # callers below supply explicit transaction budgets; cheap CLIs stay short.
        timeout = (SIMCTL_QUERY_TIMEOUT
                   if Path(args[0]).name == "xcrun" and args[1:2] == ["simctl"] else 3)
    with tempfile.TemporaryFile() as out, tempfile.TemporaryFile() as err:
        child = subprocess.Popen(args, stdout=out, stderr=err, start_new_session=True,
                                 preexec_fn=file_limit)
        try:
            child.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            os.killpg(child.pid, signal.SIGKILL)
            child.wait(timeout=2)
            raise CommandTimeout(f"{shlex.join(map(str, args))} timed out after {timeout:g}s")
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
    backend = info.get("backend")
    # Reused iOS helpers can omit backend (or return null). Accept only that
    # variant, with an exact UUID independently present in fresh simctl data.
    reused_ios = platform == "ios" and backend is None
    if (result.get("attached") is not True or actual != device
            or (backend != platform and not reused_ios)):
        raise ValueError(
            f"Orca attach identity mismatch: expected {platform} {device!r}, "
            f"returned {info.get('backend')!r} {actual!r}. No claim/install/launch. "
            "Returned device/helper is preserved; scoped helper recovery requires review."
        )
    if platform == "ios":
        if not isinstance(actual, str) or not re.fullmatch(
                r"[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", actual):
            raise ValueError("Orca attach returned an invalid simulator UDID; no claim/install/launch")
    if reused_ios:
        if ios_state(actual) != "Booted":
            raise ValueError(f"Simulator {actual} is not booted; no claim/install/launch")
    helper_pid = info.get("helperPid")
    if helper_pid is not None and (type(helper_pid) is not int or helper_pid <= 1):
        raise ValueError("Orca attach returned an invalid helper PID; no claim/install/launch")
    # The ledger independently verifies this PID, device and process start time.
    return helper_pid


def ios_state(device):
    payload = json.loads(run(["xcrun", "simctl", "list", "devices", "available", "--json"]).stdout)
    runtimes = payload.get("devices") if isinstance(payload, dict) else None
    if not isinstance(runtimes, dict) or any(
            not isinstance(devices, list) or any(not isinstance(d, dict) for d in devices)
            for devices in runtimes.values()):
        raise ValueError("simctl returned malformed simulator inventory; no claim/install/launch")
    matches = [d for devices in runtimes.values() for d in devices
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


def springboard_ready(device, timeout):
    # bootstatus can finish before the system shell is running. This is a
    # bounded readiness check, not a diagnosis of the observed FBS code 5.
    wait = min(timeout, SIMCTL_QUERY_TIMEOUT)
    deadline = time.monotonic() + wait
    query = ["xcrun", "simctl", "spawn", device, "launchctl", "print", "system/com.apple.SpringBoard"]
    while (remaining := deadline - time.monotonic()) > 0:
        try:
            result = run(query, timeout=min(SIMCTL_QUERY_TIMEOUT, remaining), check=False)
            pid = re.search(r"^\s*pid\s*=\s*([0-9]+)\s*$", result.stdout, re.MULTILINE)
            if (result.returncode == 0 and pid and int(pid[1]) > 1
                    and re.search(r"^\s*state\s*=\s*running\s*$", result.stdout, re.MULTILINE)):
                return
        except CommandTimeout:
            pass
        time.sleep(min(0.2, max(0, deadline - time.monotonic())))
    raise ValueError(f"Simulator {device} SpringBoard readiness timed out after {wait:g}s; "
                     f"query: {shlex.join(query)}; stopping before any further app launch")


def ios_launch(device, bundle):
    timeout = deadline_setting()
    for attempt in range(2):
        result = run(["xcrun", "simctl", "launch", device, bundle], timeout=timeout, check=False)
        print(result.stdout, end="")
        print(result.stderr, end="", file=sys.stderr)
        if result.returncode == 0:
            return
        fbs_code5 = re.search(r"\bdomain\s*=\s*FBSOpenApplicationServiceErrorDomain,\s*code\s*=\s*5\b",
                              result.stderr + result.stdout)
        if attempt:
            raise ValueError(f"simctl launch still failed after one retry for {device} "
                             f"(exit {result.returncode}); no further retry")
        if not fbs_code5:
            raise ValueError(f"simctl launch failed for {device} (exit {result.returncode}); no further retry")
        print("FBS code 5: retrying launch once after a short delay; root cause remains unproven.", file=sys.stderr)
        time.sleep(0.5)
        springboard_ready(device, timeout)


def ios(device):
    timeout = deadline_setting()
    token = None
    own_boot = False
    try:
        # This observation is deliberately after xcodebuild, not before it.
        if ios_state(device) == "Shutdown":
            try:
                boot = run(["xcrun", "simctl", "boot", device], timeout=10, check=False)
            except CommandTimeout:
                print(f"Warning: simulator {device} is possibly booted, unclaimed; review manually. "
                      "The timed-out boot grants no shutdown rights.", file=sys.stderr)
                raise
            if boot.returncode == 0:
                own_boot = True
                token = incarnation("ios", device)
            elif ios_state(device) != "Booted":
                raise ValueError(f"Could not boot simulator {device}: {boot.stderr[-2000:].strip()}")
            # Another actor won a failed boot: use it, but acquire no shutdown rights.
        run(["xcrun", "simctl", "bootstatus", device, "-b"], timeout=timeout)
        springboard_ready(device, timeout)
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
        sdkmanager = shutil.which("sdkmanager")
        if sdkmanager:
            install = shlex.join([str(Path(sdkmanager).absolute()), f"--sdk_root={sdk}", package])
            remedy = f"Install it with: {install}"
        else:
            remedy = ("Install that package for this SDK using Android SDK Manager, or install the "
                      "Android command-line tools so sdkmanager is executable on PATH "
                      f"and select this SDK with {shlex.quote(f'--sdk_root={sdk}')}.")
        raise ValueError(f"AVD {name!r} needs missing/incomplete image package {package!r} under {sdk}. {remedy}")


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


def verify_android_claim(serial, name, expected):
    verify_android(serial, name)
    if not isinstance(expected, str) or not re.fullmatch(r"v1:[0-9a-f]{64}", expected):
        raise ValueError(f"Missing or invalid Android boot incarnation for {serial}; no install/launch")
    entries = json.loads(run([str(LEDGER), "list", "--json"], timeout=5).stdout)
    if not isinstance(entries, list) or any(not isinstance(e, dict) for e in entries):
        raise ValueError("Invalid device claim ledger; no install/launch")
    # Includes guest boot ID and host emulator PID, group and start time. Never
    # replace the pre-build token with a newly claimed/restarted instance.
    observed = incarnation("android", serial, name)
    if (not observed or observed != expected
            or not any(e.get("platform") == "android" and e.get("id") == serial
                       and e.get("avd") == name and e.get("incarnation") == observed for e in entries)):
        raise ValueError(f"Android boot incarnation changed or is unavailable for {serial} / {name!r}; "
                         "ledger/live process mismatch, no further install/launch. Review and rerun explicitly.")
    return observed


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
        # Pin use-only devices too, before attach/claim can race a restart. The
        # shell must carry this same token across the build, install and launch.
        used_token = token or incarnation("android", serial, name)
        if not used_token:
            raise ValueError(f"Cannot verify Android boot incarnation for {serial}; no claim/install/launch")
        helper_pid = attach("android", serial, timeout)
        verify_android(serial, name)
        claim("android", serial, name, token, group, helper_pid)
        verify_android_claim(serial, name, used_token)
        print(f"{serial}\t{name}\t{used_token}")
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
        elif sys.argv[1] == "ios-launch":
            ios_launch(sys.argv[2], sys.argv[3])
        elif sys.argv[1] == "android":
            android(sys.argv[2])
        elif sys.argv[1] == "verify-android":
            verify_android_claim(sys.argv[2], sys.argv[3], sys.argv[4])
        else:
            raise ValueError("Unknown device-run operation")
    except (ValueError, OSError, KeyError, TypeError) as exc:
        print(f"Device run stopped: {exc}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
