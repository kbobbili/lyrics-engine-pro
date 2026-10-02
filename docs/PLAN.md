# XITE Lyrics — synced lyrics overlay for XITE on Google TV

## Context

XITE (music-video app on the Onn 4K Google TV Pro, Android 14 / API 34) has no lyrics or captions
for most songs. XITE itself can't be modified, so the goal is a companion app on the TV that
detects the current song, fetches synced lyrics, and draws 1–2 subtitle-style lines over the video.

**Status (2026-10-02): Phases 0 and 1 complete — all three Phase 1 gates passed on the device.
Next: Phase 2 (end-to-end alignment spike). Results and evidence: `docs/PHASE1-RESULTS.md` in the repo.**

**Phase 1 findings that constrain the design:**
- **XITE kills its own process ~0.7 s after any activity pauses it.** Nothing may ever open over
  XITE. The projection is requested only when a launcher is the top app, and kept alive by the
  foreground service; if it is lost mid-XITE, lyrics wait until the next visit to the home screen.
- **Silent consent works** with `PROJECT_MEDIA allow` **plus `appops write-settings`** (else a reboot
  can drop the op). Auto-approved in 82–189 ms, including 28 s after a reboot.
- **Force-stop drops our accessibility entry**; PlaybackWatcher re-adds only our entry
  (adb-granted `WRITE_SECURE_SETTINGS`), preserving AT4K.
- **Ad boundaries are observable:** each item change is STOPPED → BUFFERING → PLAYING with position 0;
  songs show the panel ~1.6 s later, ads don't, and the ad clip got no Shazam match.
- **Repeated sections:** one Dracula clip matched 100 s earlier in the song with 10× the usual
  timeskew — likely a repeated chorus matched to its first occurrence. Phase 2 must handle this.

Reported observations from the investigation (2026-10-01):
- **Song identity** is readable via an AccessibilityService. XITE shows an info panel automatically
  at every song start; fields have stable ids: `channelDetailsSongTitle`, `channelDetailsArtistName`,
  `channelDetailsProgressText` ("00:47 / 03:10"). When only video is showing, XITE removes the panel
  completely, so there is no text to read.
- **XITE's media session** (`androidx.media3.session.id.ReactExoplayerView`) has PLAYING/position,
  resets at each song, but title/artist are empty.
- **LRCLIB** has synced lyrics for every song tested.
- **Timing problem:** XITE plays its own trimmed video edits (e.g. Patient Zero 4:54 vs album 3:46,
  Boston 2:38 vs 2:48). No lyrics source matches XITE's cut, so video time ≠ song time.
- **Proposed alignment:** recognition may map XITE audio to a reference recording. The returned
  offset's time origin and that recording's relationship to the selected LRCLIB timeline are
  unproven. Title/artist agreement does not establish timeline equivalence.
- **Capture prerequisite:** XITE's manifest permits playback capture, but runtime capture policy
  and audio usage also affect eligibility. Actual audible capture still needs testing.
- **Recognition dependency:** SongRec uses unofficial Shazam access. Pin the upstream code revision
  and retain applicable license notices for reused SongRec/Audile code.

Decisions made: **new repo `MYWS/xite-lyrics`** (separate from sastatv-redirector); **pure-Java port
of the SongRec fingerprint** (no Rust/NDK/Gradle; keep the plain-SDK `build.sh`).
Use an existing desktop recognizer for feasibility and reference fixtures before investing in the port.

## Architecture

```text
XITE info panel ──(accessibility)──► title/artist ─────────────┐
XITE media session ──(notification listener)──► playing? + video position
XITE audio ──(AudioPlaybackCapture, 8 s)──► SongRec fingerprint (Java) ──► Shazam ──► track offset
                                                                                     │
                                                                   recording + offset │
LRCLIB synced lyrics ◄── recognized recording (panel title/artist: prefetch/fallback) ┤
        │                                                                              ▼
        └──────────► SyncEngine: referencePos(t) = videoPos(t) + delta ──► overlay (2 lines)
```

The diagram's offset mapping is conditional on the clock and lyrics-version validation below.
A changed delta is evidence to investigate, not proof of a cut: wrong recording matches and
timestamp errors can produce the same symptom.

