"""CoreSimulator query budgets, using only disposable fake executables."""
import importlib.util
from pathlib import Path
import sys
import time
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("device_timeout_harness", ROOT / "scripts/agent/selftest.py")
agent = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(agent)
UDID = "11111111-2222-3333-4444-555555555555"
IMPORT_RUNNER = (
    "import importlib.util; "
    "s = importlib.util.spec_from_file_location('device_run', 'scripts/agent/device-run.py'); "
    "m = importlib.util.module_from_spec(s); s.loader.exec_module(m); "
)
FAULTS = r'''
if name == 'xcrun' and args[:1] == ['simctl']:
    query = args[1]
    if query == os.environ.get('SLOW_SIMCTL_QUERY'):
        time.sleep(float(os.environ.get('SIMCTL_QUERY_DELAY', '5')))
'''


class DeviceTimeouts(unittest.TestCase):
    def setUp(self):
        self.h = agent.AgentCommands()
        self.h.setUp()
        self.addCleanup(self.h.doCleanups)
        self.addCleanup(self.h.tearDown)
        source = self.h.shim.read_text()
        self.assertIn("if set(args)", source)
        self.h.shim.write_text(source.replace("if set(args)", FAULTS + "\nif set(args)", 1))
        (self.h.root / ("booted-" + UDID)).touch()

    def ios(self, **env):
        return self.h.run_cmd([sys.executable, "scripts/agent/device-run.py", "ios", UDID],
                              command_timeout=35, **env)

    def assert_no_claim_or_attach(self):
        self.assertEqual(self.h.calls("flint-devices.sh"), [])
        self.assertEqual(self.h.calls("orca"), [])

    def test_m_five_second_inventory_succeeds(self):
        result = self.ios(SLOW_SIMCTL_QUERY="list")
        self.h.assert_ok(result)
        self.assertTrue(any(a[0] == "claim" for a in self.h.calls("flint-devices.sh")))

    def test_m_hung_inventory_stops_at_thirty_seconds(self):
        start = time.monotonic()
        result = self.ios(SLOW_SIMCTL_QUERY="list", SIMCTL_QUERY_DELAY="60")
        elapsed = time.monotonic() - start
        self.assertGreaterEqual(elapsed, 29)
        self.assertLess(elapsed, 34)
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("xcrun simctl list devices available --json timed out after 30s", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assert_no_claim_or_attach()

    def test_m_five_second_springboard_query_succeeds(self):
        self.h.assert_ok(self.ios(SLOW_SIMCTL_QUERY="spawn"))

    def test_m_springboard_query_respects_shorter_readiness_deadline(self):
        start = time.monotonic()
        result = self.ios(SLOW_SIMCTL_QUERY="spawn", SIMCTL_QUERY_DELAY="60",
                          FLINT_AGENT_DEVICE_TIMEOUT="0.3")
        self.assertLess(time.monotonic() - start, 4)
        self.assertEqual(result.returncode, 2, result.stdout + result.stderr)
        self.assertIn("SpringBoard readiness timed out after 0.3s", result.stderr)
        self.assert_no_claim_or_attach()

    def test_m_five_second_nonblocking_bootstatus_query_succeeds(self):
        result = self.h.run_cmd([sys.executable, "-c", IMPORT_RUNNER +
                                f"m.run(['xcrun', 'simctl', 'bootstatus', {UDID!r}])"],
                               SLOW_SIMCTL_QUERY="bootstatus")
        self.h.assert_ok(result)

    def test_m_cheap_commands_keep_short_default_and_name_command(self):
        code = IMPORT_RUNNER + "\ntry:\n m.run(['python3', '-c', 'import time; time.sleep(60)'])\nexcept m.CommandTimeout as exc:\n print(exc)\nelse:\n raise AssertionError('unbounded cheap command')\n"
        start = time.monotonic()
        result = self.h.run_cmd([sys.executable, "-c", code], command_timeout=7)
        elapsed = time.monotonic() - start
        self.h.assert_ok(result)
        self.assertGreaterEqual(elapsed, 2.9)
        self.assertLess(elapsed, 6)
        self.assertIn("python3 -c 'import time; time.sleep(60)' timed out after 3s", result.stdout)


if __name__ == "__main__":
    unittest.main()
