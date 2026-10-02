"""LRCLIB lookup, recording grouping and LRC parsing (plan: "Lyrics selection").

- Search by title/artist; keep synced candidates only.
- Drop snippets (< 60 s or < 5 timed lines) and prefer unlabelled uploads
  over remix/live/acoustic/... ones.
- Group uploads by lyric *timing*, not metadata duration: two uploads are the
  same recording if their shared lines sit within 0.75 s (median). Phase 2
  found metadata durations of one recording spread ~10 s (Dracula 200-210 s),
  so the plan's +-3 s duration rule made nearly every song "ambiguous".
- Pick the largest timing group; ambiguous if the runner-up is at least half
  its size. Within a group, prefer the upload closest to the per-line median.
Responses are cached under analysis/lrclib/.
"""
from __future__ import annotations

import hashlib
import json
import re
import statistics
import unicodedata
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

CACHE = Path("analysis/lrclib")
UA = "xite-lyrics-phase2 (personal research; github.com/kbobbili)"
VERSION_WORDS = ["remix", "live", "acoustic", "sped up", "slowed", "instrumental",
                 "karaoke", "demo", "radio edit", "extended", "unplugged", "reverb"]
SAME_TIMING_S = 0.75
MIN_COMMON_LINES = 5
MIN_DURATION_S = 60


def norm(s: str | None) -> str:
    """Lowercase letters/digits of any script; accents dropped; (...)/[...] and feat. tails removed."""
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(ch for ch in s if not unicodedata.combining(ch)).lower()
    s = re.sub(r"\(.*?\)|\[.*?\]", " ", s)          # drop (feat. ...) etc.
    s = re.sub(r"\bfeat\.?.*$|\bft\..*$", " ", s)
    return re.sub(r"[\W_]+", " ", s).strip()


VIDEO_LABEL = re.compile(r"\s*[(\[](?:official\s+)?(?:music\s+|lyrics?\s+)?(?:video|audio|visuali[sz]er|lyrics?)[)\]]",
                         re.I)


def search_title(title: str) -> str:
    """'Taste Back (Lyric Video)' -> 'Taste Back': LRCLIB has no entries under video labels."""
    return VIDEO_LABEL.sub("", title or "").strip()


def cache_key(title: str, artist: str) -> str:
    raw = f"{norm(artist)}__{norm(title)}"
    slug = re.sub(r"[^a-z0-9]+", "_", raw)[:100]
    if raw.isascii():
        return slug
    # non-Latin titles would all collapse to "_": keep them apart with a hash
    return f"{slug}_{hashlib.sha1(raw.encode()).hexdigest()[:10]}"


def labels(c: dict) -> frozenset[str]:
    text = f"{c.get('trackName', '')} {c.get('albumName', '')}".lower()
    return frozenset(w for w in VERSION_WORDS if w in text)


def search(title: str, artist: str) -> list[dict]:
    CACHE.mkdir(parents=True, exist_ok=True)
    path = CACHE / f"{cache_key(title, artist)}.json"
    record_query(title, artist, path.name)
    if path.exists():
        return json.loads(path.read_text(encoding="utf-8"))
    q_title = search_title(title)
    data = fetch({"track_name": q_title, "artist_name": primary_artist(artist)})
    if not data:   # structured search misses "A ft. B" style credits; free text finds them
        data = fetch({"q": f"{q_title} {primary_artist(artist)}"})
    path.write_text(json.dumps(data), encoding="utf-8")
    return data


def primary_artist(artist: str) -> str:
    """'Tyla ft. Zara Larsson' -> 'Tyla' (also feat./featuring/&/,/;/x/with)."""
    return re.split(r"\s+(?:ft\.?|feat\.?|featuring|x|with)\s+|\s*[&,;]\s*", artist or "", flags=re.I)[0].strip()


def fetch(params: dict) -> list:
    q = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"https://lrclib.net/api/search?{q}", headers={"User-Agent": UA})
    for attempt, wait in enumerate((2, 5, 10, None)):
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                data = json.load(r)
            break
        except urllib.error.HTTPError as e:  # LRCLIB returns transient 503s
            if e.code < 500 or wait is None:
                raise
            time.sleep(wait)
        except (urllib.error.URLError, TimeoutError):  # and TLS handshake timeouts
            if wait is None:
                raise
            time.sleep(wait)
    return data


