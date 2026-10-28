"""An adb shell exit status alone must never certify release runtime behavior."""
import unittest
from verify_instrumentation_result import TEST_CLASS, TEST_METHOD, verify


def stream():
    status = f"INSTRUMENTATION_STATUS: class={TEST_CLASS}\nINSTRUMENTATION_STATUS: test={TEST_METHOD}\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\n"
    return status + "INSTRUMENTATION_STATUS_CODE: 1\n" + status + "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nTime: 4.2\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n"


class InstrumentationResultTests(unittest.TestCase):
    def test_selected_success(self):
        verify(stream())

    def test_exit_zero_without_test_output_is_rejected(self):
        for output in ("", "INSTRUMENTATION_CODE: -1\n", "OK (0 tests)\nINSTRUMENTATION_CODE: -1\n"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                verify(output)

    def test_failure_error_ignore_and_assumption_skip_are_rejected(self):
        for code in (-1, -2, -3, -4):
            with self.subTest(code=code), self.assertRaises(ValueError):
                verify(stream().replace("INSTRUMENTATION_STATUS_CODE: 0", f"INSTRUMENTATION_STATUS_CODE: {code}"))

    def test_wrong_or_multiple_test_is_rejected(self):
        for output in (stream().replace(TEST_METHOD, "exhaustedDailyBudgetShowsTimeLimitShield"),
                       stream().replace("numtests=1", "numtests=2"), stream() + stream()):
            with self.subTest(output=output), self.assertRaises(ValueError):
                verify(output)

    def test_crash_and_missing_terminal_are_rejected(self):
        for output in (stream().replace("INSTRUMENTATION_CODE: -1", ""),
                       stream() + "INSTRUMENTATION_FAILED: Process crashed\n",
                       stream() + "INSTRUMENTATION_RESULT: shortMsg=crash\n"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                verify(output)


if __name__ == "__main__":
    unittest.main()
