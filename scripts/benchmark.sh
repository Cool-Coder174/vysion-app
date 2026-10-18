#!/usr/bin/env bash
# Measurement protocol (SRS §7): 20 runs per model on the headset in airplane mode; median and p90
# latency, peak memory, file size. Results go to bench/out/*.json and are pasted, as a Markdown row,
# into docs/benchmarks.md and the README. Numbers come from the device, never from a laptop.
#
# Usage:
#   scripts/benchmark.sh --model vlm        [--runs 20]   # READ prompt, 60 max tokens, on a live captureStill
#                                         (else a synthetic dark-gray frame: real latency, unrepresentative content;
#                                         READ on gray is not pasted into docs/benchmarks.md). No bundled frame yet.
#   scripts/benchmark.sh --model detector   [--runs 20]   # one 640x480 frame per run
#   scripts/benchmark.sh --soak 600                        # 10 min Navigation loop; fps + PSS every 5 s (NFR Thermal)
#   scripts/benchmark.sh --serial <serial> ...
#
# App-side contract (R5 implements `diagnostics/BenchmarkReceiver`, debug builds only):
#   broadcast  : am broadcast -a com.vysion.xr.BENCH --es model <vlm|detector|soak> --ei runs <n> --ei seconds <s>
#   result file: files/bench/<model>-latest.json written by the app, schema:
#     { "model": "vlm", "runs": 20, "latencies_ms": [..], "median_ms": .., "p90_ms": .., "load_ms": ..,
#       "file_bytes": .., "backend": "XNNPACK", "quantization": "..", "max_new_tokens": 60,
#       "device": "SM-...", "airplane_mode": true, "started_at": "...", "finished_at": "..." }
#   The script measures peak PSS from the outside (dumpsys meminfo) so the app cannot under-report it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ID="com.vysion.xr"
OUT_DIR="$ROOT/bench/out"
MODEL=""
RUNS=20
SOAK=0
SERIAL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --model) MODEL="$2"; shift 2 ;;
    --runs) RUNS="$2"; shift 2 ;;
    --soak) SOAK="$2"; MODEL="soak"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    -h|--help) sed -n '2,22p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done
[ -n "$MODEL" ] || { echo "pass --model vlm|detector or --soak <seconds>" >&2; exit 1; }

if command -v adb >/dev/null; then ADB=adb; else
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ] && { ADB="$sdk/platform-tools/adb"; break; }
  done
fi
[ -n "${ADB:-}" ] || { echo "adb not found" >&2; exit 1; }
[ -n "$SERIAL" ] && export ANDROID_SERIAL="$SERIAL"
command -v python3 >/dev/null || { echo "python3 required" >&2; exit 1; }

mkdir -p "$OUT_DIR"
device=$("$ADB" shell getprop ro.product.model | tr -d '\r')
airplane=$("$ADB" shell settings get global airplane_mode_on | tr -d '\r')
if [ "$airplane" != "1" ]; then
  echo "REFUSING: airplane mode is off on $device. The protocol requires it (SRS §7)." >&2
  echo "  adb shell cmd connectivity airplane-mode enable" >&2
  exit 1
fi

pss_mb() {
  "$ADB" shell dumpsys meminfo "$APP_ID" 2>/dev/null | awk '/TOTAL PSS:/{print int($3/1024); exit} /TOTAL:/{print int($2/1024); exit}' | tr -d '\r'
}

stamp=$(date -u +%Y%m%dT%H%M%SZ)
result_remote="files/bench/${MODEL}-latest.json"
result_local="$OUT_DIR/${MODEL}-${stamp}.json"
peak_log="$OUT_DIR/${MODEL}-${stamp}.pss.log"

echo "== $device · airplane mode on · model=$MODEL runs=$RUNS soak=${SOAK}s =="
"$ADB" shell am start -n "$APP_ID/.MainActivity" >/dev/null
sleep 3
"$ADB" shell run-as "$APP_ID" rm -f "$result_remote" 2>/dev/null || true
"$ADB" shell am broadcast -a com.vysion.xr.BENCH --es model "$MODEL" --ei runs "$RUNS" --ei seconds "$SOAK" >/dev/null

# Sample PSS from outside while the app works; stop when the result file appears (or after the soak time + grace).
peak=0
deadline=$(( $(date +%s) + (SOAK > 0 ? SOAK + 60 : RUNS * 20 + 120) ))
: > "$peak_log"
while [ "$(date +%s)" -lt "$deadline" ]; do
  now=$(pss_mb || echo 0)
  echo "$(date +%s) $now" >> "$peak_log"
  [ "${now:-0}" -gt "$peak" ] && peak=$now
  if "$ADB" shell run-as "$APP_ID" test -f "$result_remote" 2>/dev/null; then
    break
  fi
  sleep 1
done

if ! "$ADB" shell run-as "$APP_ID" test -f "$result_remote" 2>/dev/null; then
  echo "no result file at $result_remote after the deadline; is BenchmarkReceiver implemented and the model fetched?" >&2
  echo "peak PSS observed meanwhile: ${peak} MB (log: $peak_log)" >&2
  exit 1
fi
"$ADB" shell run-as "$APP_ID" cat "$result_remote" > "$result_local"
echo "result: $result_local"
echo "peak PSS (external, dumpsys): ${peak} MB"

python3 - "$result_local" "$peak" "$device" <<'PY'
import json, sys, datetime
r = json.load(open(sys.argv[1])); peak = sys.argv[2]; device = sys.argv[3]
def ms(v): return "not measured" if v is None else f"{v:.0f} ms"
if r.get("model") == "soak":
    print("\nSoak rows (paste into docs/benchmarks.md → Thermal soak):")
    for s in r.get("samples", []):
        print(f"| {s.get('minute','?')} | {s.get('fps','?')} fps | {s.get('pss_mb','?')} MB | {s.get('battery','?')}% | {s.get('thermal','?')} |")
else:
    size = r.get("file_bytes")
    size_s = "not measured" if not size else f"{size/1e6:.0f} MB"
    today = datetime.date.today().isoformat()
    print("\nMarkdown row (paste into docs/benchmarks.md → Models):")
    print(f"| {r.get('model')} | {r.get('quantization','not measured')} | {r.get('backend','not measured')} | {size_s} | {ms(r.get('load_ms'))} | {ms(r.get('median_ms'))} | {ms(r.get('p90_ms'))} | {peak} MB | {r.get('runs',0)} | {device} | {today} | on |")
PY
