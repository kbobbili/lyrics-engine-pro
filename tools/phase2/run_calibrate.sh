#!/usr/bin/env bash
# Analyses a calibration phone video. Usage: tools/phase2/run_calibrate.sh <video> analysis/sessions/<id>
set -euo pipefail
cd "$(dirname "$0")/../.."
exec uv run -q --python 3.12 --with imageio-ffmpeg==0.6.0 --with numpy \
    python tools/phase2/calibrate.py "$@"
