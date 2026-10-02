# Phase 3 and 4 results — fingerprint port and TV clients

Run: 2026-10-02 (overnight), Onn 4K Pro, app build 0.1.

## Phase 3 — Java fingerprint port: **passed**

**Pinned reference:** shazamio-core 1.1.2 (MIT, SongRec-derived, GPL-3.0 origin) — the exact
implementation that produced every Phase 2 match, so fixtures come from the same revision.
FFT: chfft 0.3.4 (MPL-2.0). Licence notices are in `SongRecFingerprint.java`.

| Check | Result |
|---|---|
| Byte-for-byte vs reference, desktop Java 25 | **16/16 identical** (first attempt, no timebox used) |
| Byte-for-byte on the TV (Android 14 runtime) | **16/16 identical** |
| TV-made signatures recognised by Shazam | **9/9** correct songs; offsets sensible (one clip started 0.85 s before the song → offset −0.85) |
| Fingerprint cost on the TV | ~230–260 ms per 8 s clip |

Fixtures (`tools/phase3/fixtures/*.sig`, WAVs regenerable from a session): 8 music clips (songs
and ads), 10 s, 8 s + 77 samples (not a multiple of 128), 1 s, 0.5 s, 200 samples, silence,
white noise, clipped/loud.

**Why it matched exactly:** `RealFft2048` mirrors chfft's path for n = 2048 — pack to 1024
complex values, chfft's in-place bit-reversal swaps, five radix-4 passes with its quarter-turn,
then the real-input post-step — all in `float`, with twiddles from f32 `sin`/`cos` of the same
f32 angle expression. Java (like Rust) does not fuse multiply-adds, so the operation order
determines the result. The Hanning table is copied as the same decimal literals.

**Behaviour difference kept on purpose:** the reference cuts input longer than 10 s to a centred
10 s segment (the Phase 2 "12 s clip = +1.000 s" artefact). The port takes exactly the samples it
is given; callers keep clips ≤ 10 s.

Reproduce: `tools/phase3/check.sh [analysis/sessions/<id>]`; on the TV, push fixtures and send
`FP_SELFTEST` (see `FingerprintSelfTest.java`).

## Phase 4 — TV recognition and lyrics clients: **passed**

`ShazamClient` sends the request shazamio 0.8.1 sends (endpoint, body, headers). It never throws:
match, no-match, 429 (with Retry-After), HTTP errors, timeouts and malformed bodies all come back
as results. `LrcLibClient` ports the Phase 2 selection and adds retries and a disk cache.

| Check | Result |
|---|---|
| Desktop tests (`tools/phase4/test.sh`) | **24/24 pass** |
| — real/gzip/empty/missing/offset-less/truncated/HTML responses | correct status for each |
| — 429 with Retry-After 30 | `RATE_LIMITED`, waits 30 s |
| — slow server | cut at the 10 s read timeout (10.0 s) |
| — LRCLIB 503,503,200 / always 503 / 404 / unreachable / empty | OK after 3 tries / failed after 4 / no retry / failed / `NONE` |
| — repeat lookup | served from disk cache (no request) |
| Java vs Python lyrics selection | **10/10** same upload and line count |
| TV path (TV fingerprint → TV Shazam → TV LRCLIB), 9 music fixtures | same song as desktop **9/9**, offsets within **1 ms**, same LRCLIB upload **9/9**, Shazam latency 100–170 ms |
| Short clips (0.5 s, 1 s) | no match (expected) |

Response latency never enters timing: the offset refers to the first submitted sample, whose
time comes from AudioRecord timestamps (Phase 2).

## Lyrics selection change from the plan

The plan grouped LRCLIB uploads by metadata duration (±3 s). Real data made nearly every song
"ambiguous" (Dracula: uploads spread 200–210 s, plus 30/50 s snippets and 233–240 s entries).
Uploads are now grouped by **lyric timing** (shared lines within 0.75 s median); snippets
(< 60 s or < 5 lines) are dropped; unlabelled beats remix/live/etc.; largest group wins unless
the runner-up is at least half its size. Uploads within a group agreed to ≤ 0.03 s on all songs
checked, so the choice within a group barely matters.

## LRCLIB reliability

Twice in one night: an HTTP 503 and a TLS-handshake timeout. The client retries (2 s, 5 s, 10 s)
and caches every search on disk; the engine prefetches using the panel title at song start.
