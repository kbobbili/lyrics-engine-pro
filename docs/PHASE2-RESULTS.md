# Phase 2 results — end-to-end alignment spike

Run: 2026-10-02 00:11–01:21, Onn 4K Pro, XITE 7.0.0b4. One continuous 70-minute recording of
XITE's audio (16 kHz mono, AudioRecord BOOTTIME timestamps, 60 s chunks), plus the event log.
Recognizer: shazamio 0.8.1 / shazamio-core 1.1.2 (pinned). Data: 20 songs, 17 ad items.

## Gate: **passed** (device calibration deferred by decision)

| Requirement | Result |
|---|---|
| Offset semantics | **Proven:** offset = song-timeline position of the first submitted sample (±4 ms across shifted starts, lengths 4–8 s, 0.5–2 s leading silence) |
| First-sample clock mapping | AudioRecord timestamps, local median over ±5 s; stable to a few ms per clip |
| Lyric alignment vs audible lyrics | 16 songs / 479 lines (Whisper word timing): every song's median error within ±0.53 s, 15/16 within ±0.45 s; pooled median −0.19 s |
| `devicePipelineOffset` | **Deferred** (owner decision 2026-10-02): ships as 0, tunable; phone-video tooling ready |
| Timeskew | ~1e-4 on good matches (≈0.02 s over 4 min): pure-offset model kept |
| Stable accuracy | 13/18 songs one steady delta for the whole song; within a run, windows agree to a few ms apart from XITE's late position correction (below) |
| Initial latency (planned schedule) | first match on the **first** clip for every song: **20.3 s median, 21.4 s max** (target 30 s) |
| Edit recovery | see Phase 5 replay (engine behaviour on this recording) |
| Ad detection | 37/37 items: songs show the panel 1.0–2.1 s after the item boundary, ads never; ad clips get no match |
| Rate limiting | **0 errors / 0 429s in 385 calls**, incl. 59 min at ≥ the planned cadence; median latency 0.26 s |

## Findings that changed the design

1. **XITE's position report is ~80–250 ms early at item start**, and for songs XITE only corrects
   it once, 2–5 min in. That correction appeared as a +0.1–0.2 s step in the measured delta. The
   engine now **rebases delta by the correction** so lyrics don't jump. Resume after a pause showed
   a similar ~0.15 s shift → re-measure 3 s after resume.
2. **Video edits are common: 5 of 18 songs.** American Girls (≈10 s at ~185 s), Homewrecker (~45 s
   in), Patient Zero (−2.65 s at ~90 s; music-free section 180–240 s; then −70 s), NUEVAYoL (~150 s),
   FEVER DREAM (~45 s). Every jump was confirmed by several later windows. The only single-window
   outliers sat exactly on edit points (windows straddling the cut). **No false repeated-chorus
   matches** in ~250 dense windows. ⇒ "confirm with a second clip before switching" is right.
3. **LRCLIB duration metadata is unreliable** (one recording's uploads span ~10 s; snippets; one
   entry says 3489 s). Grouping by lyric timing replaced the plan's ±3 s duration rule; uploads in
   a group agree to ≤ 0.03 s.
4. **LRCLIB is flaky** (a 503 and a TLS timeout in one night) and **its structured search misses
   "A ft. B" credits** (Tyla ft. Zara Larsson → 0 results). Fixed with retries, a disk cache,
   primary-artist search and a free-text fallback.
5. **shazamio cuts audio longer than 10 s to a centred 10 s segment** (a 12 s clip reported +1.000 s).
   The Java port doesn't; clips stay ≤ 10 s.
6. **Capture clock jumps ~60 ms when XITE's audio track starts**, and timestamps move in 64 ms
   buffer steps (paired ±32 ms). A single straight-line fit is wrong; the local median is right.

## Per-song alignment (dense windows, every 15 s)

| Song | Delta runs (count × median delta @ window range) |
|---|---|
| The Time Of My Life | 13 × −0.373 s (whole song) |
| American Girls | 11 × −0.558 s · then 3 × −10.736 s from 185 s |
| Ordinary | 12 × +0.361 s |
| the cure | 19 × +0.029 s |
| So Easy (To Fall In Love) | 11 × −0.712 s |
| Earrings | 10 × −0.080 s |
| BAILE INoLVIDABLE | 24 × +0.110 s |
| Risk It All | 13 × −0.207 s |
| SHE DID IT AGAIN | 14 × +0.036 s |
| Homewrecker | 3 × −0.371 s · 1 straddle · 10 × −10.515 s from 65 s |
| Patient Zero | 6 × +0.283 s · 6 × −2.370 s from 95 s · (no music 180–240 s) · 3 × −70.456 s |
| The Fate of Ophelia | 15 × −0.540 s |
| Wish I Didn't | 13 × +0.814 s |
| NUEVAYoL | 10 × −0.010 s · 1 straddle · 2 × −22.026 s from 170 s |
| I Just Might | 14 × +0.162 s |
| What You Need | 15 × −0.436 s |
| FEVER DREAM | 3 × −1.979 s · 7 × −21.237 s from 65 s |

Deltas differ per video (−0.7 … +0.8 s): XITE's videos start at slightly different points of
the recording. That is exactly what the per-song delta absorbs.

## Lyric-boundary check (Whisper)

faster-whisper `small` word timestamps on the captured audio, mapped through the measured delta,
against the chosen LRCLIB lines (≥ 2 words, first-3-word fuzzy match within ±6 s).

| Song | Lines | Median error |
|---|---|---|
| The Time Of My Life | 25 | −0.19 s |
| American Girls | 18 | −0.28 s |
| Ordinary | 41 | −0.40 s |
| the cure | 44 | −0.13 s |
| So Easy | 21 | +0.13 s |
| Earrings | 22 | −0.09 s |
| BAILE INoLVIDABLE | 51 | +0.02 s |
| Risk It All | 22 | −0.40 s |
| Homewrecker | 18 | −0.08 s |
| Patient Zero | 33 | −0.45 s |
| The Fate of Ophelia | 41 | −0.22 s |
| Wish I Didn't | 26 | −0.35 s |
| NUEVAYoL | 27 | −0.03 s |
| I Just Might | 47 | −0.24 s |
| What You Need | 20 | −0.53 s |
| FEVER DREAM | 23 | −0.01 s |

Pooled: median −0.19 s, median |error| 0.27 s, 71 % of lines within ±0.5 s. Per-line scatter
includes Whisper's own timing noise on sung audio (and LRC stamping conventions); the
per-song medians are the meaningful accuracy number. The consistent negative lean (words heard
before the LRC stamp) is plausible Whisper early-onset bias plus late LRC stamps; it is not
corrected for, since it is not established as a property of the pipeline.

## Not verified / limits

- Accuracy is on the captured-audio timeline. The HDMI/TV delay to what is heard is not
  measured (calibration deferred); if lyrics look consistently early/late, nudge
  `devicePipelineOffset` or run `tools/phase2/calibrate_run.sh`.
- One 70-minute evening of one channel. More songs would tighten the numbers; behaviour on
  other XITE channels/regions is untested.
- Rate-limit test was ~1 h; daily caps are unknown. The app logs 429s (`sync_rate_limited`).

## Reproduce

```bash
tools/phase2/pull.sh <serial>                       # recording + events -> analysis/
tools/phase2/run.sh semantics analysis/sessions/<id> 1
tools/phase2/run.sh schedule  analysis/sessions/<id>
tools/phase2/run.sh dense     analysis/sessions/<id>
tools/phase2/run_align.sh     analysis/sessions/<id>
python tools/phase2/summary.py analysis/sessions/<id>
```
