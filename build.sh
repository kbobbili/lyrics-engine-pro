#!/usr/bin/env bash
# Builds the XITE Lyrics APK with plain SDK tools (no Gradle).
set -euo pipefail
cd "$(dirname "$0")"

BT="$ANDROID_HOME/build-tools/36.0.0"
JAR="$ANDROID_HOME/platforms/android-37.0/android.jar"
KS="$USERPROFILE/.android/debug.keystore"

rm -rf out && mkdir -p out/classes out/dex

"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link out/res.zip -I "$JAR" --manifest AndroidManifest.xml \
    --java out/gen -o out/unsigned.apk

javac --release 17 -nowarn -cp "$JAR" -d out/classes \
    $(find src out/gen -name '*.java')
"$BT/d8.bat" --release --min-api 26 --lib "$JAR" --output out/dex \
    $(find out/classes -name '*.class')

(cd out/dex && jar --update --no-manifest --file ../unsigned.apk classes.dex)
"$BT/zipalign.exe" -f 4 out/unsigned.apk out/aligned.apk
"$BT/apksigner.bat" sign --ks "$KS" --ks-pass pass:android \
    --out out/xite-lyrics.apk out/aligned.apk
echo "built out/xite-lyrics.apk"
