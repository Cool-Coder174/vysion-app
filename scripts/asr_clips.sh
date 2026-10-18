#!/usr/bin/env bash
# Voice-command accuracy and Whisper latency on the device, with nobody wearing it (ADR-0011; docs/benchmarks.md
# "Voice commands"). macOS only: the clips are spoken by macOS `say` voices, so they measure the model on clean
# synthetic speech, not a wearer in a room.
#
# For every phrase in CommandGrammar.biasingPhrases (read from the Kotlin source, so the set cannot drift) and every
# voice below, it writes a 16 kHz mono 16-bit WAV with 0.5 s of silence before (the earcon window) and 1.2 s after
# (room for the VAD's trailing silence) plus a .txt with the phrase, pushes them to files/smoke/, runs
# `ModelSmokeReceiver --es model asr` on each (Silero VAD -> Whisper -> CommandGrammar.parse, the live path minus the
# microphone) and prints one line per clip and a summary. The clips are deleted from the device afterwards.
#
# Usage:
#   scripts/asr_clips.sh                     # every phrase, every voice
#   scripts/asr_clips.sh --limit 3           # the first 3 phrases per language (quick check)
#   scripts/asr_clips.sh --serial <serial>   # pick one of several devices
#   APP_ID=<id> scripts/asr_clips.sh         # a debug build installed under another application id
#   scripts/asr_clips.sh --make-only         # only synthesize the clips into the cache (no device needed)
# Needs a debug build installed and the asr rows sideloaded (scripts/push_models.sh).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRAMMAR="$ROOT/app/src/main/kotlin/com/vysion/xr/voice/CommandGrammar.kt"
APP_ID="${APP_ID:-com.vysion.xr}"   # override for a debug build installed under another application id
REMOTE="/sdcard/Android/data/$APP_ID/files/smoke"
# Intent.FLAG_RECEIVER_FOREGROUND: Android 14 holds ordinary broadcasts to a cached (frozen) app until it is used again.
FOREGROUND=0x10000000
CACHE="${ASR_CLIPS_CACHE:-$ROOT/ml/out/asr-clips}"   # gitignored; `say` takes seconds per clip, so clips are reused
LIMIT=0
SERIAL=""
MAKE_ONLY=0
EN_VOICES=("Samantha" "Reed (English (US))" "Daniel")
ES_VOICES=("Paulina" "Reed (Spanish (Mexico))" "Mónica")

while [ $# -gt 0 ]; do
  case "$1" in
    --limit) LIMIT="$2"; shift 2 ;;
    --serial) SERIAL="$2"; shift 2 ;;
    --make-only) MAKE_ONLY=1; shift ;;
    -h|--help) sed -n '2,21p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

command -v say >/dev/null || { echo "macOS 'say' is required" >&2; exit 1; }
if command -v adb >/dev/null; then ADB="adb"; else
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [ -n "$sdk" ] && [ -x "$sdk/platform-tools/adb" ]; then ADB="$sdk/platform-tools/adb"; break; fi
  done
fi
[ -n "${ADB:-}" ] || { echo "adb not found" >&2; exit 1; }
[ -n "$SERIAL" ] && export ANDROID_SERIAL="$SERIAL"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# lang|voice|phrase rows, phrases taken from BIASING_EN / BIASING_ES in CommandGrammar.kt.
python3 - "$GRAMMAR" "$LIMIT" "${EN_VOICES[@]}" "--" "${ES_VOICES[@]}" > "$WORK/rows" <<'PY'
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
limit = int(sys.argv[2])
args = sys.argv[3:]
split = args.index("--")
voices = {"en": args[:split], "es": args[split + 1:]}
for lang, name in (("en", "BIASING_EN"), ("es", "BIASING_ES")):
    body = re.search(name + r"\s*=\s*listOf\((.*?)\)\n", src, re.S).group(1)
    phrases = re.findall(r'"([^"]+)"', body)
    if limit:
        phrases = phrases[:limit]
    for voice in voices[lang]:
        for phrase in phrases:
            print(f"{lang}|{voice}|{phrase}")
PY

mkdir -p "$WORK/clips" "$CACHE"

# `say` can hang under load (seen at 0% CPU for minutes): give each clip 60 s and one retry.
speak() {  # <voice> <out.wav> <text>
  local try pid waited
  for try in 1 2; do
    say -v "$1" --file-format=WAVE --data-format=LEI16@16000 -o "$2" "$3" &
    pid=$!
    waited=0
    while kill -0 "$pid" 2>/dev/null && [ "$waited" -lt 120 ]; do sleep 0.5; waited=$((waited + 1)); done
    if kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null || true; continue; fi
    wait "$pid" && return 0
  done
  echo "say failed twice for [$1] \"$3\"" >&2
  return 1
}
# Synthesize, then pad with silence into a canonical WAV (python's wave module writes a 44-byte header).
i=0
names=()
while IFS='|' read -r lang voice phrase; do
  i=$((i + 1))
  name=$(printf "%s-%03d" "$lang" "$i")
  key=$(printf '%s|%s' "$voice" "$phrase" | shasum | cut -c1-16)
  if [ ! -f "$CACHE/$key.wav" ]; then
    speak "$voice" "$WORK/say.wav" "$phrase"
    python3 - "$WORK/say.wav" "$CACHE/$key.wav" <<'PY'
