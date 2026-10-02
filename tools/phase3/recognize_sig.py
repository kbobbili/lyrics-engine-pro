"""Send binary signatures (e.g. TV-made *.tv.sig) to Shazam, as shazamio 0.8.1 does.

Request format copied from shazamio 0.8.1 (api.send_recognize_request_v2,
misc.ShazamUrl.SEARCH_FROM_FILE, Converter.data_search, Request.headers).
Usage: python tools/phase3/recognize_sig.py file.sig [...]
"""
import base64
import gzip
import json
import struct
import sys
import time
import urllib.request
import uuid

URL = ("https://amp.shazam.com/discovery/v5/en-US/US/android/-/tag/{u1}/{u2}?sync=true&webv3=true"
       "&sampling=true&connected=&shazamapiversion=v3&sharehub=true&hubv5minorversion=v5.1"
       "&hidelb=true&video=v3")


def recognize(sig: bytes) -> dict:
    samples = struct.unpack_from("<I", sig, 40)[0] - int(16000 * 0.24)  # header stores samples + 3840
    body = {
        "timezone": "Europe/Moscow",
        "signature": {"uri": "data:audio/vnd.shazam.sig;base64," + base64.b64encode(sig).decode(),
                      "samplems": int(samples / 16000 * 1000)},
        "timestamp": int(time.time() * 1000),
        "context": {},
        "geolocation": {},
    }
    req = urllib.request.Request(
        URL.format(u1=str(uuid.uuid4()).upper(), u2=str(uuid.uuid4()).upper()),
        data=json.dumps(body).encode(), method="POST",
        headers={"Content-Type": "application/json", "X-Shazam-Platform": "IPHONE",
                 "X-Shazam-AppVersion": "14.1.0", "Accept": "*/*", "Accept-Language": "en-US",
                 "Accept-Encoding": "gzip, deflate",
                 "User-Agent": "Dalvik/2.1.0 (Linux; U; Android 14; onn 4K Pro Build/UTT3.240625.001)"})
    with urllib.request.urlopen(req, timeout=20) as r:
        raw = r.read()
        if r.headers.get("Content-Encoding") == "gzip":
            raw = gzip.decompress(raw)
    return json.loads(raw)


for path in sys.argv[1:]:
    res = recognize(open(path, "rb").read())
    t = res.get("track") or {}
    m = (res.get("matches") or [{}])[0]
    print(f"{path.split('/')[-1]}: {t.get('title')} / {t.get('subtitle')} offset={m.get('offset')}")
    time.sleep(5)
