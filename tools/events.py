"""Print events.jsonl (or XiteLyrics logcat lines on stdin) as a compact timeline.

Usage: python tools/events.py clips/events.jsonl
       adb logcat -d -s XiteLyrics:* | python tools/events.py -
Time column is elapsed-realtime seconds.
"""
import json
import sys


def main(src: str) -> None:
    lines = sys.stdin if src == "-" else open(src, encoding="utf-8")
    for line in lines:
        start = line.find("{")
        if start < 0:
            continue
        try:
            e = json.loads(line[start:])
        except ValueError:
            continue
        e.pop("wall", None)
        rt = e.pop("rt")
        kind = e.pop("type")
        fields = " ".join(f"{k}={v}" for k, v in e.items())
        print(f"{rt / 1000:10.1f}  {kind:<22} {fields}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "-")
