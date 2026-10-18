#!/usr/bin/env bash
# Cue placement gate (SRS §8 R4 "every cue string in Cues.kt, nothing hardcoded elsewhere"; INV-4).
# Run locally before every push; CI runs it after the privacy gate.
#
#   1. No string literal is handed to say( / speak( / repeatLast( anywhere in app/src/main or app/src/debug
#      outside voice/Cues.kt.
#      (Cues.kt is the only file that may contain spoken text; everything else passes Cues.* or VLM output.)
#   2. No hedging word appears in a string value inside voice/Cues.kt (SRS §3 "no 'probably a door'").
#      The list below mirrors Cues.HEDGING_WORDS; a line that legitimately contains one (the list itself)
#      carries the marker `cues-allow: <reason>` on the same line, which this script honors.
#
# Exit 0 = clean, 1 = violation. Prints every hit so the PR reviewer can see it.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KOTLIN_SRC="$ROOT/app/src/main/kotlin"
DEBUG_SRC="$ROOT/app/src/debug/kotlin"
CUES="$KOTLIN_SRC/com/vysion/xr/voice/Cues.kt"
fail=0

echo "== 1. string literals passed to say( / speak( outside voice/Cues.kt =="
# Matches `say("…`, `speak("…`, `repeatLast("…` and the named-argument form `say(text = "…`.
LITERAL_SAY='\b(say|speak|repeatLast)\(\s*([A-Za-z_]+\s*=\s*)?"'
if [ -d "$KOTLIN_SRC" ]; then
  SCAN_DIRS=("$KOTLIN_SRC")
  [ -d "$DEBUG_SRC" ] && SCAN_DIRS+=("$DEBUG_SRC")
  if grep -rnE "$LITERAL_SAY" "${SCAN_DIRS[@]}" --include='*.kt' | grep -v '/voice/Cues.kt:' | grep -v 'cues-allow:'; then
    echo "FAIL: a spoken string literal lives outside voice/Cues.kt (SRS §8 R4, INV-4)"
    fail=1
  else
    echo "ok"
  fi
else
  echo "skipped (no Kotlin sources)"
fi

echo "== 2. hedging words in voice/Cues.kt string values =="
# Accent-insensitive on purpose: 'quizás' and 'quizas' both fail. Phrases use \s+ between words.
BANNED='probably|maybe|perhaps|possibly|likely|i\s+think|i\s+guess|i\s+believe|might|could\s+be|seems|appears|looks\s+like|sort\s+of|kind\s+of|probablemente|quiz[aá]s?|tal\s+vez|posiblemente|creo\s+que|supongo|parece|a\s+lo\s+mejor|puede\s+que|puede\s+ser|podr[ií]a'
if [ -f "$CUES" ]; then
  # Only lines that carry a string literal; KDoc/comment lines and the allow-listed banned list are skipped.
  if grep -vE '^\s*(\*|/\*|//)' "$CUES" | grep -v 'cues-allow:' | sed -E 's://.*$::' | grep '"' | grep -niwE "$BANNED"; then
    echo "FAIL: a hedging word appears in a cue value (SRS §3: nothing is spoken that was not measured or seen)"
    fail=1
  else
    echo "ok"
  fi
else
  echo "FAIL: $CUES is missing"
  fail=1
fi

if [ "$fail" -ne 0 ]; then
  echo
  echo "Cue gate FAILED. See docs/modules/r4-voice-accessibility.md (phrasing guide)."
  exit 1
fi
echo
echo "Cue gate passed."
