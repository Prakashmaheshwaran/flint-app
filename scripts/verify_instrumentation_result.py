#!/usr/bin/env python3
"""Reject adb's false-success exit status unless the selected release test passed."""
import re
import sys
from pathlib import Path

TEST_CLASS = "com.flint.peakfocus.e2e.FlintSmokeTest"
TEST_METHOD = "authoredRuleSurvivesProcessRestartAndShieldBreakWorks"


def verify(text):
    statuses = []
    current = {}
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, separator, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            if separator:
                current[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            statuses.append((int(line.split(": ", 1)[1]), current))
            current = {}
    def require(condition, message):
        if not condition:
            raise ValueError(message)

    require([code for code, _ in statuses] == [1, 0], "Expected exactly one test start and one passing completion")
    for _, status in statuses:
        require(status.get("class") == TEST_CLASS and status.get("test") == TEST_METHOD, "Unexpected selected test")
        require(status.get("current") == "1" and status.get("numtests") == "1", "Missing or unexpected test count")
    require(re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", text, re.M) == ["-1"], "Missing successful terminal code")
    require(re.search(r"^OK \(1 test\)\s*$", text, re.M), "Missing runner success summary")
    require(not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed", text), "Instrumentation reported failure")


if __name__ == "__main__":
    try:
        verify(Path(sys.argv[1]).read_text())
    except (AssertionError, ValueError, OSError, IndexError) as error:
        raise SystemExit(f"Release instrumentation did not prove the selected test passed: {error}")
    print("Release instrumentation verified: one selected test passed.")
