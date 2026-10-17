#!/usr/bin/env bash
# Export the Vysion XR VLM (Text Reader, Audio Description, door one-shot, arrival: FR-T1, FR-D1, FR-N3,
# FR-N6) to an ExecuTorch XNNPACK .pte with 4-bit weights plus its tokenizer (SRS §8 R2 "Export Gemma 4 edge
# multimodal with 4-bit weights ... load with the Android multimodal runner; first image-to-text on the
# headset. Sat 16:00").
#
# TWO PATHS (docs/stack.md D5, plan.md Q6, RK-13):
#   --path c  (DEFAULT) Gemma 3 4B (google/gemma-3-4b-it, GATED on Hugging Face) through optimum-executorch
#             1.1.0 → model.pte + tokenizer.json that the Android LlmModule(MODEL_TYPE_MULTIMODAL) can load.
#             Flags are optimum-executorch's own `test_gemma3_image_vision_with_custom_sdpa_kv_cache_8da4w_8we`
#             config (docs/stack.md §3-R2 option C, VERIFIED as the config; the on-headset load is A-13/Q9).
#   --path a  The SRS path: examples/models/gemma4/export_gemma4.py (google/gemma-4-E2B-it, ungated). The
#             export WORKS and is VERIFIED at v1.3.1, but the resulting .pte CANNOT be loaded by the Android
#             LlmModule (no `token_embedding` method → Error::InvalidArgument 18). Only useful with the
#             Kotlin Module-level runner port (option A in ml/README.md §3). Marked accordingly.
#
# Usage:
#   ml/export/export_vlm.sh [--path c|a] [--model <hf id>] [--executorch-src <dir>] [--out ml/out] [--max-seq-len 1024]
#
# Prereqs (ml/README.md §3): ml/.venv-vlm active (executorch==1.3.1, optimum-executorch==1.1.0 for path c;
# transformers>=5.2.0 for path a), `hf auth login` done and the model license accepted for path c.
# Output: <out>/vlm.pte and <out>/vlm-tokenizer.json (the lockfile dests) plus a sha256/bytes block.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$ROOT/ml/out"
PATH_CHOICE="c"
MODEL=""
ET_SRC=""
MAX_SEQ_LEN=1024

usage() { sed -n '2,24p' "$0"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --path) PATH_CHOICE="$2"; shift 2 ;;
    --model) MODEL="$2"; shift 2 ;;
    --executorch-src) ET_SRC="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --max-seq-len) MAX_SEQ_LEN="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage >&2; exit 1 ;;
  esac
done

case "$PATH_CHOICE" in c|a) ;; *) echo "--path must be c (optimum-executorch, default) or a (gemma4 example)" >&2; exit 1 ;; esac
command -v python >/dev/null || { echo "python not on PATH; activate ml/.venv-vlm first" >&2; exit 1; }
python - <<'PY' || { echo "executorch 1.3.1 is not installed in this interpreter (see ml/requirements.txt, VLM section)" >&2; exit 1; }
import importlib.metadata as m, sys
v = m.version("executorch")
sys.exit(0 if v == "1.3.1" else (print(f"executorch=={v}, expected 1.3.1 (docs/stack.md D1)") or 1))
PY
if command -v sha256sum >/dev/null; then sha() { sha256sum "$1" | awk '{print $1}'; }; else sha() { shasum -a 256 "$1" | awk '{print $1}'; }; fi

mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/vysion-vlm.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

if [ "$PATH_CHOICE" = "c" ]; then
  MODEL="${MODEL:-google/gemma-3-4b-it}"
  command -v optimum-cli >/dev/null || { echo "optimum-cli not found: uv pip install 'optimum-executorch==1.1.0'" >&2; exit 1; }
  if [ -z "${HF_TOKEN:-}" ] && ! python -c "from huggingface_hub import get_token; import sys; sys.exit(0 if get_token() else 1)" 2>/dev/null; then
    echo "no Hugging Face token: run 'hf auth login' (or export HF_TOKEN) and accept the license at https://huggingface.co/$MODEL" >&2
    exit 1
  fi
  echo "== option C: $MODEL → LlmModule-compatible .pte via optimum-executorch (4-bit linear weights, 8-bit embeddings, XNNPACK) =="
  # docs/stack.md §3-R2 option C (VERIFIED as optimum-executorch's own test config). --max_seq_len: stack.md: UNVERIFIED
  # that the CLI exposes it under this name in 1.1.0; if rejected, drop it and keep the default context.
  optimum-cli export executorch \
    --model "$MODEL" \
    --task multimodal-text-to-text \
    --recipe xnnpack \
    --qlinear 8da4w --qlinear_group_size 32 \
    --qlinear_encoder 8da4w,8da8w --qlinear_encoder_group_size 32 \
    --qembedding 8w --qembedding_encoder 8w \
    --use_custom_sdpa --use_custom_kv_cache \
    --output_dir "$WORK"
  PRODUCED_PTE="$WORK/model.pte"
  PRODUCED_TOK="$WORK/tokenizer.json"
  if [ ! -f "$PRODUCED_TOK" ]; then
    # optimum-executorch may leave the tokenizer in the HF cache instead of output_dir; fetch it explicitly.
    python - "$MODEL" "$WORK" <<'PY'
