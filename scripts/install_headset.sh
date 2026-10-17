#!/usr/bin/env bash
# Build, install and launch the debug APK on the Galaxy XR (or the S25 Ultra fallback).
#
# Usage:
#   scripts/install_headset.sh                      # USB-C: first connected device
#   scripts/install_headset.sh --wifi 192.168.1.42  # ADB over Wi-Fi (pair the headset first, see docs/setup.md)
#   scripts/install_headset.sh --serial <serial>    # pick one of several devices
#   scripts/install_headset.sh --no-build           # install the last APK without rebuilding
#   scripts/install_headset.sh --logcat             # tail the app's logs after launch
#
# R5 runs this after every merge (docs/schedule.md). It warns if airplane mode is off:
# never demo with Wi-Fi on (RK-4).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="com.vysion.xr"
ACTIVITY="$APP_ID/.MainActivity"
WIFI=""
SERIAL=""
BUILD=1
LOGCAT=0

while [ $# -gt 0 ]; do
  case "$1" in
    --wifi) WIFI="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --no-build) BUILD=0; shift ;;
    --logcat) LOGCAT=1; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

# Locate adb: PATH, then ANDROID_HOME / ANDROID_SDK_ROOT, then the default macOS SDK location.
if command -v adb >/dev/null; then
  ADB="adb"
else
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then ADB="$sdk/platform-tools/adb"; break; fi
  done
fi
[ -n "${ADB:-}" ] || { echo "adb not found; install Android platform-tools or set ANDROID_HOME" >&2; exit 1; }

if [ -n "$WIFI" ]; then
  case "$WIFI" in *:*) ;; *) WIFI="$WIFI:5555" ;; esac
  "$ADB" connect "$WIFI" || true
  SERIAL="${SERIAL:-$WIFI}"
fi

echo "== devices =="
"$ADB" devices -l
if [ -n "$SERIAL" ]; then
  export ANDROID_SERIAL="$SERIAL"
fi
count=$("$ADB" devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}')
[ "$count" -ge 1 ] || { echo "no device in 'device' state; enable developer mode + USB/Wi-Fi debugging (docs/setup.md)" >&2; exit 1; }
if [ "$count" -gt 1 ] && [ -z "$SERIAL" ]; then
  echo "more than one device; pass --serial <serial>" >&2; exit 1
fi

model=$("$ADB" shell getprop ro.product.model | tr -d '\r')
sdk=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
echo "target: $model (API $sdk)"

airplane=$("$ADB" shell settings get global airplane_mode_on | tr -d '\r')
if [ "$airplane" != "1" ]; then
  echo "WARNING: airplane mode is OFF on $model. The demo and every benchmark run with it ON (RK-4, NFR Offline)."
fi

if [ "$BUILD" -eq 1 ]; then
  echo "== build + install =="
  (cd "$ROOT" && ./gradlew --console=plain installDebug)
else
  apk=$(ls -t "$ROOT"/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)
  [ -n "$apk" ] || { echo "no APK found; run without --no-build" >&2; exit 1; }
  "$ADB" install -r "$apk"
fi

echo "== launch =="
"$ADB" shell am start -n "$ACTIVITY"

if [ "$LOGCAT" -eq 1 ]; then
  pid=""
  for _ in 1 2 3 4 5 6 7 8 9 10; do
    pid=$("$ADB" shell pidof "$APP_ID" | tr -d '\r' || true)
    [ -n "$pid" ] && break
    sleep 1
  done
  if [ -n "$pid" ]; then
    "$ADB" logcat --pid="$pid"
  else
    "$ADB" logcat -s "vysion:*" "vysion.latency:*" "vysion.reliability:*" "vysion.bench:*" "AndroidRuntime:E"
  fi
fi
