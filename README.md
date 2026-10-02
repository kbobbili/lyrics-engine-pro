# XITE Lyrics

Synced lyrics over the XITE music-video app on the Onn 4K Google TV Pro. The app listens to XITE's
audio, identifies the song and its position with Shazam's fingerprint (a pure-Java port of the
SongRec algorithm), fetches synced lyrics from LRCLIB, and draws the current line over the video.

- Design and phases: [docs/PLAN.md](docs/PLAN.md)
- What's next: [future-roadmap.md](future-roadmap.md) (translations, menu hiding, open decisions)
- Evidence per phase: [Phase 1](docs/PHASE1-RESULTS.md) · [Phase 2](docs/PHASE2-RESULTS.md) ·
  [Phases 3–4](docs/PHASE3-4-RESULTS.md) · [Phases 5–6](docs/PHASE5-6-RESULTS.md)

**State:** in daily use on the TV. Recognition, lyrics and sync work end to end: ~3 h live
(51 songs, 39 ads) with no lookup errors, ads skipped, lyrics from the first sung line on most
songs, and scenes/edits handled. The overlay switch defaults to off on a fresh install
(`./install.sh <serial> --overlay on`).

## How it works

```text
XITE audio ──(playback capture, 8 s clips)──► Java fingerprint ──► Shazam ──► song + offset
XITE media session ──► video position (with XITE's late corrections rebased)       │
XITE info panel ──► title/artist (lyrics prefetch, ad detection)                    ▼
LRCLIB ──► synced lyrics ──► SyncEngine: line at videoPos + delta ──► overlay (2 lines)
```

XITE kills itself if any activity opens over it, so screen-capture consent is obtained only from
the home screen (silently, via an app-op) and then kept alive.

## Install

Plain SDK tools, no Gradle: needs `ANDROID_HOME` (build-tools 36.0.0, platform android-37.0) and a JDK.

```bash
./install.sh <adb-serial>                 # build, install, all grants; keeps AT4K etc.
./install.sh <adb-serial> --overlay on    # same, and switch the lyrics overlay on
```

Then visit the home screen once (capture starts there) and open XITE.

## Everyday commands

```bash
S=<adb-serial>; P=dev.kbobbili.xitelyrics
adb -s $S logcat -s XiteLyrics                                            # live decisions (sync_*, display)
adb -s $S shell am broadcast -a $P.OVERLAY --ez on true -p $P             # overlay on (false = off)
adb -s $S shell am broadcast -a $P.OVERLAY_PREVIEW -p $P                  # 5 s sample text
```

## Tests and tools

| Command | What it checks |
|---|---|
| `tools/phase3/check.sh` | fingerprint byte-for-byte vs the pinned reference (16 fixtures) |
| `tools/phase4/test.sh` | Shazam/LRCLIB parsing, failure paths, Java-vs-Python lyric selection |
| `tools/phase5/test.sh` | 38 SyncEngine scenarios |
| `tools/phase5/replay.sh analysis/sessions/<id>` | whole engine on a recorded TV session |
| `tools/phase2/*` | recording pull, recognition schedule/dense/semantics, Whisper lyric check |

Debug hooks (broadcasts): `RECORD` (continuous recording), `CAPTURE_NOW`, `STOP_PROJECTION`,
`MARKER` / `TRANSPORT` (calibration), `FP_SELFTEST`, `P4_SELFTEST`. Recordings stay on the TV under
`/sdcard/Android/data/dev.kbobbili.xitelyrics/files/`; delete pulled audio when done.

## Credits and licences

Fingerprint algorithm: SongRec (marin-m, GPL-3.0) via shazamio-core (dotX12, MIT); FFT order
mirrors chfft (MPL-2.0). Shazam access is unofficial; lyrics from LRCLIB. Personal use.