### Clock contract

Use a documented monotonic time base and normalize units. For a valid PLAYING snapshot:

`videoPos(t) = reportedPosition + speed * (t - lastPositionUpdateTime)`

Android's playback update time uses elapsed realtime in milliseconds. Do not extrapolate paused,
buffering, stopped or unknown positions. Measure XITE's timestamp validity and update cadence;
reconnect when the media session changes. Build the minimal PlaybackWatcher in the first spike.

Only after proving the recognition offset refers to the first submitted sample, calculate:

`delta = referenceOffsetAtFirstSample - videoPos(firstSampleTime) - devicePipelineOffset`

`referencePos(t) = videoPos(t) + delta`

`devicePipelineOffset` is one **measured** device constant: captured audio is tapped before the
HDMI/output path, so it runs ahead of what is heard by a fixed amount. Calibrate it in Phase 2
against annotated audible boundaries, store it as a tunable setting, and re-measure only if the
output path changes (new TV, soundbar, passthrough setting). It is distinct from — and does not
license — guessed per-song offsets.

**How "audible" is measured** (two separate measurements):
- *Alignment accuracy* (captured-audio timeline): annotate lyric-line onsets directly on the
  captured WAV (e.g. in Audacity) and compare with predicted line times for the same frames. This
  excludes the output path, so it isolates recognition/lyrics/clock error.
- *`devicePipelineOffset`* (captured audio → what you hear and see): record the TV once with a
  phone video (screen + speaker audio together) while the app logs capture-frame timestamps and
  shows a test marker in the overlay at a known capture time; measure marker→audible-onset delay over
  several events and take the median. Record at 60 fps or slow-motion if available (30 fps gives
  ±33 ms per frame). Because the marker is drawn by the overlay, this measures overlay-vs-heard
  end to end, including display latency — the quantity the viewer actually experiences.

**Timeskew:** log Shazam's `timeskew` on every match. If Phase 2 shows non-zero skew for some
videos, extend the model to `referencePos(t) = videoPos(t) * (1 + skew) + b`; otherwise keep the
pure-offset model.

The lyrics may require a separately validated mapping to reference time. Never assume their origins
match, and render against current playback time rather than network-response arrival time.

- Timestamp actual PCM frames using AudioRecord frame/time information where available, translating
  its time base explicitly. Recording-start calls and first-read arrival are not sample timestamps.
  Measure startup/buffering uncertainty before claiming subsecond accuracy.
- Save first/last sample times, frame count, playback snapshots and playback generation with clips.
  Reject captures spanning pauses, seeks, song/session changes or capture loss. An unobserved edit
  inside a clip also requires consistency checks before accepting its alignment.
- Prove offset semantics with known-start segments from one recording, several start times, leading
  silence and different clip lengths. The whole-second panel clock cannot prove ±0.5 s accuracy.
- Hide and request confirmation when recognition conflicts with established identity/alignment.
  Define uncertainty limits from spike measurements.
- **Alignment validity is based on continuity, not age.** An accepted alignment stays valid while
  the playback generation is unchanged and the clock is continuous; a failed lookup (no match, 429,
  network error) does not hide lyrics. A looser age cap — initially 120 s since the last accepted
  clip's end — guards against unobserved edits; hide on expiry. Backoff must not extend that cap.
  Tradeoff accepted: after an undetected XITE edit, wrong lines can persist up to the cap.

### Identity and asynchronous state

Track cached identity, playback generation, clock validity, selected lyrics and alignment confidence
independently. Use explicit WAITING, ACQUIRING, ALIGNED, PAUSED and UNAVAILABLE states.

| Event | Required behavior |
|---|---|
| Panel disappears during continuous playback | Retain identity and valid alignment; this is normal playback, not ad evidence |
| New identity, position reset, seek or session replacement | Advance generation, invalidate alignment and hide; clear identity when no longer trustworthy |
| Pause | Freeze the line, stop capture and invalidate in-flight clips; resume only after verifying clock continuity |
| Buffering or unknown position | Hide and invalidate interrupted clips; reacquire if continuity cannot be established |
| XITE leaves foreground or session disappears | Hide, cancel work and invalidate generation; verify/reacquire on return |
| Start midway through a song without panel text | Capture once playback is stable; recognition provides provisional identity; validate lyrics before display |
| No match or network failure | Do not classify as an ad; remain hidden if acquiring, otherwise retain the alignment while continuity holds and the 120 s cap has not expired |
| Confirmed ad/song boundary | Clear previous alignment immediately; await a newly validated song |

