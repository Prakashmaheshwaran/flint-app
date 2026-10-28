import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from ci_test_report import counts, summarize, xcresult_junit


class TestReportTests(unittest.TestCase):
    def test_nested_suites_count_cases_once_and_preserve_outcomes(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "report.xml"
            path.write_text('<testsuites tests="99"><testsuite tests="99">'
                            '<testcase name="pass"/><testcase name="fail"><failure/></testcase>'
                            '<testcase name="error"><error/></testcase>'
                            '<testcase name="skip"><skipped/></testcase></testsuite></testsuites>')
            self.assertEqual(counts(path), (4, 1, 1, 1))

    def test_empty_is_not_success_evidence(self):
        summary, total = summarize([], "Tests")
        self.assertEqual(total, [0, 0, 0, 0])
        self.assertIn("not passing test evidence", summary)

    def test_xcode_cases_ignore_nested_run_nodes_and_keep_unknown_as_error(self):
        report = {"testNodes": [{"nodeType": "Test Suite", "name": "Suite", "children": [
            {"nodeType": "Test Case", "name": "passed", "result": "Passed", "children": [
                {"nodeType": "Test Case Run", "name": "one", "result": "Passed"}]},
            *[{"nodeType": "Test Case", "name": result, "result": result}
              for result in ["Failed", "Skipped", "Expected Failure", "unknown"]],
        ]}]}
        with tempfile.TemporaryDirectory() as folder, patch(
            "ci_test_report.subprocess.check_output", return_value=json.dumps(report)
        ):
            path = Path(folder) / "report.xml"
            xcresult_junit(Path("tests.xcresult"), path)
            self.assertEqual(counts(path), (5, 1, 1, 2))

    def test_malformed_report_fails_instead_of_inventing_count(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "report.xml"
            path.write_text("not XML")
            with self.assertRaises(Exception):
                counts(path)

    def test_runner_failure_cannot_export_clean_junit(self):
        report = {"testNodes": [{"nodeType": "Test Plan", "name": "Plan", "result": "Failed", "children": [
            {"nodeType": "Test Case", "name": "passed", "result": "Passed"},
            {"nodeType": "Failure Message", "name": "Runner exited unexpectedly"},
        ]}]}
        with tempfile.TemporaryDirectory() as folder, patch(
            "ci_test_report.subprocess.check_output", return_value=json.dumps(report)
        ):
            path = Path(folder) / "report.xml"
            with self.assertRaisesRegex(ValueError, "incomplete"):
                xcresult_junit(Path("tests.xcresult"), path)
            self.assertFalse(path.exists())

    def test_missing_xcresult_retains_failure_diagnostic(self):
        import subprocess
        import sys
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "artifacts" / "report.xml"
            summary = Path(folder) / "summary.md"
            result = subprocess.run([
                sys.executable, str(Path(__file__).with_name("ci_test_report.py")),
                "--xcresult", str(Path(folder) / "missing.xcresult"),
                "--output", str(output), "--summary", str(summary),
            ], capture_output=True, text=True)
            self.assertEqual(result.returncode, 1)
            self.assertFalse(output.exists())
            self.assertIn("No passing test evidence", output.with_suffix(".error.txt").read_text())
            self.assertIn("unavailable or incomplete", summary.read_text())
