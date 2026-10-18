# Benchmarks (measured on the device, never on a laptop)

Protocol (SRS §7): **20 runs per model on the headset in airplane mode; report median and p90 latency, peak memory, and model file size.** `scripts/benchmark.sh` produces the raw JSON under `bench/out/`; R2 copies the summary here and into the README before the Sun 11:00 freeze. A cell that has not been measured says **not measured**. A target that was missed is shown as a miss, not hidden (NFR "Honesty").

## Models

| Model | Quantization | Backend | File size | Load time | Median latency | p90 latency | Peak PSS | Runs | Device | Date | Airplane mode |
|---|---|---|---|---|---|---|---|---|---|---|---|
| VLM: LFM2.5-VL-450M (Software Mansion v0.11.0, `ModuleVlmRunner`) | 8da4w | XNNPACK | 648.9 MB | 3.4 s (with tokenizer parse) | dev runs, not the protocol: 7.7–7.8 s for a 60-token answer (prefill 6.0 s = vision encoder 4.5 s + 261-token decoder 1.5 s; then 36 tok/s) | not measured | not measured | 3 (dev) | Galaxy XR | 2026-10-17 | no |
| OCR: PP-OCRv6 small (Software Mansion) | int8 | XNNPACK | 23.9 MB | 0.2 s | dev runs: 1.15 s for a 960×720 sign, 3 lines read exactly | not measured | not measured | 2 (dev) | Galaxy XR | 2026-10-17 | no |
| Detector: YOLOv8n Open Images V7, 320×320 (`ml/export/export_detector_oiv7.sh`) | fp32 | XNNPACK | 14.1 MB | 2 ms (mmap) | dev runs: 95–160 ms per `detect()` incl. Kotlin letterbox + decode + NMS (960×960 input) | not measured | not measured | 3 (dev) | Galaxy XR | 2026-10-17 | no |
| Detector: same model, FP32 (`detector` row), paired with the int8 row below | fp32 | XNNPACK | 14.1 MB | 22 ms (mmap + `loadMethod`) | dev runs, not the protocol: 93.0 ms per `detect()` (960×960 input) | 111 ms | not measured | 30 (dev) | Galaxy XR | 2026-10-18 | no |
| Detector: same model, int8 (`detector-int8` row, `export_detector_oiv7.sh --int8`; only in a `-Pdetector=detector-int8` build) | int8 (static 8-bit activations, per-channel 8-bit weights, decode fp32) | XNNPACK | 3.7 MB | 12 ms (mmap + `loadMethod`) | dev runs, not the protocol: 55.0 / 54.0 ms per `detect()` (960×960 input) | 70 / 62 ms | not measured | 2 × 30 (dev) | Galaxy XR | 2026-10-18 | no |
| VLM fallback (smaller `LlmModule`-compatible export), only if RK-2 triggers | not measured | not measured | not measured | not measured | not measured | not measured | not measured | 0 | Galaxy XR | | |

VLM median/p90 = wall time of `InferenceEngine.describe(still, READ, 60)` from the debug `BenchmarkReceiver` (a fresh `Bitmap` per run, full generation, no TTS). The input is a live `captureStill()`, so point the headset at a printed sign for the whole run; without a camera the receiver uses a synthetic dark-gray frame (`vysion.bench: input: captureStill unavailable; synthetic …`), on which READ answers "No text found" in a few tokens: such a run is not pasted here. No bundled frame exists yet (r5 page P1 item). Capture → first spoken word is the NFR row below, taken from the `vysion.latency: read capture->first-word <ms> ms` / `describe capture->first-word <ms> ms` lines (`MainActivity`, from `ModeController.firstWordLatency`; the VLM answer is spoken sentence by sentence while it generates, so this is prefill + the first sentence, not the whole answer). Detector median/p90 = wall time of one `detect()` on one frame.

## Detector int8 vs FP32 (2026-10-18)

