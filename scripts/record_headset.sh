#!/usr/bin/env bash
# See and record what the Galaxy XR shows: your passthrough view plus the app, as one square picture.
#
#   scripts/record_headset.sh              record until Ctrl-C, then pull the file and make a 1080p copy
#   scripts/record_headset.sh --seconds 60 stop by itself after 60 s
#   scripts/record_headset.sh --live       live preview window on this Mac, no file (scrcpy if installed, else ffplay)
#   scripts/record_headset.sh --test       3 s recording; says whether the headset is worn (a black picture means not)
#   Options: --size 2880x2880 (full resolution; default 1920x1920)  --bit-rate 20M  --out DIR  --serial <adb serial>
#
# How it works: `adb shell screenrecord` records the headset's display, which is the 2880x2880 composite a wearer sees
# (the same picture `adb exec-out screencap -p` returns). The headset must be ON YOUR HEAD: with the display off,
# screenrecord fails with INVALID_LAYER_STACK, and a screenshot or recording taken unworn is black. screenrecord has NO
# AUDIO; for a demo with the app's spoken answers use the headset's own recorder (Launcher > Quick settings > Screen
# record, pick an audio option) and pull the file from DCIM or Movies.
#
# Nothing here touches the app, and nothing is stored on the headset beyond the file this script deletes after pulling.
set -euo pipefail

SIZE="1920x1920"
BITRATE="20M"
SECONDS_LIMIT=0
OUT_DIR="$HOME/Movies/Vysion"
MODE="record"
SERIAL=""

while [ $# -gt 0 ]; do
  case "$1" in
    --live) MODE="live"; shift ;;
    --test) MODE="test"; shift ;;
    --seconds) SECONDS_LIMIT="$2"; shift 2 ;;
    --size) SIZE="$2"; shift 2 ;;
    --bit-rate) BITRATE="$2"; shift 2 ;;
    --out) OUT_DIR="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

if command -v adb >/dev/null; then ADB=adb; else
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ] && { ADB="$sdk/platform-tools/adb"; break; }
  done
fi
[ -n "${ADB:-}" ] || { echo "adb not found" >&2; exit 1; }
[ -n "$SERIAL" ] && export ANDROID_SERIAL="$SERIAL"

"$ADB" get-state >/dev/null 2>&1 || { echo "No headset on adb. Plug it in over USB (or adb connect it) and allow debugging." >&2; exit 1; }
model=$("$ADB" shell getprop ro.product.model | tr -d '\r')
if "$ADB" shell dumpsys power | grep -q "mWakefulness=Asleep"; then
  echo "The headset is asleep: put it on your head first. Unworn, the display is off and the picture is black." >&2
  exit 2
fi

mkdir -p "$OUT_DIR"
stamp=$(date +%Y%m%d-%H%M%S)
remote="/sdcard/Movies/vysion-$stamp.mp4"
raw="$OUT_DIR/vysion-$stamp-raw.mp4"
share="$OUT_DIR/vysion-$stamp.mp4"

if [ "$MODE" = "live" ]; then
  if command -v scrcpy >/dev/null; then
    echo "Live view with scrcpy (close the window to stop; no audio)."
    exec scrcpy --max-size=1440 --video-bit-rate=8M --no-audio --no-control --window-title "Galaxy XR"
  fi
  command -v ffplay >/dev/null || { echo "Need scrcpy (brew install scrcpy) or ffplay (brew install ffmpeg) for --live." >&2; exit 1; }
  echo "Live view with ffplay (close the window or Ctrl-C to stop; no audio)."
  trap '"$ADB" shell pkill -INT screenrecord >/dev/null 2>&1 || true' EXIT # never leave the recorder running on the headset
  "$ADB" exec-out screenrecord --output-format=h264 --size "$SIZE" --bit-rate 8M --time-limit 180 - |
    ffplay -loglevel error -fflags nobuffer -flags low_delay -framedrop -probesize 32 -analyzeduration 0 -window_title "Galaxy XR" -
  exit 0
fi

[ "$MODE" = "test" ] && SECONDS_LIMIT=3
echo "Recording $model at $SIZE, $BITRATE$([ "$SECONDS_LIMIT" -gt 0 ] && echo ", $SECONDS_LIMIT s")$([ "$SECONDS_LIMIT" -eq 0 ] && echo " (Ctrl-C to stop)")..."

stopped=0
stop_recording() {
  stopped=1
  "$ADB" shell pkill -INT screenrecord 2>/dev/null || true # a clean stop writes the mp4 index; killing adb alone would not
}
trap stop_recording INT TERM

log=$(mktemp)
"$ADB" shell screenrecord --size "$SIZE" --bit-rate "$BITRATE" --time-limit "$SECONDS_LIMIT" "$remote" >"$log" 2>&1 &
rec_pid=$!
wait "$rec_pid" 2>/dev/null || true
if [ "$stopped" -eq 1 ]; then
  wait "$rec_pid" 2>/dev/null || true # the first wait was interrupted by Ctrl-C; let the stop finish
  sleep 1 # the encoder flushes the index after the signal
fi
trap - INT TERM

if grep -q "INVALID_LAYER_STACK" "$log"; then
  echo "The display turned off: wear the headset, then run this again." >&2
  rm -f "$log"
  exit 2
fi
if grep -qi "error\|unable\|not supported\|failed" "$log"; then
  echo "screenrecord reported: $(tr '\n' ' ' <"$log")" >&2
  [ "$SIZE" != "1440x1440" ] && echo "Try a smaller picture: --size 1440x1440" >&2
fi
rm -f "$log"

"$ADB" pull "$remote" "$raw" >/dev/null 2>&1 || { echo "No recording came back from the headset." >&2; exit 1; }
"$ADB" shell rm -f "$remote"
echo "Saved $raw ($(du -h "$raw" | cut -f1))"

if command -v ffmpeg >/dev/null; then
  # Black means unworn (earlier unworn captures measured a mean luma of 13-16 of 255).
  luma=$(ffmpeg -v error -i "$raw" -frames:v 1 -vf "signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=-" -f null - 2>/dev/null |
    awk -F= '/YAVG/{print int($2); exit}')
  if [ -n "${luma:-}" ] && [ "$luma" -lt 20 ]; then
    echo "WARNING: the first frame is black (mean brightness $luma). Was the headset on your head?" >&2
  elif [ -n "${luma:-}" ]; then
    echo "Picture present (mean brightness $luma of 255)."
  fi
  if [ "$MODE" != "test" ]; then
    ffmpeg -v error -y -i "$raw" -vf "scale=1080:1080" -c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -movflags +faststart "$share"
    echo "Shareable 1080x1080 copy: $share ($(du -h "$share" | cut -f1))"
  fi
else
  echo "(install ffmpeg for a smaller 1080p copy and the black-frame check: brew install ffmpeg)"
fi
