"""Phase-averaged comparison of re-check strategies on the dense windows (no new Shazam calls).

The dense pass has a real 8 s recognition every 15 s through every song. A
strategy that checks every 45 s (or 30 s) is simulated by taking every 3rd (or
2nd) dense window, starting at each possible phase, so luck in where checks
land is averaged out. Seconds of wrong lyrics per edit:

  without A: from the edit until the conflicting check's result (lyrics then hidden
             during confirmation: 5 s wait + 8 s clip + latency ~13.3 s, shown as "hidden")
  with A:    from the edit until the conflicting check's result (new timing shown at once)

The edit time is taken as the start of the first window whose delta jumped
(the true cut lies before it, so wrong time is a slight underestimate for all).
Usage: python tools/phase5/cadence_sim.py analysis/sessions/<id>
"""
import json
import statistics
import sys
from pathlib import Path

sid = Path(sys.argv[1].rstrip("/")).name
dense = json.loads(Path(f"analysis/{sid}-dense.json").read_text())
CLIP_S, LAT_S, CONFIRM_S = 8.0, 0.3, 5.0 + 8.0 + 0.3

items = {}
for r in dense:
    items.setdefault(r["item"], []).append(r)

results = {}
for step, label in ((3, "45 s"), (2, "30 s")):
    wrong_runs, hidden_runs, calls_per_song = [], [], []
    for phase in range(step):
        wrong = hidden = 0.0
        for item, rows in items.items():
            rows = sorted(rows, key=lambda r: r["rel_s"])
            checks = rows[phase::step]
            # edits: windows where the true delta departs from the previous window's by > 0.5 s
            edits = [rows[i]["rel_s"] for i in range(1, len(rows))
                     if "delta_s" in rows[i] and "delta_s" in rows[i - 1]
                     and abs(rows[i]["delta_s"] - rows[i - 1]["delta_s"]) > 0.5]
            for e_t in edits:
                nxt = next((c for c in checks if c["rel_s"] >= e_t and "delta_s" in c), None)
                if nxt is None:
                    continue
                wrong += nxt["rel_s"] + CLIP_S + LAT_S - e_t
                hidden += CONFIRM_S
        wrong_runs.append(wrong)
        hidden_runs.append(hidden)
    n_songs = len(items)
    results[label] = (statistics.mean(wrong_runs), statistics.mean(hidden_runs), wrong_runs)
    print(f"checks every {label}: wrong lyrics after edits {statistics.mean(wrong_runs):5.1f} s "
          f"(by phase: {', '.join(f'{w:.0f}' for w in wrong_runs)}); "
          f"+ hidden while confirming {statistics.mean(hidden_runs):.0f} s without A, 0 s with A")
n_edits = sum(1 for rows in items.values() for i in range(1, len(rows))
              if "delta_s" in sorted(rows, key=lambda r: r['rel_s'])[i])
