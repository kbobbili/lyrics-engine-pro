"""Score a SyncEngine replay (analysis/<id>-replay.json) against Phase 2 ground truth.

Truth = the dense-window deltas (analysis/<id>-dense.json), taken from the
nearest dense window. Per item it reports:
  first     seconds from item start until lyrics first show
  shown     % of the item (after the first 20 s) with lyrics visible
  wrong     seconds shown with engine delta off by > 0.5 s from truth
  calls     Shazam captures the engine made for the item
Usage: python tools/phase5/replay_report.py analysis/sessions/<id>
"""
import bisect
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, "tools/phase2")
from session import Session  # noqa: E402

folder = Path(sys.argv[1].rstrip("/"))
sid = folder.name
sess = Session(folder, folder.parent.parent / "events.jsonl")
rep = json.loads(Path(f"analysis/{sid}-replay.json").read_text())
tl = rep["timeline"]
dense = json.loads(Path(f"analysis/{sid}-dense.json").read_text())

# Engine state over time: visibility (display events) and delta (accept/rebase events).
vis_t, vis_v = [], []
delta_t, delta_v = [], []
captures = []
for e in tl:
    if e["type"] == "display":
        vis_t.append(e["rt"]); vis_v.append(e["line"] is not None)
    elif e["type"] in ("sync_accept", "sync_rebase"):
        delta_t.append(e["rt"]); delta_v.append(e["deltaMs"] / 1000)
    elif e["type"] == "sync_clip" or e["type"] == "sync_generation":
        pass


def at(ts, vs, t, default=None):
    i = bisect.bisect_right(ts, t) - 1
    return vs[i] if i >= 0 else default


def truth(item, t):
    rows = [r for r in dense if r["item"] == item and "delta_s" in r]
    if not rows:
        return None
    best = min(rows, key=lambda r: abs(r["start_rt"] + 4000 - t))
    return best["delta_s"] if abs(best["start_rt"] + 4000 - t) <= 15_000 else None


# Count captures per item from the engine's accept/miss/conflict log lines is indirect; use capturesPerGen.
print(f"Shazam: {rep['shazamCacheHits']} cache hits, {rep['shazamNetworkCalls']} network calls")
rows = []
for it in sess.items:
    start, end = max(it.start_rt, sess.start_rt), min(it.end_rt, sess.end_rt)
    if end - start < 10_000:
        continue
    first = None
    shown = wrong = total = 0.0
    t = start
    while t < end:
        v = at(vis_t, vis_v, t, False)
        if v and first is None:
            first = (t - it.start_rt) / 1000
        if t - it.start_rt >= 20_000:
            total += 0.25
            if v:
                shown += 0.25
                d, tr = at(delta_t, delta_v, t), truth(it.index, t)
                if d is not None and tr is not None and abs(d - tr) > 0.5:
                    wrong += 0.25
        t += 250
    kind = (it.title or "(ad)")[:26]
    rows.append((it.index, kind, first, 100 * shown / total if total else 0, wrong, it.is_song))
    print(f"  item {it.index:2d} {kind:26s} first {('%.1fs' % first) if first is not None else '  -  ':>6} "
          f"shown {rows[-1][3]:5.1f}%  wrong {wrong:5.1f}s")

songs = [r for r in rows if r[5]]
ads = [r for r in rows if not r[5]]
firsts = [r[2] for r in songs if r[2] is not None]
print(f"\nsongs {len(songs)}: lyrics shown for {len(firsts)}; first display median {statistics.median(firsts):.1f}s, "
      f"max {max(firsts):.1f}s; shown median {statistics.median(r[3] for r in songs):.0f}%; "
      f"wrong total {sum(r[4] for r in songs):.1f}s")
print(f"ads {len(ads)}: any lyrics shown during an ad: {any(r[2] is not None for r in ads)}")
gens = rep.get("capturesPerGen", {})
print(f"captures: {sum(v[0] if isinstance(v, list) else v for v in gens.values())} total over {len(gens)} generations")
