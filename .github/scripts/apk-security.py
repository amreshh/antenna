#!/usr/bin/env python3
"""Security checks on the release APK, the way it ships (the ci workflow's build
job runs them right after building it). Each one guards a promise the app makes
or a setting that is easy to lose by accident, for example a library that adds a
permission or an exported component:

  - not debuggable
  - no plain-HTTP traffic: usesCleartextTraffic isn't on, and there's no network
    security config that could allow it (adding one should be a decision)
  - history and subscriptions stay out of cloud backups (allowBackup off, and
    dataExtractionRules for Android 12+), as the README promises
  - only the expected components are exported, and any but the main screen only
    behind a system permission
  - only the expected permissions are requested; permissions the app defines are
    signature-level
  - signed with APK Signature Scheme v2 or newer (v1 alone can be tampered with)
  - under the size budget (APK_BUDGET_MB, 8 MB by default)

    python3 -I .github/scripts/apk-security.py app-release.apk > apk-security-summary.md

Writes a markdown table and exits 1 when a check fails. Finds aapt2 and apksigner
in $ANDROID_HOME/build-tools.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.example.antenna"  # the app's id (its code is in com.antenna, below)
ALLOWED_PERMISSIONS = {
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",  # added by a library (OkHttp/Media3)
    f"{PACKAGE}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",  # AndroidX core, signature-level
}
MAIN_SCREEN = "com.antenna.ui.MainActivity"
# Exported components other than the main screen are fine only behind one of these.
SYSTEM_PERMISSIONS = {"android.permission.DUMP"}  # e.g. AndroidX's ProfileInstallReceiver
SIGNATURE = 0x2  # android:protectionLevel="signature"
ANDROID = "http://schemas.android.com/apk/res/android:"


def tool(name):
    home = os.environ.get("ANDROID_HOME", "")
    found = sorted(Path(home, "build-tools").glob(f"*/{name}"), key=lambda p: [int(x) for x in re.findall(r"\d+", p.parent.name)])
    if not found:
        sys.exit(f"{name} not found in $ANDROID_HOME/build-tools")
    return str(found[-1])


class Element:
    def __init__(self, name, depth):
        self.name, self.depth, self.attrs, self.children = name, depth, {}, []

    def get(self, attr):
        return self.attrs.get(attr)

    def iter(self, name):
        for child in self.children:
            if child.name == name:
                yield child
            yield from child.iter(name)


def manifest(apk):
    """The binary manifest, as a tree of elements (from aapt2's xmltree dump)."""
    out = subprocess.run([tool("aapt2"), "dump", "xmltree", "--file", "AndroidManifest.xml", apk],
                         check=True, capture_output=True, text=True).stdout
    root = Element("root", -1)
    stack = [root]
    for line in out.splitlines():
        depth = len(line) - len(line.lstrip())
        text = line.strip()
        if text.startswith("E: "):
            element = Element(text[3:].split(" ")[0], depth)
            while stack[-1].depth >= depth:
                stack.pop()
            stack[-1].children.append(element)
            stack.append(element)
        elif text.startswith("A: "):
            m = re.match(r"A: (?:" + re.escape(ANDROID) + r")?([\w:]+)(?:\(0x[0-9a-f]+\))?=(.*)", text)
            if m:
                name, value = m.groups()
                raw = re.search(r'\(Raw: "(.*)"\)$', value)
                value = raw.group(1) if raw else value.strip('"')
                while stack[-1].depth >= depth:
                    stack.pop()
                stack[-1].attrs[name] = value
    return root


def truthy(value):
    return value is not None and value.lower() in ("true", "0xffffffff", "-1")


def main(apk):
    m = manifest(apk)
    app = next(m.iter("application"))
    rows = []

    def check(what, ok, detail=""):
        rows.append((what, ok, detail))

    check("Not debuggable", not truthy(app.get("debuggable")), "android:debuggable is on" if truthy(app.get("debuggable")) else "")

    cleartext = truthy(app.get("usesCleartextTraffic"))
    config = app.get("networkSecurityConfig")
    check("No plain-HTTP traffic", not cleartext and config is None,
          "usesCleartextTraffic is on" if cleartext else
          f"a network security config ({config}) was added: check it allows no cleartext, then update this check" if config else
          "HTTPS only (Android's default)")

    backup_ok = app.get("allowBackup") is not None and not truthy(app.get("allowBackup")) and app.get("dataExtractionRules")
    check("Kept out of cloud backups", bool(backup_ok),
          "" if backup_ok else "allowBackup must be false and dataExtractionRules set (see the manifest)")

    problems = []
    exported = []
    for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        for c in m.iter(kind):
            # Components with intent filters are exported by default on old targets.
            if not truthy(c.get("exported")):
                continue
            name = c.get("name")
            exported.append(name.rsplit(".", 1)[-1])
            if name == MAIN_SCREEN:
                continue
            if c.get("permission") not in SYSTEM_PERMISSIONS:
                problems.append(f"{kind} {name} is exported without a system permission")
    check("Only the main screen is open to other apps", not problems,
          "; ".join(problems) or "exported: " + ", ".join(exported))

    requested = {p.get("name") for p in m.iter("uses-permission")}
    extra = sorted(requested - ALLOWED_PERMISSIONS)
    check("Only the expected permissions", not extra,
          "new: " + ", ".join(extra) + " (if it's needed, add it to ALLOWED_PERMISSIONS in this script)" if extra else
          ", ".join(sorted(p.rsplit(".", 1)[-1] for p in requested)))

    weak = [p.get("name") for p in m.iter("permission") if int(p.get("protectionLevel") or "0", 0) & SIGNATURE != SIGNATURE]
    check("The app's own permissions are signature-level", not weak, ", ".join(weak))

    verify = subprocess.run([tool("apksigner"), "verify", "--verbose", apk], capture_output=True, text=True).stdout
    schemes = [v for v in ("v2", "v3", "v3.1", "v4") if re.search(rf"\(APK Signature Scheme {v}\): true", verify)]
    check("Signed with signature scheme v2 or newer", bool(schemes), ", ".join(schemes) or "only v1 (JAR) signing")

    budget = float(os.environ.get("APK_BUDGET_MB", "8"))
    size = os.path.getsize(apk) / 1e6
    check("Size", size <= budget, f"{size:.1f} MB, budget {budget:g} MB")

    print("## Release APK security")
    print()
    print("| Check | Result |")
    print("| --- | --- |")
    for what, ok, detail in rows:
        detail = detail.replace("|", "\\|")
        print(f"| {what} | {'✅' if ok else '❌'}{' ' + detail if detail else ''} |")
    failed = [what for what, ok, _ in rows if not ok]
    if failed:
        print(f"::error::APK security checks failed: {', '.join(failed)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    sys.exit(main(sys.argv[1]))
