#!/usr/bin/env bash
# Smoke test of an APK on a running emulator or device. The ci workflow's smoke
# job runs it on an emulator with the release APK, which is built with R8: a
# class R8 removed by mistake shows up here as a crash or an error in the
# player, not in a debug build. It works locally too, against whatever adb sees:
#
#   APK=app/build/outputs/apk/release/app-release.apk .github/scripts/smoke-test.sh
#
# Writes a markdown table to $SUMMARY (for the run summary and the pull request
# comment) and screenshots to $SCREENSHOTS, and fails when a check fails. When a
# check fails, the app's log follows in the output.
#
# The player check needs YouTube. When YouTube won't serve the video to the
# network the test runs on (it limits datacenter IPs like CI's), the check
# passes with a warning: only an error from the app itself fails it.
set -uo pipefail

apk=${APK:?set APK to the APK to test}
summary=${SUMMARY:-smoke-summary.md}
shots=${SCREENSHOTS:-smoke-screenshots}
pkg=com.example.antenna
video=${VIDEO:-https://www.youtube.com/watch?v=dQw4w9WgXcQ} # public, and up for 15+ years
adb="${ANDROID_HOME:+$ANDROID_HOME/platform-tools/}adb"
mkdir -p "$shots"

ui() { "$adb" shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1 && "$adb" shell cat /sdcard/ui.xml; }
screenshot() { "$adb" exec-out screencap -p > "$shots/$1.png" 2> /dev/null; }
running() { [ -n "$("$adb" shell pidof "$pkg" | tr -d '\r')" ]; }
# (No "| grep -q": with pipefail, grep stopping early could hide a crash.)
crashed() { [[ "$("$adb" logcat -b crash -d)" == *"Process: $pkg"* ]]; }
# The text of the view with the given test tag (Compose exposes test tags to
# UI Automator as resource ids). Parsed as XML: the dump quotes and escapes
# texts in more than one way.
tagged() { # <tag>
  python3 -I -c '
import sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit()
for node in root.iter("node"):
    if node.get("resource-id") == sys.argv[1]:
        print(node.get("text", ""))
        break
' "$1"
}

# Each check is a function. It can set $note (shown next to the result) and
# $warn (passed, with a warning). After the first failure the rest don't run.
rows=()
failed=""
check() { # <what it checks> <function>
  note="" warn=""
  if [ -n "$failed" ]; then
    rows+=("| $1 | ⏭️ not run |")
  elif "$2"; then
    rows+=("| $1 | ${warn:+⚠️ }${warn:-✅}${note:+ $note} |")
  else
    rows+=("| $1 | ❌ ${note:-failed} |")
    failed=$1
  fi
}

installs() {
  local out
  out=$("$adb" install -r -g "$apk" 2>&1)
  grep -q '^Success' <<< "$out" || { note=$(tail -1 <<< "$out"); return 1; }
}

starts() {
  local out time
  "$adb" shell am force-stop "$pkg"
  "$adb" logcat -c
  out=$("$adb" shell am start -W -n "$pkg/.ui.MainActivity" 2>&1 | tr -d '\r')
  time=$(sed -n 's/^TotalTime: *\([0-9]*\)$/\1/p' <<< "$out")
  [ "${time:-0}" -gt 0 ] && note="in $time ms"
  sleep 3
  running && ! crashed
}

home_screen() {
  local xml
  for _ in $(seq 15); do
    xml=$(ui)
    if grep -q 'text="For you"' <<< "$xml" && grep -q 'text="Search"' <<< "$xml"; then
      screenshot home
      return 0
    fi
    sleep 2
  done
  screenshot home
  note="the tabs never showed"
  return 1
}

player() {
  local xml title error
  "$adb" shell am start -W -n "$pkg/.ui.PlayerActivity" --es url "$video" > /dev/null 2>&1
  for _ in $(seq 30); do
    sleep 2
    if crashed || ! running; then
      note="the app crashed"
      return 1
    fi
    # The player tags its title and its error message (TAG_TITLE, TAG_ERROR in
    # PlayerActivity.kt), so they're found whatever else is on screen.
    xml=$(ui)
    title=$(tagged player_title <<< "$xml")
    error=$(tagged player_error <<< "$xml")
    if [ -n "$title" ]; then
      screenshot player
      note="it loaded “$title”"
      return 0
    fi
    if [ -n "$error" ]; then
      screenshot player
      case "$error" in
        "YouTube is limiting requests"* | "Can"*"t reach YouTube"*)
          warn="YouTube didn't serve the video to this network, so playback wasn't checked: $error"
          return 0
          ;;
      esac
      note="the player shows an error: $error"
      return 1
    fi
  done
  screenshot player
  note="still loading after a minute"
  return 1
}

no_crash() {
  if crashed; then
    note="see the crash in the job log"
    return 1
  fi
  running || { note="the app is no longer running"; return 1; }
}

# Awake and unlocked, so the app is what's on screen (an emulator boots to its
# lock screen), and staying awake while the test runs.
"$adb" shell input keyevent KEYCODE_WAKEUP
"$adb" shell wm dismiss-keyguard
"$adb" shell settings put system screen_off_timeout 600000

check "It installs" installs
check "It starts" starts
check "The home screen shows its tabs" home_screen
check "The player opens a video" player
check "Nothing crashed" no_crash

aapt2=${AAPT2:-$(find "${ANDROID_HOME:-/nonexistent}/build-tools" -name aapt2 2> /dev/null | sort -V | tail -1)}
version=$("${aapt2:-aapt2}" dump badging "$apk" 2> /dev/null | sed -n "s/.*versionName='\([^']*\)'.*/\1/p")
size=$(awk -v b="$(stat -c %s "$apk")" 'BEGIN { printf "%.1f", b / 1e6 }')
device=$("$adb" shell getprop ro.build.version.release | tr -d '\r')
{
  echo "## Smoke test"
  echo
  echo "The release APK${version:+ (v$version}, $size MB${version:+)}, started on an Android ${device:-?} emulator and checked."
  echo
  echo "| Check | Result |"
  echo "| --- | --- |"
  printf '%s\n' "${rows[@]}"
} > "$summary"
cat "$summary"

if [ -n "$failed" ]; then
  echo "::error::Smoke test failed: $failed"
  echo "--- Crashes:"
  "$adb" logcat -b crash -d
  echo "--- The app's log:"
  "$adb" logcat -d | grep -E "$pkg|AndroidRuntime|ExoPlayer" | tail -200
  exit 1
fi
