"""An adb shell exit status alone must never certify release runtime behavior."""
import unittest
from verify_instrumentation_result import TEST_CLASS, TEST_METHOD, verify


def stream():
    status = f"INSTRUMENTATION_STATUS: class={TEST_CLASS}\nINSTRUMENTATION_STATUS: test={TEST_METHOD}\nINSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: numtests=1\n"
    return status + "INSTRUMENTATION_STATUS_CODE: 1\n" + status + "INSTRUMENTATION_STATUS_CODE: 0\nINSTRUMENTATION_RESULT: stream=\nTime: 4.2\n\nOK (1 test)\n\nINSTRUMENTATION_CODE: -1\n"


WIDGET = ("com.flint.peakfocus.e2e.GlanceStartupWidgetTest", "installedWidgetWorkerRendersUnavailableAndOpensFlint")
EXPECTED = [(TEST_CLASS, TEST_METHOD), WIDGET]


def multi_stream(tests=EXPECTED):
    output = ""
    for current, (name, method) in enumerate(tests, 1):
        status = (f"INSTRUMENTATION_STATUS: class={name}\nINSTRUMENTATION_STATUS: test={method}\n"
                  f"INSTRUMENTATION_STATUS: current={current}\nINSTRUMENTATION_STATUS: numtests={len(tests)}\n")
        output += status + "INSTRUMENTATION_STATUS_CODE: 1\n" + status + "INSTRUMENTATION_STATUS_CODE: 0\n"
    return output + f"INSTRUMENTATION_RESULT: stream=\nOK ({len(tests)} tests)\nINSTRUMENTATION_CODE: -1\n"


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

    def test_explicit_two_test_selection_accepts_both_orders(self):
        verify(multi_stream(), EXPECTED)
        verify(multi_stream(list(reversed(EXPECTED))), EXPECTED)

    def test_multi_selection_rejects_missing_extra_renamed_duplicate_or_mismatched_test(self):
        for output in (stream(), multi_stream(EXPECTED + [("Other", "test")]),
                       multi_stream().replace(WIDGET[1], "renamed"),
                       multi_stream([EXPECTED[0], EXPECTED[0]]),
                       multi_stream().replace(f"test={WIDGET[1]}", "test=wrong", 1)):
            with self.subTest(output=output), self.assertRaises(ValueError):
                verify(output, EXPECTED)

    def test_multi_selection_rejects_failed_skipped_wrong_count_or_false_summary(self):
        for output in (multi_stream().replace("INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -3", 1),
                       multi_stream().replace("numtests=2", "numtests=1"),
                       multi_stream().replace("current=2", "current=1"),
                       multi_stream().replace("OK (2 tests)", "OK (1 test)"),
                       multi_stream().replace("INSTRUMENTATION_CODE: -1", ""),
                       multi_stream() + "INSTRUMENTATION_FAILED: Process crashed\n"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                verify(output, EXPECTED)

    def test_empty_or_duplicate_expected_ids_and_implicit_multi_selection_are_rejected(self):
        for expected in ([], [EXPECTED[0], EXPECTED[0]], None):
            with self.subTest(expected=expected), self.assertRaises(ValueError):
                verify(multi_stream(), expected)


if __name__ == "__main__":
    unittest.main()
