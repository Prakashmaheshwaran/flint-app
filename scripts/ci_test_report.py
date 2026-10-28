#!/usr/bin/env python3
"""Summarize actual JUnit reports; convert Xcode 16+ result bundles without dependencies."""
import argparse
import glob
import html
import json
import os
from pathlib import Path
import subprocess
import sys
import xml.etree.ElementTree as ET


def xcresult_junit(bundle, output):
    raw = subprocess.check_output([
        "xcrun", "xcresulttool", "get", "test-results", "tests", "--path", str(bundle),
        "--compact",
    ], text=True)
    report = json.loads(raw)
    suite = ET.Element("testsuite", name="Xcode")
    failed_ancestors = []
    infrastructure_messages = []

    def visit(node, parent="Xcode"):
        if node["nodeType"] == "Test Case":
            case = ET.SubElement(suite, "testcase", classname=parent, name=node["name"])
            if "durationInSeconds" in node:
                case.set("time", str(node["durationInSeconds"]))
            result = node.get("result", "unknown")
            if result == "Failed":
                ET.SubElement(case, "failure", message="See xcresult for failure details")
            elif result in ("Skipped", "Expected Failure"):
                ET.SubElement(case, "skipped", message=result)
            elif result != "Passed":
                ET.SubElement(case, "error", message=f"Unresolved Xcode result: {result}")
            # Child runs/attachments describe this test, not additional test methods.
            return
        if node.get("result") in ("Failed", "unknown"):
            failed_ancestors.append(node["name"])
        if node["nodeType"] == "Failure Message":
            infrastructure_messages.append(node["name"])
        for child in node.get("children", []):
            visit(child, node["name"])

    for node in report["testNodes"]:
        visit(node)
    cases = list(suite)
    if infrastructure_messages or (failed_ancestors and not any(
        case.find("failure") is not None or case.find("error") is not None for case in cases
    )):
        # Do not turn a runner/plan failure into an apparently clean JUnit artifact.
        # Keep the raw xcresult and an explicit unavailable-export diagnostic instead.
        raise ValueError("Xcode execution failed outside reported test cases; "
                         "JUnit export is incomplete. Inspect the retained xcresult.")
    suite.set("tests", str(len(cases)))
    for name in ("failure", "error", "skipped"):
        suite.set(name if name == "skipped" else name + "s",
                  str(sum(case.find(name) is not None for case in cases)))
    output.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(suite).write(output, encoding="utf-8", xml_declaration=True)


def counts(path):
    root = ET.parse(path).getroot()
    cases = list(root.iter("testcase"))
    # Count test cases, never aggregate testsuite attributes (which can be nested).
    failed = sum(case.find("failure") is not None for case in cases)
    errors = sum(case.find("error") is not None for case in cases)
    skipped = sum(case.find("skipped") is not None for case in cases)
    return len(cases), failed, errors, skipped


def summarize(paths, title):
    total = [0, 0, 0, 0]
    lines = [f"## {html.escape(title)}", "",
             "| Report | Tests | Failures | Errors | Skipped |",
             "| --- | ---: | ---: | ---: | ---: |"]
    for path in paths:
        values = counts(path)
        total = [a + b for a, b in zip(total, values)]
        label = html.escape(str(path)).replace("|", "&#124;")
        lines.append(f"| {label} | " + " | ".join(map(str, values)) + " |")
    lines += ["| **Total** | " + " | ".join(map(str, total)) + " |", "",
              "Counts are reported test cases, including separate Gradle variants; "
              "cached results do not establish fresh execution. "
              "Xcode expected failures are represented as JUnit skips.", ""]
    if not total[0]:
        lines += ["**No test cases found: this is not passing test evidence.**", ""]
    return "\n".join(lines), total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--junit", action="append", default=[], help="JUnit glob (repeatable)")
    parser.add_argument("--xcresult", type=Path)
    parser.add_argument("--output", type=Path, help="JUnit output for --xcresult")
    parser.add_argument("--title", default="Test results")
    parser.add_argument("--summary", type=Path, default=os.environ.get("GITHUB_STEP_SUMMARY"))
    args = parser.parse_args()
    paths = {Path(p) for pattern in args.junit for p in glob.glob(pattern, recursive=True)}
    try:
        if args.xcresult:
            if not args.output:
                parser.error("--xcresult requires --output")
            xcresult_junit(args.xcresult, args.output)
            paths.add(args.output)
        summary, total = summarize(sorted(paths), args.title)
        status = 1 if not total[0] or total[1] or total[2] else 0
    except (OSError, ValueError, KeyError, ET.ParseError, subprocess.CalledProcessError) as exc:
        summary = (f"## {html.escape(args.title)}\n\n**Test results unavailable or incomplete.** "
                   f"No passing test evidence is inferred.\n\n{html.escape(str(exc))}\n")
        if args.output:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.with_suffix(".error.txt").write_text(summary)
        status = 1
    print(summary)
    if args.summary:
        with args.summary.open("a") as out:
            out.write(summary + "\n")
    return status


if __name__ == "__main__":
    sys.exit(main())
