# How XITE Lyrics works

XITE Lyrics listens to what the XITE app is playing on the Onn 4K Google TV Pro, works out which
song it is and exactly how far into the song the video is, fetches the song's timed lyrics, and draws
the current line at the bottom of the screen. It runs entirely on the TV, alongside XITE, without
modifying XITE. A laptop is only needed to build, install or debug it.

## The big picture

```text
                   ┌──────────────────────── on the TV ─────────────────────────┐
 XITE's audio ──► capture (5–8 s clips) ──► fingerprint ──► Shazam ──► song + offset ─┐
 XITE's info panel ──► title / artist / video length ──► LRCLIB ──► timed lyrics      │
 XITE's player ──► "playing, video at 1:23" ──────────────────────────────────────────┤
                                                                                        ▼
                                        Sync engine: which line is being sung right now?
                                                                                        ▼
                                        Overlay: one compact caption at the bottom edge
                   └────────────────────────────────────────────────────────────────┘
```

## The pieces

| Piece | Code | What it does |
|---|---|---|
| Accessibility service | `LyricsAccessibilityService` | Reads XITE's info panel (title, artist, video length), knows when XITE is on screen, hosts the overlay, launches the capture permission from the home screen. Read-only — never presses anything. |
| Playback watcher | `PlaybackWatcher` | Follows XITE's media session: playing/paused, video position, item changes, seeks. (A notification listener, only because that grants media-session access.) |
| Audio capture | `CaptureService`, `ProjectionActivity` | Holds Android's screen-capture permission (approved silently via an app-op) and records clips of **only XITE's audio**, timestamped by Android's audio clock. |
| Fingerprint | `fingerprint/SongRecFingerprint`, `RealFft2048` | Java port of Shazam's audio signature (SongRec algorithm, via shazamio-core 1.1.2). Byte-for-byte identical to the reference on 16 test fixtures, on desktop and on the TV. Raw audio never leaves the TV. |
| Shazam client | `recognition/ShazamClient` | Sends the fingerprint; gets back the song and the offset (seconds into the album track) of the **first sample sent**. Never throws: matches, no-match, 429s, errors and malformed replies all come back as results. |
| Lyrics client | `lyrics/LrcLibClient`, `Lrc` | Searches LRCLIB, picks the right upload (grouped by lyric timing; snippets and remix/live versions filtered; ambiguous cases left blank), retries outages, caches on the TV. |
| Sync engine | `sync/SyncEngine` | The decision-maker: when to listen, which answers to trust, which line to show. Pure logic on an injected clock, so it's tested without a TV. |
| Controller | `LyricsController` | Runs the engine on the main thread, records clips on the capture worker, fetches lyrics on their own thread, feeds the overlay. |
| Overlay | `LyricsOverlay`, `OverlayStyle`, `TightTextView` | Draws the current line: Lexend semi-bold, compact centred block (≤ 45 % of the width, balanced wrap) at the bottom edge, no background box — a heavy soft shadow keeps it readable — and a 120 ms crossfade started early so lines are never late. |
| Self-repair | `A11yRebinder` | If Android force-stops the app, re-enables only its own accessibility entry ~10 s later (other services such as AT4K untouched). |

## One song, start to finish

1. **Song starts.** XITE's player resets to position 0 → new item. If no info panel appears within
   **3 s**, it's an **ad**: nothing is shown and Shazam is never asked.
2. **~1.5 s** — the panel shows title/artist; lyrics are fetched from LRCLIB (cached afterwards).
3. **~2.5 s** — lines appear with a **best-guess timing** (video second = song second). Skipped when
   the video is 3–15 s longer than the album track (usually an intro — the guess would be seconds
   off); those songs start at step 4.
4. **From +2 s** — listen for **5 s**, ask Shazam → **exact timing at ~7.5 s**. A miss retries at once.
5. **Every 30 s** — an **8 s** check:
   - agrees (within 0.3 s) → nothing changes;
   - **timing jumped** (XITE cut or added footage) → switch to the new timing **immediately**,
     double-check 5 s later (switch back if wrong), then check again 15 s later (edits cluster);
   - **no song heard** (an acted scene or dialogue) → hide lyrics, listen every ~3 s, resync when the
     music returns;
   - network error → keep lyrics, retry in 15 s; rate limited → keep lyrics, wait ≥ 60 s.
6. **Between checks it doesn't listen.** Lines follow XITE's playback clock plus the measured
   offset, re-evaluated 10 times a second.
7. **Pause** freezes the line; **resume** re-checks after 3 s. **Skip/seek** hides and resyncs after
   2 s. **XITE's own late position correction** (~0.1 s, once mid-song) is absorbed silently.
8. **Safety net:** no successful check for 120 s → lyrics hide until one succeeds.

### The timing model

```text
videoPos(t)    = XITE's reported position + (t − time of that report)      while playing
delta          = Shazam offset − videoPos(time of the clip's first sample)
lyrics time(t) = videoPos(t) + delta                                       → current LRC line
```

`delta` absorbs everything that makes XITE's video differ from the album track (trimmed intros,
added scenes, re-edits). Network latency never enters it: the clip's first sample is timestamped by
Android's audio system, and Shazam's offset refers to that sample.

