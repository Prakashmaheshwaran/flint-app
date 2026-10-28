#!/usr/bin/env python3
"""Bounded Flint cleanup. No global cache eviction or implicit device ownership.

Device claims live in each checkout's Git admin directory, outside the source tree.
Orca 1.4.241's emulator list is global even with --worktree; it is diagnostic only.
"""
import argparse
import contextlib
import datetime
import fcntl
import hashlib
import inspect
import json
import os
from pathlib import Path
import plistlib
import re
import resource
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import uuid


class Unsafe(RuntimeError):
    pass


class Deadline(Unsafe):
    pass


def expired(*_):
    raise Deadline("cleanup deadline reached; remaining resources preserved")


def log(message):
    print(f"{datetime.datetime.now(datetime.timezone.utc).isoformat()} {message}", flush=True)


def claimant(event=None):
    for key in ("ORCA_TERMINAL_HANDLE", "ORCA_PANE_KEY", "CODEX_THREAD_ID"):
        if os.environ.get(key):
            return os.environ[key]
    return ((event or {}).get("session_id") or os.environ.get("FLINT_DEVICE_SESSION_ID")
            or os.environ.get("CLAUDE_SESSION_ID") or None)


def run(argv, root, *, required=False, timeout=8):
    try:
        with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
            result = subprocess.run(argv, cwd=root, stdout=output, stderr=errors, timeout=timeout,
                                    preexec_fn=lambda: resource.setrlimit(resource.RLIMIT_FSIZE, (1048576, 1048576)))
            output.seek(0)
            errors.seek(0)
            stdout = output.read(1048576).decode(errors="replace")
            stderr = errors.read(1048576).decode(errors="replace")
        if result.returncode == 0:
            return stdout
        detail = stderr.strip()[:300] or f"exit {result.returncode}"
    except (OSError, subprocess.TimeoutExpired) as exc:
        detail = str(exc)
    if required:
        raise Unsafe(f"{argv[0]}: {detail}")
    print(f"SKIP {argv[0]}: {detail}", file=sys.stderr)
    return None


def git(root, *args):
    return run(["git", *args], root, required=True).strip()


def checked_root(path):
    root = Path(path).resolve()
    if (not (root / "ios-app/project.yml").is_file()
            or not (root / "android-app/settings.gradle.kts").is_file()
            or Path(git(root, "rev-parse", "--show-toplevel")).resolve() != root):
        raise Unsafe(f"not a Flint checkout root: {root}")
    return root


def git_path(root, name):
    path = Path(git(root, "rev-parse", "--git-path", name))
    return path if path.is_absolute() else root / path


def ledger_path(root):
    # Not a dotfile in the checkout: linked worktrees have distinct admin dirs.
    return git_path(root, "flint-cleanup/devices.json")


def prefix(root):
    return "flint-wt-" + hashlib.sha256(str(root).encode()).hexdigest()[:12] + "-"


def read_ledger(root):
    path = ledger_path(root)
    if not path.exists():
        return []
    if path.is_symlink() or path.parent.is_symlink():
        raise Unsafe(f"symlinked device ledger: {path}")
    try:
        data = json.loads(path.read_text())
        if data["version"] != 1 or data["worktree"] != str(root):
            raise ValueError("wrong version or worktree")
        entries = data["devices"]
        if not isinstance(entries, list):
            raise ValueError("devices is not a list")
        keys = set()
        identities = {}
        for entry in entries:
            validate_entry(root, entry)
            key = (entry["platform"], entry["id"], entry["claimant"])
            if key in keys:
                raise ValueError("duplicate device")
            keys.add(key)
            identity = (entry["name"], entry.get("avd"))
            if key[:2] in identities and identities[key[:2]] != identity:
                raise ValueError("conflicting device identities across claimants")
            identities[key[:2]] = identity
        return entries
    except (ValueError, KeyError, TypeError) as exc:
        raise Unsafe(f"invalid device ledger {path}: {exc}") from exc


def validate_entry(root, entry):
    platform, ident = entry["platform"], entry["id"]
    if platform == "ios":
        if not isinstance(ident, str) or not re.fullmatch(r"[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", ident):
            raise ValueError("invalid iOS UDID")
        if entry["created"] and not entry["name"].startswith(prefix(root)):
            raise ValueError("created simulator lacks this worktree's name prefix")
    elif platform == "android":
        if not isinstance(ident, str) or not re.fullmatch(r"emulator-[0-9]+", ident):
            raise ValueError("only Android emulator serials can be claimed")
        if not isinstance(entry.get("avd"), str) or not re.fullmatch(r"[A-Za-z0-9_.-]+", entry["avd"]):
            raise ValueError("Android claims require the exact AVD name")
    else:
        raise ValueError("unknown platform")
    if type(entry["created"]) is not bool or type(entry["booted"]) is not bool:
        raise ValueError("created/booted must be booleans")
    if not isinstance(entry["claimant"], str) or not entry["claimant"]:
        raise ValueError("claimant must be a nonempty session identifier")
    token = entry.get("incarnation")
    if token is not None and (not isinstance(token, str) or not re.fullmatch(r"v1:[0-9a-f]{64}", token)):
        raise ValueError("invalid incarnation token")
    evidence = entry.get("incarnation_evidence")
    if evidence is not None and (not isinstance(evidence, dict) or token_for(evidence) != token):
        raise ValueError("incarnation evidence does not match its digest")
    helper = entry.get("helper")
    if helper is not None and (not isinstance(helper, dict) or type(helper.get("pid")) is not int
                               or helper["pid"] <= 1 or not isinstance(helper.get("start"), str)
                               or not isinstance(helper.get("command"), str)):
        raise ValueError("invalid helper incarnation")


