#!/usr/bin/env bash
# Drives the calibration run on the TV: ~65 s of marker flashes (every 2 s) while
# XITE is paused/resumed 8 times. Start filming the TV (screen + sound) first.
# Usage: tools/phase2/calibrate_run.sh [adb-serial]
set -euo pipefail
ADB=(adb ${1:+-s "$1"})
P=dev.kbobbili.xitelyrics
"${ADB[@]}" shell am broadcast -a $P.MARKER --ei count 32 --ei periodMs 2000 --ei onMs 300 -p $P >/dev/null
sleep 3
for i in 1 2 3 4 5 6 7 8; do
    "${ADB[@]}" shell am broadcast -a $P.TRANSPORT --es cmd pause -p $P >/dev/null
    sleep 2.7
    "${ADB[@]}" shell am broadcast -a $P.TRANSPORT --es cmd play -p $P >/dev/null
    sleep 4.3
    echo "cycle $i done"
done
echo "done; stop filming after the square stops flashing"
