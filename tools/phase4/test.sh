#!/usr/bin/env bash
# Phase 4 desktop tests (desktop org.json via tools/json-jar.sh).
set -euo pipefail
cd "$(dirname "$0")/../.."
JSON=$(bash tools/json-jar.sh)
python tools/phase4/dump_choices.py >/dev/null
rm -rf tools/out/phase4 && mkdir -p tools/out/phase4
javac --release 17 -nowarn -cp "$JSON" -d tools/out/phase4 \
    src/dev/kbobbili/xitelyrics/fingerprint/*.java \
    src/dev/kbobbili/xitelyrics/recognition/*.java \
    src/dev/kbobbili/xitelyrics/lyrics/*.java \
    tools/phase4/ClientTests.java
java -cp "tools/out/phase4;$JSON" ClientTests
