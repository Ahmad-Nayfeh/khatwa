#!/usr/bin/env bash
# Writes keystore.properties + the .jks from GitHub Secrets when they are present.
# Without them the release APK is signed with the debug key (still installable).
set -euo pipefail

if [ -n "${KHATWA_KEYSTORE_BASE64:-}" ]; then
  echo "$KHATWA_KEYSTORE_BASE64" | base64 -d > khatwa-release.jks
  cat > keystore.properties <<EOF
storeFile=khatwa-release.jks
storePassword=${KHATWA_KEYSTORE_PASSWORD}
keyAlias=${KHATWA_KEY_ALIAS}
keyPassword=${KHATWA_KEY_PASSWORD}
EOF
  echo "Release keystore decoded: the release APK will be signed with it."
else
  echo "No KHATWA_KEYSTORE_BASE64 secret: the release APK will be signed with the debug key."
fi
