"""Summarise pulled capture clips: level, silence, and timestamp quality.

Usage: python tools/clip_stats.py clips/            (after tools/pull-clips.sh)

For each clip it estimates the elapsed-realtime of the first captured sample
two ways and reports how well they agree:

  ts    from AudioRecord.getTimestamp (BOOTTIME):  tsNs - tsFrame/rate
  read  from read() return times:                  nowNs - frames/rate
        (an upper bound: includes buffering delay)

"jitter" is the spread (max - min) of each estimate across the clip's reads;
a stable time base shows a few ms. "read-ts" is the mean buffering delay.
It also prints the playback snapshot so videoPos(firstSample) can be derived.
"""
import json
import statistics
import sys
import wave
from pathlib import Path


def main(folder: str) -> None:
    for meta_path in sorted(Path(folder).glob("clip-*.json")):
        m = json.loads(meta_path.read_text())
        rate = m["rate"]
        reads = m.get("reads", [])
        ts_est = [r["tsNs"] - r["tsFrame"] * 1e9 / rate for r in reads if "tsNs" in r]
        rd_est = [r["nowNs"] - r["frames"] * 1e9 / rate for r in reads]
        ts_err = sorted({r["tsErr"] for r in reads if "tsErr" in r})

        wav = meta_path.with_suffix(".wav")
        dur = None
        if wav.exists():
            with wave.open(str(wav)) as w:
                dur = w.getnframes() / w.getframerate()

        print(f"{meta_path.stem}: {dur}s peak={m['peak']} rms={m['rmsDb']}dB silent={m['silent']}")
        if ts_est:
            t0 = statistics.median(ts_est)
            r0 = statistics.median(rd_est)
            print(f"  ts   t0={t0/1e6:.1f}ms jitter={(max(ts_est)-min(ts_est))/1e6:.2f}ms n={len(ts_est)}")
            print(f"  read t0={r0/1e6:.1f}ms jitter={(max(rd_est)-min(rd_est))/1e6:.2f}ms read-ts={(r0-t0)/1e6:.1f}ms")
            first_ts = reads[0].get("tsFrame")
            print(f"  first read: frames={reads[0]['frames']} tsFrame={first_ts}"
                  f" start->t0={(t0 - m['startedNs'])/1e6:.1f}ms")
        else:
            print(f"  no timestamps; errors={ts_err}")
        b, a = m["before"], m["after"]
        pb = b.get("playback") or {}
        print(f"  gen {b['gen']}->{a['gen']} state={pb.get('state')} pos={pb.get('position')}"
              f" updateRt={pb.get('updateRt')} panel={b.get('panelTitle')!r}/{b.get('panelArtist')!r}")
        if ts_est and pb.get("state") == 3:
            t0_ms = statistics.median(ts_est) / 1e6
            vpos = pb["position"] + pb["speed"] * (t0_ms - pb["updateRt"])
            print(f"  videoPos(firstSample)={vpos/1000:.3f}s")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "clips")
