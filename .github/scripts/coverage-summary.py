#!/usr/bin/env python3
"""Markdown summary of the unit tests' code coverage, from JaCoCo's XML report,
for the run summary and the pull request comment (the ci workflow's test job).

    python3 .github/scripts/coverage-summary.py report.xml \\
        [--base base-report.xml --base-name main] [--diff changes.diff] > coverage-summary.md

How much of the app's code the unit tests run, in all and by package. With
--base (the same report for the base branch's code), how that changed: whether
the pull request raises or lowers the coverage. With --diff (git diff
--unified=0 from the base branch, of app/src/main/java), how much of the code
the pull request adds or changes the tests run, and where they don't.

Lines are what JaCoCo counts as lines, those with code in them (not comments,
blank lines or bare declarations), and a line runs when any of it does. The
whole report, down to each line, is the run's coverage-report artifact.
"""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path

SOURCES = "app/src/main/java/"
MAX_FILES = 10  # files listed with changed lines the tests don't run


def cell(text):
    """Text that is safe inside a markdown table cell."""
    text = " ".join(str(text).split())
    return text.replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;").replace("`", "'")


def counter(element, kind):
    """(covered, total) of one of JaCoCo's counters (LINE, BRANCH…) on an element."""
    for c in element.findall("counter"):
        if c.get("type") == kind:
            covered = int(c.get("covered", 0))
            return covered, covered + int(c.get("missed", 0))
    return 0, 0


def percent(covered, total):
    return 100.0 * covered / total if total else None


def share(covered, total):
    """'57.3% (908 of 1,586)', or '–' when there's nothing to count."""
    return f"{100.0 * covered / total:.1f}% ({covered:,} of {total:,})" if total else "–"


def change(now, before):
    """'+0.4', '−0.3' or '0.0' percentage points, or '–' without both."""
    if now is None or before is None:
        return "–"
    points = round(now - before, 1)
    if points == 0:
        return "0.0"
    return f"+{points:.1f}" if points > 0 else f"−{-points:.1f}"


class Report:
    """A JaCoCo XML report: the totals, each package's, and each source file's lines."""

    def __init__(self, path):
        root = ET.parse(path).getroot()
        self.lines = counter(root, "LINE")
        self.branches = counter(root, "BRANCH")
        self.packages = {}
        self.files = {}  # (package path, file name) → {line number: whether it ran}
        for package in root.findall("package"):
            name = package.get("name", "")
            self.packages[name] = (counter(package, "LINE"), counter(package, "BRANCH"))
            for source in package.findall("sourcefile"):
                self.files[(name, source.get("name", ""))] = {
                    int(line.get("nr")): int(line.get("ci", 0)) > 0 for line in source.findall("line")
                }


def changed_lines(diff_text):
    """The lines a unified diff (--unified=0) adds or changes, by file: {path: {line numbers}}."""
    changed = {}
    path = None
    for line in diff_text.splitlines():
        if line.startswith("+++ "):
            target = line[4:].strip()
            path = target[2:] if target.startswith("b/") else None  # /dev/null: a deleted file
            continue
        match = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
        if match and path:
            start, count = int(match.group(1)), int(match.group(2) or 1)
            changed.setdefault(path, set()).update(range(start, start + count))
    return changed


def changed_coverage(report, diff_text):
    """(lines that ran, lines with code) of what the diff changes, and by file, the most not run first."""
    by_file = []
    for path, numbers in changed_lines(diff_text).items():
        if not path.startswith(SOURCES) or not path.endswith((".kt", ".java")):
            continue
        relative = path[len(SOURCES):]
        package, _, name = relative.rpartition("/")
        lines = report.files.get((package, name), {})
        code = [n for n in numbers if n in lines]
        if code:
            by_file.append((relative, sum(lines[n] for n in code), len(code)))
    by_file.sort(key=lambda f: (f[1] - f[2], f[0]))
    return sum(f[1] for f in by_file), sum(f[2] for f in by_file), by_file


def short_names(packages):
    """The packages without the part they all start with: com/antenna/data → data."""
    names = [name.split("/") for name in packages]
    common = names[0] if len(names) > 1 else []
    for parts in names[1:]:
        while parts[: len(common)] != common:
            common = common[:-1]
    return {name: ".".join(name.split("/")[len(common):]) or "(top level)" for name in packages}


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("report")
    parser.add_argument("--base", help="the base branch's report, to compare with")
    parser.add_argument("--base-name", help="the base branch, as shown")
    parser.add_argument("--diff", help="the pull request's changes: git diff --unified=0")
    args = parser.parse_args()

    now = Report(args.report)
    base = Report(args.base) if args.base and Path(args.base).is_file() else None
    where = f"`{cell(args.base_name or 'the base branch')}`"

    print("## Code coverage")
    print()
    if not now.lines[1]:
        print("No coverage was recorded.")
        return
    total = percent(*now.lines)
    headline = (
        f"The unit tests run **{total:.1f}%** of the app's lines ({now.lines[0]:,} of {now.lines[1]:,}) "
        f"and {percent(*now.branches) or 0:.1f}% of its branches"
    )
    if base is not None and base.lines[1]:
        before = percent(*base.lines)
        points = round(total - before, 1)
        if points > 0:
            headline += f": 📈 up {points:.1f} points from {where} ({before:.1f}%)."
        elif points < 0:
            headline += f": 📉 down {-points:.1f} points from {where} ({before:.1f}%)."
        else:
            headline += f", the same as {where}."
    elif args.base_name:
        headline += f". ({where} has no coverage to compare with yet.)"
    else:
        headline += "."
    print(headline)

    by_file = []
    if args.diff:
        ran, code, by_file = changed_coverage(now, Path(args.diff).read_text(encoding="utf-8", errors="replace"))
        print()
        if code:
            print(f"Of the lines this pull request adds or changes, the tests run **{100.0 * ran / code:.1f}%** ({ran:,} of {code:,}).")
        else:
            print("This pull request adds or changes no lines with code in them.")

    compare = base is not None and bool(base.lines[1])
    names = short_names(now.packages)
    # Matched by their short names, so a new name for the app's own package (all of
    # them start with it) doesn't make every package new.
    before_by_name = {}
    if compare:
        base_names = short_names(base.packages)
        before_by_name = {base_names[name]: counts for name, counts in base.packages.items()}
    print()
    print("| Package | Lines run | Branches run |" + (f" vs {where} |" if compare else ""))
    print("| --- | --- | --- |" + (" --- |" if compare else ""))
    for name, (lines, branches) in sorted(now.packages.items(), key=lambda p: -p[1][0][1]):  # biggest first
        row = f"| {cell(names[name])} | {share(*lines)} | {share(*branches)} |"
        if compare:
            old = before_by_name.get(names[name])
            row += f" {change(percent(*lines), percent(*old[0])) if old else 'new'} |"
        print(row)
    last = f"| **All** | **{share(*now.lines)}** | **{share(*now.branches)}** |"
    if compare:
        last += f" **{change(total, percent(*base.lines))}** |"
    print(last)

    missed = [f for f in by_file if f[1] < f[2]]
    if missed:
        print()
        print("Changed lines the tests don't run, by file:")
        print()
        print("| File | Changed lines run |")
        print("| --- | --- |")
        for path, ran_in_file, code_in_file in missed[:MAX_FILES]:
            print(f"| {cell(path)} | {ran_in_file:,} of {code_in_file:,} |")
        if len(missed) > MAX_FILES:
            print()
            print(f"…and {len(missed) - MAX_FILES} more files.")


if __name__ == "__main__":
    main()
