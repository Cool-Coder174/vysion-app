#!/usr/bin/env bash
# Fetch (or copy) the model artifacts listed in ml/models.lock into app/src/main/assets/models/
# and verify their sha256 (ADR-0007; SRS R5 "scripts/fetch_models.sh ... ./gradlew installDebug
# works from a fresh clone").
#
# Usage:
#   scripts/fetch_models.sh                 # download every row whose url is not TBD
#   scripts/fetch_models.sh --from ~/exports  # copy <dest> files from a local directory (after ml/export/*.sh)
#   scripts/fetch_models.sh --only detector   # one artifact
#   scripts/fetch_models.sh --force           # re-fetch even if the file exists and verifies
#
# Exit codes: 0 all artifacts present and verified · 2 some artifacts missing (url TBD and no --from) · 1 error.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK="$ROOT/ml/models.lock"
DEST_DIR="$ROOT/app/src/main/assets/models"
FROM=""
ONLY=""
FORCE=0

while [ $# -gt 0 ]; do
  case "$1" in
    --from) FROM="$2"; shift 2 ;;
    --only) ONLY="$2"; shift 2 ;;
    --force) FORCE=1; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

command -v python3 >/dev/null || { echo "python3 is required to read $LOCK" >&2; exit 1; }
if command -v sha256sum >/dev/null; then
  sha() { sha256sum "$1" | awk '{print $1}'; }
elif command -v shasum >/dev/null; then
  sha() { shasum -a 256 "$1" | awk '{print $1}'; }
else
  echo "need sha256sum or shasum" >&2; exit 1
fi

mkdir -p "$DEST_DIR"

# name|dest|url|sha256|bytes|export  (pipe-separated; none of the fields may contain '|')
ROWS="$(python3 - "$LOCK" <<'PY'
import json, sys
with open(sys.argv[1]) as f:
    lock = json.load(f)
for m in lock["models"]:
    print("|".join(str(m.get(k, "")) for k in ("name", "dest", "url", "sha256", "bytes", "export")))
PY
)"

missing=0
fetched=0
verified=0

while IFS='|' read -r name dest url sha256 bytes export; do
  [ -n "$name" ] || continue
  if [ -n "$ONLY" ] && [ "$ONLY" != "$name" ]; then continue; fi
  target="$DEST_DIR/$dest"

  if [ -f "$target" ] && [ "$FORCE" -eq 0 ]; then
    if [ "$sha256" != "TBD" ] && [ "$(sha "$target")" = "$sha256" ]; then
      echo "✓ $name: present and verified ($dest)"
      verified=$((verified + 1))
      continue
    elif [ "$sha256" = "TBD" ]; then
      echo "• $name: present, lockfile has no sha256 yet. Computed: $(sha "$target")  ← paste into ml/models.lock"
      verified=$((verified + 1))
      continue
    else
      echo "• $name: present but checksum differs; re-fetching"
    fi
  fi

  if [ -n "$FROM" ]; then
    if [ -f "$FROM/$dest" ]; then
      cp "$FROM/$dest" "$target.part" && mv "$target.part" "$target"
      echo "→ $name: copied from $FROM/$dest"
    else
      echo "✗ $name: $FROM/$dest not found. Build it with $export (see ml/README.md)."
      missing=$((missing + 1))
      continue
    fi
  elif [ "$url" = "TBD" ] || [ -z "$url" ]; then
    echo "✗ $name: no download URL in ml/models.lock yet."
    echo "    Build it:  $export      (commands in ml/README.md)"
    echo "    Then:      scripts/fetch_models.sh --from <dir containing $dest>"
    echo "    Or:        download $dest from the GitHub release assets into $DEST_DIR/"
    missing=$((missing + 1))
    continue
  else
    echo "→ $name: downloading $url"
    curl -L --fail --retry 3 --retry-delay 2 --progress-bar -o "$target.part" "$url"
    mv "$target.part" "$target"
  fi

  actual="$(sha "$target")"
  if [ "$sha256" = "TBD" ]; then
    echo "• $name: fetched, no sha256 in lockfile. Computed: $actual  ← paste into ml/models.lock"
  elif [ "$actual" != "$sha256" ]; then
    echo "✗ $name: sha256 mismatch" >&2
    echo "    expected $sha256" >&2
    echo "    actual   $actual" >&2
    rm -f "$target"
    exit 1
  else
    echo "✓ $name: verified"
  fi
  if [ "$bytes" != "0" ] && [ -n "$bytes" ]; then
    actual_bytes=$(wc -c < "$target" | tr -d ' ')
    [ "$actual_bytes" = "$bytes" ] || echo "    note: size $actual_bytes differs from lockfile $bytes"
  fi
  fetched=$((fetched + 1))
done <<< "$ROWS"

# The app reads this copy at runtime to print model name/quantization/backend/size (FR-G5).
cp "$LOCK" "$DEST_DIR/models.lock"

echo
echo "assets: $DEST_DIR"
ls -la "$DEST_DIR" | sed 's/^/  /'
echo "fetched=$fetched verified=$verified missing=$missing"
if [ "$missing" -gt 0 ]; then
  echo "Some artifacts are missing; the app will start but inference will report 'model not found'." >&2
  exit 2
fi
