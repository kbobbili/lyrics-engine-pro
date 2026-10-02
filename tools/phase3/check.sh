#!/usr/bin/env bash
# Phase 3 gate: build reference fixtures (once) and compare the Java port byte for byte.
# Usage: tools/phase3/check.sh [analysis/sessions/<id>]   (session needed only to (re)make fixtures)
set -euo pipefail
cd "$(dirname "$0")/../.."
if [ -n "${1:-}" ]; then
    uv run -q --python 3.12 --with shazamio-core==1.1.2 --with numpy python tools/phase3/make_fixtures.py "$1"
fi
rm -rf tools/out/phase3 && mkdir -p tools/out/phase3
javac --release 17 -d tools/out/phase3 src/dev/kbobbili/xitelyrics/fingerprint/*.java tools/phase3/FingerprintCheck.java
java -cp tools/out/phase3 FingerprintCheck tools/phase3/fixtures
