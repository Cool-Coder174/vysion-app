#!/usr/bin/env bash
# Export the door-capable Navigation detector (FR-N2, FR-N3 fast path): Ultralytics YOLOv8n trained on Open Images
# V7 (601 classes, including Door, Stairs, Person, Chair, Table, Waste container) to an ExecuTorch XNNPACK .pte.
# COCO (YOLO26-n) has no door class, so "find the door" needs this model or the VLM vote.
#
# Runs inside a Linux container: PyTorch publishes no Intel-Mac wheels, and the container pins the exact
# runtime pair (executorch==1.3.1, torch==2.12.0, ml/requirements.txt) whatever the laptop is.
#
# Facts (docs.ultralytics.com/integrations/executorch, exporter.py; research 2026-10-17):
#   `yolo export format=executorch` exists since ultralytics 8.3.220; FP32 only; NO NMS in the graph;
#   output [1, 4 + 601, anchors] channel-first: cx, cy, w, h in input pixels, then sigmoid class scores
#   (2100 anchors at 320). The app decodes it as OutputLayout.CXCYWH_SCORES_COLUMNS with NMS in Kotlin, and reads
#   only the class ids the lockfile names ("classes": {"164": "door", ...}).
#
# Usage:   ml/export/export_detector_oiv7.sh [--imgsz 320] [--out ml/out]
# Prereq:  Docker running. Downloads: python:3.12-slim, the CPU wheels, and yolov8n-oiv7.pt (Ultralytics release).
# Output:  <out>/detector.pte, and the lockfile fields (bytes, sha256, classes map) printed at the end.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$ROOT/ml/out"
IMGSZ=320

while [ $# -gt 0 ]; do
  case "$1" in
    --imgsz) IMGSZ="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

docker run --rm -v "$OUT_DIR":/out -e IMGSZ="$IMGSZ" python:3.12-slim bash -euo pipefail -c '
  apt-get update -qq && apt-get install -y -qq --no-install-recommends libgl1 libglib2.0-0 >/dev/null
  pip install -q --no-cache-dir --extra-index-url https://download.pytorch.org/whl/cpu \
      "torch==2.12.0" "executorch==1.3.1" "ultralytics==8.4.14" "opencv-python-headless<5"
  cd /tmp
  yolo export model=yolov8n-oiv7.pt format=executorch imgsz="$IMGSZ"
  cp yolov8n-oiv7_executorch_model/*.pte /out/detector.pte
  python - <<PY
import hashlib, os
from ultralytics import YOLO
names = YOLO("yolov8n-oiv7.pt").names
wanted = ["Door", "Stairs", "Person", "Man", "Woman", "Wheelchair", "Chair", "Table", "Desk", "Couch", "Bench",
          "Coffee table", "Waste container", "Box", "Window", "Toilet"]
ids = {v: k for k, v in names.items()}
classes = {str(ids[w]): w.lower() for w in wanted if w in ids}
data = open("/out/detector.pte", "rb").read()
print("--- paste into ml/models.lock (detector row) ---")
print("bytes:", len(data))
print("sha256:", hashlib.sha256(data).hexdigest())
print("input:", os.environ["IMGSZ"], "x", os.environ["IMGSZ"])
print("classes:", classes)
PY
'
echo "wrote $OUT_DIR/detector.pte"
