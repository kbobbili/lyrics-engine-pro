"""Python reference choices for the Java lyrics-selection equivalence test.

For every LRCLIB search cached in analysis/lrclib/, records the title/artist
that produced it and what tools/phase2/lyrics.choose() picks. Titles come from
analysis/lrclib_queries.json, which lyrics.search() maintains.
Output: analysis/lrclib_choices.json
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, "tools/phase2")
from lyrics import choose  # noqa: E402

queries = json.loads(Path("analysis/lrclib_queries.json").read_text(encoding="utf-8"))
out = []
for q in queries:
    c = choose(q["title"], q["artist"])
    out.append({"title": q["title"], "artist": q["artist"], "file": q["file"], "status": c.status,
                "id": c.candidate["id"] if c.candidate else 0, "lines": len(c.lines)})
    print(f"{q['title']} / {q['artist']}: {c.status} {out[-1]['id']}")
Path("analysis/lrclib_choices.json").write_text(json.dumps(out, indent=1), encoding="utf-8")
