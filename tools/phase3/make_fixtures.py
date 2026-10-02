"""Phase 3 reference fixtures from the pinned shazamio-core 1.1.2 binding.

Writes tools/phase3/fixtures/<name>.wav (16 kHz mono s16) and <name>.sig (the
reference binary signature, base64-decoded from signature.uri). Inputs are
<= 10 s so the reference's centred 10 s cut never applies.

Music clips come from a Phase 2 recording session (analysis/sessions/<id>).
Usage (via run.sh): make_fixtures.py analysis/sessions/<id>
"""
import asyncio
import base64
import glob
import io
import sys
import wave
from pathlib import Path

import numpy as np
from shazamio_core import Recognizer

RATE = 16000
OUT = Path("tools/phase3/fixtures")


def wav_bytes(pcm: np.ndarray) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(pcm.astype(np.int16).tobytes())
    return buf.getvalue()


def load_session(folder: str) -> np.ndarray:
    parts = []
    for f in sorted(glob.glob(f"{folder}/chunk-*.wav")):
        with wave.open(f) as w:
            parts.append(np.frombuffer(w.readframes(w.getnframes()), np.int16))
    return np.concatenate(parts)


async def reference(rec: Recognizer, data: bytes) -> bytes:
    sig = await rec.recognize_bytes(data)
    uri = sig.signature.uri
    return base64.b64decode(uri.split(",", 1)[1])


async def main() -> None:
    pcm = load_session(sys.argv[1])
    rng = np.random.default_rng(7)
    fixtures = {}
    # Music: 8 s clips spread over the session (songs and ads alike).
    for i, start_s in enumerate(np.linspace(60, len(pcm) / RATE - 120, 8)):
        s = int(start_s * RATE)
        fixtures[f"music{i}_8s"] = pcm[s:s + 8 * RATE]
    s = int(900 * RATE)
    fixtures["music_10s"] = pcm[s:s + 10 * RATE]
    fixtures["music_8s_plus77"] = pcm[s:s + 8 * RATE + 77]          # not a multiple of 128
    fixtures["music_1s"] = pcm[s:s + RATE]
    fixtures["music_0_5s"] = pcm[s:s + RATE // 2]
    fixtures["short_200"] = pcm[s:s + 200]
    fixtures["silence_8s"] = np.zeros(8 * RATE, np.int16)
    fixtures["noise_8s"] = (rng.standard_normal(8 * RATE) * 3000).clip(-32768, 32767).astype(np.int16)
    fixtures["loud_8s"] = (pcm[s:s + 8 * RATE].astype(np.int32) * 4).clip(-32768, 32767).astype(np.int16)

    OUT.mkdir(parents=True, exist_ok=True)
    rec = Recognizer(segment_duration_seconds=10)
    for name, samples in fixtures.items():
        data = wav_bytes(samples)
        (OUT / f"{name}.wav").write_bytes(data)
        sig = await reference(rec, data)
        (OUT / f"{name}.sig").write_bytes(sig)
        print(f"{name}: {len(samples)} samples -> {len(sig)} byte signature")


asyncio.run(main())