Every capture, recognition and lyrics request carries its generation and relevant recording/candidate
identity. Check these before applying results; cancellation alone does not prevent late callbacks.
Invalidate in-flight work on broken playback continuity even when the title stays unchanged.

Ad-boundary signal (Phase 1): every item change is a STOPPED → BUFFERING → PLAYING transition with
position reset to 0, which already advances the generation and hides lyrics. Songs show the panel
~1.6 s later; ads did not. Treat "new item and no panel within ~3 s" as ad/unknown; confirm across
more ads in Phase 2. If a case appears with no transition, record its detection delay. Neither missing text nor a failed match proves an ad. Foreground detection must distinguish
active XITE from a merely retrievable XITE window.

### Lyrics selection

Rank synced candidates using recognized/panel metadata and available recording evidence: normalized
title/artist, remix/live/version labels, album and reference duration. XITE's edited duration is not
the reference duration. Do not infer an album cut from the most common search-result duration.
Retain the selected candidate id and recording association; ambiguous versions remain unavailable.

**Grouping before ambiguity:** LRCLIB routinely returns several uploads of the same recording
(e.g. Boston: 168/170/171 s). Group synced candidates whose durations are within ~3 s and that carry
no differing version label (remix/live/acoustic/sped-up etc.); a group counts as one recording.
"Ambiguous" means two or more groups that genuinely differ — not multiple uploads within one group.
Uploads are user-made and some are offset by a second or more, so within a group prefer the member
validated against captured audio (Phase 2); otherwise the member whose line timestamps sit closest
to the group's per-line median.

Validate early and later lyric boundaries against captured audio, including a known XITE edit and
an ambiguous-version fixture. Fixture success proves feasibility, not universal catalogue accuracy.
Beyond the measured `devicePipelineOffset`, apply only validated per-recording corrections, never a
guessed offset. Parse fractional and multiple timestamps, LRC offset metadata and blank/instrumental
intervals without holding old lyrics indefinitely through a deliberate gap.

## Components (Java, package `dev.kbobbili.xitelyrics`)

| File | Role | Reuse |
|---|---|---|
| `LyricsAccessibilityService` | Reads panel (2 s poll + events), hosts overlay window | `collect()`/poll logic from probe `ProbeService.java` |
| `OverlayView` | `TYPE_ACCESSIBILITY_OVERLAY`, not focusable/touchable; current line bold, next line dim; hidden when XITE isn't in front or there are no lyrics | — |
| `PlaybackWatcher` | NotificationListenerService → `MediaSessionManager.getActiveSessions` → XITE controller state/position | — |
| `ProjectionActivity` | Launched by `LyricsAccessibilityService` **only while a launcher is the top app** (never over XITE); requests fresh projection authorization and starts `CaptureService` after consent; silent via persisted `PROJECT_MEDIA` app-op | — |
| `A11yRebinder` | Called by PlaybackWatcher: re-adds only our accessibility entry after a force-stop (needs `WRITE_SECURE_SETTINGS`) | — |
| `CaptureService` | Foreground service (`mediaProjection` type), XITE-uid-only capture, 16 kHz mono PCM, 8 s clips with frame timestamps; revocation cleanup | — |
| `SongRecFingerprint` | Java port of SongRec `algorithm.rs`, `hanning.rs`, `signature_format.rs` → `data:audio/vnd.shazam.sig;base64,…` | Audile `core/recognition/native/songrecfp/src/**` |
| `ShazamClient` | POST to `amp.shazam.com/discovery/v5/.../tag/{uuid}/{uuid}`, parse `matches[].offset`, track title/subtitle; 429 → backoff | Audile `ShazamRecognitionService.kt`, `ShazamRequestJson.kt`, `ShazamResponseJson.kt` (port to `HttpURLConnection` + `org.json`) |
| `LrcLibClient` + `LrcParser` | Search, evidence-based recording/version selection, LRC parsing and gap handling | — |
| `SyncEngine` | State transitions, generation checks, clock mapping, alignment confidence/freshness and scheduling | — |

