"""Private process registry shared by the selftest watchdog and its fixtures."""
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import time
import uuid

REGISTRY_ENV = 'FLINT_SELFTEST_PROCESS_REGISTRY'
TOKEN_ENV = 'FLINT_SELFTEST_PROCESS_TOKEN'


def process_table(timeout=2):
    # Use the host ps directly: fixture PATH deliberately contains a fake ps.
    result = subprocess.run(['/bin/ps', '-axo', 'pid=,ppid=,pgid=,stat=,lstart='],
                            capture_output=True, text=True, check=True, timeout=timeout,
                            env={**os.environ, 'LC_ALL': 'C'})
    rows = {}
    for line in result.stdout.splitlines():
        fields = line.split(None, 4)
        if len(fields) == 5:
            pid, parent, group = map(int, fields[:3])
            rows[pid] = dict(pid=pid, parent=parent, group=group,
                             state=fields[3], start=fields[4].strip())
    return rows


def same_process(record, table):
    current = table.get(record['pid'])
    return current is not None and current['start'] == record['start']


def tagged_processes(tokens, table, timeout=2):
    # A command may exit after daemonizing a child into an independent session.
    # Its inherited random token survives both reparenting and setsid. Read only
    # the current user's process environments, never log their contents. This is
    # fixture provenance, not a sandbox for hostile commands that scrub env.
    if not tokens:
        return {}
    result = subprocess.run(['/bin/ps', 'eww', '-U', str(os.getuid()), '-o', 'pid=,command='],
                            capture_output=True, text=True, check=True, timeout=timeout)
    pattern = re.compile(r'(?:^|\s)' + TOKEN_ENV + r'=([0-9a-f]{32})(?=\s|$)')
    selected = {}
    for line in result.stdout.splitlines():
        fields = line.strip().split(None, 1)
        if len(fields) == 2 and fields[0].isdigit():
            pid = int(fields[0])
            if pid in table and any(token in tokens for token in pattern.findall(fields[1])):
                selected[pid] = table[pid]
    return selected


def terminate(records, timeout=5):
    """Freeze the verified trees, find newly forked descendants, kill, then wait.

    Descendant discovery follows provenance tokens and parent IDs as well as the
    registered session's process group, even after its leader has exited. Stop
    parents before taking another snapshot; only remove fixtures after every
    captured identity is absent or a zombie (no further code can execute).
    """
    if not records:
        return
    deadline = time.monotonic() + timeout

    def remaining():
        seconds = deadline - time.monotonic()
        if seconds <= 0:
            raise RuntimeError('selftest process termination exceeded its deadline; fixtures retained')
        return min(2, seconds)

    def snapshot():
        return process_table(timeout=remaining())

    owned = {row['pid']: row for row in records}
    tokens = {row['token'] for row in records}
    while True:
        table = snapshot()
        live = {pid: table[pid] for pid, row in owned.items() if same_process(row, table)}
        live.update(tagged_processes(tokens, table, timeout=remaining()))
        groups = {pid for pid, row in live.items() if row['group'] == pid}
        while True:
            added = {pid: row for pid, row in table.items() if pid not in live
                     and (row['parent'] in live or row['group'] in groups)}
            if not added:
                break
            live.update(added)
            groups.update(pid for pid, row in added.items() if row['group'] == pid)
        if os.getpid() in live:
            raise RuntimeError('refusing to terminate the selftest watchdog itself')
        owned.update(live)
        running = [row for row in live.values() if not set(row['state']) & {'T', 'Z'}]
        if not running:
            break
        for row in running:
            # Verify the birth identity immediately before every signal.
            if same_process(row, snapshot()):
                try:
                    os.kill(row['pid'], signal.SIGSTOP)
                except ProcessLookupError:
                    pass
        if time.monotonic() >= deadline:
            raise RuntimeError('selftest process tree could not be stopped; fixtures retained')
    for row in reversed(list(live.values())):
        if same_process(row, snapshot()):
            try:
                os.kill(row['pid'], signal.SIGKILL)
            except ProcessLookupError:
                pass
    while True:
        table = snapshot()
        survivors = [pid for pid, row in owned.items()
                     if same_process(row, table) and 'Z' not in table[pid]['state']]
        if not survivors:
            return
        if time.monotonic() >= deadline:
            raise RuntimeError(f'selftest processes did not exit: {survivors}; fixtures retained')
        time.sleep(0.01)


class ProcessRegistry:
    def __init__(self, path):
        self.path = Path(path)

    @contextmanager
    def locked(self):
        with os.fdopen(os.open(self.path, os.O_RDWR | os.O_CREAT, 0o600), 'r+') as stream:
            deadline = time.monotonic() + 3
            while True:
                try:
                    fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError('selftest process registry is busy; fixtures retained')
                    time.sleep(0.01)
            stream.seek(0)
            contents = stream.read()
            try:
                state = json.loads(contents) if contents else {'sealed': False, 'processes': [], 'closed_tokens': []}
            except ValueError as exc:
                raise RuntimeError('invalid selftest process registry; fixtures retained') from exc
            yield state
            stream.seek(0)
            json.dump(state, stream)
            stream.truncate()
            stream.flush()
            os.fsync(stream.fileno())

    def spawn(self, args, *, owner, **kwargs):
        # Do not let the command exec until its PID/birth identity is durable.
        # The shared lock makes sealing atomic with registration, including the
        # Popen-to-register interval that an outer SIGKILL would otherwise race.
        read_fd, write_fd = os.pipe()
        child = None
        try:
            with self.locked() as state:
                parent_token = os.environ.get(TOKEN_ENV)
                if state['sealed'] or parent_token in state['closed_tokens']:
                    raise RuntimeError('selftest process registry is closed')
                token = uuid.uuid4().hex
                env = {**kwargs.pop('env', os.environ), TOKEN_ENV: token}
                child = subprocess.Popen([sys.executable, '-B', str(Path(__file__).resolve()),
                                          '--exec', str(read_fd), *args],
                                         pass_fds=(read_fd,), start_new_session=True, env=env, **kwargs)
                record = process_table().get(child.pid)
                if record is None or record['group'] != child.pid:
                    raise RuntimeError('selftest child session identity unavailable')
                state['processes'].append({**record, 'owner': owner, 'token': token, 'parent_token': parent_token})
            os.write(write_fd, b'1')
            return child
        except BaseException:
            if child is not None:
                child.kill()
                child.wait(timeout=2)
            raise
        finally:
            os.close(read_fd)
            os.close(write_fd)

    def finish(self, owner):
        with self.locked() as state:
            records = [row for row in state['processes'] if row['owner'] == owner]
            tokens = {row['token'] for row in records}
            while True:
                nested = [row for row in state['processes']
                          if row['token'] not in tokens and row['parent_token'] in tokens]
                if not nested:
                    break
                records.extend(nested)
                tokens.update(row['token'] for row in nested)
            state['closed_tokens'].extend(tokens)
        terminate(records)
        with self.locked() as state:
            state['processes'] = [row for row in state['processes'] if row['token'] not in tokens]

    def close(self):
        with self.locked() as state:
            state['sealed'] = True
            records = state['processes'][:]
        terminate(records)


if __name__ == '__main__':
    if len(sys.argv) < 4 or sys.argv[1] != '--exec':
        sys.exit('internal selftest process launcher')
    fd = int(sys.argv[2])
    ready = os.read(fd, 1)
    os.close(fd)
    if ready != b'1':
        sys.exit(125)
    os.execvpe(sys.argv[3], sys.argv[3:], os.environ)
