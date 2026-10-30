#!/usr/bin/env python3
"""Reject adb's false-success exit status unless every exact selected release test passed."""
import argparse
import re
from pathlib import Path

TEST_CLASS = "com.flint.peakfocus.e2e.FlintSmokeTest"
TEST_METHOD = "authoredRuleSurvivesProcessRestartAndShieldBreakWorks"


def verify(text, expected_tests=None):
    expected_tests = [(TEST_CLASS, TEST_METHOD)] if expected_tests is None else list(expected_tests)
    if not expected_tests or len(set(expected_tests)) != len(expected_tests):
        raise ValueError("Expected test IDs must be nonempty and unique")
    count = len(expected_tests)
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

    require([code for code, _ in statuses] == [1, 0] * count, "Expected one start and passing completion per selected test")
    observed = []
    for index in range(count):
        pair = statuses[index * 2:index * 2 + 2]
        identities = [(status.get("class"), status.get("test")) for _, status in pair]
        require(identities[0] == identities[1] and identities[0] in expected_tests, "Unexpected selected test")
        observed.append(identities[0])
        for _, status in pair:
            require(status.get("current") == str(index + 1) and status.get("numtests") == str(count),
                    "Missing or unexpected test count")
    require(set(observed) == set(expected_tests), "Missing or duplicate selected test")
    require(re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)\s*$", text, re.M) == ["-1"], "Missing successful terminal code")
    summaries = re.findall(r"^OK \((\d+) tests?\)\s*$", text, re.M)
    require(summaries == [str(count)], "Missing or unexpected runner success summary")
    require(not re.search(r"FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|shortMsg=|Process crashed", text), "Instrumentation reported failure")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("path")
    parser.add_argument("--test", action="append", help="Exact expected CLASS#METHOD; repeat for each selected test")
    args = parser.parse_args()
    try:
        expected = None
        if args.test is not None:
            expected = []
            for identity in args.test:
                parts = identity.split("#")
                if len(parts) != 2 or not all(parts):
                    raise ValueError("Expected exact CLASS#METHOD")
                expected.append(tuple(parts))
        verify(Path(args.path).read_text(), expected)
    except (AssertionError, ValueError, OSError, IndexError) as error:
        raise SystemExit(f"Release instrumentation did not prove the selected test passed: {error}")
    print(f"Release instrumentation verified: {len(expected) if expected is not None else 1} exact selected test(s) passed.")