## Timing behavior and acceptance targets

The first version provides best-effort alignment with periodic correction. It cannot guarantee
continuous ±0.5 s accuracy across unobserved edits.

- **Initial appearance:** first capture starts ~12 s after song start and takes 8 s. Earliest result
  is after 20 s plus processing/network/lyrics latency. Provisional target: display within 30 s on
  first-match, unambiguous songs with working services. Report retries and unavailable songs separately.
- **Aligned accuracy:** target ±0.5 s at sampled lyric boundaries on validated recording/lyrics pairs
  during uninterrupted playback, excluding acquisition and edit-recovery windows. Source timestamp
  quality can prevent this target even with a correct clock; measure rather than assume it.
- **Edit recovery:** captures every 45 s start-to-start can leave roughly 45 s + 8 s + processing/network
  latency before correction. Provisional target: within 60 s if the next clip is entirely after the
  edit and matches successfully. Mixed-edit clips, confirmation and retries can take longer.
- **Observed discontinuity:** hide immediately and reacquire once stable, without the 12 s intro delay.
- **Pause/unavailable:** freeze while paused; hide when buffering, ambiguous, stale or unaligned.

Retry failed recognition 15 s after completion unless provider backoff requires longer. Use one
scheduler with no overlapping normal/retry requests. Stop capture outside active foreground playback.
Expect about 4–5 scheduled lookups per 3–4 minute song plus measured retries/transition recovery.
**First response to rate limiting:** once two accepted measurements in a generation agree within
~100 ms, drop to a slower drift check (~90 s) for the rest of the song. This roughly halves volume
at the cost of slower edit recovery; enable it by default if Phase 2 or normal use shows 429s.
The 30 s / 60 s values are provisional engineering targets, not verified capabilities. If fast
correction after every edit is required, redesign alignment after the spike; sparse recognition
cannot meet that requirement.

## Phases (each ends with a go/no-go check)

0. **Repo setup.** Move `MYWS/xite-lyrics-probe` → `MYWS/xite-lyrics`, `git init`, `.gitignore`
   (`out/`), README with the setup commands. Extend `build.sh` for the new manifest and permissions
   (`RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`, `INTERNET`).
