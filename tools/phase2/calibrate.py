"""Measure devicePipelineOffset from a phone video of the TV (plan: "How audible is measured").

During tools/phase2/calibrate_run.sh the overlay flashes a white square every 2 s
(each flash's display-frame time is logged as marker_on) while XITE is paused and
resumed several times. The phone films screen and sound together.

  phone time -> rt     from the flashes (phone flash onset vs logged frame time)
  heard onset (rt)     resume onsets in the phone audio, mapped to rt
  captured onset (rt)  the same resume onsets in the captured recording
  devicePipelineOffset = median(heard - captured)

Because the mapping uses the overlay's own flashes, display latency is folded in:
the result is "overlay drawn at rt t lines up with captured audio at t - offset",
exactly what the lyrics overlay needs.

Usage (via run_calibrate.sh): calibrate.py <video> analysis/sessions/<id>
"""
from __future__ import annotations

import json
import re
import statistics
import subprocess
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from session import RATE, Session  # noqa: E402

W, H = 96, 54
HOP = 0.01  # 10 ms energy frames


def ffmpeg() -> str:
    import imageio_ffmpeg
    return imageio_ffmpeg.get_ffmpeg_exe()


def video_frames(path: Path) -> tuple[np.ndarray, np.ndarray]:
    """(pts seconds, frames[n, H, W] gray) with real per-frame timestamps (phones are often VFR)."""
    p = subprocess.run([ffmpeg(), "-v", "info", "-i", str(path), "-vf", f"scale={W}:{H},format=gray,showinfo",
                        "-fps_mode", "passthrough", "-f", "rawvideo", "-"], capture_output=True)
    pts = [float(x) for x in re.findall(rb"pts_time:([0-9.]+)", p.stderr)]
    frames = np.frombuffer(p.stdout, np.uint8).reshape(-1, H, W)
    n = min(len(pts), len(frames))
    return np.array(pts[:n]), frames[:n].astype(np.float32)


def video_audio(path: Path) -> np.ndarray:
    p = subprocess.run([ffmpeg(), "-v", "error", "-i", str(path), "-vn", "-ac", "1", "-ar", str(RATE),
                        "-f", "s16le", "-"], capture_output=True, check=True)
    return np.frombuffer(p.stdout, np.int16)


def flash_onsets(pts: np.ndarray, frames: np.ndarray, period_s: float) -> list[float]:
    """Find the pixel cell that blinks at the marker period and return its rising edges."""
    t = pts - pts[0]
    ref = np.cos(2 * np.pi * t / period_s) + 1j * np.sin(2 * np.pi * t / period_s)
    series = frames.reshape(len(frames), -1)
    series = series - series.mean(axis=0)
    power = np.abs(ref @ series) / (np.std(series, axis=0) * len(t) + 1e-6)
    cells = np.argsort(-power)[:6]                      # strongest blinking cells
    sig = frames.reshape(len(frames), -1)[:, cells].mean(axis=1)
    lo, hi = np.percentile(sig, 10), np.percentile(sig, 99)
    on = sig > lo + 0.5 * (hi - lo)
    edges = [pts[i] for i in range(1, len(on)) if on[i] and not on[i - 1]]
    return edges


def audio_onsets(pcm: np.ndarray, min_quiet_s: float = 1.0, rise_db: float = 15.0) -> list[float]:
    """Times (s) where sound starts after >= min_quiet_s of relative quiet."""
    hop = int(HOP * RATE)
    n = len(pcm) // hop
    e = np.array([np.sqrt(np.mean(pcm[i * hop:(i + 1) * hop].astype(np.float64) ** 2)) + 1e-3 for i in range(n)])
    db = 20 * np.log10(e)
    floor = np.percentile(db, 10)
    loud = db > floor + rise_db
    quiet_needed = int(min_quiet_s / HOP)
    out, quiet = [], 0
    for i in range(n):
        if not loud[i]:
            quiet += 1
        else:
            if quiet >= quiet_needed:
                out.append(i * HOP)
            quiet = 0
    return out


def main() -> None:
    video, folder = Path(sys.argv[1]), Path(sys.argv[2])
    sess = Session(folder, folder.parent.parent / "events.jsonl")
    ev = sess.events
    start = max(i for i, e in enumerate(ev) if e["type"] == "marker_start")
    period = ev[start]["periodMs"] / 1000
    marks = [e["frameRtNs"] / 1e6 for e in ev[start:] if e["type"] == "marker_on"]

    pts, frames = video_frames(video)
    flashes = flash_onsets(pts, frames, period)
    print(f"video: {len(pts)} frames, {len(flashes)} flashes seen, {len(marks)} logged")
    if len(flashes) < 5:
        raise SystemExit("too few flashes found; is the square in shot?")
    # Pair flashes with logged markers by order: the first flash seen is marker 0
    # if filming started before the run; check the spacing fits.
    n = min(len(flashes), len(marks))
    f, m = np.array(flashes[:n]), np.array(marks[:n])
    slope, icept = np.polyfit(m, f, 1)                  # phone_s = slope * rt_ms + icept
    resid_ms = (f - (slope * m + icept)) * 1000
    print(f"phone<->rt fit: slope {slope*1000:.6f} s/s, max residual {np.max(np.abs(resid_ms)):.1f} ms "
          f"(frame interval {np.median(np.diff(pts))*1000:.1f} ms)")

    def phone_to_rt(s: float) -> float:
        return (s - icept) / slope

    heard = [phone_to_rt(s) for s in audio_onsets(video_audio(video))]
    captured = [sess.frame_to_rt(s * RATE) for s in audio_onsets(sess.pcm)]
    pairs = []
    for h in heard:
        c = min(captured, key=lambda c: abs(c - h)) if captured else None
        if c is not None and abs(h - c) < 1000:
            pairs.append(h - c)
    print(f"onsets heard {len(heard)}, captured {len(captured)}, paired {len(pairs)}")
    if not pairs:
        raise SystemExit("no onset pairs; check the pause/resume cycles were filmed")
    print("heard - captured (ms):", [round(p) for p in pairs])
    med = statistics.median(pairs)
    print(f"devicePipelineOffset = {med:.0f} ms (spread {min(pairs):.0f}..{max(pairs):.0f})")
    out = Path("analysis") / f"{folder.name}-calibration.json"
    out.write_text(json.dumps({"pairs_ms": pairs, "median_ms": med, "fit_resid_ms": resid_ms.tolist(),
                               "flashes": len(flashes), "markers": len(marks)}, indent=1))


if __name__ == "__main__":
    main()
