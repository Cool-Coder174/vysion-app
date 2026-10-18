#!/usr/bin/env bash
# Privacy gate (ADR-0003 as amended by ADR-0010, NFR Privacy). Run locally before every push and in CI.
#
#   1. Network permissions: only INTERNET and ACCESS_NETWORK_STATE (ADR-0010), in our manifest.
#   2. The same in the MERGED manifest (a dependency cannot smuggle in another one).
#   3. No code path that persists camera frames or logs their contents, in main AND debug Kotlin sources
#      (the debug BenchmarkReceiver captures stills and ships in the APK install_headset.sh installs).
#   4. No frame contents in logs.
#   5. No analytics or crash-reporter SDK.
#   6. Every line that opens a network connection names its on-device fallback: `network-fallback: <what runs
#      offline>` on the same line (ADR-0010: every network feature has one).
#   7. No location in logs (FR-N9, ADR-0010 row "Where am I map"): no log call names coordinates, the fix's accuracy,
#      a tile, or what "where am I" found (the street and place names locate the wearer too).
#
# A line that legitimately needs a flagged storage API (e.g. copying model files with openFileOutput)
# carries the marker `privacy-allow: <reason>` on the same line, which this script honors.
#
# Exit 0 = clean, 1 = violation. Prints every hit so the PR reviewer can see it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_SRC="$ROOT/app/src"
# Every Kotlin source set that ends up in an installed APK: main, plus debug (BenchmarkReceiver).
KOTLIN_DIRS=()
for d in "$ROOT/app/src/main/kotlin" "$ROOT/app/src/debug/kotlin"; do
  [ -d "$d" ] && KOTLIN_DIRS+=("$d")
done
fail=0

# ADR-0010 allows INTERNET and ACCESS_NETWORK_STATE; any other network permission still fails.
NET_PERMS='android\.permission\.(ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE|CHANGE_WIFI_STATE|NEARBY_WIFI_DEVICES)'

echo "== 1. network permissions in source manifests =="
if grep -rnE "$NET_PERMS" "$APP_SRC" --include=AndroidManifest.xml | grep -v 'tools:node="remove"'; then
  echo "FAIL: a network permission beyond ADR-0010's two is declared in app/src"
  fail=1
else
  echo "ok"
fi

echo "== 2. network permissions in merged manifests (present only after a build) =="
found_merged=0
while IFS= read -r -d '' m; do
  found_merged=1
  if grep -nE "$NET_PERMS" "$m"; then
    echo "FAIL: a network permission was merged in by a dependency: $m"
    fail=1
  fi
done < <(find "$ROOT/app/build/intermediates" -type f -name AndroidManifest.xml -path '*merged_manifest*' -print0 2>/dev/null)
if [ "$found_merged" -eq 0 ]; then
  echo "skipped (no merged manifest yet; run ./gradlew assembleDebug first for the full check)"
else
  echo "ok"
fi

echo "== 3. frame persistence APIs in Kotlin sources (main + debug) =="
# Java writers plus Kotlin's usual ones (kotlin.io writeBytes/writeText/outputStream/…), NIO and pixel copies.
PERSIST='Bitmap\.compress\(|FileOutputStream\(|MediaStore\.Images|OutputFileOptions|Environment\.getExternalStorage|openFileOutput\(|ImageWriter|MediaRecorder|CameraX.*Recorder'
PERSIST+='|writeBytes\(|writeText\(|appendBytes\(|appendText\(|\.outputStream\(|bufferedWriter\(|printWriter\(|FileWriter\('
PERSIST+='|Files\.write|RandomAccessFile\(|openOutputStream\(|copyPixelsToBuffer\('
if [ "${#KOTLIN_DIRS[@]}" -gt 0 ]; then
  if grep -rnE "$PERSIST" "${KOTLIN_DIRS[@]}" | grep -v 'privacy-allow:'; then
    echo "FAIL: a storage-writing API appears without a 'privacy-allow:' marker"
    fail=1
  else
    echo "ok"
  fi
fi

echo "== 4. logging of frame contents (main + debug) =="
LOGFRAME='Log\.[dviwe]\([^)]*(pixels|getPixels|copyPixels|frame\.|bitmap\.)'
if [ "${#KOTLIN_DIRS[@]}" -gt 0 ]; then
  if grep -rnE "$LOGFRAME" "${KOTLIN_DIRS[@]}" | grep -v 'privacy-allow:'; then
    echo "FAIL: frame/pixel data reaches a log statement"
    fail=1
  else
    echo "ok"
  fi
fi

echo "== 5. analytics / crash reporter SDKs in Gradle files =="
SDKS='crashlytics|firebase-analytics|sentry|bugsnag|analytics|mixpanel|amplitude|segment'
if grep -rniE "$SDKS" "$ROOT/app/build.gradle.kts" "$ROOT/gradle/libs.versions.toml" 2>/dev/null; then
  echo "FAIL: an analytics or crash-reporter dependency is declared (ADR-0003 rule 3)"
  fail=1
else
  echo "ok"
fi

echo "== 6. network calls name their on-device fallback (ADR-0010) =="
NETCALL='openConnection\(|HttpURLConnection|HttpsURLConnection|OkHttpClient|HttpClient\(|Socket\(|URL\("http'
if [ "${#KOTLIN_DIRS[@]}" -gt 0 ]; then
  if grep -rnE "$NETCALL" "${KOTLIN_DIRS[@]}" | grep -v 'network-fallback:'; then
    echo "FAIL: a network call without a 'network-fallback: <what runs offline>' marker on the same line"
    fail=1
  else
    echo "ok"
  fi
fi

echo "== 7. location in logs (FR-N9) =="
LOGLOC='(Log\.[dviwe]\(|\blog\()[^;]*(latitude|longitude|accuracy|\.location\b|surroundings|crossStreets|\.street\b|\.places\b|TileKey|tileOf|urlFor|key\.x|key\.y)'
if [ "${#KOTLIN_DIRS[@]}" -gt 0 ]; then
  if grep -rnE "$LOGLOC" "${KOTLIN_DIRS[@]}" | grep -v 'privacy-allow:'; then
    echo "FAIL: a log call carries the wearer's location (coordinates, accuracy, a tile, or the street and places found)"
    fail=1
  else
    echo "ok"
  fi
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "Privacy gate FAILED. See docs/decisions/ADR-0003-no-network-no-storage.md and ADR-0010-network-with-fallback.md"
  exit 1
fi
echo
echo "Privacy gate passed."
