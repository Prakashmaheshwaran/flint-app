#!/usr/bin/env python3
"""Prepare a revision-keyed, never-booted emulator cache on hosted Linux CI."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time
import urllib.request
import xml.etree.ElementTree as ET

PACKAGE = "system-images;android-36;google_apis;x86_64"
REPOSITORY = "https://dl.google.com/android/repository/sys-img/google_apis/sys-img2-3.xml"
AVD = "flint-ci-api36"
RESULTS = Path("artifacts/android-e2e")
HARDWARE = {"hw.ramSize": "3072", "vm.heapSize": "512", "hw.cpu.ncore": "2"}


def configure_avd(config):
    # Set the emulator's actual VM heap key as well as the runner's heap input.
    # This is still a never-booted AVD, saved before tests or grants can mutate it.
    lines = [line for line in config.read_text().splitlines()
             if line.partition("=")[0].strip() not in HARDWARE]
    config.write_text("\n".join(lines + [f"{key}={value}" for key, value in HARDWARE.items()]) + "\n")


def revision(package):
    node = package.find("revision")
    if node is None or node.find("major") is None or node.find("preview") is not None:
        raise ValueError("Expected a stable package revision")
    parts = [node.findtext(name, "0") for name in ("major", "minor", "micro")]
    if not all(re.fullmatch(r"[0-9]+", part) for part in parts):
        raise ValueError("Invalid package revision")
    return ".".join(str(int(part)) for part in parts)


def remote_package(data):
    matches = [p for p in ET.fromstring(data).findall("remotePackage")
               if p.get("path") == PACKAGE and p.find("channelRef") is not None
               and p.find("channelRef").get("ref") == "channel-0"]
    if len(matches) != 1:
        raise ValueError("Expected exactly one stable API 36 Google APIs x86_64 package")
    return matches[0]


def installed(image, expected):
    try:
        packages = ET.parse(image / "package.xml").getroot().findall("localPackage")
        return (len(packages) == 1 and packages[0].get("path") == PACKAGE
                and revision(packages[0]) == expected
                and all((image / name).stat().st_size > 0
                        for name in ("system.img", "vendor.img", "ramdisk.img", "kernel-ranchu")))
    except (OSError, ET.ParseError, ValueError):
        return False


def command(args, log, timeout=120, input_data=b"y\n" * 100):
    """Bound only setup commands; retain output and stop their process group."""
    with log.open("ab") as output:
        child = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=output,
                                 stderr=subprocess.STDOUT, start_new_session=True)
        try:
            child.communicate(input=input_data, timeout=timeout)
            return child.returncode
        except subprocess.TimeoutExpired:
            try:
                os.killpg(child.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            child.wait(timeout=5)
            return 124


def install_image(sdkmanager, image, expected, run=command, sleep=time.sleep):
    if installed(image, expected):
        print(f"Verified cached {PACKAGE} revision {expected}", flush=True)
        return
    for attempt in range(1, 4):
        print(f"Install {PACKAGE}, attempt {attempt}/3", flush=True)
        status = run([str(sdkmanager), "--install", PACKAGE, "--channel=0"],
                     RESULTS / f"image-install-{attempt}.log")
        if status == 0 and installed(image, expected):
            return
        print(f"Image install exit {status}; required revision/files not verified", flush=True)
        if attempt < 3:
            sleep(5 * attempt)
    raise RuntimeError("Image installation failed after three bounded attempts; no emulator/tests started")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("resolve", "prepare"))
    args = parser.parse_args()
    if os.environ.get("GITHUB_ACTIONS") != "true" or os.environ.get("RUNNER_OS") != "Linux":
        raise SystemExit("This cache preparer is only for disposable GitHub Linux runners")
    sdk = Path(os.environ["ANDROID_HOME"])
    image = sdk / "system-images/android-36/google_apis/x86_64"
    tools = sdk / "cmdline-tools/latest"
    RESULTS.mkdir(parents=True, exist_ok=True)
    metadata_path = RESULTS / "emulator-cache.json"
    if args.phase == "resolve":
        with urllib.request.urlopen(REPOSITORY, timeout=30) as response:
            data = response.read(8 * 1024 * 1024 + 1)
        if len(data) > 8 * 1024 * 1024:
            raise ValueError("Repository metadata exceeds size limit")
        package = remote_package(data)
        metadata = {"package": PACKAGE, "revision": revision(package), "repository": REPOSITORY,
                    "archive_checksum": package.findtext("archives/archive/complete/checksum"),
                    "command_tools_sha256": hashlib.sha256((tools / "source.properties").read_bytes()).hexdigest(),
                    "preparer_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                    "runner_image": os.environ.get("ImageOS"), "runner_arch": os.environ.get("RUNNER_ARCH"),
                    "sdk_path": str(sdk), "avd_home": str(Path.home() / ".android/avd"),
                    "avd": AVD, "profile": "pixel_2", "hardware": HARDWARE}
        key = "flint-api36-pristine-v1-" + hashlib.sha256(json.dumps(metadata, sort_keys=True).encode()).hexdigest()
        metadata["cache_key"] = key
        metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            output.write(f"key={key}\nimage-path={image}\n")
        print(json.dumps(metadata, indent=2))
        return
    metadata = json.loads(metadata_path.read_text())
    install_image(tools / "bin/sdkmanager", image, metadata["revision"])
    avd_home = Path.home() / ".android/avd"
    avd_home.mkdir(parents=True, exist_ok=True)
    os.environ["ANDROID_AVD_HOME"] = str(avd_home)
    avd_dir = avd_home / f"{AVD}.avd"
    if not avd_dir.exists():
        # avdmanager's hardware-profile prompt expects 'no', not sdkmanager's 'yes'.
        status = command([str(tools / "bin/avdmanager"), "create", "avd", "--force",
                          "--name", AVD, "--package", PACKAGE, "--device", "pixel_2"],
                         RESULTS / "avd-create.log", timeout=60, input_data=b"no\n")
        if status != 0:
            raise RuntimeError(f"AVD creation failed with exit {status}; no retry")
    if not (avd_dir / "config.ini").is_file() or not (avd_home / f"{AVD}.ini").is_file():
        raise RuntimeError("Incomplete cached AVD; bump cache key after inspecting setup artifacts")
    configure_avd(avd_dir / "config.ini")
    print("Verified image and pristine unbooted AVD; cache is saved before tests")


if __name__ == "__main__":
    main()
