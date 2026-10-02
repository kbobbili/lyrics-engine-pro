"""Fast-start experiment: how soon can lyrics appear at the start of a song?

A. Short early clips: Shazam on clips starting +2 s / +4 s after the item
   starts, 3 / 4 / 5 s long. Match rate and correctness vs the song's true
   starting delta (first run of the dense windows).
B. Provisional display from the panel identity with delta = 0 until the
   first match: which LRC lines fall before alignment, and how far off they
   would be (|true starting delta|).

Usage (via run.sh-style env): python tools/phase2/fast_start.py analysis/sessions/<id>
"""
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from lyrics import choose  # noqa: E402
from recog import Recognizer, match  # noqa: E402
from session import Session  # noqa: E402

folder = Path(sys.argv[1].rstrip("/"))
sess = Session(folder, folder.parent.parent / "events.jsonl")
dense = json.loads((Path("analysis") / f"{folder.name}-dense.json").read_text())
rec = Recognizer(min_interval_s=6)

STARTS = [2.0, 4.0]
LENGTHS = [3.0, 4.0, 5.0]
CURRENT_ALIGNED_S = 20.3      # today's schedule: +12 s clip of 8 s + latency


def true_start_delta(item):
    rows = sorted((r for r in dense if r["item"] == item and "delta_s" in r), key=lambda r: r["rel_s"])
    return rows[0]["delta_s"] if rows else None


results = []
for it in sess.items:
    if not it.is_song or it.end_rt - it.start_rt < 60_000 or it.start_rt < sess.start_rt:
        continue
    truth = true_start_delta(it.index)
    row = {"item": it.index, "title": it.title, "truth": truth, "variants": {}}
    for st in STARTS:
        for ln in LENGTHS:
            t = it.start_rt + st * 1000
            pcm = sess.audio(t, ln)
            vpos = it.video_pos(t)
            res = rec.recognize(pcm, f"fast:{st}:{ln}", {"item": it.index, "start": st, "len": ln})
            m = match(res)
            ok = bool(m) and vpos is not None and truth is not None and \
                abs((m["offset"] - vpos / 1000) - truth) <= 0.3
            row["variants"][f"+{st:g}s/{ln:g}s"] = {
                "matched": bool(m), "correct": ok,
                "title": m["title"] if m else None,
                "delta": (m["offset"] - vpos / 1000) if m and vpos is not None else None,
            }
    # Option B: lines before today's alignment time and their provisional error.
    try:
        c = choose(it.title, it.artist)
    except Exception as e:  # LRCLIB outage
        c = None
    lines_early = []
    if c and c.status == "ok" and truth is not None:
        # line on the song timeline at time T is shown at video time T - delta; with delta=0 it shows at T.
        lines_early = [l for l in c.lines if l.text and l.t - truth < CURRENT_ALIGNED_S]
        row["first_line_s"] = round(min(l.t for l in c.lines if l.text) - truth, 1)
    row["lines_before_today_alignment"] = len(lines_early)
    row["provisional_error_s"] = abs(truth) if truth is not None else None
    results.append(row)
    v = row["variants"]
    print(f"{it.title[:28]:28s} truth {truth:+.2f}s  first line @{row.get('first_line_s', '?')}s  "
          f"lost-today {len(lines_early)}  " + " ".join(
              f"{k}:{'Y' if x['correct'] else ('w' if x['matched'] else '-')}" for k, x in v.items()), flush=True)

Path("analysis") .joinpath(f"{folder.name}-faststart.json").write_text(json.dumps(results, indent=1))
print()
for k in results[0]["variants"]:
    ok = sum(r["variants"][k]["correct"] for r in results)
    wrong = sum(r["variants"][k]["matched"] and not r["variants"][k]["correct"] for r in results)
    print(f"{k}: correct {ok}/{len(results)}, wrong match {wrong}")
errs = [r["provisional_error_s"] for r in results if r["provisional_error_s"] is not None]
print(f"\nOption B provisional error (|starting delta|): median {statistics.median(errs):.2f}s, "
      f"<=0.5s {sum(e <= 0.5 for e in errs)}/{len(errs)}, max {max(errs):.2f}s")
print(f"lines sung before today's ~20 s alignment: total {sum(r['lines_before_today_alignment'] for r in results)} "
      f"across {len(results)} songs")
