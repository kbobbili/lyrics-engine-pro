# Phase 5 and 6 results — sync engine and overlay

Run: 2026-10-02 (overnight). Status: **Phase 5 passed offline; on-TV live check and Phase 6
acceptance need the owner watching XITE.**

## Phase 5 — sync engine

`sync/SyncEngine.java` (pure Java, injected clock) decides captures, trusted matches and the
displayed line; `LyricsController` runs it on the TV (main thread), recording clips on the capture
worker and fetching lyrics on their own thread.

### Deterministic checks: 38/38 pass (`tools/phase5/test.sh`)

Covered: first clip at +12 s and every 45 s; lyrics prefetched from the panel; no refetch when
Shazam's title equals the panel's; stale results from older generations dropped (new item, pause,
foreground loss); pause freezes the line, resume keeps alignment and re-measures after 3 s;
buffering hides; seek hides and reacquires after 2 s; XITE position correction rebases delta with
no displayed jump; a miss keeps lyrics and retries after 15 s; 120 s freshness cap; 429 waits
max(Retry-After, 60 s) and keeps valid lyrics; conflicting match hides and confirms after 5 s —
old alignment restored or new one adopted when two clips agree; no-panel-in-3 s ads (no
captures), late panel reclassifies; mid-song start captures after 2 s and is never an ad; session
replacement; Shazam-vs-panel identity conflict needs two agreeing answers; lyrics for a superseded
identity ignored; instrumental gaps hide.

### End-to-end replay of the Phase 2 evening (`tools/phase5/replay.sh` + `replay_report.py`)

The real engine, Java fingerprint, Shazam client (96 live calls) and LRCLIB client, driven by the
recorded 70 minutes of audio and XITE events on a simulated clock; scored against the dense-window
ground truth.

| Measure | Result |
|---|---|
| Songs aligned | 17/17 complete songs, all on the first clip, **≈20.2–20.8 s** after the song starts |
| First line on screen | median 20.5 s (later only when the song's first sung line is later, e.g. American Girls 32 s) |
| Ads | **19/19 ad items: no lyrics, no Shazam calls** |
| Lyrics shown (after the first 20 s) | median 87 % of song time (the rest is mostly instrumental intros/gaps/outros, hidden by design, plus edit confirmation) |
| Wrong lyrics | **0 s in the 13 songs without edits**; 70 s total in the 5 edited videos (American Girls 25.8 s, Homewrecker 18.8 s, Patient Zero 17.8 s, FEVER DREAM 4.2 s, NUEVAYoL 3.8 s) |
| Shazam load | 97 captures for 18 songs (~5.4 per song) |

**Edit recovery:** after an unannounced cut the engine keeps the old alignment until its next
scheduled clip (≤ 45 s), then hides, confirms 5 s later and realigns. Worst case here was 26 s of
wrong lines — within the plan's provisional 60 s target, but it is the main visible weakness.
Options if it bothers in practice: shorter cadence (more Shazam calls), or a 30 s cadence only
for the first minutes of songs (most cuts here were early or near the end).

### On-TV live check: **pending**

The build with the engine is installed (log-only display). It runs automatically the next time
XITE plays; its decisions appear as `sync_*` and `display` events in `adb logcat -s XiteLyrics`.
XITE was not launched overnight (it would play sound in the room).

## Phase 6 — overlay: **built, off by default; acceptance pending**

Bottom-centred, current line bold over a semi-transparent backing, next line dimmed; an
accessibility overlay window (never focusable/touchable, so the remote stays with XITE and XITE is
not paused). Layout checked on the TV with a 5 s preview over a bright background: one line,
readable. Fix made: a full-width window — Android measures WRAP_CONTENT overlays at dialog width,
which wrapped lines needlessly.

```bash
adb -s <serial> shell am broadcast -a dev.kbobbili.xitelyrics.OVERLAY --ez on true -p dev.kbobbili.xitelyrics
adb -s <serial> shell am broadcast -a dev.kbobbili.xitelyrics.OVERLAY_PREVIEW -p dev.kbobbili.xitelyrics   # 5 s sample
```

Still to check with the owner: readability next to XITE's own captions/UI, remote control during
playback, whether lyrics look early/late (→ `devicePipelineOffset`), ~20 min watch session.

## Known gaps

- Capture can only start from the home screen. Polling is 0.5 s while capture is missing, so
  waking the TV and opening XITE is normally enough; if capture is lost mid-XITE, lyrics wait for
  the next home-screen visit.
- Captions in other scripts: Bad Bunny lyrics rendered fine in the replay (Latin script); other
  scripts are untested in the overlay font.
