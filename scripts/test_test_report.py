import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from test_report import counts, summarize, xcresult_junit


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
            "test_report.subprocess.check_output", return_value=json.dumps(report)
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
