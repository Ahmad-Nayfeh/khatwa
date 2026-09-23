#!/usr/bin/env bash
# Proves the release APK carries none of the debug-only hooks (fake step source, adb receiver).
set -euo pipefail

APK=app/build/outputs/apk/release/app-release.apk
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
AAPT=$(ls "$SDK"/build-tools/*/aapt2 | sort -V | tail -1)

echo "== manifest of $APK =="
"$AAPT" dump xmltree --file AndroidManifest.xml "$APK" > /tmp/release-manifest.txt
if grep -q "DebugReceiver\|com.khatwa.app.debug" /tmp/release-manifest.txt; then
  echo "FAIL: debug receiver present in the release manifest"; exit 1
fi
echo "OK: no debug receiver in the release manifest"

echo "== dex strings =="
rm -rf /tmp/release-dex && mkdir -p /tmp/release-dex
unzip -o -q "$APK" 'classes*.dex' -d /tmp/release-dex
if strings /tmp/release-dex/classes*.dex | grep -q "FakeStepSource\|DebugReceiver\|khatwa_debug"; then
  echo "FAIL: debug classes found in the release dex"; exit 1
fi
echo "OK: no fake step source in the release dex"

echo "== signature =="
APKSIGNER=$(ls "$SDK"/build-tools/*/apksigner | sort -V | tail -1)
"$APKSIGNER" verify --print-certs "$APK" | head -5
