#!/usr/bin/env bash
# Replays a recorded TV session through the real SyncEngine. Usage: tools/phase5/replay.sh analysis/sessions/<id>
set -euo pipefail
cd "$(dirname "$0")/../.."
JSON=out/lib/json-20240303.jar
rm -rf out/replay && mkdir -p out/replay
javac --release 17 -nowarn -cp "$JSON" -d out/replay \
    src/dev/kbobbili/xitelyrics/fingerprint/*.java \
    src/dev/kbobbili/xitelyrics/recognition/*.java \
    src/dev/kbobbili/xitelyrics/lyrics/*.java \
    src/dev/kbobbili/xitelyrics/sync/*.java \
    tools/phase5/Replay.java
java -cp "out/replay;$JSON" Replay "${1%/}"
