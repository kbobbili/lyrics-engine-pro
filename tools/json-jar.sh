#!/usr/bin/env bash
# Prints the path of the desktop org.json jar (android.jar only has stubs), downloading it once.
# Kept outside out/, which build.sh wipes.
set -euo pipefail
JAR=tools/lib/json-20240303.jar
if [ ! -f "$JAR" ]; then
    mkdir -p tools/lib
    curl -sfL https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar -o "$JAR"
fi
echo "$JAR"