import sys, wave
with wave.open(sys.argv[1]) as w:
    assert w.getframerate() == 16000 and w.getnchannels() == 1 and w.getsampwidth() == 2, w.getparams()
    frames = w.readframes(w.getnframes())
with wave.open(sys.argv[2], "wb") as out:
    out.setnchannels(1); out.setsampwidth(2); out.setframerate(16000)
    out.writeframes(b"\0\0" * 8000 + frames + b"\0\0" * 19200)
PY
  fi
  cp "$CACHE/$key.wav" "$WORK/clips/$name.wav"
  printf '%s' "$phrase" > "$WORK/clips/$name.txt"
  printf '%s\t%s\t%s\n' "$name" "$voice" "$phrase" >> "$WORK/index"
  names+=("$name")
done < "$WORK/rows"
echo "made ${#names[@]} clips (cache: $CACHE)"
[ "$MAKE_ONLY" -eq 1 ] && exit 0
echo "pushing to $REMOTE"

# The receiver asks for files/smoke/ (getExternalFilesDir), so the app owns it; a folder made by `adb shell mkdir`
# would belong to the shell and the app could not read the clips. Any broadcast makes it; this one is rejected.
"$ADB" shell am broadcast -f "$FOREGROUND" -a com.vysion.xr.SMOKE -n "$APP_ID/com.vysion.xr.diagnostics.ModelSmokeReceiver" \
  --es model none >/dev/null
sleep 2
"$ADB" push "$WORK"/clips/* "$REMOTE/" >/dev/null
since="$("$ADB" shell "date +'%m-%d %H:%M:%S.000'" | tr -d '\r')"
: > "$WORK/log"

for name in "${names[@]}"; do
  "$ADB" shell am broadcast -f "$FOREGROUND" -a com.vysion.xr.SMOKE -n "$APP_ID/com.vysion.xr.diagnostics.ModelSmokeReceiver" \
    --es model asr --es image "$name.wav" >/dev/null
  # One clip at a time: wait for its result line (the first one also waits for Whisper to load).
  line=""
  # Each result line is kept locally as it arrives: on a shared headset another session may clear the device log.
  for _ in $(seq 1 240); do
    line=$("$ADB" logcat -d -T "$since" -s vysion.smoke:I |
      grep -E "asr $name\.wav |asr on $name\.wav|rejected: model=asr" | tail -1 || true)
    [ -n "$line" ] && break
    sleep 0.5
  done
  echo "$line" >> "$WORK/log"
  case "$line" in
    *rejected:*) echo "the installed build has no asr smoke mode; install this branch's debug APK" >&2; exit 1 ;;
  esac
  voice=$(awk -F'\t' -v n="$name" '$1 == n {print $2}' "$WORK/index")
  echo "[$voice] ${line#*: }"
done

"$ADB" logcat -d -T "$since" -s vysion.latency:I | grep -E "asr load_ms" >> "$WORK/log" || true
"$ADB" shell "cd $REMOTE && rm -f ${names[*]/%/.wav} ${names[*]/%/.txt}"

python3 - "$WORK/log" <<'PY'
import re, statistics, sys
rows = [l for l in open(sys.argv[1], encoding="utf-8", errors="replace") if " asr " in l and "clip_to_command_ms" in l or "not transcribed" in l]
def pct(xs, p):
    xs = sorted(xs); k = max(0, min(len(xs) - 1, round(p / 100 * (len(xs) - 1))))
    return xs[k]
for lang in ("en", "es"):
    mine = [l for l in rows if re.search(rf"asr {lang}-\d+\.wav", l)]
    if not mine:
        continue
    ok = sum("match=true" in l for l in mine)
    ms = [int(m.group(1)) for l in mine if (m := re.search(r"clip_to_command_ms=(\d+)", l))]
    miss = sum("not transcribed" in l for l in mine)
    line = f"{lang}: {ok}/{len(mine)} commands right"
    if ms:
        line += f"; Whisper clip->command ms median {statistics.median(ms):.0f}, p90 {pct(ms, 90)}, max {max(ms)}"
    if miss:
        line += f"; VAD heard no speech in {miss}"
    print(line)
loads = [l.strip() for l in open(sys.argv[1], encoding="utf-8", errors="replace") if "asr load_ms" in l]
if loads:
    print("load:", loads[-1].split(": ", 1)[-1])
PY
