"""Phase 2 experiments on a pulled recording session. Run via tools/phase2/run.sh.

  summary   <session>        clock fit, items (songs/ads) found
  semantics <session> [N]    offset semantics: shifted starts, lengths, leading silence on N songs
  schedule  <session>        the plan's schedule (first clip +12 s, every 45 s, retry 15 s after a miss)
  dense     <session>        8 s windows every 15 s for delta-consistency analysis
  live      <minutes> [serial]  pull from the TV every minute and run the schedule on all
                             sessions at the planned pace (the Phase 2 rate-limit test)

<session> is analysis/sessions/<id>/ (made by tools/phase2/pull.sh); events.jsonl sits beside it.
"""
from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from recog import Recognizer, match  # noqa: E402
from session import RATE, Session  # noqa: E402

CLIP_S = 8.0


def load(arg: str) -> Session:
    folder = Path(arg)
    return Session(folder, folder.parent.parent / "events.jsonl")


def window_ok(sess: Session, item, start_rt: float, seconds: float) -> bool:
    """Whole window inside the recording and inside one uninterrupted playing span."""
    end = start_rt + seconds * 1000
    if start_rt < sess.start_rt or end > sess.end_rt:
        return False
    return any(a <= start_rt and end <= b for a, b in item.playing_spans())


def recognize_window(rec: Recognizer, sess: Session, item, start_rt: float, seconds: float,
                     label: str, pad_s: float = 0.0) -> dict:
    pcm = sess.audio(start_rt, seconds)
    if pad_s:
        pcm = np.concatenate([np.zeros(int(pad_s * RATE), np.int16), pcm])
    vpos = item.video_pos(start_rt)
    res = rec.recognize(pcm, label, {"item": item.index, "start_rt": start_rt, "seconds": seconds,
                                     "pad_s": pad_s, "video_pos_ms": vpos})
    m = match(res)
    row = {"label": label, "item": item.index, "cached": bool(res.get("cached")), "start_rt": start_rt, "rel_s": (start_rt - item.start_rt) / 1000,
           "video_pos_s": None if vpos is None else vpos / 1000, "latency_s": res.get("latency_s"),
           "error": res.get("error"), "match": m}
    if m and vpos is not None and m["offset"] is not None:
        row["delta_s"] = m["offset"] - vpos / 1000
    return row


def cmd_summary(sess: Session) -> None:
    print(f"session {sess.folder.name}: {len(sess.pcm)/RATE/60:.1f} min, rt {sess.start_rt/1000:.1f}..{sess.end_rt/1000:.1f}s")
    print(f"clock fit: effective rate {sess.effective_rate:.3f} Hz, max residual {sess.fit_resid_ms:.2f} ms")
    for it in sess.items:
        dur = (it.end_rt - it.start_rt) / 1000
        kind = f"{it.title} / {it.artist}" if it.is_song else "(no panel: ad/unknown)"
        panel = f"panel +{(it.panel_rt - it.start_rt)/1000:.1f}s" if it.panel_rt else ""
        print(f"  item {it.index:2d} start {it.start_rt/1000:9.1f}s len {dur:6.1f}s spans {len(it.playing_spans())} {panel:12s} {kind}")


def cmd_semantics(sess: Session, rec: Recognizer, n_songs: int) -> list[dict]:
    """Cut known-start variants from one stretch of a song. If Shazam's offset refers to the
    first submitted sample, offset - shift is constant across shifts and lengths, and leading
    silence of L seconds lowers the offset by exactly L."""
    rows = []
    songs = [it for it in sess.items if it.is_song and (it.end_rt - it.start_rt) > 90_000][:n_songs]
    for it in songs:
        base = it.start_rt + 40_000
        variants = [("shift", d, CLIP_S, 0.0) for d in (0.0, 0.37, 1.0, 2.5, 5.0)]
        variants += [("length", 0.0, L, 0.0) for L in (4.0, 6.0, 12.0)]
        variants += [("pad", 0.0, CLIP_S, p) for p in (0.5, 1.0, 2.0)]
        for kind, d, L, pad in variants:
            if not window_ok(sess, it, base + d * 1000, L):
                continue
            r = recognize_window(rec, sess, it, base + d * 1000, L, f"sem:{kind}:{d}:{L}:{pad}", pad)
            r.update(kind=kind, shift=d, length=L, pad=pad)
            rows.append(r)
            m = r["match"]
            print(f"item {it.index} {kind:6s} shift={d:4.2f} len={L:4.1f} pad={pad:3.1f} -> "
                  f"{m['offset'] if m else None} {m['title'] if m else r['error']}", flush=True)
    return rows