def write_ledger(root, entries):
    path = ledger_path(root)
    path.parent.mkdir(mode=0o700, exist_ok=True)
    if path.parent.is_symlink() or path.is_symlink():
        raise Unsafe("refusing symlinked device ledger")
    fd, tmp = tempfile.mkstemp(prefix="devices-", suffix=".json", dir=path.parent)
    try:
        with os.fdopen(fd, "w") as stream:
            json.dump({"version": 1, "worktree": str(root), "devices": entries}, stream, indent=2)
            stream.write("\n")
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


@contextlib.contextmanager
def claims_lock(root):
    # One lock, no shared registry: prevent a sibling claim racing shutdown.
    common = Path(git(root, "rev-parse", "--git-common-dir"))
    if not common.is_absolute():
        common = root / common
    fd = os.open(common / "flint-device-claims.lock",
                 os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    try:
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise Unsafe("another worktree is updating device claims; retry cleanup") from exc
        yield
    finally:
        os.close(fd)


def worktrees(root):
    raw = run(["git", "worktree", "list", "--porcelain", "-z"], root, required=True)
    return [Path(field[9:]).resolve() for field in raw.split("\0") if field.startswith("worktree ")]


def siblings(root):
    entries = []
    for path in worktrees(root):
        if path != root and path.is_dir():
            # Any unreadable/malformed sibling ledger blocks all device mutations.
            entries.extend(read_ledger(path))
    return entries


def orca_command():
    return os.environ.get("ORCA_CLI_COMMAND") or "orca"


def sim_devices(root):
    raw = run(["xcrun", "simctl", "list", "devices", "--json"], root)
    if raw is None:
        return None
    try:
        data = json.loads(raw)["devices"]
        return {d["udid"]: d for devices in data.values() for d in devices}
    except (ValueError, KeyError, TypeError, AttributeError):
        print("SKIP unreadable simctl inventory", file=sys.stderr)
        return None


def android_avd(root, ident):
    raw = run(["adb", "-s", ident, "emu", "avd", "name"], root)
    return raw.splitlines()[0].strip() if raw else None


def token_for(value):
    return "v1:" + hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def helper_incarnation(root, ident, expected_pid=None):
    raw = run([orca_command(), "emulator", "list", "--worktree", f"path:{root}", "--json"], root)
    try:
        helper = json.loads(raw)["result"]
        pid = helper["pid"]
        if (helper.get("running") is not True or helper.get("device") != ident
                or type(pid) is not int or pid <= 1 or (expected_pid is not None and pid != expected_pid)):
            return None
    except (ValueError, KeyError, TypeError):
        return None
    process = run(["ps", "-p", str(pid), "-o", "lstart=,comm="], root)
    fields = (process or "").strip().split(None, 5)
    if len(fields) != 6:
        return None
    return {"pid": pid, "start": " ".join(fields[:5]), "command": fields[5]}


def observe_incarnation(root, platform, ident, avd=None, device=None, pid=None, process_group=None):
    """Read an actual boot epoch; absence never authorizes a device mutation.

    iOS inventory may omit lastBootedAt on older tools/never-booted devices.
    Android guest boot_id alone is unsafe because Quick Boot restores it. Bind
    it to a uniquely identified host emulator PID and process start time too.
    Unrecognizable process arguments deliberately disable automatic shutdown.
    """
    if platform == "ios":
        if device is None:
            device = (sim_devices(root) or {}).get(ident)
        epoch = (device or {}).get("lastBootedAt")
        if isinstance(epoch, str):
            try:
                parsed = datetime.datetime.fromisoformat(epoch.replace("Z", "+00:00"))
                if parsed.tzinfo is not None and parsed.year >= 2000:
                    return {"platform": "ios", "id": ident, "name": device.get("name"),
                            "kind": "simctl-boot-epoch", "boot_epoch": epoch}
            except ValueError:
                pass
        if not device or device.get("state") != "Booted":
            return None
        manager = run(["xcrun", "simctl", "spawn", ident, "launchctl", "managerpid"], root)
        if not manager or not manager.strip().isdigit() or int(manager.strip()) <= 1:
            return None
        process = run(["ps", "-p", manager.strip(), "-o", "lstart=,comm="], root)
        fields = (process or "").strip().split(None, 5)
        if len(fields) != 6 or Path(fields[5]).name != "launchd_sim":
            return None
        return {"platform": "ios", "id": ident, "kind": "launchd_sim-process",
                "pid": int(manager.strip()), "start": " ".join(fields[:5])}
    if not re.fullmatch(r"emulator-[0-9]+", ident) or not avd or android_avd(root, ident) != avd:
        return None
    boot_id = run(["adb", "-s", ident, "shell", "cat", "/proc/sys/kernel/random/boot_id"], root)
    if not boot_id or not re.fullmatch(r"[0-9a-fA-F-]{36}", boot_id.strip()):
        return None
    processes = run(["ps", "-axo", "pid=,pgid=,lstart=,command="], root)
    matches = []
    for line in (processes or "").splitlines():
        fields = line.split(None, 7)
        if len(fields) != 8 or not fields[0].isdigit() or not fields[1].isdigit():
            continue
        try:
            argv = shlex.split(fields[7])
            executable = Path(argv[0]).name
            if not (executable == "emulator" or executable.startswith("qemu-system-")):
                continue
            exact_avd = argv[argv.index("-avd") + 1]
            if "-port" in argv:
                port = argv[argv.index("-port") + 1]
            else:
                port = argv[argv.index("-ports") + 1].split(",")[0]
            if exact_avd == avd and port == ident.removeprefix("emulator-"):
                matches.append([fields[0], fields[1], " ".join(fields[2:7]), exact_avd, port])
        except (ValueError, IndexError):
            continue
    if len(matches) != 1 or (pid is not None and str(pid) != matches[0][0]):
        return None
    if process_group is not None and str(process_group) != matches[0][1]:
        return None
    process = matches[0]
    return {"platform": "android", "id": ident, "avd": avd, "kind": "emulator-process",
            "pid": int(process[0]), "process_group": int(process[1]), "start": process[2],
            "boot_id": boot_id.strip()}


def incarnation(root, platform, ident, avd=None, device=None, pid=None, process_group=None):
    evidence = observe_incarnation(root, platform, ident, avd, device, pid, process_group)
    return token_for(evidence) if evidence is not None else None


def claim(root, args):
    entry = {"platform": args.platform, "id": args.id, "created": args.created,
             "booted": args.booted, "avd": args.avd, "name": "",
             "claimant": args.session or claimant() or "manual",
             "claimed_at": datetime.datetime.now(datetime.timezone.utc).isoformat()}
    if args.platform == "ios":
        devices = sim_devices(root)
        if devices is None or args.id not in devices:
            raise Unsafe("cannot verify the simulator being claimed")
        entry["name"] = devices[args.id]["name"]
    elif args.avd != android_avd(root, args.id):
        raise Unsafe("AVD name does not match the emulator serial")
    evidence = observe_incarnation(root, args.platform, args.id, args.avd, pid=getattr(args, "pid", None),
                                   process_group=getattr(args, "process_group", None))
    observed = token_for(evidence) if evidence is not None else None
    supplied = getattr(args, "incarnation", None)
    if supplied is not None and supplied != observed:
        raise Unsafe("incarnation token is unavailable or changed; claim not granted")
    if args.booted and (supplied is None or observed is None):
        raise Unsafe("--booted requires --incarnation from this proven boot instance")
    entry["incarnation"] = observed
    entry["incarnation_evidence"] = evidence
    helper_pid = getattr(args, "helper_pid", None)
    entry["helper"] = helper_incarnation(root, args.id, helper_pid) if helper_pid else None
    if helper_pid and entry["helper"] is None:
        raise Unsafe("cannot verify the attached helper PID and device")
    validate_entry(root, entry)
    with claims_lock(root):
        current = read_ledger(root)
        key = (args.platform, args.id, entry["claimant"])
        old = next((e for e in current if (e["platform"], e["id"], e["claimant"]) == key), None)
        if old:
            if old.get("avd") != entry["avd"] or old["name"] != entry["name"]:
                raise Unsafe("device identity changed; release the stale claim first")
            # Rights never cross a restart, including a same-session reclaim.
            if observed is not None and old.get("incarnation") == observed:
                entry["created"] |= old["created"]
                entry["booted"] |= old["booted"]
                if entry["helper"] is None and old.get("helper") == helper_incarnation(root, args.id):
                    entry["helper"] = old.get("helper")
        current = [e for e in current if (e["platform"], e["id"], e["claimant"]) != key]
        write_ledger(root, current + [entry])
    print(f"Claimed {args.platform} {args.id}; created={entry['created']} booted={entry['booted']}")


def release(root, platform, ident, session=None):
    with claims_lock(root):
        current = read_ledger(root)
        owner = session or claimant() or "manual"
        write_ledger(root, [e for e in current if (e["platform"], e["id"], e["claimant"]) != (platform, ident, owner)])
    print(f"Released claim for {platform} {ident} (no device action)")


def rollback(root, args):
    """Undo only a proven own boot when failed attach produced no usable claim."""
    with claims_lock(root):
        claims = read_ledger(root) + siblings(root)
        if any((e["platform"], e["id"]) == (args.platform, args.id)
               or (args.platform == "android" and e["platform"] == "android" and e["avd"] == args.avd)
               for e in claims):
            raise Unsafe("rollback refused: device/AVD is claimed; preserve peers")
        observed = incarnation(root, args.platform, args.id, args.avd, pid=args.pid, process_group=args.process_group)
        if observed is None or observed != args.incarnation:
            raise Unsafe("rollback refused: boot incarnation changed or unavailable")
        if args.platform == "ios":
            fresh = (sim_devices(root) or {}).get(args.id)
            if fresh is None or fresh.get("state") != "Booted":
                raise Unsafe("rollback refused: simulator is not booted")
            command = ["xcrun", "simctl", "shutdown", args.id]
        else:
            command = ["adb", "-s", args.id, "emu", "kill"]
        if incarnation(root, args.platform, args.id, args.avd, pid=args.pid,
                       process_group=args.process_group) != args.incarnation:
            raise Unsafe("rollback refused: incarnation changed before action")
        run(command, root, required=True)


def clean_devices(root, dry_run=False, session=None):
    with claims_lock(root):
        current = read_ledger(root)
        if not current:
            print("No claimed devices; unclaimed devices/helpers are left alone.")
            return
        other = siblings(root)
        # Inspect Orca for diagnostics only; never interpret its list as ownership.
        run([orca_command(), "emulator", "list", "--worktree", f"path:{root}", "--json"], root)
        sims = sim_devices(root) if any(e["platform"] == "ios" for e in current) else None
        for entry in list(current):
            if entry not in current:
                continue
            if session and entry["claimant"] != session:
                continue
            platform, ident = entry["platform"], entry["id"]
            local_claims = [e for e in current if (e["platform"], e["id"]) == (platform, ident)]
            matching = [e for e in local_claims if entry.get("incarnation") is not None
                        and e.get("incarnation") == entry["incarnation"]]
            entry["created"] = any(e["created"] for e in matching)
            entry["booted"] = any(e["booted"] for e in matching)
            remaining_here = [e for e in current if e is not entry and session
                              and e["claimant"] != session]
            shared = any((e["platform"], e["id"]) == (platform, ident)
                         or (platform == "android" and e["platform"] == "android" and e["avd"] == entry["avd"])
                         for e in other + remaining_here)
            if shared:
                print(f"KEEP shared {platform} {ident}; release only this worktree's claim")
                if not dry_run:
                    # Keep worktree creation/boot provenance for the last local
                    # claimant; never transfer deletion rights to another worktree.
                    for remaining in remaining_here:
                        if ((remaining["platform"], remaining["id"]) == (platform, ident)
                                and remaining.get("incarnation") == entry.get("incarnation")
                                and entry.get("incarnation") is not None):
                            remaining["created"] |= entry["created"]
                            remaining["booted"] |= entry["booted"]
                            if remaining.get("helper") is None:
                                remaining["helper"] = entry.get("helper")
                    current.remove(entry)
                    write_ledger(root, current)
                continue
            observed = incarnation(root, platform, ident, entry.get("avd"))
            device_matches = (observed is not None and observed == entry.get("incarnation")
                              and entry.get("incarnation_evidence") is not None)
            helper_matches = (entry.get("helper") is not None
                              and entry["helper"] == helper_incarnation(root, ident))
            if not helper_matches:
                print(f"KEEP unproven helper for {ident}; no matching helper PID/start provenance")
            if not device_matches:
                print(f"KEEP {platform} {ident}: boot incarnation unavailable or changed")
                if not helper_matches:
                    continue
            commands = []
            if helper_matches:
                commands.append([orca_command(), "emulator", "kill", "--worktree", f"path:{root}", "--device", ident, "--json"])
            if platform == "ios" and device_matches:
                if sims is None:
                    continue
                device = sims.get(ident)
                if device and device["name"] != entry["name"]:
                    print(f"KEEP renamed simulator {ident}")
                    continue
                if device:
                    if (entry["booted"] or entry["created"]) and device.get("state") == "Booted":
                        commands.append(["xcrun", "simctl", "shutdown", ident])
                    if entry["created"]:
                        commands.append(["xcrun", "simctl", "delete", ident])
            elif platform == "android" and device_matches:
                if android_avd(root, ident) != entry["avd"]:
                    print(f"KEEP {ident}: AVD identity unavailable or changed (expected {entry['avd']})")
                    continue
                if entry["booted"]:
                    # Never avdmanager delete, wipe-data, kill-server, or touch AVD files.
                    commands.append(["adb", "-s", ident, "emu", "kill"])
            success = True
            shutdown_owned = False
            for command in commands:
                log(("WOULD " if dry_run else "RUN ") + shlex.join(command))
                if not dry_run:
                    is_helper = command[0] == orca_command()
                    is_delete = command[:3] == ["xcrun", "simctl", "delete"]
                    fresh = (sim_devices(root) or {}).get(ident) if shutdown_owned and is_delete else None
                    same_stopped_device = (fresh is not None and fresh.get("state") == "Shutdown"
                                           and all(fresh.get(k) == device.get(k) for k in
                                                   ("udid", "name", "deviceTypeIdentifier", "dataPath")))
                    verified = (helper_incarnation(root, ident) == entry["helper"] if is_helper else
                                same_stopped_device if shutdown_owned and is_delete else
                                incarnation(root, platform, ident, entry.get("avd")) == entry["incarnation"])
                    if not verified:
                        print(f"KEEP {ident}: incarnation changed immediately before action")
                        success = False
                        break
                    if run(command, root) is None:
                        success = False
                        # Do not power off/delete after an unverified helper stop.
                        break
                    if command[:3] == ["xcrun", "simctl", "shutdown"]:
                        shutdown_owned = True
            if success and not dry_run:
                current = [e for e in current if not ((e["platform"], e["id"]) == (platform, ident)
                           and (not session or e["claimant"] == session))]
                write_ledger(root, current)


def inside(path, root):
    try:
        return path.resolve().is_relative_to(root) and path.resolve() != root
    except OSError:
        return False


def identity(info):
    return [info.st_dev, info.st_ino]


class Directory:
    """Hold every path component open; never traverse a symlink during deletion.

    A parent dirfd with a multi-component relative path is NOT sufficient: the
    kernel would follow intermediate symlinks. Keep and revalidate the complete
    chain, including the selected leaf, and pass rmtree one basename only.
    """
    def __init__(self, path):
        self.path = Path(path)
        self.fds = []
        self.names = []

    def __enter__(self):
        if not self.path.is_absolute() or ".." in self.path.parts:
            raise Unsafe(f"noncanonical cleanup directory: {self.path}")
        if (not getattr(shutil.rmtree, "avoids_symlink_attacks", False)
                or "dir_fd" not in inspect.signature(shutil.rmtree).parameters):
            raise Unsafe("Python lacks fd-safe shutil.rmtree; artifacts preserved")
        try:
            self.fds.append(os.open("/", os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW))
            for component in self.path.parts[1:]:
                self.fds.append(os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                                        dir_fd=self.fds[-1]))
                self.names.append(component)
            self.recheck()
            return self
        except BaseException:
            self.close()
            raise

    @property
    def fd(self):
        return self.fds[-1]

    @property
    def key(self):
        return identity(os.fstat(self.fd))

    def recheck(self):
        for index, name in enumerate(self.names):
            observed = os.stat(name, dir_fd=self.fds[index], follow_symlinks=False)
            opened = os.fstat(self.fds[index + 1])
            if not stat.S_ISDIR(observed.st_mode) or identity(observed) != identity(opened):
                raise Unsafe(f"cleanup directory changed: {self.path}")

    def read(self, name, limit=1048576):
        if Path(name).name != name:
            raise Unsafe("metadata must be a basename")
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=self.fd)
        with os.fdopen(fd, "rb") as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                raise Unsafe("cleanup metadata is not a regular file")
            data = stream.read(limit + 1)
            if len(data) > limit:
                raise Unsafe("cleanup metadata exceeds size limit")
            return data

    def remove(self):
        self.recheck()
        parent = self.fds[-2]
        name = self.names[-1]
        # Atomically quarantine the leaf before recursive deletion. Verify the
        # moved inode against the descriptor approved above: a last-moment leaf
        # replacement is preserved, never fed to rmtree. The private directory
        # also removes the original name from subsequent replacement races.
        quarantine = ".flint-clean-" + uuid.uuid4().hex
        os.mkdir(quarantine, mode=0o700, dir_fd=parent)
        qfd = os.open(quarantine, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
        moved = False
        try:
            self.recheck()
            os.rename(name, "artifact", src_dir_fd=parent, dst_dir_fd=qfd)
            moved = True
            observed = os.stat("artifact", dir_fd=qfd, follow_symlinks=False)
            if not stat.S_ISDIR(observed.st_mode) or identity(observed) != self.key:
                raise Unsafe(f"artifact replaced before deletion; preserved in {quarantine}/artifact")
            shutil.rmtree("artifact", dir_fd=qfd)
            moved = False
        finally:
            os.close(qfd)
            if not moved:
                os.rmdir(quarantine, dir_fd=parent)

    def close(self):
        for fd in reversed(self.fds):
            os.close(fd)
        self.fds.clear()

    def __exit__(self, *_):
        self.close()


def canonical_artifact(path, root):
    path = Path(path)
    if not path.is_absolute():
        path = root / path
    # Reject dot-dot even if normalization would end inside the checkout. This
    # also avoids silently normalizing away a symlink preceding that component.
    if ".." in path.parts or not path.is_relative_to(root) or path == root:
        raise Unsafe(f"artifact must be a canonical directory inside the worktree: {path}")
    protected = [root / "ios-app", root / "android-app", root / "scripts",
                 git_path(root, ".").resolve()]
    common = Path(git(root, "rev-parse", "--git-common-dir"))
    protected.append((root / common).resolve())
    if (".git" in path.relative_to(root).parts or path in protected[:3]
            or any(path == p or path.is_relative_to(p) or p.is_relative_to(path)
                   for p in protected[3:])):
        raise Unsafe(f"protected source or Git-admin directory: {path}")
    # Check even absent targets: an existing symlink ancestor must never be
    # accepted merely because its current leaf is missing.
    current = root
    for component in path.relative_to(root).parts:
        current /= component
        if current.is_symlink():
            raise Unsafe(f"symlink component in artifact: {current}")
    if path.exists() and not path.is_dir():
        raise Unsafe(f"artifact is not a directory: {path}")
    return path


def size_bytes(path, root):
    raw = run(["du", "-sk", str(path)], root)
    try:
        return int(raw.split()[0]) * 1024 if raw else 0
    except (ValueError, IndexError):
        return 0


def human_size(count):
    return f"{count / (1024 * 1024):.1f} MiB ({count} bytes)"


def artifact_paths(root, derived):
    candidates = []
    for top in ("ios-app", "android-app"):
        base = root / top
        for directory, dirs, _ in os.walk(base, followlinks=False):
            for name in list(dirs):
                path = Path(directory) / name
                artifact = (name in {"build", ".build", "DerivedData", ".swiftpm", ".gradle", ".kotlin", ".cxx", ".externalNativeBuild"}
                            or (top == "ios-app" and name.endswith((".xcodeproj", ".xcworkspace", ".xcresult"))))
                if artifact:
                    candidates.append(path)
                if artifact or path.is_symlink() or name in {".git", "node_modules"}:
                    dirs.remove(name)
    for name in (".build", ".swiftpm", ".kotlin", "android-app/captures"):
        candidates.append(root / name)
    for directory, dirs, _ in os.walk(root / "scripts", followlinks=False):
        for name in list(dirs):
            if name == "__pycache__":
                candidates.append(Path(directory) / name)
                dirs.remove(name)
    custom = canonical_artifact(derived, root)
    candidates.append(custom)
    selected = []
    for path in sorted(set(candidates), key=lambda p: (len(p.parts), str(p))):
        path = canonical_artifact(path, root)
        if path.exists() or path.is_symlink():
            if not any(path.is_relative_to(parent) for parent in selected):
                selected.append(path)
    return selected


def clean_artifacts(root, derived):
    tracked = [root / p for p in run(["git", "ls-files", "-z"], root, required=True).split("\0") if p]
    freed = 0
    paths = artifact_paths(root, derived)
    # Validate the whole candidate set before the first deletion.
    for path in paths:
        if any(p == path or p.is_relative_to(path) for p in tracked):
            raise Unsafe(f"refusing artifact containing tracked source: {path}")
    with contextlib.ExitStack() as stack:
        directories = [stack.enter_context(Directory(path)) for path in paths]
        for directory in directories:
            count = size_bytes(directory.path, root)
            directory.recheck()
            print(f"REMOVE {directory.path.relative_to(root)} {human_size(count)}", flush=True)
            directory.remove()
            freed += count
    print(f"Removed {human_size(freed)} of worktree artifacts; local.properties and shared caches preserved.")


def workspace_checkout(workspace):
    # Xcode records a generated project/workspace, which make clean may remove.
    # An absent .xcodeproj is NOT proof that the checkout itself is gone.
    path = Path(workspace)
    if not path.is_absolute() or ".." in path.parts:
        return None
    for ancestor in [path, *path.parents]:
        if ancestor.name == "ios-app":
            return ancestor.parent
    return None


def live_checkouts(root):
    live = worktrees(root)
    raw = run([orca_command(), "worktree", "list", "--json"], root, required=True)
    try:
        live.extend(Path(w["path"]).resolve() for w in json.loads(raw)["result"]["worktrees"])
    except (ValueError, KeyError, TypeError):
        raise Unsafe("cannot interpret Orca worktree inventory")
    return live


def orphan_artifact(directory, live):
    data = directory.read("info.plist")
    workspace = plistlib.loads(data)["WorkspacePath"]
    checkout = workspace_checkout(workspace)
    if (checkout is None or checkout.exists() or checkout.is_symlink()
            or checkout.resolve() in live or Path(workspace).exists()):
        raise Unsafe(f"live/unknown checkout for {directory.path.name}: {checkout}")
    directory.recheck()
    return {"path": str(directory.path), "identity": directory.key,
            "ancestors": [identity(os.fstat(fd)) for fd in directory.fds],
            "workspace": workspace, "metadata_sha256": hashlib.sha256(data).hexdigest()}


def plan_digest(plan):
    return hashlib.sha256(json.dumps(plan, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def write_plan(path, plan):
    path = Path(os.path.abspath(path))
    with Directory(path.parent) as parent:
        name = ".flint-plan-" + uuid.uuid4().hex
        fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent.fd)
        try:
            with os.fdopen(fd, "w") as stream:
                json.dump(plan, stream, sort_keys=True, indent=2)
                stream.write("\n")
            parent.recheck()
            # link is atomic and refuses an existing destination (including a
            # symlink). A plan filename must never overwrite checkout source or
            # a previously reviewed approval, even in a concurrent preview.
            os.link(name, path.name, src_dir_fd=parent.fd, dst_dir_fd=parent.fd, follow_symlinks=False)
        finally:
            try:
                os.unlink(name, dir_fd=parent.fd)
            except FileNotFoundError:
                pass


def orphan_preview(root, plan_path=None, allowed=()):
    print("DRY RUN; deletion requires --confirm --plan FILE --plan-digest SHA256")
    live = live_checkouts(root)
    plan = {"version": 1, "worktree": str(root), "artifacts": [], "simulators": [],
            "approved_legacy_udids": sorted(set(allowed))}
    host = Path.home() / "Library/Developer/Xcode/DerivedData"
    if host.exists() or host.is_symlink():
        with Directory(host) as host_directory:
            names = sorted(n for n in os.listdir(host_directory.fd) if n.startswith("Flint-"))
            for name in names:
                path = host / name
                try:
                    with Directory(path) as directory:
                        record = orphan_artifact(directory, live)
                        count = size_bytes(path, root)
                        directory.recheck()
                        plan["artifacts"].append(record)
                except (OSError, ValueError, KeyError, TypeError, Unsafe) as exc:
                    print(f"KEEP {path.name}: {exc}")
                    continue
                print(f"WOULD REMOVE {path} {human_size(count)} workspace={record['workspace']}")
    devices = sim_devices(root)
    if devices is None:
        raise Unsafe("orphan inventory unavailable; no approval plan produced")
    claimed = read_ledger(root) + siblings(root)
    eligible = set()
    for ident, device in sorted(devices.items()):
        if not re.fullmatch(r"flint-review-[0-9]+", device.get("name", "")):
            continue
        if device.get("state") != "Shutdown" or any(e["id"] == ident for e in claimed):
            print(f"KEEP active/claimed {device['name']} {ident}")
            continue
        eligible.add(ident)
        if ident not in allowed:
            print(f"KEEP name-only candidate {device['name']} {ident}; explicit --allow-legacy-udid required")
            continue
        plan["simulators"].append({"id": ident, "identity": device,
                                   "provenance": "explicit-legacy-udid"})
        print(f"WOULD DELETE simulator {device['name']} {ident} (explicit exact UDID)")
    if set(allowed) - eligible:
        raise Unsafe("an explicitly selected legacy UDID is absent, active, claimed, or not a legacy candidate")
    digest = plan_digest(plan)
    if plan_path:
        write_plan(plan_path, plan)
    print(f"PLAN_DIGEST={digest}")
    return plan


def clean_orphans(root, confirm=False, plan_path=None, digest=None, allowed=()):
    if not confirm:
        if digest:
            raise Unsafe("--plan-digest applies only to confirmation")
        return orphan_preview(root, plan_path, allowed)
    if not plan_path or not digest or allowed:
        raise Unsafe("confirmation requires --plan FILE --plan-digest SHA256; select exact UDIDs in the preview")
    path = Path(os.path.abspath(plan_path))
    with Directory(path.parent) as parent:
        plan = json.loads(parent.read(path.name))
    if plan_digest(plan) != digest or plan.get("version") != 1 or plan.get("worktree") != str(root):
        raise Unsafe("orphan plan digest, version or originating worktree mismatch")
    artifacts = plan.get("artifacts")
    simulators = plan.get("simulators")
    if not isinstance(artifacts, list) or not isinstance(simulators, list):
        raise Unsafe("malformed orphan plan")
    selected = [d["id"] for d in simulators]
    if sorted(set(selected)) != plan.get("approved_legacy_udids") or len(set(selected)) != len(selected):
        raise Unsafe("orphan plan lacks exact-UDID approval provenance")
    host = Path.home() / "Library/Developer/Xcode/DerivedData"
    live = live_checkouts(root)
    with contextlib.ExitStack() as stack:
        directories = []
        for record in artifacts:
            target = Path(record["path"])
            if target.parent != host or not target.name.startswith("Flint-"):
                raise Unsafe("orphan plan artifact is outside the permitted host directory")
            directory = stack.enter_context(Directory(target))
            if orphan_artifact(directory, live) != record:
                raise Unsafe(f"orphan artifact changed since approval: {target}")
            directories.append((directory, record))
        # Preflight the complete approved set before the first mutation. Rescans
        # are for validation only; they never add objects to the approved plan.
        def verify_simulator(record):
            ident = record["id"]
            if (not re.fullmatch(r"[A-Fa-f0-9]{8}(?:-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}", ident)
                    or record.get("provenance") != "explicit-legacy-udid"
                    or not re.fullmatch(r"flint-review-[0-9]+", record["identity"].get("name", ""))):
                raise Unsafe("unapproved orphan simulator identity")
            fresh = sim_devices(root)
            claimed = read_ledger(root) + siblings(root)
            if (fresh is None or fresh.get(ident) != record["identity"]
                    or fresh[ident].get("state") != "Shutdown" or any(e["id"] == ident for e in claimed)):
                raise Unsafe(f"orphan simulator changed/claimed since approval: {ident}")
        for record in simulators:
            verify_simulator(record)
        for directory, record in directories:
            count = size_bytes(directory.path, root)
            if orphan_artifact(directory, live_checkouts(root)) != record:
                raise Unsafe(f"orphan artifact changed before deletion: {directory.path}")
            print(f"REMOVE {directory.path} {human_size(count)}")
            directory.remove()
        for record in simulators:
            verify_simulator(record)
            run(["xcrun", "simctl", "delete", record["id"]], root, required=True)


def queue_session_cleanup(root):
    """Return within Codex's 3s SessionEnd budget; the child owns a 55s budget.

    A detached child with closed inherited pipes survives a hook runner's exit.
    The shared claims lock makes overlapping hooks/explicit cleanup fail closed.
    """
    try:
        event = json.loads(sys.stdin.read(65536))
        cwd = Path(event["cwd"]).resolve()
        if event.get("hook_event_name") != "SessionEnd" or not (cwd == root or inside(cwd, root)):
            raise ValueError("not SessionEnd for this checkout")
    except (ValueError, KeyError, TypeError) as exc:
        print(f"Session cleanup skipped: {exc}")
        return
    owner = claimant(event)
    if not owner or not any(e["claimant"] == owner for e in read_ledger(root)):
        print("Session cleanup: no claimed devices.")
        return
    log = ledger_path(root).parent / "session-cleanup.log"
    fd = os.open(log, os.O_CREAT | os.O_WRONLY | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as stream:
        child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--root", str(root),
                                  "--deadline", "55", "session-devices", "--session", owner], cwd=root,
                                 stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT,
                                 start_new_session=True, close_fds=True,
                                 preexec_fn=lambda: resource.setrlimit(resource.RLIMIT_FSIZE, (1048576, 1048576)))
    print(f"Session cleanup queued (pid {child.pid}); log: {log}")


def remember_claude_session(root):
    """Persist only the session ID in Claude's documented per-session env file."""
    target = os.environ.get("CLAUDE_ENV_FILE")
    if not target:
        return
    event = json.loads(sys.stdin.read(65536))
    owner = event.get("session_id")
    cwd = Path(event["cwd"]).resolve()
    if (event.get("hook_event_name") != "SessionStart" or not isinstance(owner, str) or not owner
            or not (cwd == root or inside(cwd, root))):
        raise Unsafe("invalid Claude SessionStart identity")
    fd = os.open(target, os.O_CREAT | os.O_WRONLY | os.O_APPEND | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as stream:
        stream.write(f"export FLINT_DEVICE_SESSION_ID={shlex.quote(owner)}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[1]))
    parser.add_argument("--deadline", type=int, default=100)
    sub = parser.add_subparsers(dest="mode", required=True)
    for mode in ("artifacts", "archive"):
        sub.add_parser(mode).add_argument("--derived-data", default="ios-app/DerivedData")
    for mode in ("devices", "session-devices"):
        part = sub.add_parser(mode)
        part.add_argument("--dry-run", action="store_true")
        part.add_argument("--session")
    orphan = sub.add_parser("orphans")
    orphan.add_argument("--confirm", action="store_true")
    orphan.add_argument("--plan")
    orphan.add_argument("--plan-digest")
    orphan.add_argument("--allow-legacy-udid", action="append", default=[])
    for mode in ("incarnation", "rollback"):
        boot = sub.add_parser(mode)
        boot.add_argument("platform", choices=("ios", "android"))
        boot.add_argument("id")
        boot.add_argument("--avd")
        boot.add_argument("--pid", type=int)
        boot.add_argument("--process-group", type=int)
        if mode == "rollback":
            boot.add_argument("--incarnation", required=True)
    sub.add_parser("prefix")
    sub.add_parser("session-end")
    sub.add_parser("session-start")
    sub.add_parser("list").add_argument("--json", action="store_true")
    for mode in ("claim", "release"):
        part = sub.add_parser(mode)
        part.add_argument("platform", choices=("ios", "android"))
        part.add_argument("id")
        part.add_argument("--session")
        if mode == "claim":
            part.add_argument("--created", action="store_true")
            part.add_argument("--booted", action="store_true")
            part.add_argument("--avd")
            part.add_argument("--incarnation")
            part.add_argument("--pid", type=int)
            part.add_argument("--process-group", type=int)
            part.add_argument("--helper-pid", type=int)
    args = parser.parse_args()
    signal.signal(signal.SIGALRM, expired)
    signal.alarm(max(1, min(args.deadline, 100)))
    root = checked_root(args.root)
    if args.mode in {"artifacts", "archive"}:
        if args.mode == "archive":
            try:
                clean_devices(root)
            except Deadline:
                raise
            except (Unsafe, OSError, ValueError) as exc:
                print(f"Device cleanup deferred: {exc}", file=sys.stderr)
        clean_artifacts(root, args.derived_data)
    elif args.mode in {"devices", "session-devices"}:
        try:
            clean_devices(root, args.dry_run, args.session or claimant())
        except (Unsafe, OSError, ValueError) as exc:
            if args.mode != "session-devices":
                raise
            log(f"Session cleanup deferred ({exc}); use make clean-devices or the archive hook.")
    elif args.mode == "orphans":
        with claims_lock(root):
            clean_orphans(root, args.confirm, args.plan, args.plan_digest, args.allow_legacy_udid)
    elif args.mode == "incarnation":
        token = incarnation(root, args.platform, args.id, args.avd, pid=args.pid, process_group=args.process_group)
        if token is None:
            raise Unsafe("cannot verify device boot incarnation")
        print(token)
    elif args.mode == "rollback":
        rollback(root, args)
    elif args.mode == "claim":
        claim(root, args)
    elif args.mode == "release":
        release(root, args.platform, args.id, args.session)
    elif args.mode == "prefix":
        print(prefix(root))
    elif args.mode == "list":
        print(json.dumps(read_ledger(root), indent=2))
    elif args.mode == "session-end":
        queue_session_cleanup(root)
    elif args.mode == "session-start":
        remember_claude_session(root)


if __name__ == "__main__":
    try:
        main()
    except (Unsafe, OSError, ValueError, KeyError, TypeError) as exc:
        print(f"Cleanup refused/deferred: {exc}", file=sys.stderr)
        sys.exit(1)
