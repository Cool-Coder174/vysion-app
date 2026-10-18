#!/usr/bin/env bash
# Export Whisper-tiny for voice commands on the headset (FR-G2, ADR-0011): the three files ExecuTorch's AsrModule
# reads (model with methods encoder + text_decoder, the log-mel preprocessor, tokenizer.json) for XNNPACK, with the
# SAME ExecuTorch version as the app's AAR (gradle/libs.versions.toml [executorch] = 1.4.0).
#
# The lockfile ships the prebuilt fp32 larryliu0820/whisper-tiny-ExecuTorch-XNNPACK (it runs on the 1.4.0 runtime);
# this script rebuilds the same thing with the runtime's own toolchain. Quantization did not work on the Galaxy XR
# (Cortex-A78C, 2026-10-18, docs/benchmarks.md "Voice commands"):
#   - prebuilt larryliu0820/whisper-tiny-INT8-INT4 (ExecuTorch 1.2.0a0): SIGSEGV in the encoder on every clip;
#   - `--qlinear 8da4w` from this script (ExecuTorch 1.4.0): ran, but decoded garbage ("!!!!.!! the!!!") for all 64
#     tokens at 285 ms per token; its encoder was 1.16 s against fp32's 1.45 s.
#
# Runs inside a Linux container: PyTorch publishes no Intel-Mac wheels.
#
# Usage:   ml/export/export_asr.sh [--out ml/out] [--qlinear 8da4w]   # no --qlinear: fp32, as shipped
# Prereq:  Docker running. Downloads: python:3.12-slim, the CPU wheels, openai/whisper-tiny at a pinned revision.
# Output:  <out>/asr.pte, <out>/asr-preprocessor.pte, <out>/asr-tokenizer.json and their lockfile fields.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$ROOT/ml/out"
QLINEAR=""
REVISION="169d4a4341b33bc18d8881c4b69c2e104e1cc0af" # openai/whisper-tiny on Hugging Face, 2026-10-18

while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT_DIR="$2"; shift 2 ;;
    --qlinear) QLINEAR="$2"; shift 2 ;;
    -h|--help) sed -n '2,19p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
mkdir -p "$OUT_DIR"
OUT_DIR="$(cd "$OUT_DIR" && pwd)"

# The toolchain is an image of its own, so a failed export does not reinstall a gigabyte of wheels.
IMAGE="vysion-asr-export:1.4.0"
docker build -q -t "$IMAGE" - <<'DOCKERFILE'
FROM python:3.12-slim
RUN pip install -q --no-cache-dir --root-user-action=ignore --extra-index-url https://download.pytorch.org/whl/cpu \
      "torch==2.13.0" "torchao==0.18.0" "executorch==1.4.0" "optimum-executorch==1.1.0"
DOCKERFILE

docker run --rm -v "$OUT_DIR":/out -e QLINEAR="$QLINEAR" -e REVISION="$REVISION" "$IMAGE" bash -euo pipefail -c '
  python -c "from importlib.metadata import version as v; print(*(f\"{p} {v(p)}\" for p in (\"executorch\", \"torch\", \"torchao\", \"optimum-executorch\", \"transformers\")))"
  cd /tmp
  python -c "from huggingface_hub import snapshot_download; snapshot_download(\"openai/whisper-tiny\", revision=\"$REVISION\", local_dir=\"whisper-tiny\")"
  quant=""
  [ -n "$QLINEAR" ] && quant="--qlinear $QLINEAR --qlinear_encoder $QLINEAR"
  optimum-cli export executorch --model ./whisper-tiny --task automatic-speech-recognition --recipe xnnpack \
      $quant --output_dir asr
  # 30 s, not the model card'"'"'s 300: the preprocessor plans memory for its longest input, and the 300 s one took
  # ~3.6 GB of native heap on its first run on the headset. Commands are at most 7.5 s; Whisper pads to 30 s.
  python -m executorch.extension.audio.mel_spectrogram --feature_size 80 --stack_output --max_audio_len 30 \
      --output_file asr/whisper_preprocessor.pte
  cp asr/model.pte /out/asr.pte
  cp asr/whisper_preprocessor.pte /out/asr-preprocessor.pte
  cp whisper-tiny/tokenizer.json /out/asr-tokenizer.json
  python - <<PY
import hashlib
for name in ("asr.pte", "asr-preprocessor.pte", "asr-tokenizer.json"):
    data = open("/out/" + name, "rb").read()
    print(f"{name}: bytes {len(data)} sha256 {hashlib.sha256(data).hexdigest()}")
PY
'
echo "wrote $OUT_DIR/asr.pte, asr-preprocessor.pte, asr-tokenizer.json"
