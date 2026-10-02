"""Phase 2 numbers for docs/PHASE2-RESULTS.md from cached analysis files.

Usage: python tools/phase2/summary.py analysis/sessions/<id>
"""
import json
import statistics
import sys
from pathlib import Path

sid = Path(sys.argv[1].rstrip("/")).name
A = Path("analysis")


def load(kind):
    p = A / f"{sid}-{kind}.json"
    return json.loads(p.read_text()) if p.exists() else []


sched, dense = load("schedule"), load("dense")
calls = [json.loads(l) for l in open(A / "recog_calls.jsonl")]
print(f"Shazam calls logged: {len(calls)}, errors (incl. 429): {sum(1 for c in calls if c['error'])}, "
      f"median latency {statistics.median(c['latency_s'] for c in calls):.2f}s")

# Schedule: time to first match per item (window end + latency), songs only.
by_item = {}
for r in sched:
    by_item.setdefault(r["item"], []).append(r)
firsts, songs_matched, ads = [], 0, 0
for item, rows in sorted(by_item.items()):
    rows.sort(key=lambda r: r["rel_s"])
    m = next((r for r in rows if r["match"]), None)
    if m:
        songs_matched += 1
        firsts.append(m["rel_s"] + 8 + (m["latency_s"] or 0))
    else:
        ads += 1
print(f"schedule: {len(by_item)} items with windows; first match for {songs_matched}; never matched {ads}")
if firsts:
    print(f"  time to first match: median {statistics.median(firsts):.1f}s, max {max(firsts):.1f}s, "
          f"all <= 30 s: {sum(f <= 30 for f in firsts)}/{len(firsts)}")


def runs(rows):
    """Split an item's deltas into runs of agreeing values (+-0.3 s)."""
    out = []
    for r in rows:
        if "delta_s" not in r:
            continue
        if out and abs(r["delta_s"] - out[-1][-1]["delta_s"]) <= 0.3:
            out[-1].append(r)
        else:
            out.append([r])
    return out


src = dense or sched
print(f"\ndelta analysis from {'dense' if dense else 'schedule'} windows:")
items = {}
for r in src:
    items.setdefault(r["item"], []).append(r)
stable_spread = []
for item, rows in sorted(items.items()):
    rows.sort(key=lambda r: r["rel_s"])
    rs = runs(rows)
    if not rs:
        continue
    title = next((r["match"]["title"] for r in rows if r["match"]), "?")
    main = max(rs, key=len)
    ds = [r["delta_s"] for r in main]
    if len(ds) >= 3:
        stable_spread.append(max(ds) - min(ds))
    desc = " | ".join(f"{len(g)}x {statistics.median(x['delta_s'] for x in g):+.3f}s @{g[0]['rel_s']:.0f}-{g[-1]['rel_s']:.0f}s"
                      for g in rs)
    print(f"  item {item:2d} {title[:28]:28s} {desc}")
if stable_spread:
    print(f"\nlargest run spread per song (ms): median {statistics.median(stable_spread)*1000:.0f}, "
          f"max {max(stable_spread)*1000:.0f}")
