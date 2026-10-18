#!/usr/bin/env bash
# Sideload model artifacts to the device during development (plan.md Q1; AGENTS.md §10).
#
# The dev APK must NOT bundle the multi-GB models (option C VLM estimated 2.5-3.5 GB, ml/README.md §3; not
# measured): a Wi-Fi reinstall would take minutes and break the
# 15-minute merge cadence. So the APK stays small and the .pte files are pushed ONCE to the app's
# external files dir, which ExecuTorch can mmap directly (docs/stack.md §3 → R2 "Paths"; A-15 / Q20):
#
#   /sdcard/Android/data/com.vysion.xr/files/models/<dest>        (= getExternalFilesDir(null)/models)
#
# The engine (R2, ModelCatalog) looks there first, then in the assets copy made by fetch_models.sh.
# ml/README.md (R2) records the same path next to the export recipes. The release tag bundles assets instead.
#
# Usage:
#   scripts/push_models.sh                    # every lockfile artifact present in app/src/main/assets/models
#   scripts/push_models.sh --from ~/exports   # take the files from another directory (after ml/export/*.sh)
#   scripts/push_models.sh --only detector    # one artifact (lockfile name)
#   scripts/push_models.sh --wifi <ip[:port]> # ADB over Wi-Fi (pair first, docs/setup.md)
#   scripts/push_models.sh --serial <serial>  # pick one of several devices
#   scripts/push_models.sh --sha256           # also verify sha256 on the device (slow for multi-GB files)
#
# Every pushed file is size-verified on the device (adb push can truncate on a flaky Wi-Fi link).
# Exit codes: 0 everything present and verified · 2 nothing to push (no artifact found locally) · 1 error.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/ml/models.lock"
APP_ID="com.vysion.xr"
REMOTE_DIR="/sdcard/Android/data/$APP_ID/files/models"
FROM="$ROOT/app/src/main/assets/models"
ONLY=""
WIFI=""
SERIAL=""
VERIFY_SHA=0

while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --wifi) WIFI="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --sha256) VERIFY_SHA=1; shift ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

command -v python3 >/dev/null || { echo "python3 is required to read $LOCK" >&2; exit 1; }
[ -f "$LOCK" ] || { echo "lockfile not found: $LOCK" >&2; exit 1; }
[ -d "$FROM" ] || { echo "source directory not found: $FROM" >&2; exit 1; }

# Locate adb exactly like scripts/install_headset.sh: PATH, then ANDROID_HOME / ANDROID_SDK_ROOT, then the default SDK dirs.
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
if [ -n "$SERIAL" ]; then
  export ANDROID_SERIAL="$SERIAL"
fi
count=$("$ADB" devices | awk 'NR>1 && $2=="device"{c++} END{print c+0}')
[ "$count" -ge 1 ] || { echo "no device in 'device' state; enable developer mode + USB/Wi-Fi debugging (docs/setup.md)" >&2; exit 1; }
if [ "$count" -gt 1 ] && [ -z "$SERIAL" ]; then
  echo "more than one device; pass --serial <serial>" >&2; exit 1
fi

model=$("$ADB" shell getprop ro.product.model | tr -d '\r')
echo "target: $model → $REMOTE_DIR"

# The app owns /sdcard/Android/data/<pkg>/; pushing before it is installed leaves files the app may not be able to read.
if ! "$ADB" shell pm path "$APP_ID" >/dev/null 2>&1; then
  echo "WARNING: $APP_ID is not installed on $model; install first (scripts/install_headset.sh), then push." >&2
fi
"$ADB" shell mkdir -p "$REMOTE_DIR"

if command -v sha256sum >/dev/null; then
  sha() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null; then
  sha() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  sha() { echo "unavailable"; }
fi

local_size() { wc -c < "$1" | tr -d ' '; }
remote_size() { "$ADB" shell stat -c %s "$1" 2>/dev/null | tr -d '\r' || echo ""; }

# name|dest  (pipe-separated; same parser as fetch_models.sh)
ROWS="$(python3 - "$LOCK" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    lock = json.load(f)
for m in lock["models"]:
    print("|".join(str(m.get(k, "")) for k in ("name", "dest")))
PY
)"

pushed=0
skipped=0
missing=0

push_one() {  # <local file> <remote path>
  local src="$1" dst="$2"
  local want have
  want=$(local_size "$src")
  have=$(remote_size "$dst")
  if [ -n "$have" ] && [ "$have" = "$want" ]; then
    echo "= $(basename "$src"): already on device with the same size ($want bytes)"
    skipped=$((skipped + 1))
    return 0
  fi
  echo "→ $(basename "$src"): pushing $want bytes"
  "$ADB" push "$src" "$dst"
  have=$(remote_size "$dst")
  if [ "$have" != "$want" ]; then
    echo "✗ $(basename "$src"): size on device ($have) differs from local ($want); push again" >&2
    exit 1
  fi
  if [ "$VERIFY_SHA" -eq 1 ]; then
    local local_sha remote_sha
    local_sha=$(sha "$src")
    remote_sha=$("$ADB" shell sha256sum "$dst" 2>/dev/null | awk '{print $1}' | tr -d '\r' || echo "")
    if [ -n "$remote_sha" ] && [ "$local_sha" != "$remote_sha" ]; then
      echo "✗ $(basename "$src"): sha256 differs (local $local_sha, device $remote_sha)" >&2
      exit 1
    fi
    echo "  sha256 ok: $local_sha"
  fi
  pushed=$((pushed + 1))
}

# The rows come in on fd 3: adb reads stdin, and on fd 0 the first push would swallow the remaining rows (only the
# first artifact was ever pushed).
while IFS='|' read -r name dest <&3; do
  [ -n "$name" ] || continue
  if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then continue; fi
  src="$FROM/$dest"
  if [ ! -f "$src" ]; then
    echo "• $name: $src not present locally (build it with ml/export/*.sh, or scripts/fetch_models.sh)"
    missing=$((missing + 1))
    continue
  fi
  push_one "$src" "$REMOTE_DIR/$dest" </dev/null
done 3<<< "$ROWS"

# The lockfile copy lets the engine print name / quantization / backend / size for sideloaded files too (FR-G5, ADR-0007).
if [ "$((pushed + skipped))" -gt 0 ]; then
  push_one "$LOCK" "$REMOTE_DIR/models.lock"
fi

echo
echo "on device: $REMOTE_DIR"
"$ADB" shell ls -l "$REMOTE_DIR" | sed 's/^/  /'
echo "pushed=$pushed unchanged=$skipped missing=$missing"
if [ "$((pushed + skipped))" -eq 0 ]; then
  echo "nothing was pushed: no lockfile artifact exists under $FROM" >&2
  exit 2
fi
if [ "$missing" -gt 0 ]; then
  echo "Some artifacts are missing; the app will report 'model not found' for them (docs/setup.md troubleshooting)." >&2
fi