def cmd_schedule(sess: Session, rec: Recognizer, quiet_cached: bool = False) -> list[dict]:
    rows = []
    for it in sess.items:
        t = it.start_rt + 12_000
        while t + CLIP_S * 1000 <= it.end_rt:
            if not window_ok(sess, it, t, CLIP_S):
                t += 5_000
                continue
            r = recognize_window(rec, sess, it, t, CLIP_S, "sched")
            rows.append(r)
            m = r["match"]
            if not (quiet_cached and r.get("cached")):
                print(f"item {it.index:2d} +{r['rel_s']:6.1f}s {('%-40s' % (m['title'] + ' / ' + m['artist']))[:40] if m else 'no match':40s} "
                  f"delta={r.get('delta_s', float('nan')):8.3f} skew={m['timeskew'] if m else ''} err={r['error'] or ''}", flush=True)
            if m:
                t += 45_000
            else:
                t += (CLIP_S + (r["latency_s"] or 0) + 15) * 1000
    return rows


def cmd_dense(sess: Session, rec: Recognizer) -> list[dict]:
    rows = []
    for it in sess.items:
        if not it.is_song:
            continue
        t = it.start_rt + 5_000
        while t + CLIP_S * 1000 <= it.end_rt:
            if window_ok(sess, it, t, CLIP_S):
                r = recognize_window(rec, sess, it, t, CLIP_S, "dense")
                rows.append(r)
                m = r["match"]
                print(f"item {it.index:2d} +{r['rel_s']:6.1f}s {(m['title'] if m else 'no match')[:30]:30s} "
                      f"delta={r.get('delta_s', float('nan')):8.3f} skew={m['timeskew'] if m else ''} n={m['n_matches'] if m else 0}",
                      flush=True)
            t += 15_000
    return rows


def save(sess: Session, name: str, rows: list[dict]) -> None:
    out = Path("analysis") / f"{sess.folder.name}-{name}.json"
    out.write_text(json.dumps(rows, indent=1))
    print(f"saved {out}")


def cmd_live(minutes: float, serial: str | None) -> None:
    import subprocess
    import time
    rec = Recognizer(min_interval_s=50)   # <= 72 calls/hour, the plan's cadence
    stop = time.time() + minutes * 60
    while time.time() < stop:
        # adb directly: "bash" from Python on Windows can resolve to WSL, not Git Bash.
        adb = ["adb"] + (["-s", serial] if serial else [])
        src = "/sdcard/Android/data/dev.kbobbili.xitelyrics/files"
        Path("analysis/sessions").mkdir(parents=True, exist_ok=True)
        p1 = subprocess.run(adb + ["pull", f"{src}/rec/.", "analysis/sessions/"], capture_output=True, text=True)
        p2 = subprocess.run(adb + ["pull", f"{src}/events.jsonl", "analysis/events.jsonl"], capture_output=True, text=True)
        if p1.returncode or p2.returncode:
            print(f"pull failed: {p1.stderr.strip()} {p2.stderr.strip()}", flush=True)
        for folder in sorted(Path("analysis/sessions").iterdir()):
            try:
                sess = load(str(folder))
            except (FileNotFoundError, ValueError) as e:
                print(f"skip {folder.name}: {e}")
                continue
            rows = cmd_schedule(sess, rec, quiet_cached=True)
            save(sess, "schedule", rows)
        time.sleep(60)


def main() -> None:
    cmd, arg = sys.argv[1], sys.argv[2]
    if cmd == "live":
        cmd_live(float(arg), sys.argv[3] if len(sys.argv) > 3 else None)
        return
    sess = load(arg)
    if cmd == "summary":
        cmd_summary(sess)
        return
    rec = Recognizer(min_interval_s=float(__import__("os").environ.get("RECOG_INTERVAL", "50")))
    if cmd == "semantics":
        save(sess, "semantics", cmd_semantics(sess, rec, int(sys.argv[3]) if len(sys.argv) > 3 else 2))
    elif cmd == "schedule":
        save(sess, "schedule", cmd_schedule(sess, rec))
    elif cmd == "dense":
        save(sess, "dense", cmd_dense(sess, rec))
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
