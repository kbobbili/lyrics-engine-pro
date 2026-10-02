#!/usr/bin/env bash
# Phase 5 deterministic SyncEngine checks (desktop).
set -euo pipefail
cd "$(dirname "$0")/../.."
JSON=$(bash tools/json-jar.sh)
rm -rf tools/out/phase5 && mkdir -p tools/out/phase5
javac --release 17 -nowarn -cp "$JSON" -d tools/out/phase5 \
    src/dev/kbobbili/xitelyrics/fingerprint/*.java \
    src/dev/kbobbili/xitelyrics/recognition/*.java \
    src/dev/kbobbili/xitelyrics/lyrics/*.java \
    src/dev/kbobbili/xitelyrics/sync/*.java \
    tools/phase5/EngineTests.java
java -cp "tools/out/phase5;$JSON" EngineTests
