#!/usr/bin/env bash
# Builds and installs XITE Lyrics on the TV with every grant it needs.
# Safe to re-run (updates keep settings). Usage: ./install.sh <adb-serial> [--overlay on|off]
#
# Preserves every other enabled accessibility service (e.g. AT4K); only this
# app's own entry is added, or removed and re-added if it isn't bound.
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: don't rewrite device paths
cd "$(dirname "$0")"

SERIAL="${1:?usage: ./install.sh <adb-serial> [--overlay on|off]}"
OVERLAY="${3:-}"
ADB=(adb -s "$SERIAL")
P=dev.kbobbili.xitelyrics
SVC="$P/$P.LyricsAccessibilityService"

bash build.sh
"${ADB[@]}" install -r out/xite-lyrics.apk

"${ADB[@]}" shell pm grant $P android.permission.RECORD_AUDIO
"${ADB[@]}" shell pm grant $P android.permission.WRITE_SECURE_SETTINGS
"${ADB[@]}" shell cmd notification allow_listener $P/$P.PlaybackWatcher
# Silent projection consent; write-settings flushes it so it survives a reboot (Phase 1).
"${ADB[@]}" shell "appops set $P PROJECT_MEDIA allow && appops write-settings"

# Accessibility: keep everything else, make sure our entry is present exactly once.
CUR=$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')
[ "$CUR" = "null" ] && CUR=""
OTHERS=$(printf '%s' "$CUR" | tr ':' '\n' | grep -v -x -e "$SVC" -e "$P/.LyricsAccessibilityService" | grep -v '^$' | paste -sd: - || true)
NEW="${OTHERS:+$OTHERS:}$SVC"
"${ADB[@]}" shell settings put secure enabled_accessibility_services "'$OTHERS'"
"${ADB[@]}" shell settings put secure enabled_accessibility_services "'$NEW'"
"${ADB[@]}" shell settings put secure accessibility_enabled 1

if [ "$OVERLAY" = "on" ] || [ "$OVERLAY" = "off" ]; then
    sleep 3
    "${ADB[@]}" shell am broadcast -a $P.OVERLAY --ez on "$([ "$OVERLAY" = on ] && echo true || echo false)" -p $P >/dev/null
fi

echo "enabled accessibility services: $("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')"
echo "Done. Visit the home screen once so capture starts, then open XITE."