1. **Capture and playback-clock spike.** Build minimal ProjectionActivity, CaptureService and
   PlaybackWatcher, plus a stub `LyricsAccessibilityService` (from the probe's) that launches
   ProjectionActivity, so Gate C tests the real launch path. Use the normal authorization flow and correct foreground-service startup order.
   Grant `pm grant <pkg> android.permission.RECORD_AUDIO` and
   `cmd notification allow_listener <pkg>/<pkg>.PlaybackWatcher`.
   Export an 8 s WAV with sample timestamps, playback snapshots and transition logs to
   app-specific external storage (`getExternalFilesDir`, readable with plain `adb pull
   /sdcard/Android/data/<pkg>/files/...`, no permission or `run-as` needed). The plain-SDK
   `build.sh` APK is not debuggable, so don't rely on `run-as`; if private storage is ever needed,
   add `android:debuggable="true"` (or `aapt2 link --debug-mode`) to a debug build variant.
   **Gate A — capture:** confirm audible XITE-only audio and usable timing; repeat clips within one
   authorized projection session. A silent WAV is a failure. Test with the TV's surround/passthrough
   audio setting both on and off — encoded passthrough or tunneled/offloaded playback is the most
   likely cause of silent capture. Record which settings work.
   **Gate B — startup UX (go/no-go):** test `appops set <pkg> PROJECT_MEDIA allow`. A consent dialog
   means silent startup failed, not that capture is impossible. Measure how often consent would be
   needed (each boot? each revocation?). **The owner decides here** whether pressing OK on the
   remote at that frequency is acceptable. If not, stop automatic sync and revisit (manual timing).
   **Gate C — lifecycle:** implement `MediaProjection.Callback.onStop`, release resources and test
   revocation, process restart and device restart. Obtain fresh authorization when necessary; do not
   reuse stale authorization results or assume service lifetime equals projection lifetime.
   Background activity-launch limits could block relaunching `ProjectionActivity`. Android exempts
   apps with a system-bound AccessibilityService, so (a) launch `ProjectionActivity` from
   `LyricsAccessibilityService` — expected to need no extra grant. Start `CaptureService` from
   `ProjectionActivity` after consent, while it is in front, so background foreground-service limits
   don't apply. (b) Fallback only if (a) is blocked on this device: `appops set <pkg>
   SYSTEM_ALERT_WINDOW allow`. Confirm recovery without touching the remote.
   If audible capture fails, stop automatic-sync work and revisit capture or manual timing.
2. **End-to-end alignment spike BEFORE the Java port.** Use a pinned desktop SongRec or shazamio
   recognizer on timestamped captures. Save tool revisions, PCM preprocessing, responses and LRCLIB
   candidate ids. First prove offset semantics with known-start segments; then test 3–4 XITE songs
   at several points, including a known video edit and an ambiguous-version case. Observe an ad break.
   Include songs with repeated choruses: decide how a conflicting match (e.g. Dracula's −100 s jump)
   is told apart from a real edit — e.g. require a second agreeing clip, or prefer the candidate
   offset consistent with the current delta when the match statistics are weaker.
   Resolve the AudioRecord frame-origin question from Phase 1 (first sample dated ~70 ms before
   `startRecording()` returned) with the known-start tests.
   **Gate:** establish first-sample clock mapping and lyric alignment against audible boundaries;
   calibrate `devicePipelineOffset`; log `timeskew`; measure stable accuracy, initial latency, edit
   recovery and ad detection. Also run the planned lookup cadence (~60–80/hour) for at least 30 min
   and record any 429s/blocks — sustained rate limiting changes the design. A 30 min run won't
   reveal daily caps, so also log 429 counts during normal use from Phase 4 on. Reject wrong versions
   rather than forcing a delta. If mapping fails, do not invest in the port. Record evidence and
   remaining limitations in the new repo before proceeding.
3. **Fingerprint port.** Port the pinned SongRec implementation to Java. Use identical PCM, sample
   rate, windowing, sample selection and serialization for the reference and Java harness. Prefer
   reference fixtures from the same upstream revision; arbitrary shazamio/Audile revisions may differ.
   **Gate:** byte-for-byte signatures on several deterministic fixtures (music, silence, short input)
   plus successful recognition of a TV-produced Java signature. Diagnose reference/preprocessing
   mismatches instead of silently weakening the comparison. **Timebox the diffing to ~1 day;** if
   float/FFT differences remain, fall back explicitly (and record it) to: Shazam returns the same
   track with an offset within ~50 ms of the reference signature's result.
4. **TV recognition and lyrics clients.** Implement bounded timeouts/backoff, generation-tagged
   responses, candidate selection and LRC parsing. Repeat the desktop fixtures through the TV path.
   **Gate:** equivalent validated alignment without response-latency timing shifts; safe behavior for
   no match, ambiguous lyrics, malformed responses, network failure and 429s.
5. **Sync engine and lifecycle integration.** Implement the state table, freshness limits, generation
   rejection and clock-aware scheduling. Test controlled events and delayed/out-of-order responses.
   **Gate:** old results cannot update a new generation; panel disappearance preserves normal lyrics;
   pause/resume, seeks, mid-song startup, foreground changes, session replacement and observable ad
   boundaries behave as specified. Explicitly record any unobservable-ad limitation.
6. **Overlay and acceptance.** Bottom-centered 1–2 lines with semi-transparent backing. Verify
   readability alongside XITE's rare captions and uninterrupted remote control.
   **Gate:** pass the separate timing and behavioral criteria below; record failed criteria and
   explicitly revise the design/target before calling the work complete.
7. **Install script + cleanup.** `install.sh`: build, install, all grants (`RECORD_AUDIO`,
   `WRITE_SECURE_SETTINGS`, notification listener, `PROJECT_MEDIA` followed by `appops write-settings`),
   enable the accessibility
   service while preserving all existing services, including **AT4K**
   (`com.overdevs.at4k/com.overdevs.at4k.Hra`). Use full component names; toggle only this app's
   service if rebinding is needed. Document consent-based startup if silent startup is unsupported.
   **Gate:** fresh install and update preserve AT4K and restore capture/overlay; only then uninstall
   the probe (`dev.kbobbili.xiteprobe`).

## Risks

- **Capture, silent startup and recovery** — proven in Phase 1 on this TV/firmware; a firmware update
  could change app-op or background-launch behavior. Re-run the Phase 1 gates after major updates.
- **XITE's self-kill on pause** means any future UI (settings, consent) must never open over XITE.
- **Repeated choruses** can produce confident-looking wrong offsets (Phase 1 Dracula clip #7).
- **Unofficial Shazam access** can rate-limit (429) or stop working. Recognition sits behind one
  provider interface; any alternative needs separate SDK, pricing, offset-semantics and alignment
  validation before it can replace the implementation.
- **Lyrics version mismatch:** Shazam may match a remix or live version whose offset doesn't fit the
  LRCLIB lyrics we picked. Use recording evidence and timeline validation; hide ambiguous versions.
- **Unobserved edits:** periodic recognition leaves intervals of incorrect lyrics. Retry or
  confirmation may exceed the provisional recovery target; continuous accuracy is not promised.
  Continuity-based validity means a failed lookup right after an edit can leave wrong lines up to
  the 120 s cap — accepted in exchange for not blanking lyrics on every 429 or network blip.
- **Unobservable ads:** without a reliable boundary signal, old lyrics may remain until new evidence
  or the 120 s age cap. Immediate ad hiding remains unmet until the boundary detector is proven.
- **XITE app updates** may rename panel ids. Fallback: match text by position or structure.
- **Accessibility service unbinding** after app updates. Rebind only this service and verify recovery.
- **Debug recordings:** clean up exported WAVs after inspection. Normal operation keeps audio
  transient, sends fingerprints to recognition, and sends song metadata to lyrics lookup.

## Verification

**Rigor is front-loaded.** Phases 1–2 hold the real unknowns and get the full regime below
(timestamped recordings, annotated boundaries, measured targets). Phases 5–6 need only the
deterministic unit checks plus one ~20 min watch session covering the listed scenarios; the
measured accuracy figures from Phase 2 stand unless the watch session shows a regression.

- Record each phase gate as passed, failed or untested with device/build and relevant evidence.
- Deterministic checks: playback-clock extrapolation, invalidated clips, stale/out-of-order response
  rejection, confidence expiry, lyrics parsing and fingerprint compatibility.
- End-to-end: ~20 min/~6 songs plus targeted cases for a known edit, ad break, pause/resume, seek if
  supported, mid-song startup, app switching, network loss and projection revocation. Use controlled
  fixtures for transitions that cannot be reliably triggered on the TV.
- Measure initial appearance, stable lyric-boundary error and edit recovery separately against the
  provisional 30 s / ±0.5 s / 60 s criteria and their stated conditions. Use timestamped recordings
  and annotated audible boundaries for subsecond checks; whole-second logs or casual observation
  alone cannot establish accuracy. Report retries, failed matches, ambiguous versions and hidden time.
- Confirm panel disappearance preserves valid lyrics, confirmed boundaries clear old lyrics, pause
  freezes lines, other foreground apps show no overlay, and the remote still controls XITE normally.
- `adb logcat -s XiteLyrics`: generation, sample times, recording/candidate ids, accepted delta,
  confidence/freshness, rejection reasons, request latency and backoff. Distinguish clock errors,
  wrong versions, source timestamp errors and actual video edits in recorded findings.
- Full-build readiness requires passing capture and alignment gates. Completion requires acceptance
  evidence and explicit treatment of unmet requirements; an untested assumption is not a passed gate.

## Platform references

- [Android audio capture and token revocation](https://developer.android.com/media/platform/av-capture)
- [MediaProjection authorization and lifecycle](https://developer.android.com/media/grow/media-projection)
- [PlaybackState clock, speed and position](https://developer.android.com/reference/android/media/session/PlaybackState)