**Latency on the headset.** Debug `ModelSmokeReceiver` (`--es model detector --es image scene.png --ei runs 30`): one
untimed warm-up `detect()`, then 30 timed calls of the whole `InferenceEngine.detect()` (getPixels of the 960×960
test image, letterbox to 320, forward, decode, NMS) on 4 threads. Nobody wearing the headset, so no camera and no
Navigation loop; Wi-Fi on (dev runs, not the SRS §7 protocol). One build (`-Pdetector=detector-int8`), legs in the
order int8 → FP32 → int8 in one minute: FP32 is the same build with `detector-int8.pte` moved away, which is also the
fallback check (`detector-int8 unavailable …`, then `detector load … row=detector`). Galaxy XR SM-I610, thermal
status 0 before and after.

| Leg | Row loaded | Median | p90 | Min | Max |
|---|---|---|---|---|---|
| 1 | `detector-int8` | 55.0 ms | 70 ms | 51 ms | 75 ms |
| 2 | `detector` (FP32, fallback) | 93.0 ms | 111 ms | 89 ms | 226 ms |
| 3 | `detector-int8` | 54.0 ms | 62 ms | 51 ms | 71 ms |

An earlier A-B-A the same night with an 8-image-calibration build of the same graph gave 55.0 / 93.5 / 55.0 ms
(p90 61 / 112 / 60). One 30-run int8 leg that overlapped another app's Whisper inference read 89 ms median; it is
not in the table. Single `detect()` calls on the two door images below: int8 56 and 54 ms, FP32 115 and 100 ms.

**Same detections on the headset as on the laptop.** Two held-out Open Images test images (768×1024, pushed as PNG):
int8 found `door 0.31, person 0.30` and `door 0.27`; FP32 `door 0.33, person 0.28` and `door 0.25`, the values
`compare_detectors.py`'s decode gives in Docker for the same files.

