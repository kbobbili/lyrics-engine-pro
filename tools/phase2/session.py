"""Load a continuous recording session pulled from the TV and put it on one clock.

A session is rec/<session>/chunk-NNNNN.{wav,json} (60 s each, frames numbered
continuously) plus events.jsonl. Everything here works in elapsed-realtime
milliseconds ("rt"), the clock of PlaybackState and of our event log.

Frame -> rt uses AudioRecord BOOTTIME timestamps (tsNs vs tsFrame), assuming
stream frame 0 is timestamp frame 0 (Phase 1 open question; a constant error is
absorbed by devicePipelineOffset calibration if it is the same every session).
The mapping is local, not one straight line: the capture clock jumps ~60 ms when
XITE's audio track starts, and timestamps move in 64 ms buffer steps (paired
+-32 ms artifacts). rt(frame) = median(tsNs - tsFrame/rate) over +-5 s of reads.
"""
from __future__ import annotations

import json
import wave
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

RATE = 16000
STATE_STOPPED, STATE_PAUSED, STATE_PLAYING, STATE_BUFFERING = 1, 2, 3, 6


@dataclass
class Snapshot:
    rt: float          # when we saw it
    state: int
    position: float    # ms
    update_rt: float   # ms
    speed: float

    def pos_at(self, rt: float) -> float:
        if self.state != STATE_PLAYING:
            return self.position
        return self.position + self.speed * (rt - self.update_rt)


@dataclass
class Item:
    """One XITE playlist item (song or ad) with continuous playback."""
    index: int
    start_rt: float                  # rt of the first PLAYING snapshot
    end_rt: float                    # rt of the item boundary / end of data
    snapshots: list[Snapshot] = field(default_factory=list)
    title: str | None = None
    artist: str | None = None
    panel_rt: float | None = None    # first panel sighting

    @property
    def is_song(self) -> bool:
        return self.title is not None

    def video_pos(self, rt: float) -> float | None:
        """XITE's video position (ms) at rt, or None if not playing then."""
        snap = None
        for s in self.snapshots:
            if s.update_rt <= rt:
                snap = s
            else:
                break
        if snap is None or snap.state != STATE_PLAYING:
            return None
        return snap.pos_at(rt)

    def playing_spans(self) -> list[tuple[float, float]]:
        """(start_rt, end_rt) intervals where the item was playing."""
        spans, start = [], None
        for s in self.snapshots:
            if s.state == STATE_PLAYING and start is None:
                start = s.update_rt
            elif s.state != STATE_PLAYING and start is not None:
                spans.append((start, s.update_rt))
                start = None
        if start is not None:
            spans.append((start, self.end_rt))
        return spans


class Session:
    def __init__(self, folder: Path, events_path: Path):
        self.folder = folder
        metas = sorted(folder.glob("chunk-*.json"))
        if not metas:
            raise FileNotFoundError(f"no chunks in {folder}")
        pcm, ts_frame, ts_ns = [], [], []
        expected = 0
        for m in metas:
            meta = json.loads(m.read_text())
            if meta["firstFrame"] != expected:
                raise ValueError(f"gap before {m.name}: {meta['firstFrame']} != {expected}")
            with wave.open(str(m.with_suffix(".wav"))) as w:
                pcm.append(np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16))
            expected += meta["frames"]
            for r in meta["reads"]:
                if "tsNs" in r:
                    ts_frame.append(r["tsFrame"])
                    ts_ns.append(r["tsNs"])
        self.pcm = np.concatenate(pcm)
        tf, tn = np.array(ts_frame, float), np.array(ts_ns, float)
        # rt_ms(frame) = a + b * frame
        self.b, self.a = np.polyfit(tf, tn / 1e6, 1)
        resid = tn / 1e6 - (self.a + self.b * tf)
        self.fit_resid_ms = float(np.max(np.abs(resid)))  # of the global line; diagnostic only
        self.effective_rate = 1000.0 / self.b
        self._tf = tf
        self._off = tn / 1e6 - self.b * tf                  # per-read intercept (ms)
        self.start_rt = self.frame_to_rt(0)
        self.end_rt = self.frame_to_rt(len(self.pcm))
        self.events = load_events(events_path)
        self.items = build_items(self.events, self.start_rt, self.end_rt)

    def _local_offset(self, frame: float) -> float:
        lo = np.searchsorted(self._tf, frame - 5 * RATE)
        hi = np.searchsorted(self._tf, frame + 5 * RATE)
        if hi <= lo:  # outside the timestamped range: nearest read
            k = min(max(np.searchsorted(self._tf, frame), 0), len(self._tf) - 1)
            return float(self._off[k])
        return float(np.median(self._off[lo:hi]))

    def frame_to_rt(self, frame: float) -> float:
        return self._local_offset(frame) + self.b * frame

    def rt_to_frame(self, rt: float) -> int:
        f = (rt - self.a) / self.b
        for _ in range(3):  # converge on the local offset
            f = (rt - self._local_offset(f)) / self.b
        return int(round(f))

    def audio(self, rt: float, seconds: float) -> np.ndarray:
        f0 = self.rt_to_frame(rt)
        return self.pcm[f0:f0 + int(seconds * RATE)]

    def item_at(self, rt: float) -> Item | None:
        for it in self.items:
            if it.start_rt <= rt < it.end_rt:
                return it
        return None


def load_events(path: Path) -> list[dict]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        try:
            out.append(json.loads(line))
        except ValueError:
            pass
    # The log restarts its rt clock after a reboot; keep the last boot only.
    last = 0
    for i in range(1, len(out)):
        if out[i]["rt"] < out[i - 1]["rt"] - 60_000:
            last = i
    return out[last:]


def build_items(events: list[dict], start_rt: float, end_rt: float) -> list[Item]:
    """Split playback into items. A new item starts when XITE goes through
    STOPPED/BUFFERING with position 0 and then plays, or its position resets."""
    items: list[Item] = []
    cur: Item | None = None
    boundary = True
    for e in events:
        if e["type"] == "playback":
            s = Snapshot(e["rt"], e["state"], e["position"], e["updateRt"], e["speed"])
            if s.state in (STATE_STOPPED,) or (s.state == STATE_BUFFERING and s.position == 0):
                boundary = True
            reset = cur is not None and s.state == STATE_PLAYING and cur.snapshots and \
                s.position + 1500 < cur.snapshots[-1].pos_at(s.update_rt)
            if s.state == STATE_PLAYING and (boundary or reset or cur is None):
                if cur is not None:
                    cur.end_rt = s.update_rt
                cur = Item(len(items), s.update_rt, end_rt)
                items.append(cur)
                boundary = False
            if cur is not None:
                cur.snapshots.append(s)
        elif e["type"] == "panel" and cur is not None and cur.title is None:
            if e["rt"] - cur.start_rt < 15_000:
                cur.title, cur.artist, cur.panel_rt = e.get("title"), e.get("artist"), e["rt"]
        elif e["type"] in ("session_gone", "session_destroyed") and cur is not None:
            cur.end_rt = e["rt"]
            cur = None
            boundary = True
    return [it for it in items if it.end_rt > start_rt and it.start_rt < end_rt]
