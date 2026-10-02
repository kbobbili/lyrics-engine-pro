#!/usr/bin/env bash
# Copies continuous recordings and the event log from the TV into analysis/.
# Usage: tools/phase2/pull.sh [adb-serial]
set -euo pipefail
export MSYS_NO_PATHCONV=1 # stop Git Bash rewriting /sdcard/... into a Windows path
cd "$(dirname "$0")/../.."
ADB=(adb ${1:+-s "$1"})
SRC=/sdcard/Android/data/dev.kbobbili.xitelyrics/files
mkdir -p analysis/sessions
"${ADB[@]}" pull "$SRC/rec/." analysis/sessions/ >/dev/null
"${ADB[@]}" pull "$SRC/events.jsonl" analysis/events.jsonl >/dev/null
for d in analysis/sessions/*/; do
    echo "$(basename "$d"): $(ls "$d"chunk-*.wav 2>/dev/null | wc -l) chunks"
done
