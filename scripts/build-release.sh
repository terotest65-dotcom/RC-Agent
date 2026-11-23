#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
APK="$ROOT/android/app/build/outputs/apk/release/app-release.apk"

if [[ -z "${RC_RELAY_URL:-}" ]]; then
  echo "Set RC_RELAY_URL, e.g. wss://rc.example.com/ws" >&2
  exit 1
fi
if [[ -z "${RC_DEVICE_TOKEN:-}" ]]; then
  echo "Set RC_DEVICE_TOKEN to the server's shared device token" >&2
  exit 1
fi
if [[ "$RC_RELAY_URL" != wss://* ]]; then
  echo "RC_RELAY_URL must start with wss://" >&2
  exit 1
fi
if [[ ! -f "$ROOT/android/keystore.properties" ]]; then
  echo "Missing android/keystore.properties." >&2
  echo "Copy android/keystore.properties.example and fill in your keystore." >&2
  exit 1
fi

cd "$ROOT/android"
./gradlew :app:assembleRelease \
  -PrcRelayUrl="$RC_RELAY_URL" \
  -PrcDeviceToken="$RC_DEVICE_TOKEN"

BUILD_TOOLS="$(find "$ANDROID_HOME/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
"$BUILD_TOOLS/apksigner" verify --verbose "$APK"
shasum -a 256 "$APK" 2>/dev/null || sha256sum "$APK"
echo
echo "Signed APK: $APK"
echo "Certificate: $("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" | head -3 | tr '\n' ' ')"