## Why it's built this way

- **XITE closes itself if any activity opens on top of it.** The one-time capture permission is
  therefore taken only from the home screen (app-op → no dialog) and then kept alive by a foreground
  service. Overlays are windows, not activities, so they're safe.
- **XITE's videos often differ from the album tracks** — about 1 song in 4 had cuts or scenes, and
  some videos are minutes longer. Measuring the offset by listening is what makes the lyrics land.
- **Ads have no info panel**, which makes them detectable instantly without spending a Shazam call.
- **Failures degrade gracefully.** Missing or uncertain lyrics show nothing rather than wrong lines;
  network trouble keeps valid lyrics on screen.

## How well it works (measured 2026-10-02)

| | |
|---|---|
| Songs identified | every song tried, on the first check |
| Lyrics on screen | from the first sung line on most songs (1–3 opening lines missed per 40 songs) |
| Timing accuracy | every song's median error within ±0.5 s vs. the actual singing (Whisper check, 479 lines) |
| Ads | 58/58 skipped — no lyrics, no Shazam calls |
| Video edits | detected and corrected; wrong lines after an unannounced cut ~10–20 s typically |
| Shazam load | ~8 lookups per song; zero errors or rate limits in ~900 calls |
| Coverage | ~85–90 % of songs have lyrics (LRCLIB's catalogue is the limit; live versions skipped) |

## Remote buttons (while XITE is in front)

| Button | Action |
|---|---|
| **TV icon** (below D-pad right; Android `GUIDE`) | Lyrics on/off. Off also stops listening (no Shazam calls); on resyncs ~2 s later. Remembered across reboots; a brief "Lyrics off/on" note confirms. |
| **Star** (Google TV "magic button"; Android `MACRO_1`) | Save the playing video to the XITE library: briefly opens XITE's info panel (the only place its Save button exists), highlights **Save Video**, presses OK, and closes the panel again with Back as soon as the save is confirmed (~1.6 s in all). XITE confirms with its own "Saved to Library". If the owner already had the panel open, it stays open. If the button already reads **Video Saved**, nothing is pressed ("Already saved") — it can never un-save. |

On the home screen both buttons keep their normal jobs, and every other key always passes through
untouched. How it works: the accessibility service may filter key events, and acts on these two
only while XITE is in front. XITE's buttons respond to the remote's OK, not to accessibility clicks,
so Star highlights Save and sends a D-pad-centre press (Android 13+) — only after a fresh read
confirms the highlight is on an unsaved "Save Video".

## Running it

**On the TV, day to day:** nothing to do. The app survives reboots, sleep/wake and XITE updates. It
needs internet, and one pass through the home screen after the TV starts (normally automatic). If
lyrics don't appear after launching XITE some unusual way, press Home once and reopen XITE.

**XITE's own captions** are switched off in the XITE app (owner choice) so lines aren't shown twice.

**From the laptop** (only to change things):

```bash
./install.sh <adb-serial>            # build, install, every grant; keeps AT4K etc.
adb -s <serial> logcat -s XiteLyrics # live decisions: sync_*, display, ...
```

After every install: press Home on the TV and reopen XITE (capture restarts from the home screen).
Wireless adb is `192.168.1.129:5555` after reboots.

Switches (adb broadcasts to `dev.kbobbili.xitelyrics`): `OVERLAY` (on/off), `STYLE` (preset 0–6;
default 6 = "Caption, no box"; 5 = the same with a box), `OVERLAY_PREVIEW` (sample text). Debug: `RECORD`, `CAPTURE_NOW`,
`STOP_PROJECTION`, `MARKER`, `TRANSPORT`, `FP_SELFTEST`, `P4_SELFTEST`, `DUMP_UI` (read-only XITE
screen dump), `STAR_TEST` (runs the Star action — injected key presses don't reach the key filter).

## Testing and tools

| Command | Checks |
|---|---|
| `tools/phase3/check.sh` | fingerprint byte-for-byte against the pinned reference |
| `tools/phase4/test.sh` | Shazam/LRCLIB parsing, every failure path, Java-vs-Python lyric selection (61 songs) |
| `tools/phase5/test.sh` | ~50 SyncEngine scenarios (ads, edits, scenes, pauses, seeks, rate limits, …) |
| `tools/phase5/replay.sh analysis/sessions/<id>` | the whole engine on a recorded 70-minute evening |
| `tools/phase5/cadence_sim.py` | fair comparison of re-check rhythms (averages out where checks land) |
| `tools/live_report.py` | per-song summary of what the TV actually did, from its event log |

The test bench recording lives in `analysis/sessions/1790914276394` (gitignored, PC only).

## Further reading

- [PLAN.md](PLAN.md) — the original plan and gates
- [PHASE1-RESULTS.md](PHASE1-RESULTS.md) · [PHASE2-RESULTS.md](PHASE2-RESULTS.md) ·
  [PHASE3-4-RESULTS.md](PHASE3-4-RESULTS.md) · [PHASE5-6-RESULTS.md](PHASE5-6-RESULTS.md) — evidence
- [../future-roadmap.md](../future-roadmap.md) — what's next (translations via DeepL) and what was
  decided against
