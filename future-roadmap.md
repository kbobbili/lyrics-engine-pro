# Future roadmap

Open ideas and decisions for XITE Lyrics, with the evidence behind them. Newest first.

---

## 1. Translations for non-English songs (next up — owner leaning towards DeepL)

### The ask

While Bad Bunny's *NUEVAYoL* played, the overlay showed each Spanish line followed by its English
translation. The owner liked that and wants it for Latin and other international music on XITE —
with a **clear visual separation** between lyric and translation (it currently reads as one run-on
line, joined by a single `^`).

### What we found (2026-10-02)

- **The translation was an accident of one LRCLIB upload**, not an LRCLIB feature. The chosen upload
  (id 34424320) writes every line as `original^translation`:
  ```text
  [00:20.75] Si te quieres divertir^If you want to have fun
  [00:25.25] Con encanto y con primor^With charm and grace
  ```
  The app shows the whole string, hence the `^` between them.
- **Other non-English songs had no translation on LRCLIB**: BAILE INoLVIDABLE (Spanish), Likey Likey
  (mixed English/Spanish), XENA (Arabic), Jacaré (Portuguese).
- **Volume is small:** in ~3 h of live viewing (51 songs) about **1 in 10 songs was non-English**.
  Lyrics are ~1,500 characters per song; each song needs translating once and can then be cached.

### Approaches considered

| Source | Quality on lyrics | Cost | Setup | Mixed-language songs |
|---|---|---|---|---|
| LRCLIB uploads only | Human, when present | Free | None | Rare; separators vary (`^`, ` / `, parentheses) |
| **DeepL API Free** *(leaning)* | Very good for es/pt/fr/it/de…; Arabic, Korean, Japanese supported | Free up to 500k characters/month (~300 songs) | DeepL account + API key | Per-text `detected_source_language` lets English lines be skipped |
| Google Cloud Translation | Good, 130+ languages | Free up to 500k/month, then paid | Billing account + key | Same as DeepL |
| Claude API (Haiku) | Best with slang/idioms; keeps lines paired | ~$0.003–0.005 per song | Anthropic API key | Instructable: "translate only non-English lines" |
| On-device ML Kit | Weaker on lyrics | Free, offline | Hard with the plain-SDK (no Gradle) build | Weak |

### Proposed design (DeepL)

1. **Prefer a human translation when the upload has one.** If most lines of the chosen LRCLIB upload
   contain a separator (`^` first; consider ` / `), split each line into original + translation.
2. **Otherwise machine-translate once per song** and cache on the TV, keyed by LRCLIB upload id
   (+ target language). Repeat plays cost nothing; the free tier covers this usage many times over.
3. **Only translate lines that aren't English.** Send all non-empty lines in one request (DeepL accepts
   multiple `text` values per call) with `target_lang=EN-US`; drop a translation whose
   `detected_source_language` is `EN`, or whose text equals the original.
4. **Never block lyrics on translation.** Show the original line as today; translations appear when
   they arrive. Failures/429s/quota-exhausted just mean no translation for that song (logged).
5. **Display: clear separation.** Original line unchanged (style 5, Lexend). Translation underneath:
   smaller, italic, a different colour (e.g. soft amber, or dimmed white), with a visible gap — inside
   the same box. Render 2–3 variants on the TV with the preview hook and let the owner pick, as with
   the main style. Add an on/off switch (`TRANSLATE` broadcast, persisted).
6. **Secrets:** the DeepL key must not be committed. Push it to the TV once (e.g. an adb broadcast or a
   file in app storage) and keep it out of git and logs.

DeepL details to confirm at implementation time: Free endpoint `https://api-free.deepl.com/v2/translate`,
header `Authorization: DeepL-Auth-Key <key>`, up to 50 texts per request, per-text
`detected_source_language`, current language list and free-tier limits.

### Where it fits in the code

- `lyrics/` — new `Translator` (DeepL client, disk cache) and `Lrc` support for split
  original/translation lines; `Lrc.Line` gains an optional `translation`.
- `SyncEngine.Display` — carries `translation` alongside `line`.
- `LyricsOverlay` — second text view with its own style; keep the INVISIBLE-not-GONE rule.
- Tests: separator splitting, English-line skipping, cache, failure paths (local test server as in
  `tools/phase4/ClientTests.java`), engine display of translations.

---

## 2. Hide lyrics while XITE's own menu is open

Pressing D-pad on the remote opens XITE's menu (Find / Create / Explore); the lyrics box overlaps those
buttons. The accessibility service already reads XITE's screen, so it can detect the menu and hide the
overlay while it's up. Small change; proposed, not yet approved.

## 3. Re-check rhythm within songs — done 2026-10-02

Now: first 5 s clip as soon as the info panel appears, then an 8 s clip **every 30 s** (~8 per song);
on a timing jump the new timing is shown **at once** and confirmed by the next clip (reverted if the
old timing is confirmed); 15 s re-check after a confirmed edit; 3 s polling after the music stops.
Phase-averaged on the recorded evening (`tools/phase5/cadence_sim.py`): wrong-lyric time after edits
104 s → 79 s, and no blank gaps while confirming. Watch the event log for `sync_rate_limited`; if
Shazam starts limiting, `SyncEngine.CLIP_PERIOD` is the knob.

## 4. Decided against (for now)

- **Extra early check ~25 s into each song.** Redundant once checks are every 30 s.
- **On-TV scene detector** (spot acted scenes the moment music stops). Only 1 of 51 songs had real
  scenes; "hide when the music stops" already handles them reasonably. Riskiest change for the smallest
  gain.
- **Word-level highlighting.** LRCLIB has no word timing (0 of 421 uploads); an estimated sweep would
  look wrong on held notes.
- **Phone-video latency calibration** (`devicePipelineOffset`). Lyrics look right with 0; revisit only if
  lines feel consistently early/late.

## 5. Known limits (by design)

- **Live versions** (`[Live]`) get no lyrics: studio timing doesn't fit them.
- **Songs with conflicting LRCLIB timings** (e.g. *Treat People With Kindness*, *Jacaré*) stay blank
  rather than risk wrong lines all song. Could be revisited by validating candidate timings against the
  captured audio.
- **Capture can only start from the home screen** (XITE kills itself under any activity). If capture is
  lost mid-XITE, lyrics wait for the next home-screen visit.

## 6. Housekeeping — done 2026-10-02

- Old probe app `dev.kbobbili.xiteprobe` uninstalled (Phase 7); AT4K and XITE Lyrics stay enabled.
- Recordings deleted from the TV. Kept on the PC: the 70-minute Phase 2 session in
  `analysis/sessions/1790914276394` (gitignored) — the replay/simulation test bench and the source of
  the fingerprint fixtures.
