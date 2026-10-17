#!/usr/bin/env bash
# Privacy gate (ADR-0003, NFR Offline + Privacy). Run locally before every push and in CI.
#
#   1. No network permission in our manifest.
#   2. No network permission in the MERGED manifest (a dependency cannot smuggle one in).
#   3. No code path that persists camera frames or logs their contents.
#
# A line that legitimately needs a flagged API (e.g. copying model files with openFileOutput)
# carries the marker `privacy-allow: <reason>` on the same line, which this script honors.
#
# Exit 0 = clean, 1 = violation. Prints every hit so the PR reviewer can see it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_SRC="$ROOT/app/src"
KOTLIN_SRC="$ROOT/app/src/main/kotlin"
fail=0

NET_PERMS='android\.permission\.(INTERNET|ACCESS_NETWORK_STATE|ACCESS_WIFI_STATE|CHANGE_NETWORK_STATE)'

echo "== 1. network permissions in source manifests =="
# The one allowed form is the manifest-merger GUARD that strips INTERNET from libraries:
#   <uses-permission android:name="android.permission.INTERNET" tools:node="remove" />
if grep -rnE "$NET_PERMS" "$APP_SRC" --include=AndroidManifest.xml | grep -v 'tools:node="remove"'; then
  echo "FAIL: a network permission is declared in app/src (ADR-0003)"
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

echo "== 3. frame persistence APIs in Kotlin sources =="
PERSIST='Bitmap\.compress\(|FileOutputStream\(|MediaStore\.Images|OutputFileOptions|Environment\.getExternalStorage|openFileOutput\(|ImageWriter|MediaRecorder|CameraX.*Recorder'
if [ -d "$KOTLIN_SRC" ]; then
  if grep -rnE "$PERSIST" "$KOTLIN_SRC" | grep -v 'privacy-allow:'; then
    echo "FAIL: a storage-writing API appears without a 'privacy-allow:' marker"
    fail=1
  else
    echo "ok"
  fi
fi

echo "== 4. logging of frame contents =="
LOGFRAME='Log\.[dviwe]\([^)]*(pixels|getPixels|copyPixels|frame\.|bitmap\.)'
if [ -d "$KOTLIN_SRC" ]; then
  if grep -rnE "$LOGFRAME" "$KOTLIN_SRC" | grep -v 'privacy-allow:'; then
    echo "FAIL: frame/pixel data reaches a log statement"
    fail=1
  else
    echo "ok"
  fi
fi

echo "== 5. analytics / crash reporter / network SDKs in Gradle files =="
SDKS='firebase|crashlytics|sentry|bugsnag|okhttp|retrofit|ktor-client|analytics|mixpanel|amplitude'
if grep -rniE "$SDKS" "$ROOT/app/build.gradle.kts" "$ROOT/gradle/libs.versions.toml" 2>/dev/null; then
  echo "FAIL: a dependency with a network path is declared (ADR-0003)"
  fail=1
else
  echo "ok"
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "Privacy gate FAILED. See docs/decisions/ADR-0003-no-network-no-storage.md"
  exit 1
fi
echo
echo "Privacy gate passed."
