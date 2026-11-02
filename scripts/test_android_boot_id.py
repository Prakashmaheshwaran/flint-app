"""Bounded boot identity reads distinguish a reconnect, reboot, and lost evidence."""
import subprocess
import unittest
from unittest.mock import patch

import android_boot_id


SAME = "11111111-1111-1111-1111-111111111111"


class BootReadTests(unittest.TestCase):
    def read(self, replies):
        events = []
        with patch.object(android_boot_id.subprocess, "run", side_effect=replies) as run, \
                patch.object(android_boot_id.time, "sleep"):
            boot_id = android_boot_id.read_boot_id("emulator-5554", lambda **e: events.append(e))
        for call in run.call_args_list:
            self.assertEqual(call.args[0][:3], ["adb", "-s", "emulator-5554"])
            self.assertLessEqual(call.kwargs["timeout"], 2)
        return boot_id, events, run.call_count

    def test_reconnect_then_same_id(self):
        ok = subprocess.CompletedProcess([], 0, stdout="", stderr="")
        boot = subprocess.CompletedProcess([], 0, stdout=SAME + "\r\n", stderr="")
        for failure in (subprocess.CalledProcessError(1, "adb"), subprocess.TimeoutExpired("adb", 2)):
            with self.subTest(failure=failure):
                result, events, calls = self.read([failure, ok, boot])
                self.assertEqual(result, SAME)
                self.assertEqual(len(events), 1)
                self.assertEqual(calls, 3)

    def test_empty_malformed_and_failed_reads_exhaust_only_five_attempts(self):
        ok = subprocess.CompletedProcess([], 0, stdout="", stderr="")
        for reply in (ok, subprocess.CompletedProcess([], 0, stdout="not-a-boot-id", stderr=""),
                      subprocess.CalledProcessError(1, "adb")):
            with self.subTest(reply=reply):
                result, events, calls = self.read([ok, reply] * 5)
                self.assertIsNone(result)
                self.assertEqual(len(events), 5)
                self.assertEqual(calls, 10)

    def test_deadline_bounds_even_a_hung_transport(self):
        with patch.object(android_boot_id.time, "monotonic", side_effect=[0, 0, 21]), \
                patch.object(android_boot_id.subprocess, "run", side_effect=subprocess.TimeoutExpired("adb", 2)) as run:
            self.assertIsNone(android_boot_id.read_boot_id("emulator-5554", lambda **e: None))
        self.assertEqual(run.call_count, 1)


if __name__ == "__main__":
    unittest.main()
