#!/usr/bin/env python3
"""Markdown summary of the unit test results, from the JUnit XML reports Gradle
writes, for the run summary and the pull request comment (the ci workflow's
test job).

    python3 .github/scripts/test-summary.py app/build/test-results > tests-summary.md
"""
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_ROWS = 50  # the job's log has them all


def cell(text):
    """Text that is safe inside a markdown table cell."""
    text = " ".join(str(text).split())
    return text.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;")


def main(results_dir):
    suites = []
    for report in sorted(Path(results_dir).rglob("TEST-*.xml")):
        try:
            root = ET.parse(report).getroot()
        except ET.ParseError:
            continue
        suites += [root] if root.tag == "testsuite" else list(root.iter("testsuite"))

    tests = sum(int(s.get("tests", 0)) for s in suites)
    skipped = sum(int(s.get("skipped", 0)) for s in suites)
    failed = []
    for suite in suites:
        for case in suite.iter("testcase"):
            problem = case.find("failure")
            if problem is None:
                problem = case.find("error")
            if problem is not None:
                name = f"{suite.get('name', '?')}.{case.get('name', '?')}"
                failed.append((name, problem.get("message") or problem.get("type") or ""))

    print("## Unit tests")
    print()
    if tests == 0:
        print("No unit tests yet (they go in `app/src/test`).")
        return
    print(f"{tests - len(failed) - skipped} passed, {len(failed)} failed, {skipped} skipped.")
    if failed:
        print()
        print("| Test | Failure |")
        print("| --- | --- |")
        for name, message in failed[:MAX_ROWS]:
            print(f"| {cell(name)} | {cell(message)} |")
        if len(failed) > MAX_ROWS:
            print()
            print(f"…and {len(failed) - MAX_ROWS} more: see the job's log.")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/build/test-results")
