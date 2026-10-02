# Phase 1 results — capture and playback-clock spike

Device: Onn 4K Pro (jarvis2), Android 14 / API 34. XITE 7.0.0b4. App build 0.1.
Run: 2026-10-01 23:43 → 2026-10-02 00:03.

## Gates

| Gate | Result | Evidence |
|---|---|---|
| A — capture | **Passed** | Every clip non-silent (peak 5–13k, −24 to −29 dBFS). Repeated 8 s clips within one projection session. Shazam identified all song clips correctly (Tame Impala *Dracula*, Olivia Dean *Man I Need*, ROSÉ *new trick*, KAROL G & Bruno Mars *Still*). Works with surround output Auto, Always and Never. No microphone service type needed. |
| B — silent startup | **Passed (with persisted app-op)** | Without the app-op: system dialog "Start recording or casting with XITE Lyrics?" ("Start now" focused). With `appops set … PROJECT_MEDIA allow` **plus `appops write-settings`**: auto-approved in 82–189 ms, no dialog drawn, including after a reboot. Without `write-settings`, a reboot soon after setting the op loses it (one dialog). |
| C — lifecycle | **Passed** | Revocation (`projection.stop()`, same `onStop` path): clean shutdown, no launch over XITE, silent reacquire 82 ms after reaching home. Sleep/wake: projection survived. Package replace: services rebind, reacquired. Force-stop: accessibility service is *not* rebound by Android → fixed with self-rebind (below), recovered in ~10 s. Reboot: reacquired 28 s after boot with no remote input. |

## Findings that change the design

1. **XITE kills its own process ~0.7 s after any activity pauses it** (`Process: Sending signal … SIG: 9`
   from XITE's own pid, after `ReactHost.onHostPause`). `FLAG_ACTIVITY_NO_USER_ACTION` doesn't help.
   ⇒ Nothing may ever open an activity over XITE. The projection is requested **only when a launcher
   is the top app** (any package with a HOME activity — on this TV that includes AT4K), and kept alive
   by the foreground service. If it dies mid-XITE, lyrics stay unavailable until the user is next on
   the home screen.
2. **Background launch exemption works as planned.** `ProjectionActivity` started from the accessibility
   service: `BAL_ALLOW_PERMISSION`; consent activity: `BAL_ALLOW_VISIBLE_WINDOW`. No `SYSTEM_ALERT_WINDOW`.
3. **Force-stop removes our entry from `enabled_accessibility_services`.** `PlaybackWatcher` (a
   notification listener, which Android does rebind) re-adds only our entry 10 s later if the
   accessibility service hasn't connected. Needs adb-granted `WRITE_SECURE_SETTINGS`. Other entries
   (AT4K, probe) are preserved.
4. **`isActive()`/`isFocused()` flicker** for XITE's window every poll; "XITE in front" now means XITE
   owns the topmost `TYPE_APPLICATION` window.
5. **Ad boundaries are observable.** Each item change is STOPPED → BUFFERING → PLAYING with position
   reset to 0 (new generation). Songs show the info panel ~1.6 s after the boundary; the 30 s ad never
   did. The ad clip returned no Shazam match. ⇒ "new item and no panel within ~3 s = ad/unknown" is a
   viable immediate signal; confirm across more ads in Phase 2.

## Timing measurements

- **AudioRecord timestamps (BOOTTIME)** are stable: first-sample estimate spread 2–4 ms across 80 reads
  per clip. `read()`-return times lag by a steady ~79 ms (buffering), so read times must not be used.
- **Open question for Phase 2:** with "framePosition 0 = first delivered frame", the first sample is
  dated ~65–72 ms *before* `startRecording()` returned, and the first read already reports
  `tsFrame=3072` after 1600 frames. Known-start tests must confirm the frame origin.
- **Playback clock:** XITE's session sometimes reports position only at item start (Dracula: one update
  in 90 s), sometimes every 3 s (ad, Man I Need). Extrapolation tracked the 30.0 s clip cadence to
  within ~16 ms either way; the plan's "do not rely on update cadence" holds.
- **Panel clock** reads ~1.1–1.7 s behind the media-session position (00:00 at 1.55 s, 00:06 at 7.13 s).
  Consistent with the plan: it can't be used to judge sub-second accuracy.
- **Preview of Phase 2 (desktop shazamio, not yet the formal gate):**

  | Clip | videoPos(first sample) | Shazam offset | delta |
  |---|---|---|---|
  | Dracula #1–6 | 23.497 … 173.554 s | 23.619 … 173.677 s | +0.074 … +0.123 s |
  | Dracula #7 | 203.599 s | 103.490 s | **−100.1 s** (timeskew 7.5e-4, 10× the others) |
  | Man I Need #2–3 | 11.840 / 41.853 s | 11.930 / 41.928 s | +0.090 / +0.075 s |
  | new trick (app just launched) | 7.284 s | 65.408 s | +58.1 s — XITE joined the video mid-way while the session position started at 0 |

  Timeskew ≈ 1e-4 on good matches (≈ 0.02 s over a 4-min song): pure-offset model is fine so far.
  Dracula #7 is either a real edit or a **repeated chorus matched to its earlier occurrence** — the
  weaker match statistics suggest the latter. Phase 2 must handle repeated sections explicitly.

## Not verified

- "XITE-only" audio is by construction (uid-matched capture config); no other app played during clips,
  so exclusion wasn't observed directly.
- Projection loss caused by the *system* (another app starting a cast) wasn't triggered; the debug
  `STOP_PROJECTION` hook exercises the same `onStop` path.

## Operating notes

- Grants (all needed): `pm grant … RECORD_AUDIO`, `pm grant … WRITE_SECURE_SETTINGS`,
  `cmd notification allow_listener …/.PlaybackWatcher`, `appops set … PROJECT_MEDIA allow`
  **then `appops write-settings`**, and our entry appended to `enabled_accessibility_services`.
- After a reboot, wireless adb came back on `192.168.1.129:5555` (the pairing port changed).
- Pull data: `tools/pull-clips.sh <serial>`; summarise: `python tools/clip_stats.py clips/`;
  timeline: `python tools/events.py clips/events.jsonl`.
