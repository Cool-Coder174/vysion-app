#!/usr/bin/env bash
# Export YOLO26-n (COCO) to an ExecuTorch XNNPACK int8 .pte for Vysion XR Navigation (FR-N2; SRS §8 R2
# "Export YOLO26-n (COCO) to .pte with XNNPACK, int8; run on headset; record ms/frame. Sat 13:00").
#
# Source of every flag: docs/stack.md §3-R2 "YOLO26 facts" (VERIFIED at tag v1.3.1):
#   examples/models/yolo26/export_and_validate.py --model_name yolo26n --backend xnnpack --quantize
#       --video_path <calibration.mp4> --input_dims=[640,640]
#   --quantize = XNNPACK static int8 (symmetric, per-tensor) and REQUIRES --video_path (300 calibration
#   frames by default); the output file name is fixed: <model>_{int8|fp32}_{backend}.pte in the CWD.
#   Input float32 NCHW [1,3,640,640] RGB letterboxed /255; output [1, N, 6] rows [x1,y1,x2,y2,conf,cls],
#   post-NMS, letterboxed-input pixels; COCO80 ids.
#
# Usage:
#   ml/export/export_detector.sh --executorch-src <path to executorch checkout at tag v1.3.1> \
#                                --video <calibration.mp4> [--model yolo26n] [--no-quantize] [--out ml/out]
#
# Prereqs (ml/README.md §2): ml/.venv active (executorch==1.3.1, ultralytics==8.4.14, opencv-python).
# Output: <out>/detector.pte (+ the original fixed-name file next to it) and a sha256/bytes block to paste
# into ml/models.lock.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$ROOT/ml/out"
ET_SRC=""
VIDEO=""
MODEL="yolo26n"
QUANTIZE=1
INPUT_DIMS="[640,640]"

usage() { sed -n '2,22p' "$0"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --executorch-src) ET_SRC="$2"; shift 2 ;;
    --video) VIDEO="$2"; shift 2 ;;
    --model) MODEL="$2"; shift 2 ;;
    --no-quantize) QUANTIZE=0; shift ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 1 ;;
  esac
done

[ -n "$ET_SRC" ] || { echo "--executorch-src <dir> is required (git clone -b v1.3.1 https://github.com/pytorch/executorch)" >&2; exit 1; }
EXAMPLE="$ET_SRC/examples/models/yolo26/export_and_validate.py"
[ -f "$EXAMPLE" ] || { echo "not found: $EXAMPLE (is the checkout at tag v1.3.1?)" >&2; exit 1; }
if [ "$QUANTIZE" -eq 1 ]; then
  [ -n "$VIDEO" ] || { echo "--video <calibration.mp4> is required with int8 quantization (the example calibrates on 300 frames)" >&2; exit 1; }
  [ -f "$VIDEO" ] || { echo "not found: $VIDEO" >&2; exit 1; }
  VIDEO="$(cd "$(dirname "$VIDEO")" && pwd)/$(basename "$VIDEO")"
fi
case "$MODEL" in yolo26n|yolo26s|yolo26m|yolo26l|yolo26x) ;; *) echo "--model must be one of yolo26n|yolo26s|yolo26m|yolo26l|yolo26x" >&2; exit 1 ;; esac
command -v python >/dev/null || { echo "python not on PATH; activate ml/.venv first" >&2; exit 1; }
python - <<'PY' || { echo "executorch 1.3.1 is not installed in this interpreter (uv pip install -r ml/requirements.txt)" >&2; exit 1; }
import importlib.metadata as m, sys
v = m.version("executorch")
sys.exit(0 if v == "1.3.1" else (print(f"executorch=={v}, expected 1.3.1 (docs/stack.md D1)") or 1))
PY
if command -v sha256sum >/dev/null; then sha() { sha256sum "$1" | awk '{print $1}'; }; else sha() { shasum -a 256 "$1" | awk '{print $1}'; }; fi

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vysion-detector.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

echo "== export $MODEL → XNNPACK $( [ "$QUANTIZE" -eq 1 ] && echo int8 || echo fp32 ), input $INPUT_DIMS =="
args=(--model_name "$MODEL" --backend xnnpack "--input_dims=$INPUT_DIMS")
if [ "$QUANTIZE" -eq 1 ]; then args+=(--quantize --video_path "$VIDEO"); fi
# The example writes its fixed-name output into the CWD; run it from a scratch dir so the repo stays clean.
( cd "$WORK" && python "$EXAMPLE" "${args[@]}" )

PRODUCED="$WORK/${MODEL}_$( [ "$QUANTIZE" -eq 1 ] && echo int8 || echo fp32 )_xnnpack.pte"
[ -f "$PRODUCED" ] || { echo "export finished but $PRODUCED was not produced; files in $WORK:" >&2; ls -la "$WORK" >&2; exit 1; }
cp "$PRODUCED" "$OUT_DIR/$(basename "$PRODUCED")"
cp "$PRODUCED" "$OUT_DIR/detector.pte"

BYTES=$(wc -c < "$OUT_DIR/detector.pte" | tr -d ' ')
SHA=$(sha "$OUT_DIR/detector.pte")
COMMIT=$(git -C "$ET_SRC" rev-parse --short HEAD 2>/dev/null || echo "v1.3.1")
W="${INPUT_DIMS#[}"; W="${W%%,*}"; H="${INPUT_DIMS#*,}"; H="${H%]}"
cat <<EOF

detector.pte written to $OUT_DIR ($BYTES bytes)
Paste into ml/models.lock (row "detector"), then: scripts/fetch_models.sh --from $OUT_DIR --only detector
      "dest": "detector.pte",
      "sha256": "$SHA",
      "bytes": $BYTES,
      "backend": "XNNPACK",
      "quantization": "$( [ "$QUANTIZE" -eq 1 ] && echo int8 || echo fp32 )",
      "input_width": $W,
      "input_height": $H,
      "export_commit": "executorch $COMMIT"
EOF
echo
echo "Sideload for development (plan.md Q1):"
echo "  adb push $OUT_DIR/detector.pte /sdcard/Android/data/com.vysion.xr/files/models/"