import sys
from huggingface_hub import hf_hub_download
import shutil
p = hf_hub_download(sys.argv[1], "tokenizer.json")
shutil.copy(p, f"{sys.argv[2]}/tokenizer.json")
PY
  fi
  QUANT="8da4w linear (4-bit weights, 8-bit dynamic activations), 8w embeddings"
  SOURCE="$MODEL via optimum-executorch 1.1.0 (option C, docs/stack.md D5)"
else
  MODEL="${MODEL:-google/gemma-4-E2B-it}"
  [ -n "$ET_SRC" ] || { echo "--executorch-src <dir> is required for path a (git clone -b v1.3.1 https://github.com/pytorch/executorch)" >&2; exit 1; }
  EXAMPLE="$ET_SRC/examples/models/gemma4/export_gemma4.py"
  [ -f "$EXAMPLE" ] || { echo "not found: $EXAMPLE (is the checkout at tag v1.3.1?)" >&2; exit 1; }
  echo "== option A: $MODEL → examples/models/gemma4 export (NOT loadable by the Android LlmModule, docs/stack.md D5) =="
  echo "   This .pte needs the Kotlin Module-level runner (ml/README.md §3 option A). Continue only if that path was chosen."
  CKPT="$WORK/checkpoint"
  python - "$MODEL" "$CKPT" <<'PY'
import sys
from huggingface_hub import snapshot_download
snapshot_download(sys.argv[1], local_dir=sys.argv[2])
PY
  # docs/stack.md §3-R2 (VERIFIED flags at v1.3.1): 8da4w+emb8 default, vision 8da8w, no audio, 1260 patches = 140 soft tokens.
  python "$EXAMPLE" \
    --checkpoint_path "$CKPT" --variant e2b --no-audio \
    --quantize 8da4w+emb8 --vision_quantize 8da8w \
    --max_seq_len "$MAX_SEQ_LEN" --max_patches 1260 \
    --output_path "$WORK/gemma4_e2b_vision.pte"
  PRODUCED_PTE="$WORK/gemma4_e2b_vision.pte"
  PRODUCED_TOK="$CKPT/tokenizer.json"
  QUANT="8da4w+emb8 (4-bit weights), vision 8da8w"
  SOURCE="$MODEL via examples/models/gemma4 at v1.3.1 (option A; Module-level runner only)"
fi

[ -f "$PRODUCED_PTE" ] || { echo "export finished but no .pte at $PRODUCED_PTE; files:" >&2; ls -la "$WORK" >&2; exit 1; }
[ -f "$PRODUCED_TOK" ] || { echo "no tokenizer.json produced/downloaded ($PRODUCED_TOK)" >&2; exit 1; }
cp "$PRODUCED_PTE" "$OUT_DIR/vlm.pte"
cp "$PRODUCED_TOK" "$OUT_DIR/vlm-tokenizer.json"

PTE_BYTES=$(wc -c < "$OUT_DIR/vlm.pte" | tr -d ' ')
TOK_BYTES=$(wc -c < "$OUT_DIR/vlm-tokenizer.json" | tr -d ' ')
PTE_SHA=$(sha "$OUT_DIR/vlm.pte")
TOK_SHA=$(sha "$OUT_DIR/vlm-tokenizer.json")
cat <<EOF

vlm.pte ($PTE_BYTES bytes) and vlm-tokenizer.json ($TOK_BYTES bytes) written to $OUT_DIR
Paste into ml/models.lock, then: scripts/fetch_models.sh --from $OUT_DIR
  row "vlm":
      "dest": "vlm.pte",
      "sha256": "$PTE_SHA",
      "bytes": $PTE_BYTES,
      "backend": "XNNPACK",
      "quantization": "$QUANT",
      "source_model": "$SOURCE"
  row "vlm-tokenizer":
      "dest": "vlm-tokenizer.json",
      "sha256": "$TOK_SHA",
      "bytes": $TOK_BYTES

Sideload for development (multi-GB: never bundle in the dev APK, plan.md Q1):
  adb push $OUT_DIR/vlm.pte $OUT_DIR/vlm-tokenizer.json /sdcard/Android/data/com.vysion.xr/files/models/
EOF
