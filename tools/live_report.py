"""How did the app behave live? Summarises the TV's events.jsonl per item.

Per song: when the panel/provisional/first match/first line happened, how far the
provisional guess was off, timing conflicts, realigns, "music lost" moments
(scenes), errors and rate limits. Only events after the last app start
(a11y_connected) at or before --since (elapsed-realtime seconds) are used.

Usage: python tools/live_report.py analysis/live/events.jsonl [--since RT_SECONDS]
"""
import json
import statistics
import sys

sys.stdout.reconfigure(encoding="utf-8", errors="replace")
path = sys.argv[1]
since = float(sys.argv[sys.argv.index("--since") + 1]) * 1000 if "--since" in sys.argv else 0

ev = []
for line in open(path, encoding="utf-8"):
    try:
        ev.append(json.loads(line))
    except ValueError:
        pass
# last boot only
cut = 0
for i in range(1, len(ev)):
    if ev[i]["rt"] < ev[i - 1]["rt"] - 60_000:
        cut = i
ev = [e for e in ev[cut:] if e["rt"] >= since]

items, cur = [], None
for e in ev:
    t = e["type"]
    if t == "sync_generation" and e.get("why") == "new_item":
        cur = {"start": e["rt"], "title": None, "events": []}
        items.append(cur)
        continue
    if cur is None:
        continue
    if t == "sync_generation":            # foreground lost / session gone ends the item
        cur["end_reason"] = e.get("why")
        cur = None
        continue
    if t == "panel" and cur["title"] is None:
        cur["title"] = f"{e.get('title')} / {e.get('artist')}"
    cur["events"].append(e)

songs, ads = [], 0
calls = errors = limited = 0
for it in items:
    es = it["events"]
    rel = lambda e: (e["rt"] - it["start"]) / 1000  # noqa: E731
    kinds = [e["type"] for e in es]
    if "sync_ad" in kinds:
        ads += 1
        continue
    clips = [e for e in es if e["type"] == "sync_clip"]
    calls += len(clips)
    errors += sum("ERROR" in e.get("result", "") for e in clips)
    limited += kinds.count("sync_rate_limited")
    if not it["title"]:
        continue
    first_line = next((rel(e) for e in es if e["type"] == "display" and e.get("line")), None)
    snap = next((e["deltaMs"] for e in es if e["type"] == "sync_provisional_snap"), None)
    first_match = next((rel(e) for e in es if e["type"] == "sync_accept"), None)
    lost = [rel(e) for e in es if e["type"] == "sync_music_lost"]
    conflicts = [rel(e) for e in es if e["type"] == "sync_conflict"]
    realigned = [(rel(e), e.get("toMs", 0) - e.get("fromMs", 0)) for e in es if e["type"] == "sync_realigned"]
    back = [rel(e) for e in es if e["type"] == "sync_accept" and e.get("why") == "music_back"]
    dur = (es[-1]["rt"] - it["start"]) / 1000 if es else 0
    songs.append(dict(title=it["title"], dur=dur, first_line=first_line, snap=snap, first_match=first_match,
                      lost=lost, back=back, conflicts=conflicts, realigned=realigned, clips=len(clips)))

print(f"{len(songs)} songs, {ads} ads | Shazam calls {calls}, errors {errors}, rate-limited {limited}\n")
for s in songs:
    fl = f"{s['first_line']:5.1f}s" if s["first_line"] is not None else "  -  "
    sn = f"{s['snap']:+5.0f}ms" if s["snap"] is not None else "   -   "
    extra = []
    if s["lost"]:
        extra.append("music lost @" + ",".join(f"{x:.0f}" for x in s["lost"]))
    if s["back"]:
        extra.append("back @" + ",".join(f"{x:.0f}" for x in s["back"]))
    if s["realigned"]:
        extra.append("edit " + ",".join(f"@{x:.0f}s {d/1000:+.1f}s" for x, d in s["realigned"]))
    print(f"  {s['title'][:40]:40s} {s['dur']:5.0f}s  first line {fl}  provisional off {sn}  "
          f"clips {s['clips']:2d}  {'; '.join(extra)}")

fls = [s["first_line"] for s in songs if s["first_line"] is not None]
snaps = [abs(s["snap"]) for s in songs if s["snap"] is not None]
print(f"\nfirst line on screen: median {statistics.median(fls):.1f}s (n={len(fls)})")
if snaps:
    print(f"provisional timing error: median {statistics.median(snaps):.0f} ms, "
          f"<=500 ms {sum(x <= 500 for x in snaps)}/{len(snaps)}, max {max(snaps):.0f} ms")
print(f"songs with scenes/music lost: {sum(bool(s['lost']) for s in songs)}; "
      f"with edits/realigns: {sum(bool(s['realigned']) for s in songs)}; "
      f"clips per song: median {statistics.median(s['clips'] for s in songs):.0f}")
