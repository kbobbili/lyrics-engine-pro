#!/usr/bin/env bash
# Lyric-boundary check with faster-whisper (pinned). Usage: tools/phase2/run_align.sh analysis/sessions/<id> [item ...]
set -euo pipefail
cd "$(dirname "$0")/../.."
exec uv run -q --python 3.12 --with faster-whisper==1.2.0 --with numpy \
    python tools/phase2/align.py "$@"
