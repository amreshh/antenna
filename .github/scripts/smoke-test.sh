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
# The player, search and channel checks need YouTube. When YouTube won't serve
# the network the test runs on (it limits datacenter IPs like CI's), they pass
# with a warning: only an error from the app itself fails them.
#
# It also measures performance, with budgets that fail the test when exceeded
# (generous ones, for an emulator: they catch big regressions, not small ones):
#   STARTUP_BUDGET_MS  median cold start of 5, from launch to first frame (4000)
#   MEMORY_BUDGET_MB   memory (PSS) with a video loaded (400)
# and reports how long the video took to start playing, without a budget: that
# depends on YouTube's servers more than on the app.
set -uo pipefail

apk=${APK:?set APK to the APK to test}
summary=${SUMMARY:-smoke-summary.md}
shots=${SCREENSHOTS:-smoke-screenshots}
pkg=com.example.antenna
video=${VIDEO:-https://www.youtube.com/watch?v=dQw4w9WgXcQ} # public, and up for 15+ years
query=${QUERY:-rick astley}
startup_budget=${STARTUP_BUDGET_MS:-4000}
memory_budget=${MEMORY_BUDGET_MB:-400}
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

# The middle of the view with the given test tag or content description, for a
# tap: "x y", or nothing when it isn't on screen.
center() { # <attribute> <value>
  python3 -I -c '
import re, sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit()
for node in root.iter("node"):
    if node.get(sys.argv[1]) == sys.argv[2]:
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
' "$1" "$2"
}
# YouTube refusing this network (see the top), from an error message.
limited() { [[ "$1" == "YouTube is limiting requests"* || "$1" == "Can"*"t reach YouTube"* ]]; }

# Performance numbers, for their own table: "| what | value | budget |".
perf=()

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
    if grep -q 'text="For you"' <<< "$xml" && grep -q 'content-desc="Search"' <<< "$xml"; then
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
  # Opened the way a link from another app opens it (the app is already running).
  "$adb" shell am start -W -a android.intent.action.VIEW -d "$video" -n "$pkg/.ui.MainActivity" > /dev/null 2>&1
  for _ in $(seq 30); do
    sleep 2
    if crashed || ! running; then
      note="the app crashed"
      return 1
    fi
    # The player tags its title and its error message (TAG_TITLE, TAG_ERROR in
    # PlayerScreen.kt), so they're found whatever else is on screen.
    xml=$(ui)
    title=$(tagged player_title <<< "$xml")
    error=$(tagged player_error <<< "$xml")
    # Loaded. A playback error shows above the details (the title stays), so give the
    # video a few seconds to start, and fail if an error turns up meanwhile.
    if [ -n "$title" ] && [ -z "$error" ]; then
      for _ in 1 2 3 4; do
        sleep 2
        error=$(tagged player_error <<< "$(ui)")
        [ -z "$error" ] || break
      done
    fi
    if [ -n "$title" ] && [ -z "$error" ]; then
      if crashed || ! running; then
        note="the app crashed"
        return 1
      fi
      screenshot player
      note="it loaded and plays “$title”"
      # How long it took to start playing, as the player logs it (logStart in
      # PlayerViewModel.kt): fetching the video's details and links, then buffering.
      local start
      start=$("$adb" logcat -d -s Player:I | tr -d '\r' |
        sed -n 's/.*Playing after \([0-9]*\) ms (details \([0-9]*\) ms, buffering \([0-9]*\) ms.*/\1 ms (details \2 ms, buffering \3 ms)/p' | tail -1)
      [ -n "$start" ] && perf+=("| Video start, link to playing | $start | none: it depends on YouTube's servers |")
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

# Back shrinks the player into the mini player (TAG_MINI) above the tabs.
minimizes() {
  local xml
  "$adb" shell input keyevent KEYCODE_BACK
  for _ in $(seq 10); do
    sleep 1
    xml=$(ui)
    if grep -q 'resource-id="mini_player"' <<< "$xml" && grep -q 'text="For you"' <<< "$xml"; then
      screenshot mini-player
      return 0
    fi
  done
  screenshot mini-player
  note="no mini player over the tabs after Back"
  return 1
}

# Memory (PSS) with the video loaded, after the player check.
memory() {
  local kb mb
  kb=$("$adb" shell dumpsys meminfo "$pkg" | tr -d '\r' |
    sed -n -e 's/^ *TOTAL PSS: *\([0-9]*\).*/\1/p' -e 's/^ *TOTAL *\([0-9]*\) .*/\1/p' | head -1)
  [ -n "$kb" ] || { note="dumpsys meminfo gave no total"; return 1; }
  mb=$((kb / 1024))
  note="$mb MB"
  perf+=("| Memory (PSS) with a video loaded | $mb MB | under $memory_budget MB |")
  [ "$mb" -le "$memory_budget" ] || { note="$mb MB, over the $memory_budget MB budget"; return 1; }
}

# The magnifier in the top bar opens search; a query shows results, each with
# its channel's avatar (TAG_CHANNEL_AVATAR in VideoLayouts.kt).
search() {
  local xml at error
  xml=$(ui)
  at=$(center content-desc Search <<< "$xml")
  [ -n "$at" ] || { note="no Search button in the top bar"; return 1; }
  # shellcheck disable=SC2086 # "x y"
  "$adb" shell input tap $at
  sleep 2
  "$adb" shell input text "${query// /%s}"
  "$adb" shell input keyevent KEYCODE_ENTER
  for _ in $(seq 20); do
    sleep 2
    if crashed || ! running; then
      note="the app crashed"
      return 1
    fi
    xml=$(ui)
    if [ -n "$(center resource-id channel_avatar <<< "$xml")" ]; then
      screenshot search
      note="for “$query”"
      return 0
    fi
    error=$(tagged search_error <<< "$xml")
    if [ -n "$error" ]; then
      screenshot search
      if limited "$error"; then
        warn="YouTube didn't answer this network, so search wasn't checked: $error"
        return 0
      fi
      note="search shows an error: $error"
      return 1
    fi
  done
  screenshot search
  note="no results after 40 seconds"
  return 1
}

# A result's channel avatar opens the channel's page (TAG_CHANNEL_NAME in
# ChannelScreen.kt), and Back returns to the results.
channel() {
  local xml at name error
  xml=$(ui)
  at=$(center resource-id channel_avatar <<< "$xml")
  if [ -z "$at" ]; then
    warn="no search results to open a channel from"
    return 0
  fi
  # shellcheck disable=SC2086 # "x y"
  "$adb" shell input tap $at
  for _ in $(seq 20); do
    sleep 2
    if crashed || ! running; then
      note="the app crashed"
      return 1
    fi
    xml=$(ui)
    name=$(tagged channel_name <<< "$xml")
    error=$(tagged channel_error <<< "$xml")
    if [ -n "$error" ]; then
      screenshot channel
      if limited "$error"; then
        warn="YouTube didn't answer this network, so the channel page wasn't checked: $error"
        return 0
      fi
      note="the channel page shows an error: $error"
      return 1
    fi
    # Its videos have loaded once a video's channel line is there.
    if [ -n "$name" ] && grep -q 'text="[^"]* · [^"]*"' <<< "$xml"; then
      screenshot channel
      note="it opened “$name”"
      "$adb" shell input keyevent KEYCODE_BACK
      sleep 2
      [ -n "$(center resource-id channel_avatar <<< "$(ui)")" ] && return 0
      note="Back from “$name” didn't return to the results"
      return 1
    fi
  done
  screenshot channel
  note="the channel page didn't load in 40 seconds"
  return 1
}

# Cold starts: the app stopped, then started 5 times; the median time from
# launch to its first frame (am start -W's TotalTime). Runs last: it restarts
# the app.
cold_start() {
  local times=() out t median
  for _ in 1 2 3 4 5; do
    "$adb" shell am force-stop "$pkg"
    sleep 1
    out=$("$adb" shell am start -W -n "$pkg/.ui.MainActivity" 2>&1 | tr -d '\r')
    t=$(sed -n 's/^TotalTime: *\([0-9]*\)$/\1/p' <<< "$out")
    [ -n "$t" ] || { note="am start gave no time"; return 1; }
    times+=("$t")
    sleep 2
  done
  crashed && { note="the app crashed"; return 1; }
  median=$(printf '%s\n' "${times[@]}" | sort -n | sed -n 3p)
  note="$median ms (runs: ${times[*]} ms)"
  perf+=("| Cold start, launch to first frame (median of 5) | $median ms | under $startup_budget ms |")
  [ "$median" -le "$startup_budget" ] || { note="$median ms, over the $startup_budget ms budget (runs: ${times[*]} ms)"; return 1; }
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
check "Memory with a video loaded" memory
check "Back minimizes the player" minimizes
check "Search shows results" search
check "A result's avatar opens its channel" channel
check "Nothing crashed" no_crash
check "Cold start" cold_start

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
  if [ ${#perf[@]} -gt 0 ]; then
    echo
    echo "**Performance** (on the emulator, so slower than a phone; the budgets catch big regressions)"
    echo
    echo "| Measured | Value | Budget |"
    echo "| --- | --- | --- |"
    printf '%s\n' "${perf[@]}"
    echo "| APK size | $size MB | checked by the build job |"
  fi
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
