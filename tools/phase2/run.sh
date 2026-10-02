#!/usr/bin/env bash
# Runs a Phase 2 experiment with pinned recognizer versions (see run.py for commands).
# Usage: tools/phase2/run.sh <command> analysis/sessions/<id> [args]
set -euo pipefail
cd "$(dirname "$0")/../.."
exec uv run -q --python 3.12 \
    --with shazamio==0.8.1 --with shazamio-core==1.1.2 --with numpy \
    python tools/phase2/run.py "$@"
