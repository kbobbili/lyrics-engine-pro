#!/usr/bin/env bash
# Phase 4 desktop tests. Needs the real org.json (android.jar only has stubs);
# downloaded once into out/lib.
set -euo pipefail
cd "$(dirname "$0")/../.."
JSON=out/lib/json-20240303.jar
if [ ! -f "$JSON" ]; then
    mkdir -p out/lib
    curl -sfL https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JSON"
fi
python tools/phase4/dump_choices.py >/dev/null
rm -rf out/phase4 && mkdir -p out/phase4
javac --release 17 -nowarn -cp "$JSON" -d out/phase4 \
    src/dev/kbobbili/xitelyrics/fingerprint/*.java \
    src/dev/kbobbili/xitelyrics/recognition/*.java \
    src/dev/kbobbili/xitelyrics/lyrics/*.java \
    tools/phase4/ClientTests.java
java -cp "out/phase4;$JSON" ClientTests
