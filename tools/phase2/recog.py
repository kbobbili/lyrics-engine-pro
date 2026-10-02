"""Desktop recognition via pinned shazamio (SongRec-derived Rust core).

Pinned: shazamio==0.8.1, shazamio-core==1.1.2 (see run.sh). Input is always the
captured 16 kHz mono 16-bit PCM, wrapped as WAV, so no resampling happens.

Every request and raw response is cached under analysis/recog/<key>.json, so
re-running analysis never re-queries Shazam. Calls are throttled, and HTTP
failures (incl. 429) are recorded rather than retried silently.
"""
from __future__ import annotations

import asyncio
import hashlib
import io
import json
import time
import wave
from pathlib import Path

import numpy as np

RATE = 16000
CACHE = Path("analysis/recog")
LOG = Path("analysis/recog_calls.jsonl")


def wav_bytes(pcm: np.ndarray) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.astype(np.int16).tobytes())
    return buf.getvalue()


class Recognizer:
    def __init__(self, min_interval_s: float = 50.0):
        from shazamio import Shazam
        self.shazam = Shazam()
        self.min_interval_s = min_interval_s
        self.last_call = 0.0
        CACHE.mkdir(parents=True, exist_ok=True)

    def recognize(self, pcm: np.ndarray, label: str, meta: dict) -> dict:
        """Returns {'meta', 'response' | 'error', 'latency_s'}; cached by audio hash."""
        key = hashlib.sha1(pcm.tobytes()).hexdigest()[:16]
        path = CACHE / f"{key}.json"
        if path.exists():
            return {**json.loads(path.read_text()), "cached": True}
        wait = self.min_interval_s - (time.time() - self.last_call)
        if wait > 0:
            time.sleep(wait)
        self.last_call = time.time()
        t0 = time.time()
        out: dict = {"label": label, "meta": meta, "called_at": t0}
        try:
            out["response"] = asyncio.run(self.shazam.recognize(wav_bytes(pcm)))
        except Exception as e:  # shazamio surfaces HTTP errors (429 etc.) as exceptions
            out["error"] = f"{type(e).__name__}: {e}"
        out["latency_s"] = round(time.time() - t0, 3)
        if "error" not in out:   # never cache failures (429s, timeouts)
            path.write_text(json.dumps(out))
        with LOG.open("a") as f:
            f.write(json.dumps({"t": t0, "label": label, "latency_s": out["latency_s"],
                                "error": out.get("error"),
                                "matched": bool(match(out))}) + "\n")
        return out


def match(result: dict) -> dict | None:
    """First match summary: title, artist, offset (s), skews, track key."""
    r = result.get("response") or {}
    ms = r.get("matches") or []
    if not ms:
        return None
    t = r.get("track") or {}
    m = ms[0]
    return {
        "title": t.get("title"), "artist": t.get("subtitle"), "key": t.get("key"),
        "offset": m.get("offset"), "timeskew": m.get("timeskew"),
        "freqskew": m.get("frequencyskew"), "n_matches": len(ms),
        "all_offsets": [x.get("offset") for x in ms],
    }