**Recall (laptop, ExecuTorch runtime in Docker, the app's decode; not a headset number).** Every Open Images V7 *test*
image with a door (544 images, 784 door boxes: 2 of the split's 546 failed to download in that run; calibration
used the *validation* split). A detection counts when it
overlaps a ground-truth box of its label at IoU ≥ 0.5. Input 320×320, as on the headset.

| Confidence floor | Door recall FP32 | Door recall int8 | Door precision FP32 | Door precision int8 | FP32 door detections int8 reproduces | Person recall FP32 / int8 | Window recall FP32 / int8 |
|---|---|---|---|---|---|---|---|
| 0.25 (the app's) | 9.6% (75/784) | 8.9% (70/784) | 72.1% | 73.7% | 82.7% (86/104) | 22.5% / 21.4% (of 173) | 13.6% / 12.4% (of 832) |
| 0.10 | 19.4% (152/784) | 18.5% (145/784) | 56.9% | 57.3% | 90.3% (241/267) | 34.1% / 33.5% | 26.8% / 26.3% |

On anchors where FP32 scores a door ≥ 0.1, the int8 door score differs by 0.030 (median) and 0.076 (p90). On a mixed
300-image test set (210 with doors), door recall is 10.4% FP32 vs 9.7% int8 and person recall 27.4% vs 27.1%.

What this says: int8 costs about 5 of every 75 doors FP32 finds (and its door precision is no worse), for 40% less
time per `detect()`. The larger problem is FP32's own door recall on these photos at 320 px and 0.25: about 1 door in
10 (Ultralytics' own predictor at 320 agrees: 5 vs 6 doors on the first 60 images). Open Images doors are often
small or partly hidden, unlike a door in front of a wearer, so this is not the headset's recall, but it is the
headroom question for any speed spent: a larger input or a door-specific threshold. **Shipping default stays FP32**
until Isaac switches it (`app/build.gradle.kts` `DETECTOR`, or `-Pdetector=detector-int8`).

## NFR targets vs measured

| NFR | Target | Measured | Pass? | Evidence |
|---|---|---|---|---|
| Command recognized → action started | ≤ 1.5 s | not measured | | |
| Text Reader capture → first spoken word | ≤ 6 s | dev runs, not the protocol: 1.27 s and 1.72 s whole frame; 1.48 s through the reading crosshair (OCR 1.48 s on the 554×365 crop, read "Q-TEES 8925" from the box it framed) | pass in 2 development runs (Wi-Fi on, not airplane mode) | `vysion.latency` read capture → first word, worn Galaxy XR SM-I610, 2026-10-17 |
| Audio Description capture → first spoken word | ≤ 8 s | not measured | | |
| Detector throughput during Navigation | ≥ 5 fps | dev run, not the protocol: ~11 fps live (70–150 ms per frame) | pass in 1 development run (Wi-Fi on) | `vysion.latency` detect, worn Galaxy XR SM-I610, 2026-10-17 |
| Proximity warning after threshold crossed | ≤ 300 ms | 16–40 ms depth copy → cue emitted (≈30 cues, 3 runs); a later worn run 17–114 ms; earcon/speech not included | pass for the R3 part (development runs, Wi-Fi on, not airplane mode) | `vysion.latency: proximity cue … after the depth sample`, Galaxy XR SM-I610, 2026-10-17 |
| App resident memory with VLM loaded | ≤ 4 GB | not measured | | |
| 10 min continuous Navigation, detector ≥ 3 fps | pass | not measured | | |
| "stop" interrupts speech | ≤ 300 ms | not measured | | |

## Cloud Describe (ADR-0010 row; opt-in, network on)

Debug `ModelSmokeReceiver` on the 960×960 `files/smoke/scene.png` with nobody wearing the headset (the camera needs a
wearer, so capture → first spoken word with the cloud is **not measured**). Wi-Fi on, Galaxy XR SM-I610, 2026-10-18,
`gemini-3.6-flash`, thinking `minimal`, free tier (project `vysion-app`, no billing). Times are from the start of
`describeScene` (`vysion.latency: describe …`), not from capture. Runs 1–4 used an API-restricted key; runs 5–7 the
app-restricted key in `local.properties` (with `X-Android-Package` / `X-Android-Cert`).

| Run | Path | Cloud first chunk | Headset | Who answered | Note |
|---|---|---|---|---|---|
| 1 `--es model cloud` (cloud only) | JPEG 61 172 B | 0.96 s; whole answer 1.61 s | not started | cloud | |
| 2 `describe`, first request after launch | hedged | none by 7.35 s | started at 1.01 s; prefill 6.09 s; first chunk 7.35 s | headset | the fallback working: a cold, slow cloud lost to the headset |
| 3 `describe` | hedged | 1.50 s | started at 1.01 s, cancelled | cloud | the flow ends after the cancelled prefill (8.9 s); speech is still on sentence 1 then |
| 4 `describe` | hedged | 1.17 s | started at 1.00 s, cancelled | cloud | flow ends at 8.1 s, same reason |
| 5 `--es model cloud` (cloud only) | JPEG 61 172 B | 1.41 s; whole answer 1.86 s | not started | cloud | app-restricted key: 200 from the headset |
| 6 `describe` | hedged | 0.90 s | never started | cloud | whole flow 1.25 s |
| 7 `describe` | hedged | 1.15 s | started at 1.00 s, cancelled | cloud | flow ends at 8.9 s |
| offline (airplane mode) | | | | | **not checked on the device** (JVM tests only: `CloudDescribeEngineTest`) |

## Where am I (FR-N9; ADR-0010 rows "Where am I map" and "Where am I wording"; network on)

Debug smoke test (`adb shell am broadcast -a com.vysion.xr.SMOKE -n com.vysion.xr/.diagnostics.ModelSmokeReceiver --es
model whereami --ez cold true|false`) at a fixed test point (Larkin Street by the Main Library, San Francisco: the unit
tests' tile), not the wearer's location. Galaxy XR SM-I610 on Wi-Fi, unworn, 2026-10-18; development runs, not the
20-run protocol. `vysion.latency: whereami …` (`MapPlaceSource`) and `vysion.smoke: whereami …` lines; times are
wall-clock `System.nanoTime`. "Lookup" = tiles + decode + finder; "cold" = tiles dropped from memory first.

| Run | Tiles | Download (559 KB z14 tile) | Decode (`transportation_name` + `poi`) | Finder | Lookup | Gemini first chunk | Gemini whole answer |
|---|---|---|---|---|---|---|---|
| 1 cold | 1 downloaded | 359 ms | 369 ms | 134 ms | 0.87 s | 1.13 s | 1.45 s |
| 2 warm | 1 from memory | none | none | 130 ms | 0.13 s | 0.76 s | 1.08 s |
| 3 cold | 1 downloaded | 86 ms | 288 ms | 105 ms | 0.48 s | 1.45 s | 1.60 s |
| 4 warm | 1 from memory | none | none | 129 ms | 0.13 s | 2.49 s | 2.92 s |

Both wordings of run 1, as spoken: Gemini: "You are on Larkin Street. The monument Portrait of a Phenomenal Woman is 10
meters away, the Larkin Street and Grove Street bus stop is 20 meters away, and the artwork Double L Excentric Gyratory
is 30 meters away. You are 50 meters from the Grove Street crossing and 50 meters from the Fulton Street crossing."
Template (offline): "You are near Larkin Street, between Grove Street and Fulton Street. Nearby: Portrait of a
Phenomenal Woman, about 10 meters; Larkin Street & Grove Street, about 20 meters; and Double L Excentric Gyratory,
about 30 meters."

Worn, live (Isaac, 2026-10-18 07:58, Location on with Google Location Accuracy on, Wi-Fi; one run, asked through the
debug `--es model ask-whereami` broadcast, which injects the same `Command.WhereAmI` as the voice):

| Step | Measured |
|---|---|
| Wi-Fi location fix (fused provider, fresh) | 1.78 s |
| Tiles (2: the fix was within 300 m of an edge; 958,109 bytes) | download 403 ms, decode 696 ms, finder 473 ms |
| Gemini wording, first chunk | 0.89 s |
| "where am I" recognized → first spoken word (`vysion.latency: whereami command->first-word`) | **4.56 s**, worded by the cloud |
| Offline (airplane mode): tiles from memory + template | not checked on the device (JVM tests only: `ModeControllerTest`, `CloudDescribeEngineTest`, `CuesTest`) |

Found on the way: with Google Location Accuracy off, Android's network provider is `allowed=false` and the fused
provider returns nothing after its 10 s, so the wearer hears the no-fix sentence (02:03 run, 10.05 s to that sentence).
The headset needs Settings → Location → Location services → Google Location Accuracy on.

## Voice commands (ADR-0011; Whisper-tiny + Silero VAD on the headset)

`scripts/asr_clips.sh`: every phrase in `CommandGrammar.biasingPhrases` (15 English, 14 Spanish) said by three macOS
voices each (English: Samantha, Reed en-US, Daniel en-GB; Spanish: Paulina es-MX, Reed es-MX, Mónica es-ES), 16 kHz
mono with 0.5 s of silence before and 1.2 s after: 87 clips. Each runs on the headset through the debug
`ModelSmokeReceiver --es model asr`: Silero VAD in 32 ms frames until it ends the clip, Whisper on what it kept, then
`CommandGrammar.parse`. "Right" = the parsed command is the same kind as the parse of the phrase that was said.
Galaxy XR SM-I610, unworn, debug build installed as a separate package (`com.vysion.xr.asrlab`, so other sessions'
app was not replaced), development runs, 2026-10-18. Synthetic voices in silence, so these are upper bounds for a
wearer in a room. Spanish clips run as with the app in Spanish ("in Spanish" said first).

| Path (all fp32 Whisper-tiny, XNNPACK) | English right | Spanish right | Empty answers | Clip end → command: median / p90 / max |
|---|---|---|---|---|
| `AsrModule` (Whisper picks its own prompt), WAV file, 300 s preprocessor, pool at 3 threads | 37 / 43 (2 clips unread: smoke bug) | 16 / 41 (1 unread) | 21 | EN 2.43 / 2.59 / 2.99 s · ES 2.31 / 2.85 / 2.98 s |
| **Shipped:** plain `Module`, prompt in code (en/es, transcribe, no timestamps, suppress blank), language bias 1 toward the app's language, 30 s preprocessor, 4 threads | **41 / 45** | **28 / 42** | 0 | **EN 1.54 / 1.79 / 1.89 s · ES 1.62 / 1.80 / 1.88 s** |
| Same, language bias 0 | 41 / 45 | 26 / 42 | 0 | EN 1.52 / 1.79 / 1.89 s · ES 1.62 / 1.79 / 1.98 s |
| Same, the app's language always | 41 / 45 | 28 / 42 | 0 | EN 1.53 / 1.80 / 1.89 s · ES 1.62 / 1.80 / 1.87 s |

Where the time goes (shipped path, 87 clips, `vysion.latency: asr transcribe_ms=… preprocess_ms=… encoder_ms=…
decode_ms=… steps=…`): log-mel 18 ms median; encoder 0.90 s median (0.89–1.03 s; every clip is padded to 30 s, so a
short command costs the same); decoding 0.69 s median (87 ms per decoder step, 5–11 steps: 4 prompt + the words).
Silero VAD 0.56 ms median per 32 ms frame (max 1.38 ms). Whisper load (model, preprocessor, tokenizer JSON, VAD) 2.15–2.19
s, once per process, in the background.

Speech end → command recognized, by design plus the numbers above (the live microphone path needs a wearer; see
below): **pinch released or Done tapped** → command = clip end → command, 1.54 s median in English; **Talk button with
no Done** → the VAD waits 640 ms of silence (every clip: `vad_end_ms - last_speech_ms = 640`), so ≈ 2.2 s median.
The live port logs `vysion.latency: asr speech_end_to_command_ms=… end=release|silence|cap` on every command.

What it gets wrong (shipped path): English "read that" by one voice heard as "Re-plat.", "say that again" as "See you
later, good.", "in Spanish" as "It's mad at you.", "what's here" as "Whoops here."; Spanish "lee esto / lee eso" is heard as
"listo", "leso", "NESTO" or "¿Le eso?" by all three voices (6 of the 14 Spanish misses), and one Spanish voice is
sometimes decoded as English ("Okay, I am printed."). One miss became a wrong command: "Let me take." parsed as
`SelectDoor(LEFT)`.

Other checks on the headset, same day:

| Check | Result |
|---|---|
| Airplane mode on (`Active default network: none`), 12 clips | transcribed as with Wi-Fi: 1.45–1.63 s; English 5 / 6, Spanish 0 / 6 (all "lee esto / lee eso") |
| Live microphone (`ModelSmokeReceiver --es model listen` + the clip played through the headset's speaker, app in front, unworn) | **not measured**: `audioserver: App op 27 missing, silencing record` — with nobody wearing the headset Android silences the microphone; the session ran (VAD 5 s, ~1.2 ms per frame) and ended quietly as "no speech". Needs a wearer. |
| Prebuilt `larryliu0820/whisper-tiny-INT8-INT4` (ExecuTorch 1.2.0a0) | SIGSEGV in the encoder on every clip |
| `ml/export/export_asr.sh --qlinear 8da4w` (ExecuTorch 1.4.0) | ran; encoder 1.16 s; decoded garbage ("!!!!.!! the!!!", all 64 tokens, 285 ms per token) |
| Preprocessor `--max_audio_len 300` (model card) vs 30 | native heap 420 MB after load → 4.06 GB after the first clip with 300; stays ~416 MB with 30 |
| `Module.load` without a thread count (first VAD build) | "Resetting threadpool to 3 threads" on the shared XNNPACK pool the VLM and detector set to 4; fixed by loading with 4 |

## Thermal soak (`scripts/benchmark.sh --soak 600`)

| Minute | Detector fps | PSS MB | Battery % | Thermal status |
|---|---|---|---|---|
| 0 | not measured | | | |
| 5 | not measured | | | |
| 10 | not measured | | | |

## P1 test matrix (R5, Sun 10:00)

| Condition | Read | Describe | Find the door | Notes |
|---|---|---|---|---|
| Bright light | | | | |
| Office light | | | | |
| Dim light | | | | |
| Door type A (hinged, closed) | | | | |
| Door type B (glass / open) | | | | |
| Partially open door | | | | |
| Person walking across | | | | |

## How a row gets filled

1. Headset in airplane mode (`adb shell settings get global airplane_mode_on` → `1`), charged, cool.
2. `scripts/benchmark.sh --model vlm --runs 20` (then `--model detector`).
3. Paste the printed Markdown row here and in the README table; commit with `r2: bench: <model> <date>`.
4. Attach the JSON from `bench/out/` to the PR, not to the repo.

Dev runs (2026-10-17) come from the debug `ModelSmokeReceiver` on test images with Wi-Fi on; they are evidence the models run, not the SRS §7 protocol (20 runs, airplane mode), which is still owed.
