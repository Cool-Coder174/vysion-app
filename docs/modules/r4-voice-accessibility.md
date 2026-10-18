# R4 Voice and Accessibility UX

## Purpose

R4 is the voice in and the voice out of Vysion XR: the on-device `SpeechRecognizer` session that becomes a flow of parsed commands, the Android `TextToSpeech` queue that is the only thing in the app that speaks, every spoken sentence in English and Spanish, the six earcons, and the accessibility rules the other roles build to. SRS §8, verbatim: **Owns** "Speech in, TTS, command grammar, phrasing, earcons, large-print panel spec, TalkBack labels, user testing"; **Hands off** "`CommandBus` (parsed commands as a flow) and `Speaker` (queue, interrupt, rate, locale)"; **Due** Sat 15:00 (gate 3, the first merge). Nothing here needs a model; everything here is on the critical path of every mode, so the fakes and the pure logic are real from the first commit.

## Files you own

| Path | What it is | Status |
|---|---|---|
| `app/src/main/kotlin/com/vysion/xr/voice/CommandGrammar.kt` | `CommandGrammar.parse(utterance, locale): Command?` — normalization, wake word, fuzzy token matching (Levenshtein ≤ 2), follow-up detection; `TextFold` shared with `Cues` | pure, tested |
| `app/src/main/kotlin/com/vysion/xr/voice/Cues.kt` | `object Cues`: every spoken string, en-US + es-MX, keyed; `phrase(NavigationCue, locale)`; `meters`, `clockFace`, `doorBearing`; `PHRASING_RULES`, `HEDGING_WORDS`, `violatesPhrasingRules`; `object CuesPhrasebook : ModePhrasebook` (R1's seam) with `bind(speaker)` | pure, tested |
| `app/src/main/kotlin/com/vysion/xr/voice/Earcons.kt` | `EarconPlayer`, `NoOpEarconPlayer`, `Tone`, `EarconSynth.pcm` (chirp synthesis), `Earcons` (six tone constants; `play` is the `AudioTrack` stub) | pure synth, tested / stub playback |
| `app/src/main/kotlin/com/vysion/xr/voice/AndroidSpeaker.kt` | `TtsPort` (device boundary), `VoiceCandidate` + `VoiceSelection` (es-MX → es-ES → notice), `AndroidTtsPort` (stub over `TextToSpeech`), `AndroidSpeaker : Speaker` (bounded queue, interrupt, stop, watchdog, progress, rate, locale, repeat, idempotent `release`) | plumbing real + tested / port stub |
| `app/src/main/kotlin/com/vysion/xr/voice/SpeechCommandBus.kt` | `RecognizerPort` (device boundary), `AndroidRecognizerPort` (stub over `SpeechRecognizer`; the A-7 availability line is real), `SpeechCommandBus : CommandBus` (8-deep DROP_OLDEST flow, `inject`, 8 s cap, state machine, `setLocale` for the next session, idempotent `release`) | plumbing real + tested / port stub |
| `app/src/main/kotlin/com/vysion/xr/voice/WhisperRecognizerPort.kt` | `WhisperRecognizerPort : RecognizerPort` (ADR-0011: `AudioRecord` 16 kHz mono, Silero VAD end of speech, Whisper, the system recognizer as fallback), `SpeechEndpointer` (end-of-speech rules) | device / pure, tested |
| `app/src/main/kotlin/com/vysion/xr/voice/WhisperTranscriber.kt` | `WhisperTranscriber` (Whisper on plain `Module`s on its own thread, audio in memory only), `WhisperDecoding` (prompt: en/es, transcribe, no timestamps, suppress blank), `WhisperTokenizer` (byte-level BPE to text), `SileroVad`, `WhisperText`, `AsrModelFiles` | device / pure, tested |
| `scripts/check_cues.sh` | CI gate: no spoken literal outside `Cues.kt` (main and debug sources); no hedging word inside it | script |
| `app/src/main/res/values/strings.xml` | `a11y_*` TalkBack labels and `product_sentence`; R4 extends it with the visible panel captions R1 asks for (r1 open issue 6) | resources |
| `app/src/main/assets/sounds/ping.ogg` | the ping earcon R3's `SpatialAudioPinger` loads (`PING_ASSET`); not in the skeleton yet, see the `Speaker` P0 item | asset (to add) |
| `app/src/test/kotlin/com/vysion/xr/voice/CommandGrammarTest.kt` | every command, both languages, fuzzy variants, follow-up, wake word, tie-breaking | JUnit 4 |
| `app/src/test/kotlin/com/vysion/xr/voice/CuesTest.kt` | `phrase` for each `NavigationCue` in both locales, meters/clock, rules, completeness, welcome ↔ grammar, product sentence ↔ `strings.xml` | JUnit 4 |
| `app/src/test/kotlin/com/vysion/xr/voice/AndroidSpeakerTest.kt` | queue order, interrupt, stop, progress, watchdog, repeat, locale fallback (A-8, also when requested before init), `release` via `FakeTtsPort`; `VoiceSelectionTest` | JUnit 4 + coroutines-test |
| `app/src/test/kotlin/com/vysion/xr/voice/SpeechCommandBusTest.kt` | inject, DROP_OLDEST, voice results → grammar → `VOICE` events, 8 s cap, UNAVAILABLE path, `setLocale` / `release` through the `CommandBus` contract via `FakeRecognizerPort` | JUnit 4 + coroutines-test |
| `app/src/test/kotlin/com/vysion/xr/voice/WhisperPartsTest.kt` | end of speech (earcon window, hysteresis, pauses, no speech), the decoding prompt and suppress blank, byte-level BPE decoding, Whisper text cleanup → grammar | JUnit 4 |
| `scripts/asr_clips.sh` | voice-command accuracy and Whisper latency on the device without a wearer (macOS `say` clips through `ModelSmokeReceiver --es model asr`) | script |
| `app/src/test/kotlin/com/vysion/xr/voice/EarconsTest.kt` | tone table, PCM length/amplitude/frequency, the PROXIMITY sweep rises | JUnit 4 |
| `docs/modules/r4-voice-accessibility.md` | this page | doc |

## Contracts you provide / consume

| Direction | Contract (`core/Contracts.kt`) | Implemented / used by | Other side |
|---|---|---|---|
| provide | `interface CommandBus { val commands: Flow<CommandEvent>; val state: StateFlow<ListeningState>; fun startListening(); fun stopListening(); fun inject(command: Command, source: CommandSource = PANEL); fun setLocale(locale: Locale) {}; fun release() {} }` | `voice.SpeechCommandBus(app: Application, scope: CoroutineScope)` (overrides `setLocale` and `release`) | R1 `ModeController` collects `commands` and `state` and calls `setLocale` on `InSpanish` (next to `Speaker.setLocale`); R1 panels / pinch call `startListening` / `inject`; tests call `inject(cmd, TEST)`; `AppGraph.release()` calls `release` |
| provide | `interface Speaker { val progress: Flow<SpeechProgress>; val locale: StateFlow<Locale>; val isSpeaking: StateFlow<Boolean>; suspend fun say(text: String, interrupt: Boolean = false); fun stop(); fun earcon(kind: Earcon); fun setRate(rate: Float); fun setLocale(locale: Locale); suspend fun repeatLast(rate: Float = 1.0f); fun release() {} }` | `voice.AndroidSpeaker(app: Application, scope: CoroutineScope)` (overrides `release`) | R1 `ModeController` (every spoken line), R1 `TranscriptPanel` (`progress`), R3 through R1 (`NavigationCue` → `Cues.phrase` → `say(…, interrupt = cue is Proximity)`); `AppGraph.release()` calls `release` |
| internal (not a cross-package contract) | `object Cues`: `text(Key, Locale)`, named accessors, `modeAnnouncement(Mode, Locale)`, `phrase(NavigationCue, Locale)`. No other package imports it (ADR-0005); it reaches R1 only through `CuesPhrasebook`, the `docs/contracts.md` row "`ModePhrasebook` (seam in `core`)" | `voice.Cues` | `voice.CuesPhrasebook` (and `AndroidSpeaker` for `spanishVoiceUnavailable`); R3 never builds strings |
| provide | `core.ModePhrasebook { val welcome, busy, inferenceFailed, doorNotFound, navigationUnavailable, holdStill, permissionExplanation, permissionDenied: String; fun modeAnnounced(Mode): String; fun navigationCue(NavigationCue): String? }` | `voice.CuesPhrasebook` (`bind(speaker)` makes every value follow `Speaker.locale`) | R1 `ModeController(…, phrasebook = CuesPhrasebook)`; `AppGraph` passes `CuesPhrasebook.also { it.bind(speaker) }` (`ModePhrasebook.Unwired`, which speaks nothing, is only for tests) |
| consume | `sealed interface Command { Read, Describe, GuideMe, FindDoor, Stop, Repeat, Slower, InSpanish, Next, Brief, Detailed, FollowUp(question), SelectDoor(side) }`, `CommandEvent(command, source, recognizedAtNanos)`, `enum ListeningState`, `enum CommandSource` | `CommandGrammar`, `SpeechCommandBus` | R5 owns the file |
| consume | `enum Earcon { LISTENING, THINKING, DONE, ERROR, PROXIMITY, PING }`, `data class SpeechProgress(utteranceId, text, charStart, charEnd, done)` | `Earcons`, `AndroidSpeaker` | R1 panel reads `SpeechProgress` |
| consume | `sealed interface NavigationCue { Proximity, DoorBearing(clock, distanceM, inView, pinged = true), WallAhead, FloorEnds, Arrived, TargetLost }`, `enum Mode`, `enum HorizontalBearing`, `core/Units.kt` (`ClockFace`, `Distances.roundToHalfMeter`) | `Cues.phrase`, `Cues.meters` | R3 emits cues; R1 forwards them |

Internal seams (not contracts, same package only): `TtsPort` / `AndroidTtsPort`, `RecognizerPort` / `AndroidRecognizerPort`, `EarconPlayer` / `Earcons` / `NoOpEarconPlayer`. They exist so the real plumbing is testable with `FakeTtsPort` and `FakeRecognizerPort` (in the test sources) and so the device code is a handful of one-line stubs.

## Assumptions to verify first

Verbatim from `docs/assumptions.md`:

| # | Owner | Assumption | By | Result | Evidence | Checked by |
|---|---|---|---|---|---|---|
| A-7 | R4 | `SpeechRecognizer` works with network off on the headset | Sat 14:00 | | | |
| A-8 | R4 | TTS has an es-MX or es-ES voice installed offline | Sat 14:00 | | | |

| Failed | Then (verbatim) |
|---|---|
| A-7 | RK-4: pinch-to-talk and panel buttons are P0; recognition becomes P1 |
| A-8 | Fall back to es-ES, then en-US with a spoken notice; Spanish stays P1 |

Also depended on, owned by others: A-6 (SceneCore spatial audio, R3) → fallback is `Speaker.earcon(PING)` and bearings every 2 s; `docs/stack.md` Q14 (does TalkBack announce inside a `SpatialPanel`, R4 Sun 09:00) → fallback: duplicate labels as spoken cues.

### How to run the A-7 / A-8 checks (Sat 14:00, airplane mode ON)

```bash
adb shell settings get global airplane_mode_on          # must print 1
scripts/install_headset.sh --logcat | grep -E 'A-7|A-8|vysion'
```

- **A-7** is logged by `AndroidRecognizerPort` at construction: `vysion: A-7 on-device speech recognition available=true|false`. Then (once `startListening` is implemented) run `checkRecognitionSupport(intent("en-US"), mainExecutor, cb)` and `intent("es-MX")` and log `installedOnDeviceLanguages`; both tags must be listed (`docs/stack.md` Q12). If a pack is missing, `triggerModelDownload` ONCE on Wi-Fi before the demo, then airplane mode again. If `isOnDeviceRecognitionAvailable` is false: `state` is `UNAVAILABLE`, `inject` still works, R1 speaks `Cues.voiceCommandsUnavailable` once per run (seam in core: `ModePhrasebook.voiceCommandsUnavailable`, overridden by `CuesPhrasebook`; `ModeControllerTest` covers it), and the row above gets "no" + the logcat line. Nobody argues after 14:00 (RK-4).
- **A-8** is logged by `AndroidTtsPort.setLocale` once implemented: log `tts.voices.filter { it.locale.language == "es" && !it.isNetworkConnectionRequired && it.features?.contains("notInstalled") != true }` with name, locale, quality. Evidence is that list. Empty → install the Google TTS Spanish pack on Wi-Fi, re-check; still empty → `setLocale(es-MX)` keeps en-US and says `Cues.spanishVoiceUnavailable` (already wired), Spanish stays P1.

## Task checklist

Verbatim from SRS §8 "R4 Voice and Accessibility UX":

- [ ] P0 `CommandBus`: `SpeechRecognizer` with on-device preference; grammar for read, describe, guide me, find the door, stop, repeat, slower, in Spanish; fuzzy matching so "read it" and "read that" both work. Sat 14:00
  Files: `voice/CommandGrammar.kt`, `voice/SpeechCommandBus.kt` (replace the four `TODO(FR-G2)` bodies in `AndroidRecognizerPort`) · FR: FR-G2, NFR latency ≤ 1.5 s command→action · Verify: `./gradlew testDebugUnitTest --tests 'com.vysion.xr.voice.CommandGrammarTest' --tests 'com.vysion.xr.voice.SpeechCommandBusTest'` green; on the headset in airplane mode say "Vysion, read this" and see logcat `vysion.latency: command->action <ms> ms` (`MainActivity` logs it from `ModeController.commandLatencyNanos`, i.e. `CommandEvent.recognizedAtNanos` → CAPTURING/LISTENING)
- [ ] P0 `Speaker`: TTS queue with interrupt-on-stop, rate control, en-US and es-MX voices, earcons for listening, thinking, done, error. Sat 15:00
  Files: `voice/AndroidSpeaker.kt` (replace the six `TODO(FR-G4/T3/G6)` bodies in `AndroidTtsPort`; stop→silence log), `voice/Earcons.kt` (`TODO(FR-N1)` in `Earcons.play`), `app/src/main/assets/sounds/ping.ogg` (needed by R3 by Sat 13:00 for the A-6 probe; see "Rendering ping.ogg" below) · FR: FR-G4, FR-T1 (FR-T1's first-word timing reads `Speaker.progress`, so `onStart` must emit; R1 leads FR-T1), FR-T3, FR-G6, FR-N1 (earcon), A-6 · Verify: `./gradlew testDebugUnitTest --tests 'com.vysion.xr.voice.AndroidSpeakerTest' --tests 'com.vysion.xr.voice.EarconsTest'` green; `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/sounds/ping.ogg`; on the headset `say(Cues.welcome())` is audible and "stop" silences it: logcat `vysion.latency: stop→silence <ms> ms` ≤ 300 (R4: `AndroidSpeaker.stop()` records `System.nanoTime()`; the `AndroidTtsPort` `onStop` callback logs `vysion.latency: stop→silence <ms> ms`)
  Rendering ping.ogg (the same tone as `Earcons.PING` = `Earcons.toneFor(Earcon.PING)`: 1760 Hz, 60 ms, 5 ms fades, amplitude 0.5, mono, `EarconSynth.SAMPLE_RATE_HZ` = 22050). There is no `EarconSynth.pcm(Earcon)` overload; the signature is `EarconSynth.pcm(tone: Tone, sampleRateHz: Int = SAMPLE_RATE_HZ)`. ffmpeg's `sine` source reproduces the tone without a JVM step (its amplitude is 1/8, hence `volume=4`); checked with ffmpeg 7.1.1 + libvorbis on 2026-10-17 (3.7 KB, vorbis, 22050 Hz, mono, 0.060 s, peak −5.8 dBFS):
  ```bash
  mkdir -p app/src/main/assets/sounds
  ffmpeg -y -f lavfi -i "sine=frequency=1760:sample_rate=22050:duration=0.06" \
    -af "volume=4,afade=t=in:d=0.005,afade=t=out:st=0.055:d=0.005" -ac 1 -c:a libvorbis -q:a 4 \
    app/src/main/assets/sounds/ping.ogg
  ffprobe -v error -show_entries stream=codec_name,sample_rate,channels:format=duration -of default=nw=1 \
    app/src/main/assets/sounds/ping.ogg   # vorbis, 22050, 1, 0.060000
  ```
  If `Earcons.PING` changes, change the three numbers here in the same commit. AAPT stores `.ogg` uncompressed, which `AssetManager.openFd` needs. Then `./gradlew assembleDebug` and `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep assets/sounds/ping.ogg`.
- [ ] P0 Phrasing guide: clock-face bearings, meters, hazards first, no hedging words; every cue string in `Cues.kt`, nothing hardcoded elsewhere. Sat 16:00
  Files: `voice/Cues.kt`, this page ("Phrasing guide"), `scripts/check_cues.sh` · FR: FR-G1, FR-G3, FR-N1, FR-N3, FR-N5, FR-N6, RK-6, RK-9 · Verify: `bash scripts/check_cues.sh` prints "Cue gate passed."; `./gradlew testDebugUnitTest --tests 'com.vysion.xr.voice.CuesTest'` green
- [ ] P0 Transcript panel spec for R1: 48 dp+ text, 7:1 contrast, auto-scroll synced to speech. Sat 16:00
  Files: this page ("Transcript panel spec"); R1 implements it as the answer text in `platform/ui/HeadsetUi.kt` (`StatusWindow` / `AnswerText`) · FR: FR-T2 · Verify: R1's panel collects `Speaker.progress`; on the headset the highlighted range moves with the voice; screenshot path in the PR; contrast measured on the screenshot ≥ 7:1
- [ ] P0 Confirm offline speech recognition works in airplane mode on the headset; if not, pinch-to-talk and panel buttons are the P0 path and recognition becomes P1. Sat 14:00
  Files: `docs/assumptions.md` row A-7, `docs/risks.md` RK-4 · FR: FR-G2, A-7 · Verify: logcat `vysion: A-7 on-device speech recognition available=true` with `adb shell settings get global airplane_mode_on` = 1, and a `CommandEvent` with `source=VOICE` in logcat (R1's `ModeController` command log, R1 P0 `ModeController` item)
- [ ] P1 Blindfold test with two teammates: 5 read, 5 describe, 3 guide tasks; log every confusing cue and fix wording. Sat 21:00
  Files: this page ("Blindfold test protocol" log sheet), `voice/Cues.kt` wording fixes · FR: NFR accessibility, FR-T1, FR-D1, FR-N3 · Verify: the log sheet below is filled for 13 tasks with a time-to-first-word per task from `vysion.latency`; every "confusing" row has a commit in `Cues.kt`
- [ ] P1 TalkBack labels on every control; verify with TalkBack on. Sun 09:00
  Files: `res/values/strings.xml` (`a11y_*`, R1 applies them), this page ("TalkBack checklist") · FR: NFR accessibility, SRS §3 rule 4 · Verify: with TalkBack on, swiping through the control panel announces every label in the checklist order; `docs/stack.md` Q14 answered yes/no with a screen recording path
- [ ] P2 ExecuTorch TTS (Supertonic or Voxtral TTS) behind the same `Speaker` interface. Sun 11:00
  Files: a second `TtsPort` implementation in `voice/` (`ExecuTorchTtsPort`), no change to `AndroidSpeaker` · FR: FR-G7 · Verify: `AndroidSpeakerTest` still green against the new port's fake; on the headset `DiagnosticsSnapshot.backend` shows the TTS `.pte`; first word latency in `docs/benchmarks.md`

Skeleton backlog in code: `grep -rn "TODO(" app/src/main/kotlin/com/vysion/xr/voice` (11 lines: 4 recognizer, 6 TTS, 1 earcon).

## Command grammar

Normalization (`CommandGrammar.tokens`): lower-case → accents stripped (`qué` → `que`, `español` → `espanol`) → apostrophes removed (`what's` → `whats`) → every other non-letter is a space → leading filler (`hey, ok, okay, please, oye, hola, por, favor`) and one wake word (`vysion` / `vision`, within edit distance 1, so `visión` works) dropped. A pattern is a set of key tokens; all keys must be present in any order. Keys of 4–5 letters match within Levenshtein 1, 6+ letters within 2, shorter keys exactly; "exact" rows never fuzz; "≤ n" rows only apply to utterances of at most n tokens. More matched keys win, then smaller total edit distance, then the language of `Speaker.locale`, then table order (Stop is first).

**Stop tolerance (FR-G4, INV-6).** English `stop` does not use the length rule. It has its own rule (`CommandGrammar.SAFETY_*`):

| Utterance (after wake word and filler) | `stop` matches | Examples → Stop | Examples → not Stop |
|---|---|---|---|
| any length | the exact token | "stop reading", "please stop reading that now" | |
| bare command, ≤ 2 tokens (`SAFETY_FUZZY_MAX_TOKENS`) | within 1 edit, or within 2 edits (`SAFETY_MAX_EDIT_DISTANCE`) when the token keeps the "st" onset (`SAFETY_ONSET_LETTERS = 2`) | "stahp", "stap it", "please stopp", "stahp reading" | "so", "to", "up"; "slow" (Slower wins at 0 edits); "leg esto" (`esto` is 2 edits away without the onset) |
| 3 tokens or more | the exact token only | | "is there a step ahead?" and "what's on the top shelf?" stay follow-up questions |

Why: Stop is safety-relevant, so a slip in a bare command must still stop, and a 4-letter key's single edit misses "stahp". A false Stop only silences and the user says the command again. A missed Stop is the INV-6 failure. Requiring the "st" onset for the second edit keeps short words that only share letters ("so", "to", "esto") from becoming Stop. A longer utterance is a sentence, not a slip, so a similar word in it ("step", "top") is not taken for Stop and the user's question gets answered. The bus also tries every recognizer hypothesis, so an exact "stop" in the n-best list still wins. The Spanish Stop keys keep the length rule (`detente`, `silencio`: 2 edits; `basta`: 1 edit; `para`, `alto`: exact, ≤ 2 tokens).

| Command | English keys (examples) | Spanish keys (examples) | Notes |
|---|---|---|---|
| `Stop` | `stop` (Stop tolerance above: "stop", "stop it", "stahp", "stap it"; exact in 3+ tokens) | `detente`, `basta`, `silencio`; `para`, `alto` (exact, ≤ 2 tokens) | first in the table: never shadowed (INV-6); "para"/"alto" are exact and short so "lee esto para mí" stays Read |
| `FindDoor` | `find`+`door`, `where`+`door`, `find`+`exit`, `where`+`exit`; `door`, `exit` (≤ 3) | `busca`+`puerta`, `donde`+`puerta`, `encuentra`+`puerta`, `busca`+`salida`; `puerta`, `salida` (≤ 3) | "where is the door?" is a command, not a follow-up |
| `SelectDoor(LEFT/CENTER/RIGHT)` | `one`+`left`, `door`+`left`, `one`/`door`+`middle`/`center`, `one`/`door`+`right`; `left`, `middle`, `center`, `right` (≤ 3) | `puerta`+`izquierda`/`centro`/`medio`/`derecha`; `izquierda`, `centro`, `medio`, `derecha` (≤ 4) | P2 FR-N7; two-key rows beat FindDoor's single `door` |
| `Read` | `read` ("read this/it/that", "could you read this please") | `lee` (exact), `leer`, `leelo`, `leeme` ("lee esto", "léelo") | FR-T1; "read the door sign" → Read (door alone needs ≤ 3 tokens) |
| `Describe` | `describe`, `around` ("what's around me"), `surroundings` | `describe`, `describeme`, `alrededor`, `que`+`hay` (exact) | FR-D1 |
| `GuideMe` | `guide`, `navigate`, `navigation` | `guiame`, `guia` (exact), `navega`, `navegacion` | "guide me to the door" → GuideMe (5 tokens, `door` row needs ≤ 3) |
| `Repeat` | `repeat`, `again` | `repite`, `repitelo`, `otra`+`vez` (exact), `de`+`nuevo` (exact) | FR-T3 |
| `Slower` | `slower`, `slowly`, `slow` | `despacio`, `lento` ("más despacio") | FR-T3 → `repeatLast(SLOWER_RATE)` |
| `InSpanish` | `spanish`, `translate` | `espanol`, `castellano`, `traduce`, `traducelo` | FR-T4 / FR-G6 |
| `Next` | `next` | `siguiente`, `proxima`, `proximo` | P2 FR-T6 |
| `Brief` | `brief`, `briefly`, `shorter`, `short` | `breve`, `corto`, `resumen` | P1 FR-D3 |
| `Detailed` | `detailed`, `detail`, `details`, `longer` | `detallado`, `detalle`, `detalles` | P1 FR-D3 |
| `CloudDescribe(on)` | `cloud`+`on`, `cloud`+`off` (≤ 4: "cloud on", "turn the cloud off") | `activa`/`enciende`+`nube`, `nube`+`activada`; `desactiva`/`apaga`+`nube`, `nube`+`desactivada` (≤ 4) | ADR-0010; ≤ 4 so "is it cloudy on the left?" stays a follow-up; spoken confirmation `Cues.cloudDescribe(on)` names Google |
| `FollowUp(question)` | no pattern matched AND (ends with `?` OR first token ∈ what/whats/which/where/who/how/why/when/is/are/does/do/can) AND ≥ 2 tokens | … OR first token ∈ que/cual/cuales/cuanto(s)/cuantas/donde/quien/como/hay/es/esta/estan/por | P1 FR-D2; the VLM gets the raw text minus the wake word, punctuation kept |
| `null` | anything else, the bare wake word, a lone question word | | the bus plays `ERROR` and returns to IDLE |

Known ambiguities (accepted, documented, tested): `red`/`bread` parse as Read. In a bare command of ≤ 2 tokens, `shop`, `step`, `top`, `stay` and `store` parse as Stop. This is fail-safe: Stop only silences. It is harmless in a command-only context and goes away once the recognizer's first hypothesis is right. In a longer sentence these words are never Stop. Add a row here and a test before adding a key.

## Phrasing guide

SRS §3, enforced by `Cues.violatesPhrasingRules` (unit test over every value) and `scripts/check_cues.sh` (CI):

1. **Clock-face bearings.** `ClockFace.fromRelativeYawDeg` gives 1..12; speak "2 o'clock" / "a las 2" ("a la 1"). Never degrees, never left/right for a bearing (left/right is only the VLM's one-shot answer, which becomes an anchor, not a sentence).
2. **Meters rounded to 0.5, spoken as words.** `Distances.roundToHalfMeter` then `Cues.meters`: "3 meters", "1 meter", "2 and a half meters", "half a meter", "less than half a meter" / "3 metros", "1 metro", "2 metros y medio", "medio metro". Never "2.5".
3. **Hazards first.** `Proximity` is said with `say(…, interrupt = true)`; everything else with `interrupt = false`. R1 does this in the cue collector; R3 never speaks.
4. **No hedging.** Banned (accent-folded): probably, maybe, perhaps, possibly, likely, I think, I guess, I believe, might, could be, seems, appears, looks like, sort of, kind of, probablemente, quizá(s), tal vez, posiblemente, creo que, supongo, parece, a lo mejor, puede que, puede ser, podría. Nothing is spoken that was not measured or seen: no "probably a door". VLM output is spoken as is (the prompt says "Do not guess"); R2 may run `violatesPhrasingRules` on it for the test matrix.
5. **Short and interruptible.** One clause per cue; "stop" must have little to cut. Sentences for errors, fragments for navigation.
6. **Verbatim phrases** the SRS or a risk row fixes: "door, 2 o'clock, 3 meters" (FR-N3), "obstacle ahead" (FR-N1), "wall ahead", "floor ends" (FR-N5), "hold still" (FR-T5), "I can't find a door, turn slowly" (RK-6), the product sentence (SRS §3; `R.string.product_sentence` is the visual twin, the es-MX value is a translation and English stays canonical).
7. **Every spoken string lives in `Cues.kt`** (`Key` enum, both locales, tested for completeness). A new sentence = a new `Key` + two values + a test line. `docs/nfr.md` calls the error cue `Cues.error`; it is an alias of `inferenceFailed`.

Who says what (so R1 does not invent sentences): welcome on launch (FR-G1) · mode announcement on every change (FR-G3) · `listening` only if the earcon is unavailable · `busy` for a non-Stop command while CAPTURING/INFERRING · `inferenceFailed` on `InferenceException` or the 15 s timeout · `cameraLost` after 2 s without frames · `modelNotFound` when the lockfile hash fails · `holdStill` on high head angular speed (FR-T5) · `Cues.phrase(cue)` for every `NavigationCue` · `doorNotFound` when the two-frame vote disagrees (RK-6) · `navigationUnavailableOnThisDevice` on the S25 Ultra (ADR-0006) · `voiceCommandsUnavailable` when `CommandBus.state == UNAVAILABLE` · `spanishVoiceUnavailable` is said by the Speaker itself (A-8) · `permissionExplanation` by `MainActivity` before the system dialogs and `permissionDenied` when camera or microphone is refused (FR-G1, through `ModePhrasebook`) · `Arrived` says nothing through the phrasebook (R1 runs the ARRIVAL prompt, FR-N6); `Cues.phrase(Arrived)` = "you have arrived" stays available · "No text found" (FR-T1): there is no `Cues` line; the READ prompt asks the VLM to answer "No text found." and the controller speaks the VLM's answer as is (INV-4 allows VLM text). A `Cues` line (`Key.NO_TEXT_FOUND`, en-US "No text found.", es-MX "No encontré texto.") is part of the open P1 proposal in `docs/contracts.md` "Open contract proposals" and is not added before R5 merges the `ModePhrasebook` change.

## Transcript panel spec (for R1, FR-T2)

| Property | Spec | Why / source |
|---|---|---|
| Text size | ≥ 48 dp at the system font scale 1.0 (`fontSize = 48.sp`, `lineHeight = 60.sp`); never smaller, grows with the user's font scale | SRS §3 rule 4, FR-T2 |
| Contrast | ≥ 7:1: `#FFFFFF` on `#000000` (21:1) or on `#121212` (18.7:1); solid background box, no translucency behind text (passthrough bleeds through anything less) | WCAG 2.2 SC 1.4.6 via `docs/stack.md` §3-R4 |
| Panel | `SpatialPanel` at ~1.75 m, ~30 characters per line, 24 dp padding, 56 dp+ touch targets for any control on it; on the S25 Ultra the same composable fills the 2D screen | Android XR visual-design numbers in `docs/stack.md` §3-R4 |
| Anchoring | P0: head-locked in front of the user; P1 (Sun 09:00): placed where the user was looking at `captureStill()` time (R3 `HeadPoseSource.pose` sampled by R1 at capture), stays world-locked until the next capture | FR-T2 "anchored where the user was looking" |
| Content | the VLM text as soon as `describe` returns (before TTS starts); the latest `Cues` sentence in Navigation; `Speaker.locale` picks nothing here (the text is already in the right language) | FR-T1/FR-D1 |
| Auto-scroll | collect `Speaker.progress`; on each `SpeechProgress` with `done = false` highlight `[charStart, charEnd)` (bold + a 4 dp left bar, never color alone) and scroll so that line sits in the upper third; `done = true` freezes the view; `stop()` freezes it where it was | SRS §8 R4 "auto-scroll synced to speech"; `UtteranceProgressListener.onRangeStart` is only delivered when the engine supplies timing, so the panel must also work with `onStart` + `onDone` alone (highlight the whole text) |
| TalkBack | `contentDescription = R.string.a11y_transcript_panel`; NOT a live region (the Speaker is already the audio twin; a live region would double-speak) | NFR accessibility |
| Honesty | no number is rendered that the panel did not get from a flow; the panel never shows a placeholder transcript | INV-7 |

## TalkBack checklist (P1, Sun 09:00)

- [ ] Every control carries a `contentDescription` from `res/values/strings.xml` (`a11y_mic_button`, `a11y_mode_read`, `a11y_mode_describe`, `a11y_mode_navigate`, `a11y_diagnostics_toggle`, `a11y_stop_button`, `a11y_transcript_panel`); no bare `Icon(contentDescription = null)` on an actionable element.
- [ ] State is exposed, not just the label: mic button `stateDescription` = Listening / Idle / Unavailable (from `CommandBus.state`); the active mode button `selected = true` (FR-G3's visual twin).
- [ ] Focus order: mic → Text Reader → Audio Description → Navigation → Stop → Diagnostics → Transcript. Nothing is unreachable by swipe.
- [ ] Targets ≥ 48 dp (56 dp on XR), 8 dp apart.
- [ ] Diagnostics overlay values read as text, including the literal "not measured".
- [ ] `docs/stack.md` Q14: TalkBack announces controls inside a `SpatialPanel` (xr-compose beta01). Test with TalkBack on in Full Space; if no → duplicate the labels as spoken cues (new `Cues.Key`s) and record the result in `docs/stack.md` "Corrections found at build time".
- [ ] Same walkthrough on the S25 Ultra 2D fallback (ADR-0006).
- [ ] Evidence: a screen recording path + the checklist ticked in the PR (`r4: NFR-accessibility: TalkBack labels verified`).

Procedure: Settings → Accessibility → TalkBack on; swipe right through every control, listen for the label and state; double-tap each; say "stop" mid-sentence; TalkBack off.

## Blindfold test protocol (P1, Sat 21:00)

Two teammates who did not write the cues, blindfolded, headset in airplane mode, one observer with the log sheet and logcat (`adb logcat -s vysion.latency vysion`). 13 tasks: **5 read** (printed menu, wall sign, business card, poster at 2 m, a phone screen), **5 describe** (cluttered table, hallway, doorway with a person, kitchen counter, a room from the entrance), **3 guide** (door 3 m straight ahead; door at about 2 o'clock, 4 m; door behind the user after a 180° turn, FR-N4). The user may only use voice. Pass = task done without the observer speaking. Every cue the user asks about ("what did it say?") or misreads is "confusing" and gets a wording fix in `Cues.kt` the same evening.

| # | Task | Tester | Done? | Time to first word (s, `vysion.latency`) | Confusing cue (verbatim) | Fix (commit) |
|---|---|---|---|---|---|---|
| R1 | read: printed menu | | | | | |
| R2 | read: wall sign | | | | | |
| R3 | read: business card | | | | | |
| R4 | read: poster at 2 m | | | | | |
| R5 | read: phone screen | | | | | |
| D1 | describe: cluttered table | | | | | |
| D2 | describe: hallway | | | | | |
| D3 | describe: doorway with a person | | | | | |
| D4 | describe: kitchen counter | | | | | |
| D5 | describe: room from the entrance | | | | | |
| G1 | guide: door 3 m ahead | | | | | |
| G2 | guide: door at 2 o'clock, 4 m | | | | | |
| G3 | guide: door behind after 180° | | | | | |

Numbers go to `docs/benchmarks.md` ("NFR targets vs measured": first spoken word, stop latency); "not measured" until the sheet is filled.

## API cheat-sheet

Copied from `docs/stack.md` §3-R4 (sources: https://developer.android.com/reference/android/speech/SpeechRecognizer , https://developer.android.com/reference/android/speech/RecognizerIntent , https://developer.android.com/reference/android/speech/tts/TextToSpeech , https://developer.android.com/reference/android/speech/tts/Voice , https://developer.android.com/reference/android/speech/tts/UtteranceProgressListener , https://developer.android.com/develop/xr/jetpack-xr-sdk/asr ; all rows **VERIFIED** DV-PS + DV-AC unless tagged).

| Fact | Exact | Tag |
|---|---|---|
| Recognizer factories | `SpeechRecognizer.createOnDeviceSpeechRecognizer(Context)` (API 31; throws `UnsupportedOperationException` iff `isOnDeviceRecognitionAvailable(Context)` is false); `createSpeechRecognizer(Context)` (may use network; needs the manifest `<queries>`, already declared). **All methods on the main thread; `destroy()` mandatory; `RECORD_AUDIO` required.** | VERIFIED |
| Support / download | `checkRecognitionSupport(Intent, Executor, RecognitionSupportCallback)` (API 33) → `RecognitionSupport.getInstalledOnDeviceLanguages()`; `triggerModelDownload(Intent, Executor, ModelDownloadListener)` (API 34; needs network ONCE, run before airplane mode) | VERIFIED |
| Intent | `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`; `EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM` (required); `EXTRA_LANGUAGE` ("en-US" / "es-MX"); `EXTRA_PREFER_OFFLINE` (boolean, "may have no effect"); `EXTRA_PARTIAL_RESULTS` | VERIFIED |
| Results | `SpeechRecognizer.RESULTS_RECOGNITION` → `ArrayList<String>` (first = most likely); `CONFIDENCE_SCORES` → `float[]` optional (null-check) | VERIFIED |
| Errors | `ERROR_SPEECH_TIMEOUT=6, ERROR_NO_MATCH=7, ERROR_RECOGNIZER_BUSY=8, ERROR_INSUFFICIENT_PERMISSIONS=9, ERROR_LANGUAGE_NOT_SUPPORTED=12, ERROR_LANGUAGE_UNAVAILABLE=13` (the bus mirrors these in `RecognizerPort`) | VERIFIED |
| XR doc | ASR guide says SpeechRecognizer "works even when offline" but its device box lists only glasses; nothing confirms on-device ASR or an offline es-MX pack on Galaxy XR → the A-7 runtime check | VERIFIED (that it is unconfirmed) |
| TTS core | `TextToSpeech(Context, OnInitListener)`; `setLanguage(Locale): Int` (`LANG_MISSING_DATA=-1`, `LANG_NOT_SUPPORTED=-2`); `speak(CharSequence, queueMode, Bundle?, utteranceId): Int` (SUCCESS=0); `QUEUE_FLUSH=0` (this app's entries only), `QUEUE_ADD=1`; `stop()`; `setSpeechRate(Float)` (0.7f for "slower"); `shutdown()`; `getMaxSpeechInputLength()` | VERIFIED |
| Offline voice | `getVoices(): Set<Voice>` (**null before init**); `setVoice(Voice)`; `Voice.getLocale()`, `isNetworkConnectionRequired()`, `getQuality()` (100…500), `getFeatures()` (**may be null**); skip voices whose features contain `TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED` (`"notInstalled"`) | VERIFIED |
| Progress | `UtteranceProgressListener`: `onStart`, `onDone`, `onError(String)` (deprecated, still override), `onError(String, Int)`, `onStop(String, Boolean interrupted)` (fires on `stop()` / `QUEUE_FLUSH`), `onRangeStart(String, start, end, frame)` (API 26, only if the engine supplies timing); callbacks "can be called from multiple threads" | VERIFIED |
| Earcons via TTS | `addEarcon(String, packageName, resId)` + `playEarcon` — needs a resource file, so NOT used (earcons are synthesized PCM; the one asset file is `sounds/ping.ogg` for R3's SceneCore ping) | VERIFIED, unused |
| `android.media.AudioTrack` for `Earcons.play` | `AudioTrack(AudioAttributes(USAGE_ASSISTANCE_SONIFICATION), AudioFormat(PCM_16BIT, 22050 Hz, mono), bufferBytes, MODE_STATIC, sessionId)`, `write(ShortArray, 0, n)`, `play()`, `release()` | **UNVERIFIED** (not on the stack page; check the artifact per AGENTS.md §8 before relying on it; used only behind `// stack.md: UNVERIFIED`) |
| Panel numbers | 7:1 = `#FFFFFF` on `#000000` (21.0:1) / on `#121212` (18.7:1); XR targets ≥ 56 dp, icons ≥ 48 dp, panels at 1.75 m; Compose `Modifier.semantics { contentDescription = … }` | VERIFIED |

```kotlin
// CommandBus: construct ON THE MAIN THREAD (factories require it).   — docs/stack.md §3-R4, VERIFIED
val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)                    // A-7, logged at startup
val recognizer = if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) else null
fun intent(tag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)                 // "en-US" / "es-MX"
    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
}
recognizer?.setRecognitionListener(object : RecognitionListener {
    override fun onResults(results: Bundle) { results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) }
    override fun onReadyForSpeech(params: Bundle) {}      // play the LISTENING earcon
    override fun onError(error: Int) {}                   // 7 NO_MATCH, 6 SPEECH_TIMEOUT, 8 BUSY, 12/13 language
    /* onPartialResults, onBeginningOfSpeech, onRmsChanged, onBufferReceived, onEndOfSpeech, onEvent */
})
recognizer?.startListening(intent("en-US")); recognizer?.stopListening(); recognizer?.destroy()

// Speaker                                                              — docs/stack.md §3-R4, VERIFIED
val tts = TextToSpeech(ctx) { status -> ready = status == TextToSpeech.SUCCESS }
fun useLocale(locale: Locale): Boolean {                                // only after ready
    val code = tts.setLanguage(locale)
    if (code == TextToSpeech.LANG_MISSING_DATA || code == TextToSpeech.LANG_NOT_SUPPORTED) return false
    val voice = tts.voices.orEmpty()
        .filter { it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
                  it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
        .sortedWith(compareByDescending<Voice> { it.locale.country == locale.country }.thenByDescending { it.quality })
        .firstOrNull() ?: return false                                   // == VoiceSelection.pick
    return tts.setVoice(voice) == TextToSpeech.SUCCESS
}
tts.setSpeechRate(0.7f)                                                 // "slower"
tts.speak(text, if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
tts.stop()                                                              // -> onStop(id, interrupted = true); FR-G4
tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
    override fun onStart(utteranceId: String) {}
    override fun onDone(utteranceId: String) {}
    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String) {}
    override fun onError(utteranceId: String, errorCode: Int) {}
    override fun onStop(utteranceId: String, interrupted: Boolean) {}
    override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) {}   // auto-scroll transcript
})
tts.shutdown()
```

## Fallbacks

Rows in `docs/risks.md` that name R4, verbatim:

| # | Risk | Likelihood | Impact | Mitigation | Owner | Decide by |
|---|---|---|---|---|---|---|
| RK-4 | Offline speech recognition unavailable on the headset | Medium | Voice commands need network | Pinch-to-talk plus panel buttons are P0; recognition becomes P1; never demo with Wi-Fi on | R4 | Sat 14:00 |
| RK-9 | Overclaiming accessibility | Low | Judges push back on the 40-point line | The assistive-not-safety sentence appears in the pitch, README, and app onboarding | R4 | continuous |

Already wired: RK-4 → `SpeechCommandBus.state == UNAVAILABLE` at construction, `inject` keeps working; seam in core: `ModePhrasebook.voiceCommandsUnavailable` (`CuesPhrasebook` overrides it with `Cues.voiceCommandsUnavailable`), R1 speaks it once per run on UNAVAILABLE (`ModeControllerTest`). `cameraLost` and `modelNotFound` have the same seam (`ModePhrasebook.cameraLost` / `.modelNotFound`); R1 speaks them under its P1 fault-model item; RK-9 → `Cues.productSentence` (= `R.string.product_sentence`) for onboarding. R4 also supplies the strings for RK-6 (`Cues.doorNotFound`, R2/R3, Sun 09:00) and the non-spatial fallback for A-6 (`Speaker.earcon(PING)`, R3, Sat 13:00). From the fault model in `docs/nfr.md`: "TTS voice for es-MX missing → fall back to es-ES, then en-US with a spoken notice" is `VoiceSelection` + `AndroidSpeaker.setLocale`.

## How to test without the headset

- **Unit tests (no device, run in CI):** `./gradlew testDebugUnitTest --tests 'com.vysion.xr.voice.*'`. 55 tests: `CommandGrammarTest` (14), `CuesTest` (12), `AndroidSpeakerTest` (13) + `VoiceSelectionTest` (2), `SpeechCommandBusTest` (10), `EarconsTest` (4) (counts are what the skeleton ships; the Gradle report is the truth). They drive the real plumbing through `FakeTtsPort` and `FakeRecognizerPort` (defined in the test files) with `kotlinx-coroutines-test` virtual time, so the 8 s cap and the utterance watchdog take milliseconds.
- **What the fakes do:** `FakeTtsPort` records every `speak/stop/setRate/setLocale/shutdown` and lets the test fire `onStart/onRangeStart/onDone/onError/onStop` for a given utterance; `bindable` decides which locale a `setLocale` binds (A-8 scenarios; like a real engine it should answer a non-Spanish request with that locale). `FakeRecognizerPort` records sessions (their language tags) and `destroy` calls and hands the test the listener to fire `onReadyForSpeech/onResults/onError`. `NoOpEarconPlayer` swallows earcons; `RecordingEarcons` lists them.
- **`-PuseFakes=true` (laptop emulator, S25 Ultra without models):** `AppGraph` wires `FakeFrameSource` and `FakeInferenceEngine` but keeps the REAL `AndroidSpeaker` and `SpeechCommandBus` ("real on both devices; fakes inject via `CommandBus.inject`"). In the skeleton their device ports are stubs: every `say()` returns at once after logging `TODO(FR-G4) AndroidTtsPort.speak …` (so the controller never hangs), `progress` still emits `done = true` (so the transcript panel shows the text), `startListening()` logs `TODO(FR-G2)` and the 8 s cap returns the state to IDLE, and panel buttons / tests go through `inject`, which is complete. `grep -rn "TODO(" app/src/main/kotlin/com/vysion/xr/voice` is the device backlog.
- **Gates:** `bash scripts/check_cues.sh` and `scripts/check_privacy.sh` (this package has no storage, network or frame code).

## Definition of done

Per [`CONTRIBUTING.md`](../../CONTRIBUTING.md) "Definition of done for a task" (FR ids in the PR title; `./gradlew assembleDebug testDebugUnitTest` and `scripts/check_privacy.sh` + `scripts/check_cues.sh` green; device evidence; numbers in `docs/benchmarks.md`; checkbox ticked here; cue changes only in `Cues.kt`). Device evidence R4 must attach:

1. A-7 logcat line (`vysion: A-7 on-device speech recognition available=…`) with airplane mode = 1, and one `CommandEvent(source=VOICE)` reaching `ModeController` (logcat `vysion: CommandEvent(…, source=VOICE, …)` from R1's command log, then `vysion.latency: command->action <ms> ms`).
2. A-8 voice list from `AndroidTtsPort.setLocale` (name, locale, quality of every offline `es` voice) and a Spanish utterance audible on the headset.
3. Stop latency: `vysion.latency: stop→silence <ms> ms` ≤ 300 (R4 logs `AndroidSpeaker.stop()` call to the `AndroidTtsPort` `onStop` callback), ten tries, median in `docs/benchmarks.md`.
4. Proximity earcon start ≤ 300 ms after the depth sample (`vysion.latency`, with R3).
5. A screenshot path of the transcript panel at 48 dp+ and the contrast ratio measured from it.
6. The filled blindfold log sheet (P1) and the TalkBack checklist with a recording path (P1).

## Contract issues

1. **RESOLVED: `ModePhrasebook` lived in `platform`.** Before, `voice/Cues.kt` imported `com.vysion.xr.platform.ModePhrasebook`, a breach of ADR-0005. Now `ModePhrasebook` and `PromptSet`, with their `Unwired` placeholders, are in `core/Contracts.kt`, and `voice/Cues.kt` (`CuesPhrasebook`) and the tests import them from `core`. No package in `voice` imports another role's package. `AppGraph` passes `phrasebook = CuesPhrasebook.also { it.bind(speaker) }` and `prompts = LibraryPromptSet` (R2's `object LibraryPromptSet : PromptSet` in `inference/PromptLibrary.kt`) to `ModeController`, so the controller speaks the real cues.
2. **RESOLVED: `CommandBus` had no locale.** `CommandBus` now has `fun setLocale(locale: Locale) {}` with a default body, and `SpeechCommandBus.setLocale` overrides it. The language applies from the next recognition session; an open session keeps its language. `ModeController` calls it through the contract on `InSpanish`, right after `Speaker.setLocale(ES_MX)`. Both languages still parse regardless of the session language. Test: `SpeechCommandBusTest` "setLocale through the contract changes the language of the next session only (FR-G6)".
3. **RESOLVED: no `release()` on `CommandBus` / `Speaker`.** Both contracts now have `fun release() {}` with a default body. `SpeechCommandBus.release()` destroys the recognizer, and `AndroidSpeaker.release()` silences, closes the queue and calls `TtsPort.shutdown()`. Both are terminal and idempotent: the port is released once; afterwards `startListening` / `say` / `stop` / `setRate` / `setLocale` never reach it, and a late `say` returns at once. `AppGraph.release()` calls both from `MainActivity.onDestroy` when finishing. `AppGraph.stop()` (`onStop`) is now reversible: it keeps the recognizer and TTS, so taking the headset off and on does not recreate them. Tests: `AndroidSpeakerTest` "release silences, shuts the port down exactly once and makes later calls no-ops"; `SpeechCommandBusTest` (release through the contract, destroy once, no session afterwards).
4. **Documented deviation from the "value-returning stub throws `TODO()`" rule (kept).** `AndroidTtsPort.speak` returns `false` and `setLocale` returns `null` instead of throwing, because `say(Cues.welcome)` runs on the launch path (FR-G1) and the skeleton app must launch without crashing. `RecognizerPort.onDeviceAvailable` is real for the same reason (it is read in the `AppGraph` constructor) and is also the A-7 check. Each stub still logs a `TODO(FR-xx)` line under tag `vysion`.

## Dependencies needed

None. `android.media.AudioTrack`, `android.speech.*` and `android.speech.tts.*` are platform APIs; `kotlinx-coroutines-test` (tests) is already pinned in `gradle/libs.versions.toml`.
