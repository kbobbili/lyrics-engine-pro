"""Lyric-boundary check on the captured-audio timeline (plan: "How audible is measured").

Instead of hand-annotating line onsets, transcribe the captured song with
faster-whisper word timestamps, map each word to the recording timeline via the
measured delta, and compare with the chosen LRCLIB line times:

    referencePos(word) = videoPos(word_rt) + delta(nearest consistent match)
    error(line)        = referencePos(first sung words of the line) - lrc_time(line)

Whisper's own word timing in singing is roughly +-0.1-0.3 s, so these errors are
an upper-bound-ish estimate; the median over many lines is the useful number.

Usage (via run_align.sh): align.py analysis/sessions/<id> [item ...]
Uses deltas from analysis/<id>-dense.json and/or -schedule.json (run.py).
"""
from __future__ import annotations

import difflib
import json
import statistics
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from lyrics import choose, norm  # noqa: E402
from session import RATE, Session  # noqa: E402

MAX_LINE_SEARCH_S = 6.0


def consistent_deltas(rows: list[dict], item: int) -> list[tuple[float, float]]:
    """(rt, delta_s) for windows whose delta agrees (+-0.3 s) with a neighbour window."""
    pts = [(r["start_rt"], r["delta_s"]) for r in rows if r["item"] == item and "delta_s" in r]
    pts.sort()
    keep = []
    for i, (rt, d) in enumerate(pts):
        nb = [pts[j][1] for j in (i - 1, i + 1) if 0 <= j < len(pts)]
        if any(abs(d - x) <= 0.3 for x in nb):
            keep.append((rt, d))
    return keep


def delta_at(deltas: list[tuple[float, float]], rt: float) -> float | None:
    if not deltas:
        return None
    rt_near, d = min(deltas, key=lambda p: abs(p[0] - rt))
    return d if abs(rt_near - rt) <= 25_000 else None


def transcribe(model, pcm: np.ndarray) -> list[tuple[float, str]]:
    audio = pcm.astype(np.float32) / 32768.0
    segments, _ = model.transcribe(audio, word_timestamps=True, vad_filter=False, beam_size=5)
    words = []
    for seg in segments:
        for w in seg.words or []:
            words.append((w.start, norm(w.word)))
    return [(t, w) for t, w in words if w]


def main() -> None:
    from faster_whisper import WhisperModel
    folder = Path(sys.argv[1])
    sess = Session(folder, folder.parent.parent / "events.jsonl")
    rows = []
    for kind in ("dense", "schedule"):   # any windows with deltas will do
        f = Path("analysis") / f"{folder.name}-{kind}.json"
        if f.exists():
            rows += json.loads(f.read_text())
    only = {int(x) for x in sys.argv[2:]}
    model = WhisperModel("small", device="cpu", compute_type="int8")
    report = []
    for it in sess.items:
        if not it.is_song or (only and it.index not in only):
            continue
        deltas = consistent_deltas(rows, it.index)
        if not deltas:
            print(f"item {it.index}: no consistent alignment, skipped")
            continue
        try:
            choice = choose(it.title, it.artist)
        except Exception as e:  # LRCLIB outage: record and move on
            print(f"item {it.index} {it.title}: lyrics lookup failed ({e})")
            report.append({"item": it.index, "title": it.title, "lyrics": "lookup_failed", "note": str(e)})
            continue
        if choice.status != "ok":
            print(f"item {it.index} {it.title}: lyrics {choice.status} ({choice.note})")
            report.append({"item": it.index, "title": it.title, "lyrics": choice.status, "note": choice.note})
            continue
        start = max(it.start_rt, sess.start_rt)
        end = min(it.end_rt, sess.end_rt)
        pcm = sess.audio(start, (end - start) / 1000)
        words = []
        cache = Path("analysis/whisper") / f"{folder.name}-{it.index}.json"
        if cache.exists():
            words_raw = json.loads(cache.read_text())
        else:
            words_raw = transcribe(model, pcm)
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_text(json.dumps(words_raw))
        for t, w in words_raw:
            rt = start + t * 1000
            vp, d = it.video_pos(rt), delta_at(deltas, rt)
            if vp is not None and d is not None:
                words.append((vp / 1000 + d, w))
        errors = []
        for line in choice.lines:
            toks = norm(line.text).split()
            if len(toks) < 2:
                continue
            head = " ".join(toks[:3])
            best = None
            for k in range(len(words)):
                ref_t = words[k][0]
                if abs(ref_t - line.t) > MAX_LINE_SEARCH_S:
                    continue
                cand = " ".join(w for _, w in words[k:k + len(toks[:3])])
                if difflib.SequenceMatcher(None, head, cand).ratio() >= 0.75:
                    err = ref_t - line.t
                    if best is None or abs(err) < abs(best):
                        best = err
            if best is not None:
                errors.append(best)
        n_lines = sum(1 for l in choice.lines if len(norm(l.text).split()) >= 2)
        if errors:
            med = statistics.median(errors)
            within = sum(abs(e) <= 0.5 for e in errors) / len(errors)
            print(f"item {it.index} {it.title}: lines matched {len(errors)}/{n_lines}, median err {med:+.2f}s, "
                  f"median |err| {statistics.median(abs(e) for e in errors):.2f}s, within 0.5s {within:.0%}, "
                  f"lrclib id {choice.candidate['id']} dur {choice.candidate['duration']}")
        else:
            print(f"item {it.index} {it.title}: no lines matched (of {n_lines})")
        report.append({"item": it.index, "title": it.title, "artist": it.artist, "lyrics": "ok",
                       "lrclib_id": choice.candidate["id"], "lrclib_duration": choice.candidate["duration"],
                       "groups": [[c["id"] for c in g] for g in choice.groups],
                       "lines": n_lines, "errors_s": errors})
    out = Path("analysis") / f"{folder.name}-align.json"
    out.write_text(json.dumps(report, indent=1))
    print(f"saved {out}")


if __name__ == "__main__":
    main()