def record_query(title: str, artist: str, file: str) -> None:
    """Remember which title/artist produced each cache file (for the Java equivalence test)."""
    qpath = Path("analysis/lrclib_queries.json")
    qs = json.loads(qpath.read_text(encoding="utf-8")) if qpath.exists() else []
    if not any(q["file"] == file for q in qs):
        qs.append({"title": title, "artist": artist, "file": file})
        qpath.write_text(json.dumps(qs, indent=1), encoding="utf-8")


@dataclass
class Line:
    t: float      # seconds on the recording's timeline
    text: str     # "" marks a deliberate gap


def parse_lrc(lrc: str) -> list[Line]:
    offset_ms = 0
    m = re.search(r"\[offset:\s*([+-]?\d+)\]", lrc, re.I)
    if m:
        offset_ms = int(m.group(1))   # positive = lyrics come earlier
    lines = []
    for raw in lrc.splitlines():
        stamps = re.findall(r"\[(\d+):(\d+(?:\.\d+)?)\]", raw)
        if not stamps:
            continue
        text = re.sub(r"\[[^\]]*\]", "", raw).strip()
        for mm, ss in stamps:
            lines.append(Line(int(mm) * 60 + float(ss) - offset_ms / 1000.0, text))
    lines.sort(key=lambda l: l.t)
    return lines


@dataclass
class Choice:
    status: str                  # "ok" | "ambiguous" | "none"
    candidate: dict | None
    lines: list[Line]
    groups: list[list[dict]]
    note: str = ""


def timing_key(c: dict) -> dict[str, float]:
    """First time of each distinct normalized line."""
    out: dict[str, float] = {}
    for l in parse_lrc(c["syncedLyrics"]):
        k = norm(l.text)
        if k and k not in out:
            out[k] = l.t
    return out


def same_timing(a: dict[str, float], b: dict[str, float]) -> bool:
    common = set(a) & set(b)
    if len(common) < MIN_COMMON_LINES:
        return False
    return statistics.median(abs(a[k] - b[k]) for k in common) <= SAME_TIMING_S


def choose(title: str, artist: str) -> Choice:
    want_t, want_a = norm(title), norm(artist)
    cands = [c for c in search(title, artist) if c.get("syncedLyrics")]
    # Title must match; artist must overlap (handles "A & B" vs "A").
    cands = [c for c in cands if norm(c.get("trackName")) == want_t and
             (want_a in norm(c.get("artistName")) or norm(c.get("artistName")) in want_a)]
    cands = [c for c in cands if (c.get("duration") or 0) >= MIN_DURATION_S and
             len(parse_lrc(c["syncedLyrics"])) >= 5]
    if not cands:
        return Choice("none", None, [], [], "no full-length synced candidate with matching title/artist")
    plain = [c for c in cands if not labels(c)]
    pool = plain or cands
    keys = [timing_key(c) for c in pool]
    groups_idx: list[list[int]] = []
    for i in range(len(pool)):
        for g in groups_idx:
            if any(same_timing(keys[i], keys[j]) for j in g):
                g.append(i)
                break
        else:
            groups_idx.append([i])
    groups = sorted(([pool[i] for i in g] for g in groups_idx), key=len, reverse=True)
    if len(groups) > 1 and len(groups[1]) * 2 >= len(groups[0]):
        return Choice("ambiguous", None, [], groups,
                      "timing groups of size " + ", ".join(str(len(g)) for g in groups))
    best = best_in_group(groups[0])
    return Choice("ok", best, parse_lrc(best["syncedLyrics"]), groups)


def best_in_group(group: list[dict]) -> dict:
    if len(group) == 1:
        return group[0]
    parsed = [{norm(l.text): l.t for l in parse_lrc(c["syncedLyrics"]) if norm(l.text)} for c in group]
    common = set.intersection(*(set(p) for p in parsed)) if parsed else set()
    if not common:
        return group[0]
    med = {k: statistics.median(p[k] for p in parsed) for k in common}
    scores = [statistics.median(abs(p[k] - med[k]) for k in common) for p in parsed]
    return group[scores.index(min(scores))]
