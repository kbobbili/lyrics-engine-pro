#!/usr/bin/env bash
# Phase 5 deterministic SyncEngine checks (desktop).
set -euo pipefail
cd "$(dirname "$0")/../.."
JSON=out/lib/json-20240303.jar
if [ ! -f "$JSON" ]; then
    mkdir -p out/lib
    curl -sfL https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON"
fi
rm -rf out/phase5 && mkdir -p out/phase5
javac --release 17 -nowarn -cp "$JSON" -d out/phase5 \
    src/dev/kbobbili/xitelyrics/fingerprint/*.java \
    src/dev/kbobbili/xitelyrics/recognition/*.java \
    src/dev/kbobbili/xitelyrics/lyrics/*.java \
    src/dev/kbobbili/xitelyrics/sync/*.java \
    tools/phase5/EngineTests.java
java -cp "out/phase5;$JSON" EngineTests
