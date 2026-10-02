#!/usr/bin/env bash
# Copies capture clips and the event log from the TV into ./clips/ (gitignored).
# Usage: tools/pull-clips.sh [adb-serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1 # stop Git Bash rewriting /sdcard/... into a Windows path
cd "$(dirname "$0")/.."
ADB=(adb ${1:+-s "$1"})
SRC=/sdcard/Android/data/dev.kbobbili.xitelyrics/files
mkdir -p clips
"${ADB[@]}" pull "$SRC/clips/." clips/ >/dev/null
"${ADB[@]}" pull "$SRC/events.jsonl" clips/events.jsonl >/dev/null
echo "pulled $(ls clips/*.wav | wc -l) clips into clips/"
