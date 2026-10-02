# XITE Lyrics

Companion app for the Onn 4K Google TV Pro that will show synced lyrics over the XITE music-video
app. See [docs/PLAN.md](docs/PLAN.md) for the design and phases, and
[docs/PHASE1-RESULTS.md](docs/PHASE1-RESULTS.md) for what's been proven on the device.

**Current state (Phase 1 spike):** reads XITE's info panel and media session, holds a MediaProjection,
and records 8 s clips of XITE's audio every 30 s while XITE plays, with timestamps for offline
analysis. No lyrics or overlay yet.

## Build

Plain SDK tools, no Gradle. Needs `ANDROID_HOME` (build-tools 36.0.0, platform android-37.0) and a JDK.

```bash
bash build.sh            # -> out/xite-lyrics.apk
```

## Install and set up (adb)

```bash
D="-s <tv-ip>:<port>"; P=dev.kbobbili.xitelyrics
adb $D install -r out/xite-lyrics.apk
adb $D shell pm grant $P android.permission.RECORD_AUDIO
adb $D shell pm grant $P android.permission.WRITE_SECURE_SETTINGS
adb $D shell cmd notification allow_listener $P/$P.PlaybackWatcher
# Silent projection consent; write-settings makes it survive a reboot.
adb $D shell "appops set $P PROJECT_MEDIA allow && appops write-settings"
# Append (don't replace) the accessibility service, keeping AT4K etc.
CUR=$(adb $D shell settings get secure enabled_accessibility_services | tr -d '\r')
adb $D shell settings put secure enabled_accessibility_services "$CUR:$P/$P.LyricsAccessibilityService"
```

The projection is only requested from the home screen (XITE kills itself if anything opens over it),
so after setup go to the home screen once, then open XITE.

## Debug

```bash
adb $D logcat -s XiteLyrics                       # live events
bash tools/pull-clips.sh <serial>                 # clips + events.jsonl -> clips/
python tools/clip_stats.py clips/                 # level, silence, timestamp quality
python tools/events.py clips/events.jsonl         # readable timeline
adb $D shell am broadcast -a $P.CAPTURE_NOW -p $P      # extra clip now
adb $D shell am broadcast -a $P.STOP_PROJECTION -p $P  # simulate revocation
```

Clips stay on the TV in `/sdcard/Android/data/dev.kbobbili.xitelyrics/files/clips/` (last 60 kept).
Delete pulled WAVs when done with them.
