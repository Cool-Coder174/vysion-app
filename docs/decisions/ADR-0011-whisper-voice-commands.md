# ADR-0011: Voice commands are heard on the headset by Whisper, outside the inference `Mutex`

**Status:** accepted (2026-10-18; Isaac asked for voice detection that works on the headset; amends ADR-0004)

## Context

On the worn Galaxy XR every on-device `SpeechRecognizer` session failed to bind ("Bind to system recognition service
failed with error 10") and never called back, so voice commands were dead. Commit 84c3e15 added a watchdog and a
fallback to Speech Services by Google, which is still a system service and may go online when its offline language
pack is missing (ADR-0010 row "Voice commands"). ExecuTorch, which the app already ships (AAR 1.4.0), runs
Whisper; its `AsrModule` wrapper reads audio only from a 16 kHz WAV **file** and lets Whisper choose its own prompt
(`docs/roadmap-research.md` §1, `docs/stack.md` §3-R4).

## Decision

1. **Whisper first.** `voice/WhisperRecognizerPort` records 16 kHz mono PCM with `AudioRecord` (source
   `VOICE_RECOGNITION`) while the wearer talks; `voice/WhisperTranscriber` runs Whisper-tiny with plain ExecuTorch
   `Module`s: the clip goes from memory to the log-mel preprocessor as a tensor, then to the model's `encoder` and
   `text_decoder`. The prompt is set in code (`WhisperDecoding`): the language is chosen between English and Spanish
   only, then `<|transcribe|><|notimestamps|>`; the first token may not be end-of-text (Whisper's "suppress blank");
   greedy, at most 28 text tokens. The text goes to `CommandGrammar.parse` exactly as the system recognizer's did.
2. **Silero VAD ends the clip.** It ends a clip after 640 ms of silence following speech, so the Talk button needs no
   Done tap, and a clip with no speech is not transcribed (Whisper invents words for silence). Release of the pinch
   or Done still ends the clip at once. Without the VAD file, clips end on release, Done or the 7.5 s cap.
3. **The system recognizer is the fallback** (`AndroidRecognizerPort`, on-device then Speech Services by Google) when
   the Whisper files are missing, Whisper fails to load, or it fails twice in a row; and for one session when the
   microphone does not open for us.
4. **Nothing is stored.** The clip lives in memory (at most 7.5 s, 240 KB) and is dropped after the one
   transcription. No transcript is kept; logs carry durations, step counts, the chosen language, the VAD's decisions
   and the kind of command recognized, never audio or words. ADR-0003 rule 4 stands as it is.
5. **Outside the inference `Mutex` (amends ADR-0004).** Whisper runs on its own thread ("vysion-asr"), not inside
   `InferenceEngine`'s `Mutex`: a spoken "stop" must not queue behind a Describe that holds the `Mutex` for seconds.
   Both use ExecuTorch's one XNNPACK thread pool, which runs one parallel operation at a time, so a command spoken
   during a Describe is slower, not blocked. Every ASR `Module` loads with 4 threads, the count the VLM and the
   detector use: `Module.load` without a count resizes that shared pool to cores / 2 = 3, under whatever is running.
6. **Files** (`ml/models.lock`, sideloaded like every model, never committed, ADR-0007): `asr` = Whisper-tiny fp32
   (prebuilt, `encoder` + `text_decoder`), `asr-preprocessor` = 30 s log-mel (`ml/export/export_asr.sh`),
   `asr-tokenizer` = OpenAI's `tokenizer.json` (vocabulary read in Kotlin), `vad` = Silero VAD. A debug process holding
   only these (no VLM, no OCR) had ~420 MB of native heap on the headset; Whisper's own share is not measured.
7. Camera frames are unaffected (ADR-0003 rule 2); nothing here sends audio off the headset.

## Alternatives rejected

- **Keep the system recognizer first:** it never bound on the worn headset, and its fallback service can use the network.
- **`AsrModule` from the AAR:** measured on the headset with the same files, Whisper's own prompt returned nothing
  for most one-word commands ("stop", "describe", "slower") and heard Spanish as other languages; it also needs the
  clip written to a WAV file. Plain `Module` with the prompt in code fixed both and stores nothing.
- **A quantized Whisper (8da4w).** The prebuilt `larryliu0820/whisper-tiny-INT8-INT4-ExecuTorch-XNNPACK` (ExecuTorch
  1.2.0a0 nightly) crashed with SIGSEGV in the encoder on every clip on the 1.4.0 runtime; the same recipe exported
  with ExecuTorch 1.4.0 (`ml/export/export_asr.sh --qlinear 8da4w`) ran but decoded garbage at 285 ms per token, and
  its encoder saved only 0.3 s. fp32 transcribes correctly; revisit with a newer exporter.
- **The model card's 300 s preprocessor:** it planned ~3.6 GB of native memory on its first run on the headset (heap
  420 MB after load, 4.06 GB after one clip); the 30 s export stays at ~420 MB.
- **Inside the inference `Mutex`:** voice "stop" would wait for the VLM to finish.
- **An energy threshold instead of Silero:** needs calibrating per room and microphone; nobody can tune it without a
  wearer, and Silero costs about 0.5 ms per 32 ms frame on the headset.
- **Whisper-small:** larger and slower; commands are a few words. Not measured.

## Consequences

- Voice commands work with the network off: the Whisper path has no network code.
- Whisper pads every clip to 30 s, so a short command costs nearly as much as a long one (the encoder is the same
  ~0.9 s); times and accuracy are in `docs/benchmarks.md` "Voice commands".
- Spanish works without "in Spanish": the language is chosen per clip, and `CommandGrammar` parses both.
- Accuracy and latency are measured without a wearer by `scripts/asr_clips.sh` (macOS voices through the debug
  `ModelSmokeReceiver --es model asr`); a worn check is still needed for room noise and real voices.
