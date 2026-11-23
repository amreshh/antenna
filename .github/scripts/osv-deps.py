#!/usr/bin/env python3
"""Known vulnerabilities in the libraries the app ships, from the OSV database
(osv.dev, which gathers GitHub's and Maven's advisories). The ci workflow's deps
job runs it on every pull request; Dependabot's security updates then fix what it
finds, and this keeps such a pull request from being merged as is.

    ./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath > deps.txt
    python3 -I .github/scripts/osv-deps.py deps.txt > deps-summary.md

Reads Gradle's dependency tree (each library at the version Gradle resolved it
to: what goes into the APK), asks OSV about all of them at once, and writes a
markdown table. Exits 1 when a library has a known vulnerability, unless its id
is listed in .github/osv-ignore.txt with the reason it doesn't apply. Only the
library names and versions are sent.
"""
import json
import re
import sys
import urllib.request
from pathlib import Path

OSV = "https://api.osv.dev/v1"
IGNORE_FILE = Path(__file__).resolve().parent.parent / "osv-ignore.txt"
# "+--- group:artifact:version", "... -> resolved", "(*)" repeats; "(c)" constraints
# and "(n)" unresolved ones aren't in the APK.
LINE = re.compile(r"[+\\]--- ([\w.\-]+):([\w.\-]+):(\S+)(?: -> (\S+))?(.*)$")


def post(path, body):
    request = urllib.request.Request(f"{OSV}/{path}", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def get(path):
    with urllib.request.urlopen(f"{OSV}/{path}", timeout=60) as response:
        return json.load(response)


def libraries(tree):
    found = set()
    for line in tree.splitlines():
        m = LINE.search(line)
        if not m:
            continue
        group, artifact, version, resolved, rest = m.groups()
        if "(c)" in rest or "(n)" in rest or "FAILED" in rest:
            continue
        found.add((f"{group}:{artifact}", resolved or version))
    return sorted(found)


def ignored():
    if not IGNORE_FILE.exists():
        return set()
    ids = set()
    for line in IGNORE_FILE.read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            ids.add(line.split()[0])
    return ids


def severity(vuln):
    level = (vuln.get("database_specific") or {}).get("severity")
    return level.title() if level else "?"


def fixed_in(vuln, name):
    versions = []
    for affected in vuln.get("affected", []):
        if affected.get("package", {}).get("name") != name:
            continue
        for r in affected.get("ranges", []):
            versions += [e["fixed"] for e in r.get("events", []) if "fixed" in e]
    return ", ".join(sorted(set(versions))) or "no fix yet"


def cell(text):
    return " ".join(str(text).split()).replace("|", "\\|")


def main(path):
    libs = libraries(Path(path).read_text())
    if not libs:
        sys.exit("No dependencies found: is that Gradle's dependency tree?")
    queries = [{"package": {"ecosystem": "Maven", "name": n}, "version": v} for n, v in libs]
    results = []
    for i in range(0, len(queries), 1000):  # OSV's batch limit
        results += post("querybatch", {"queries": queries[i:i + 1000]})["results"]

    skip = ignored()
    rows, failing = [], 0
    for (name, version), result in zip(libs, results):
        for ref in result.get("vulns", []):
            vuln = get(f"vulns/{ref['id']}")
            ids = [vuln["id"]] + vuln.get("aliases", [])
            is_ignored = any(i in skip for i in ids)
            failing += not is_ignored
            link = f"[{vuln['id']}](https://osv.dev/vulnerability/{vuln['id']})"
            rows.append(f"| {'⚪ ignored' if is_ignored else '❌'} | `{name}` {version} | {link} "
                        f"| {severity(vuln)} | {fixed_in(vuln, name)} | {cell(vuln.get('summary', ''))} |")

    print("## Dependency vulnerabilities")
    print()
    if not rows:
        print(f"None known in the {len(libs)} libraries the release APK is built from (checked on osv.dev).")
        return 0
    print(f"{len(libs)} libraries checked on osv.dev:")
    print()
    print("| | Library | Advisory | Severity | Fixed in | Summary |")
    print("| --- | --- | --- | --- | --- | --- |")
    print("\n".join(rows))
    if failing:
        print()
        print("Update the library (Dependabot's security updates usually open a pull request), or, if the "
              "vulnerability can't affect the app, add its id and the reason to `.github/osv-ignore.txt`.")
        print(f"::error::{failing} known vulnerabilit{'y' if failing == 1 else 'ies'} in the app's libraries", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
