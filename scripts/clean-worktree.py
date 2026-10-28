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
import json
import os
from pathlib import Path
import plistlib
import re
import resource
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile


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
        result = subprocess.run(argv, cwd=root, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode == 0:
            return result.stdout
        detail = result.stderr.strip()[:300] or f"exit {result.returncode}"
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
    validate_entry(root, entry)
    with claims_lock(root):
        current = read_ledger(root)
        key = (args.platform, args.id, entry["claimant"])
        old = next((e for e in current if (e["platform"], e["id"], e["claimant"]) == key), None)
        if old:
            if old.get("avd") != entry["avd"] or old["name"] != entry["name"]:
                raise Unsafe("device identity changed; release the stale claim first")
            entry["created"] |= old["created"]
            entry["booted"] |= old["booted"]
        current = [e for e in current if (e["platform"], e["id"], e["claimant"]) != key]
        write_ledger(root, current + [entry])
    print(f"Claimed {args.platform} {args.id}; created={entry['created']} booted={entry['booted']}")


def release(root, platform, ident, session=None):
    with claims_lock(root):
        current = read_ledger(root)
        owner = session or claimant() or "manual"
        write_ledger(root, [e for e in current if (e["platform"], e["id"], e["claimant"]) != (platform, ident, owner)])
    print(f"Released claim for {platform} {ident} (no device action)")


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
            entry["created"] = any(e["created"] for e in local_claims)
            entry["booted"] = any(e["booted"] for e in local_claims)
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
                        if (remaining["platform"], remaining["id"]) == (platform, ident):
                            remaining["created"] |= entry["created"]
                            remaining["booted"] |= entry["booted"]
                    current.remove(entry)
                    write_ledger(root, current)
                continue
            if platform == "ios":
                if sims is None:
                    continue
                device = sims.get(ident)
                if device and device["name"] != entry["name"]:
                    print(f"KEEP renamed simulator {ident}")
                    continue
                if not device:
                    commands = []
                else:
                    commands = [[orca_command(), "emulator", "kill", "--worktree", f"path:{root}", "--device", ident, "--json"]]
                    if (entry["booted"] or entry["created"]) and device.get("state") == "Booted":
                        commands.append(["xcrun", "simctl", "shutdown", ident])
                    if entry["created"]:
                        commands.append(["xcrun", "simctl", "delete", ident])
            else:
                if android_avd(root, ident) != entry["avd"]:
                    print(f"KEEP {ident}: AVD identity unavailable or changed (expected {entry['avd']})")
                    continue
                commands = [[orca_command(), "emulator", "kill", "--worktree", f"path:{root}", "--device", ident, "--json"]]
                if entry["booted"]:
                    # Never avdmanager delete, wipe-data, kill-server, or touch AVD files.
                    commands.append(["adb", "-s", ident, "emu", "kill"])
            success = True
            for command in commands:
                log(("WOULD " if dry_run else "RUN ") + shlex.join(command))
                if not dry_run and run(command, root) is None:
                    success = False
                    # Do not power off/delete after an unverified helper stop.
                    break
            if success and not dry_run:
                current = [e for e in current if not ((e["platform"], e["id"]) == (platform, ident)
                           and (not session or e["claimant"] == session))]
                write_ledger(root, current)


def inside(path, root):
    try:
        return path.resolve().is_relative_to(root) and path.resolve() != root
    except OSError:
        return False


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
    custom = Path(derived)
    if not custom.is_absolute():
        custom = root / custom
    if (not inside(custom, root) or ".git" in custom.parts
            or custom in (root / "ios-app", root / "android-app")):
        raise Unsafe(f"DerivedData must be a disposable directory inside this worktree: {custom}")
    candidates.append(custom)
    selected = []
    for path in sorted(set(candidates), key=lambda p: len(p.parts)):
        if path.exists() or path.is_symlink():
            if not any(path.is_relative_to(parent) for parent in selected):
                selected.append(path)
    return selected


def clean_artifacts(root, derived):
    tracked = [root / p for p in run(["git", "ls-files", "-z"], root, required=True).split("\0") if p]
    freed = 0
    for path in artifact_paths(root, derived):
        if path.is_symlink() or not inside(path, root):
            raise Unsafe(f"refusing symlink/outside-worktree artifact: {path}")
        if any(p == path or p.is_relative_to(path) for p in tracked):
            raise Unsafe(f"refusing artifact containing tracked source: {path}")
        count = size_bytes(path, root)
        print(f"REMOVE {path.relative_to(root)} {human_size(count)}", flush=True)
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink()
        freed += count
    print(f"Removed {human_size(freed)} of worktree artifacts; local.properties and shared caches preserved.")


def workspace_checkout(workspace):
    # Xcode records a generated project/workspace, which make clean may remove.
    # An absent .xcodeproj is NOT proof that the checkout itself is gone.
    path = Path(workspace)
    if not path.is_absolute():
        return None
    for ancestor in [path, *path.parents]:
        if ancestor.name == "ios-app":
            return ancestor.parent
    return None


def clean_orphans(root, confirm=False):
    print("DELETE ENABLED" if confirm else "DRY RUN (use make clean-orphans CONFIRM=1 to delete listed candidates)")
    live = worktrees(root)
    raw = run([orca_command(), "worktree", "list", "--json"], root)
    if raw:
        try:
            live.extend(Path(w["path"]).resolve() for w in json.loads(raw)["result"]["worktrees"])
        except (ValueError, KeyError, TypeError):
            raise Unsafe("cannot interpret Orca worktree inventory")
    host = Path.home() / "Library/Developer/Xcode/DerivedData"
    for path in sorted(host.glob("Flint-*")):
        if path.is_symlink() or not path.is_dir():
            print(f"KEEP non-directory or symlink {path}")
            continue
        try:
            with (path / "info.plist").open("rb") as stream:
                workspace = plistlib.load(stream)["WorkspacePath"]
            checkout = workspace_checkout(workspace)
        except (OSError, ValueError, KeyError, TypeError, plistlib.InvalidFileException):
            print(f"KEEP unknown WorkspacePath {path.name}")
            continue
        if checkout is None or checkout.exists() or checkout.resolve() in live or Path(workspace).exists():
            print(f"KEEP live/unknown checkout {path.name}: {checkout}")
            continue
        count = size_bytes(path, root)
        print(f"{'REMOVE' if confirm else 'WOULD REMOVE'} {path} {human_size(count)} workspace={workspace}")
        if confirm:
            # Recheck immediately before deleting in case a worktree appeared.
            if checkout.exists() or Path(workspace).exists():
                print("KEEP checkout appeared during scan")
            else:
                shutil.rmtree(path)
    # Deliberately no 'simctl delete unavailable': it would include other projects.
    devices = sim_devices(root)
    if devices is None:
        return
    claimed = read_ledger(root) + siblings(root)
    for ident, device in sorted(devices.items()):
        if not re.fullmatch(r"flint-review-[0-9]+", device.get("name", "")):
            continue
        path = Path.home() / "Library/Developer/CoreSimulator/Devices" / ident
        count = size_bytes(path, root) if path.is_dir() else 0
        if device.get("state") != "Shutdown" or any(e["id"] == ident for e in claimed):
            print(f"KEEP active/claimed {device['name']} {ident} {human_size(count)}")
            continue
        print(f"{'DELETE' if confirm else 'WOULD DELETE'} simulator {device['name']} {ident} {human_size(count)} (legacy agent name rule)")
        if confirm:
            fresh = sim_devices(root)
            if fresh and fresh.get(ident, {}).get("state") == "Shutdown" and fresh[ident]["name"] == device["name"]:
                run(["xcrun", "simctl", "delete", ident], root)


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
    sub.add_parser("orphans").add_argument("--confirm", action="store_true")
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
            clean_orphans(root, args.confirm)
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
    except (Unsafe, OSError, ValueError) as exc:
        print(f"Cleanup refused/deferred: {exc}", file=sys.stderr)
        sys.exit(1)
