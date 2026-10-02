"""Interpret the offset-semantics experiment (analysis/<id>-semantics.json).

If Shazam's offset is the reference time of the FIRST submitted sample:
  shift d      -> offset - d          is constant
  length L     -> offset              is unchanged
  pad p (zeros) -> offset + p          equals the unpadded offset
Prints the residual of each variant against the baseline (shift 0, 8 s, no pad).
"""
import json
import sys
from pathlib import Path

rows = json.loads(Path(sys.argv[1]).read_text())
by_item: dict[int, list[dict]] = {}
for r in rows:
    by_item.setdefault(r["item"], []).append(r)
for item, rs in by_item.items():
    base = next(r for r in rs if r["kind"] == "shift" and r["shift"] == 0 and r["match"])
    b = base["match"]["offset"]
    print(f"item {item}: {base['match']['title']} baseline offset {b:.3f}s")
    for r in rs:
        m = r["match"]
        if not m:
            print(f"  {r['kind']:6s} shift={r['shift']} len={r['length']} pad={r['pad']}: no match")
            continue
        o = m["offset"]
        if r["kind"] == "shift":
            res = (o - r["shift"]) - b
            rule = "offset - shift"
        elif r["kind"] == "length":
            res = o - b
            rule = "offset"
        else:
            res = (o + r["pad"]) - b
            rule = "offset + pad"
        print(f"  {r['kind']:6s} shift={r['shift']:4.2f} len={r['length']:4.1f} pad={r['pad']:3.1f}: "
              f"offset {o:8.3f}  ({rule}) - baseline = {res*1000:+7.1f} ms  skew={m['timeskew']}")
