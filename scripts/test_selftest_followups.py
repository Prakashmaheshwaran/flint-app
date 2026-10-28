"""Device-free regressions for the outer watchdog and Apple Make launchers."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(os.environ.get('FLINT_SELFTEST_SOURCE_ROOT', ROOT))


def load_agent():
    sys.path.insert(0, str(SOURCE / 'scripts'))
    spec = importlib.util.spec_from_file_location('followup_agent', SOURCE / 'scripts/agent/selftest.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def alive(pid):
    result = subprocess.run(['/bin/ps', '-p', str(pid), '-o', 'stat='],
                            capture_output=True, text=True, timeout=2)
    return bool(result.stdout.strip()) and not result.stdout.lstrip().startswith('Z')


class SelftestFollowups(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='.selftest-followup-', dir=ROOT)
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def test_n4_apple_make_launchers_and_symlinks_pin_gnumake(self):
        agent = load_agent()
        native = '/Library/Developer/CommandLineTools/usr/bin/gnumake'
        for index, launcher in enumerate(('/usr/bin/make', '/Library/Developer/CommandLineTools/usr/bin/make')):
            alias = self.root / f'make-alias-{index}'
            alias.symlink_to(launcher)
            for selected in (launcher, str(alias)):
                with self.subTest(selected=selected), mock.patch.object(agent.shutil, 'which', return_value=selected), \
                        mock.patch.object(Path, 'is_file', return_value=True):
                    self.assertEqual(agent.real_tool('make'), native)

    def test_n4_third_party_make_and_symlink_are_preserved(self):
        agent = load_agent()
        third_party = self.root / 'third-party-make'
        third_party.write_text('#!/bin/sh\nexit 0\n')
        alias = self.root / 'make'
        alias.symlink_to(third_party)
        for selected in (str(third_party), str(alias)):
            with self.subTest(selected=selected), mock.patch.object(agent.shutil, 'which', return_value=selected):
                self.assertEqual(agent.real_tool('make'), selected)

    def test_n4_missing_native_make_keeps_discovered_tool(self):
        agent = load_agent()
        with mock.patch.object(agent.shutil, 'which', return_value='/usr/bin/make'), \
                mock.patch.object(Path, 'is_file', return_value=False):
            self.assertEqual(agent.real_tool('make'), '/usr/bin/make')

    @unittest.skipUnless(Path('/Library/Developer/CommandLineTools/usr/bin/gnumake').is_file(),
                         'Apple CommandLineTools GNU Make is unavailable')
    def test_n4_pinned_make_runs_with_fake_developer_dir(self):
        agent = load_agent()
        makefile = self.root / 'Makefile'
        makefile.write_text('all:\n\t@echo isolated-make-ok\n')
        for index, launcher in enumerate(('/usr/bin/make', '/Library/Developer/CommandLineTools/usr/bin/make')):
            alias = self.root / f'apple-make-{index}'
            alias.symlink_to(launcher)
            for selected in (launcher, str(alias)):
                with self.subTest(selected=selected), mock.patch.object(agent.shutil, 'which', return_value=selected):
                    binary = agent.real_tool('make')
                result = subprocess.run([binary, '--no-print-directory', '-f', str(makefile)],
                                        env={'PATH': '/usr/bin:/bin', 'HOME': str(self.root),
                                             'DEVELOPER_DIR': str(self.root / 'fake-xcode')},
                                        text=True, capture_output=True, timeout=5)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(result.stdout.strip(), 'isolated-make-ok')

    def test_n2_outer_deadline_stops_actual_run_cmd_tree_before_teardown(self):
        self.outer_deadline()

    def test_n2_outer_deadline_stops_every_registered_command(self):
        self.outer_deadline(commands=2)

    def test_n2_outer_deadline_recovers_orphans_after_session_leader_exits(self):
        self.outer_deadline(leader_exits=True)

    def test_n2_failed_termination_retains_fixture_and_reports_failure(self):
        self.outer_deadline(deny_signal=True)

    def outer_deadline(self, commands=1, leader_exits=False, deny_signal=False):
        # The actual run_cmd child starts two grandchildren, one in a new session.
        # All write a sentinel if they can execute after the outer runner returns.
        tree = self.root / 'tree.py'
        tree.write_text('''import os, pathlib, signal, subprocess, sys, time
base = pathlib.Path(sys.argv[1])
prefix, exit_early, role = sys.argv[2:]
name = prefix + role
signal.signal(signal.SIGTERM, signal.SIG_IGN)
(base / (name + '.pid')).write_text(str(os.getpid()))
if role == 'child':
    subprocess.Popen([sys.executable, __file__, str(base), prefix, exit_early, 'grandchild'])
    subprocess.Popen([sys.executable, __file__, str(base), prefix, exit_early, 'session-grandchild'], start_new_session=True)
    if exit_early == '1':
        while len(list(base.glob(prefix + '*.pid'))) != 3:
            time.sleep(0.01)
        # Stop the actual run_cmd supervisor before exiting: only the outer
        # watchdog can now recover the orphaned, independently sessioned child.
        os.kill(os.getppid(), signal.SIGSTOP)
        os._exit(0)
while not (base / 'release').exists():
    time.sleep(0.01)
(base / (name + '.escaped')).touch()
time.sleep(30)
''')
        suite = self.root / 'suite.py'
        suite.write_text('''import importlib.util, os, pathlib, sys, tempfile, threading
tempfile.tempdir = str(pathlib.Path.home())
source, base = map(pathlib.Path, sys.argv[1:3])
count, exit_early = sys.argv[3:]
(base / 'suite-pid').write_text(str(os.getpid()))
sys.path.insert(0, str(source / 'scripts'))
spec = importlib.util.spec_from_file_location('actual_agent', source / 'scripts/agent/selftest.py')
agent = importlib.util.module_from_spec(spec)
spec.loader.exec_module(agent)
def command(index):
    case = agent.AgentCommands()
    case.setUp()
    (base / ('fixture-' + str(index))).write_text(str(case.root.parent))
    try:
        case.run_cmd([sys.executable, str(base / 'tree.py'), str(base), str(index) + '-', exit_early, 'child'], command_timeout=30)
    finally:
        case.tearDown()
        case.doCleanups()
threads = [threading.Thread(target=command, args=(index,)) for index in range(int(count))]
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
''')
        driver = self.root / 'driver.py'
        driver.write_text('''import importlib.util, json, pathlib, subprocess, sys, tempfile, time
source, base = map(pathlib.Path, sys.argv[1:3])
count, exit_early, deny_signal = sys.argv[3:]
sys.path.insert(0, str(source / 'scripts'))
spec = importlib.util.spec_from_file_location('actual_outer', source / 'scripts/selftest.py')
outer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(outer)
outer.SUITES = [[sys.executable, '-B', str(base / 'suite.py'), str(source), str(base), count, exit_early]]
if deny_signal == '1':
    import selftest_processes
    def denied(*args):
        raise PermissionError('injected cancellation denial')
    selftest_processes.os.kill = denied
original_wait = subprocess.Popen.wait
def accelerated_wait(child, timeout=None):
    if timeout == 120:
        deadline = time.monotonic() + 8
        while len(list(base.glob('*.pid'))) != 3 * int(count):
            if child.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError('actual run_cmd tree did not become ready')
            time.sleep(0.01)
        timeout = 0.1
    return original_wait(child, timeout=timeout)
subprocess.Popen.wait = accelerated_wait
original_directory = tempfile.TemporaryDirectory
class ObservedDirectory(original_directory):
    def __init__(self, *args, **kwargs):
        kwargs['dir'] = base
        super().__init__(*args, **kwargs)
    def cleanup(self):
        states = {}
        for marker in base.glob('*.pid'):
            result = subprocess.run(['/bin/ps', '-p', marker.read_text(), '-o', 'stat='],
                                    capture_output=True, text=True, timeout=2)
            states[marker.stem] = bool(result.stdout.strip()) and not result.stdout.lstrip().startswith('Z')
        fixtures = [pathlib.Path(marker.read_text()) for marker in base.glob('fixture-*')]
        (base / 'before-teardown.json').write_text(json.dumps({'alive': states, 'fixture_exists': bool(fixtures) and all(p.exists() for p in fixtures)}))
        super().cleanup()
tempfile.TemporaryDirectory = ObservedDirectory
sys.exit(outer.main())
''')
        try:
            with tempfile.TemporaryFile(dir=self.root) as log:
                result = subprocess.run([sys.executable, '-B', str(driver), str(SOURCE), str(self.root),
                                         str(commands), str(int(leader_exits)), str(int(deny_signal))],
                                        env={'PATH': os.environ['PATH'], 'HOME': str(self.root),
                                             'TMPDIR': str(self.root), 'PYTHONDONTWRITEBYTECODE': '1'},
                                        stdout=log, stderr=subprocess.STDOUT, timeout=20)
                log.seek(0)
                output = log.read(1024 * 1024).decode(errors='replace')
            self.assertEqual(result.returncode, 1, output)
            if deny_signal:
                self.assertIn('injected cancellation denial', output)
                self.assertIn('Selftest fixtures retained:', output)
                self.assertFalse((self.root / 'before-teardown.json').exists())
                fixtures = [Path(marker.read_text()) for marker in self.root.glob('fixture-*')]
                self.assertEqual(len(fixtures), commands)
                self.assertTrue(all(p.is_dir() for p in fixtures))
                self.assertTrue(alive(int((self.root / '0-child.pid').read_text())))
                return
            self.assertIn('exit 124', output)
            before = json.loads((self.root / 'before-teardown.json').read_text())
            (self.root / 'release').touch()
            time.sleep(0.2)
            escaped = sorted(path.name for path in self.root.glob('*.escaped'))
            self.assertEqual(before['alive'], {f'{index}-{role}': False for index in range(commands)
                                              for role in ('child', 'grandchild', 'session-grandchild')},
                             f'processes were alive at teardown; escaped sentinels: {escaped}')
            self.assertTrue(before['fixture_exists'], 'the teardown observation missed fixture removal')
            self.assertTrue(all(not Path(marker.read_text()).exists() for marker in self.root.glob('fixture-*')))
            self.assertEqual(escaped, [])
        finally:
            # The red baseline must leave no test-owned survivor behind.
            markers = list(self.root.glob('*.pid'))
            if (self.root / 'suite-pid').exists():
                markers.insert(0, self.root / 'suite-pid')
            for marker in markers:
                pid = int(marker.read_text())
                if alive(pid):
                    try:
                        os.kill(pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
            deadline = time.monotonic() + 3
            while any(alive(int(p.read_text())) for p in markers):
                if time.monotonic() >= deadline:
                    self.fail('test-owned process remained alive after emergency cleanup')
                time.sleep(0.02)

    def test_n2_stale_pid_identity_never_signals_replacement(self):
        load_agent()
        import selftest_processes as processes
        original = dict(pid=123, parent=1, group=123, state='S', start='original', token='a' * 32)
        replacement = {**original, 'start': 'replacement'}
        with mock.patch.object(processes, 'process_table', return_value={123: replacement}), \
                mock.patch.object(processes, 'tagged_processes', return_value={}), \
                mock.patch.object(processes.os, 'kill') as kill:
            processes.terminate([original])
        kill.assert_not_called()

    def test_n2_busy_registry_lock_is_bounded(self):
        load_agent()
        import selftest_processes as processes
        path = self.root / 'busy-registry'
        with path.open('w') as held:
            processes.fcntl.flock(held, processes.fcntl.LOCK_EX)
            with mock.patch.object(processes.time, 'monotonic', side_effect=[0, 4]), \
                    self.assertRaisesRegex(RuntimeError, 'registry is busy; fixtures retained'):
                with processes.ProcessRegistry(path).locked():
                    self.fail('busy registry lock was acquired')

    def test_n2_sealed_registry_never_starts_another_command(self):
        load_agent()
        import selftest_processes as processes
        registry = processes.ProcessRegistry(self.root / 'sealed-registry')
        registry.close()
        with mock.patch.object(processes.subprocess, 'Popen') as popen, \
                self.assertRaisesRegex(RuntimeError, 'registry is closed'):
            registry.spawn([sys.executable, '-c', 'pass'], owner='late-command')
        popen.assert_not_called()


if __name__ == '__main__':
    unittest.main()
