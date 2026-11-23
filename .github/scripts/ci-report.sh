#!/usr/bin/env bash
# The pull request comment: what merging does, each check's result, and the
# tables the jobs left behind (*-summary.md in the current folder: tests, apk,
# apk-security, smoke, lint, zizmor, deps). The ci workflow's report job runs it and posts the
# result, updating its earlier comment (found by the first line, a marker).
#
#   ci-report.sh > comment.md
#
# Environment: SHA (the pull request's head commit), BASE (its base branch),
# VERSION (what merging releases), the jobs' results (TEST, LINT, WORKFLOWS,
# DEPS, BUILD, SMOKE: success, failure, cancelled or skipped), APK_URL (the APK
# artifact), RUN_URL, PR (its number), and GITHUB_SERVER_URL and
# GITHUB_REPOSITORY as GitHub Actions sets them.
set -euo pipefail

icon() {
  case "$1" in
    success) echo "✅ passed" ;;
    failure) echo "❌ failed" ;;
    cancelled) echo "⚪ cancelled" ;;
    skipped) echo "⏭️ skipped" ;;
    *) echo "$1" ;;
  esac
}

# A table from one of the jobs, folded under its heading; open when asked to
# (a failed check, or a short table that's always worth seeing).
section() { # <file> [open]
  [ -f "$1" ] || return 0
  local title
  title=$(sed -n '1s/^## //p' "$1")
  echo "<details${2:+ open}><summary><b>${title:-$1}</b></summary>"
  echo
  tail -n +2 "$1"
  echo
  echo "</details>"
  echo
}

# "open" when a check didn't pass, to unfold its table.
open_unless_passed() { [ "$1" = success ] || echo open; }

all_passed=true
for result in "$TEST" "$LINT" "$WORKFLOWS" "$DEPS" "$BUILD" "$SMOKE"; do
  [ "$result" = success ] || all_passed=false
done

echo "<!-- antenna-ci-report -->"
echo "## CI for ${SHA:0:7}"
echo
if [ "$BASE" != main ]; then
  echo "This goes into \`$BASE\`: merging it releases nothing."
elif [ "$all_passed" = true ]; then
  echo "Merging this into \`main\` releases **v$VERSION**: this APK, signed with the release key. Nothing is rebuilt."
else
  echo "⚠️ Not everything passed. Merged anyway, \`main\` would build and release an APK of its own instead of this one."
fi
echo
echo "| Check | Result |"
echo "| --- | --- |"
echo "| Unit tests | $(icon "$TEST") |"
echo "| Android lint | $(icon "$LINT") |"
echo "| Workflows: actionlint, zizmor | $(icon "$WORKFLOWS") |"
echo "| Dependency vulnerabilities (osv.dev) | $(icon "$DEPS") |"
echo "| Release APK, built with R8, security checks | $(icon "$BUILD") |"
echo "| Smoke test and performance on an emulator | $(icon "$SMOKE") |"
echo

section apk-summary.md open
section apk-security-summary.md "$(open_unless_passed "$BUILD")"
section smoke-summary.md open
section deps-summary.md "$(open_unless_passed "$DEPS")"
section tests-summary.md "$(open_unless_passed "$TEST")"
section lint-summary.md "$(open_unless_passed "$LINT")"
section zizmor-summary.md "$(open_unless_passed "$WORKFLOWS")"

links=()
if [ -n "${APK_URL:-}" ]; then
  links+=("[Download the APK]($APK_URL) (signed with a test key: uninstall it before installing a release)")
fi
links+=("[Run details]($RUN_URL)")
links+=("[Code scanning alerts for this PR]($GITHUB_SERVER_URL/$GITHUB_REPOSITORY/security/code-scanning?query=pr%3A$PR+is%3Aopen)")
line=""
for link in "${links[@]}"; do
  line+="${line:+ · }$link"
done
echo "$line"
