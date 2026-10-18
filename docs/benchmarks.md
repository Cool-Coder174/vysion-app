# Benchmarks (measured on the device, never on a laptop)

Protocol (SRS §7): **20 runs per model on the headset in airplane mode; report median and p90 latency, peak memory, and model file size.** `scripts/benchmark.sh` produces the raw JSON under `bench/out/`; R2 copies the summary here and into the README before the Sun 11:00 freeze. A cell that has not been measured says **not measured**. A target that was missed is shown as a miss, not hidden (NFR "Honesty").

## Models

| Model | Quantization | Backend | File size | Load time | Median latency | p90 latency | Peak PSS | Runs | Device | Date | Airplane mode |
|---|---|---|---|---|---|---|---|---|---|---|---|
| VLM: LFM2.5-VL-450M (Software Mansion v0.11.0, `ModuleVlmRunner`) | 8da4w | XNNPACK | 648.9 MB | 3.4 s (with tokenizer parse) | dev runs, not the protocol: 7.7–7.8 s for a 60-token answer (prefill 6.0 s = vision encoder 4.5 s + 261-token decoder 1.5 s; then 36 tok/s) | not measured | not measured | 3 (dev) | Galaxy XR | 2026-10-17 | no |
| OCR: PP-OCRv6 small (Software Mansion) | int8 | XNNPACK | 23.9 MB | 0.2 s | dev runs: 1.15 s for a 960×720 sign, 3 lines read exactly | not measured | not measured | 2 (dev) | Galaxy XR | 2026-10-17 | no |
| Detector: YOLOv8n Open Images V7, 320×320 (`ml/export/export_detector_oiv7.sh`) | fp32 | XNNPACK | 14.1 MB | 2 ms (mmap) | dev runs: 95–160 ms per `detect()` incl. Kotlin letterbox + decode + NMS (960×960 input) | not measured | not measured | 3 (dev) | Galaxy XR | 2026-10-17 | no |
| Detector: same model, FP32 (`detector` row), paired with the int8 row below | fp32 | XNNPACK | 14.1 MB | 22 ms (mmap + `loadMethod`) | dev runs, not the protocol: 93.0 ms per `detect()` (960×960 input) | 111 ms | not measured | 30 (dev) | Galaxy XR | 2026-10-18 | no |
| Detector: same model, int8 (`detector-int8` row, `export_detector_oiv7.sh --int8`; the shipping default since 2026-10-18) | int8 (static 8-bit activations, per-channel 8-bit weights, decode fp32) | XNNPACK | 3.7 MB | 12 ms (mmap + `loadMethod`) | dev runs, not the protocol: 55.0 / 54.0 ms per `detect()` (960×960 input) | 70 / 62 ms | not measured | 2 × 30 (dev) | Galaxy XR | 2026-10-18 | no |
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
headroom question for any speed spent: a larger input or a door-specific threshold. **Shipping default: int8 since
2026-10-18** (Isaac's call; `app/build.gradle.kts` `DETECTOR`). `detector.pte` (FP32) is the fallback when
`detector-int8.pte` is missing, refused or will not load; `-Pdetector=detector` builds FP32-first. Whether int8's saved
time buys door recall back: next section.

## Detector input size vs door recall (2026-10-18)

The question: int8 at 320 saved ~40 ms per `detect()`; does spending it on a larger input (or a lower door threshold)
buy back door recall? Same model (YOLOv8n Open Images V7) exported at 416, 480 and 640
(`export_detector_oiv7.sh --imgsz N`, FP32, and `--int8` at 416 and 480 with **100** calibration images: the first 100
by file name of the 300-image validation set, so a first look, not the file to ship).

**Latency on the headset.** Same smoke as above (960×960 `scene.png`, one warm-up broadcast, then `--ei runs 30` with its
own untimed warm-up call; nobody wearing it; Wi-Fi on; dev runs, not the SRS §7 protocol). Each size is its own debug
build (`-Pdetector=<row>`) whose bundled lockfile adds a test row with that size's `sha256`, `bytes` and
`input_width/height` (the app letterboxes to the row's size; nothing else changes). Legs run back to back, each
bracketed by the shipping `detector-int8` (320) build. Galaxy XR SM-I610, thermal status 0 before and after every leg.

| Session | Leg | Row loaded | Input | Median | p90 | Min | Max |
|---|---|---|---|---|---|---|---|
| 09:01–09:05 | 1 | `detector-int8` | 320 int8 | 54.5 ms | 59 ms | 52 ms | 69 ms |
| | 2 | test row | 416 FP32 | 137.0 ms | 146 ms | 127 ms | 150 ms |
| | 3 | test row | 480 FP32 | 174.0 ms | 184 ms | 162 ms | 188 ms |
| | 4 | test row | 640 FP32 | 299.5 ms | 304 ms | 269 ms | 308 ms |
| | 5 | `detector-int8` | 320 int8 | 55.0 ms | 63 ms | 51 ms | 72 ms |
| 10:09–10:12 | A | `detector-int8` | 320 int8 | 55.0 ms | 64 ms | 52 ms | 73 ms |
| | B | test row | **416 int8** | **78.0 ms** | 92 ms | 73 ms | 106 ms |
| | A | `detector-int8` | 320 int8 | 54.5 ms | 66 ms | 51 ms | 79 ms |
| | C | `detector` (`-Pdetector=detector`) | 320 FP32 | 94.5 ms | 105 ms | 87 ms | 111 ms |
| 10:23–10:25 | A | `detector-int8` | 320 int8 | 55.5 ms | 60 ms | 52 ms | 71 ms |
| | B | test row | **480 int8** | **97.5 ms** | 114 ms | 88 ms | 118 ms |
| | A | `detector-int8` | 320 int8 | 55.0 ms | 58 ms | 51 ms | 68 ms |

The 320 int8 legs stay within 54.5–55.5 ms across the three sessions, so the sizes are comparable. Model load (mmap +
`loadMethod`) is 11–28 ms at every size. File size barely moves with input size (FP32 14.08–14.16 MB, int8 3.74–3.77
MB): the extra cost is activations, not weights.

**Door recall vs time (laptop recall, headset time).** `compare_detectors.py` (ExecuTorch runtime in Docker, the app's
decode) on every Open Images V7 *test* image with a door: 546 images, 787 door boxes (all downloaded this time, so the
320 numbers differ slightly from the 544-image table above). A detection counts at IoU ≥ 0.5 with a door box. Recall %
(hits) and precision % per confidence floor. 0.35 is what "find the door" uses today (`DoorDetections.MIN_CONFIDENCE`
in `ModeController.kt`); 0.25 is the decode floor for every class (`YoloPostProcessor.DEFAULT_CONF_THRESHOLD`).

| Input | Weights | `detect()` median on the headset | Door @ 0.35 | Door @ 0.25 | Door @ 0.15 | Door @ 0.10 |
|---|---|---|---|---|---|---|
| 320 | int8 (shipping) | 55 ms | 6.2% (49), P 79.0% | 9.0% (71), P 74.0% | 13.5% (106), P 63.1% | 18.6% (146), P 57.5% |
| 320 | FP32 | 94.5 ms | 6.9% (54), P 80.6% | 9.7% (76), P 72.4% | 15.8% (124), P 62.3% | 19.4% (153), P 57.1% |
| 416 | int8 | 78.0 ms | 9.0% (71), P 71.0% | 12.5% (98), P 65.8% | 18.6% (146), P 61.6% | 23.6% (186), P 54.7% |
| 416 | FP32 | 137.0 ms | 8.9% (70), P 68.6% | 13.0% (102), P 64.6% | 19.2% (151), P 59.0% | 24.3% (191), P 54.3% |
| 480 | int8 | 97.5 ms | 9.8% (77), P 71.3% | 13.1% (103), P 66.9% | 20.1% (158), P 56.2% | 24.4% (192), P 53.2% |
| 480 | FP32 | 174.0 ms | 11.4% (90), P 71.4% | 15.0% (118), P 66.3% | 22.7% (179), P 56.3% | 28.2% (222), P 52.5% |
| 640 | FP32 | 299.5 ms | 13.7% (108), P 73.0% | 19.2% (151), P 66.5% | 26.2% (206), P 56.6% | 32.1% (253), P 51.2% |

int8 tracks FP32 closely at 416 (≤ 0.7 points of door recall at every floor; door score |FP32 − int8| median 0.032,
p90 0.088) but loses more at 480 (1.6–3.8 points; median 0.045, p90 0.127), probably the 100-image calibration;
re-export with the recipe's 300 images before shipping either. Person recall rises the same way (int8 at 0.25: 21.4%
at 320, 27.2% at 416, 28.3% at 480, of 173). Every image here has a door, so "false" door boxes include unlabeled or
loosely boxed doors; precision is a floor, not the wearer's false-alarm rate.

**What it buys.** The door threshold is free; the input size costs time:

- Threshold only, 320 int8: a door floor of 0.15 instead of 0.35 takes "find the door" from 6.2% to 13.5% recall at no
  latency cost; door precision 79% → 63% (false door boxes 13 → 62 over 546 door photos).
- **416 int8 + door floor 0.15 (recommended):** 18.6% recall at the find-the-door gate, 3× today's 6.2%, for 78 ms per
  `detect()`: 23 ms more than today's 320 int8, still 16.5 ms faster than the 320 FP32 build that shipped until this
  morning. Door precision 61.6% (91 false door boxes over 546 door photos, about 1 in 6 images).
- 480 int8 + 0.15: 20.1% for 97.5 ms, i.e. about FP32-320's time; +1.5 points over 416 for +19.5 ms. Not worth it with
  this calibration; worth one re-check after a 300-image calibration (FP32-480 reaches 22.7%).
- 640: the best recall (26.2% at 0.15 FP32), but 299.5 ms FP32 is over the 200 ms a 5 fps Navigation loop allows
  (NFR detector ≥ 5 fps); int8-640 was not built.

What a switch needs (not done here; changing the shipping default is Isaac's call): (1) re-export int8 at 416 with 300
calibration images and paste its `sha256`/`bytes` with `input_width/height = 416` into the `detector-int8` row (or a
new row plus `-Pdetector=`); (2) a door-only floor: decode at 0.15 for the door class while every other class keeps
0.25, and `DoorDetections.MIN_CONFIDENCE` 0.35 → 0.15. A 0.20 door floor was not measured. The 416 int8 file timed here:
3,758,692 B, sha256 `dbd34378…e098e2bb54`.

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
| Second worn run (10:14, 4 tiles: fix near a corner; 1,260,326 bytes) | fix 1.23 s; download 416 ms, decode 606 ms, finder 296 ms; Gemini first chunk 1.15 s; **3.78 s** command → first word, cloud |
| Third and fourth worn runs (10:41, 11:02; same 4 tiles, main 3493450 / 7161952) | fix 1.32 s / 1.25 s; download 367 / 384 ms, decode 608 / 680 ms, finder 287 / 329 ms; Gemini first chunk 1.13 / 0.92 s; **3.79 s / 3.71 s** command → first word, cloud |
| Fifth worn run (11:21, main 7161952) | fix 1.44 s; 4 tiles downloaded 431 ms, decode 542 ms, finder 248 ms; Gemini 0.86 s; **3.68 s**, cloud |
| Asked again 20 s later (fix cached, tiles in memory) | fix 4 ms; no download; finder 251 ms; Gemini 0.84 s; **1.22 s** command → first word, cloud |
| Offline (airplane mode): tiles from memory + template | not checked on the device (JVM tests only: `ModeControllerTest`, `CloudDescribeEngineTest`, `CuesTest`); skipped by Isaac before the demo, which runs on Wi-Fi |

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
| `c2278a8`: plain `Module`, prompt in code (en/es, transcribe, no timestamps, suppress blank), language bias 1 toward the app's language, 30 s preprocessor, 4 threads | 41 / 45 | 28 / 42 | 0 | EN 1.54 / 1.79 / 1.89 s · ES 1.62 / 1.80 / 1.88 s |
| Same, language bias 0 | 41 / 45 | 26 / 42 | 0 | EN 1.52 / 1.79 / 1.89 s · ES 1.62 / 1.79 / 1.98 s |
| Same, the app's language always | 41 / 45 | 28 / 42 | 0 | EN 1.53 / 1.80 / 1.89 s · ES 1.62 / 1.80 / 1.87 s |
| **Shipped:** the `c2278a8` Whisper path; `CommandGrammar` with the Whisper-tiny aliases and exact door choices (`docs/modules/r4-voice-accessibility.md`, "Whisper-tiny aliases") | **41 / 45** | **36 / 42** | 0 | **EN 1.50 / 1.76 / 1.84 s · ES 1.58 / 1.75 / 1.84 s** |

Where the time goes (shipped path, 87 clips, `vysion.latency: asr transcribe_ms=… preprocess_ms=… encoder_ms=…
decode_ms=… steps=…`): log-mel 18 ms median; encoder 0.90 s median (0.89–1.03 s; every clip is padded to 30 s, so a
short command costs the same); decoding 0.69 s median (87 ms per decoder step, 5–11 steps: 4 prompt + the words).
Silero VAD 0.56 ms median per 32 ms frame (max 1.38 ms). Whisper load (model, preprocessor, tokenizer JSON, VAD) 2.15–2.19
s, once per process, in the background.

Speech end → command recognized, by design plus the numbers above (the live microphone path needs a wearer; see
below): **pinch released or Done tapped** → command = clip end → command, 1.54 s median in English; **Talk button with
no Done** → the VAD waits 640 ms of silence (every clip: `vad_end_ms - last_speech_ms = 640`), so ≈ 2.2 s median.
The live port logs `vysion.latency: asr speech_end_to_command_ms=… end=release|silence|cap` on every command.

What `c2278a8` got wrong: English "read that" by one voice heard as "Re-plat.", "say that again" as "See you
later, good.", "in Spanish" as "It's mad at you.", "what's here" as "Whoops here."; Spanish "lee esto / lee eso" heard as
"listo", "leso", "NESTO" or "¿Le eso?" by all three voices (6 of the 14 Spanish misses), "más detalle" as "más de tarde",
"¿dónde estoy?" as "¿Dónde es toy?", "guíame" as "Nielme." / "Ria me.", "para" as "¡Bada!", and one Spanish voice
sometimes decoded as English ("Okay, I am printed."). One miss became a wrong command: "Let me take." (a "repite")
parsed as `SelectDoor(LEFT)` through a fuzzy "left".

What the shipped grammar still gets wrong (same clips, `com.vysion.xr.asrlab`, 2026-10-18): the four English misses above,
unchanged (Whisper, not the grammar); Spanish "listo" / "¿Listo?" for "lee esto" (2 voices; "listo" means "ready", so no
alias: a false Read starts a capture), "Okay, I am printed.", "here I bet." and "Let me take." (Spanish decoded as
English: now no command instead of `SelectDoor(LEFT)`), and "Was the target?" for "más detalle", which becomes a
follow-up question to the VLM, the only clip that starts something other than what was said. The aliases were taken from these same 87 clips, so
36 / 42 is an upper bound for these voices; a wearer's mishearings need a wearer.

Other checks on the headset, same day:

| Check | Result |
|---|---|
| Airplane mode on (`Active default network: none`), 12 clips | transcribed as with Wi-Fi: 1.45–1.63 s; English 5 / 6, Spanish 0 / 6 (all "lee esto / lee eso") |
| Live microphone (`ModelSmokeReceiver --es model listen` + the clip played through the headset's speaker, app in front, unworn) | **not measured**: `audioserver: App op 27 missing, silencing record` — with nobody wearing the headset Android silences the microphone; the session ran (VAD 5 s, ~1.2 ms per frame) and ended quietly as "no speech". Needs a wearer. |
| Prebuilt `larryliu0820/whisper-tiny-INT8-INT4` (ExecuTorch 1.2.0a0) | SIGSEGV in the encoder on every clip |
| `ml/export/export_asr.sh --qlinear 8da4w` (ExecuTorch 1.4.0) | ran; encoder 1.16 s; decoded garbage ("!!!!.!! the!!!", all 64 tokens, 285 ms per token) |
| Preprocessor `--max_audio_len 300` (model card) vs 30 | native heap 420 MB after load → 4.06 GB after the first clip with 300; stays ~416 MB with 30 |
| `Module.load` without a thread count (first VAD build) | "Resetting threadpool to 3 threads" on the shared XNNPACK pool the VLM and detector set to 4; fixed by loading with 4 |

## Mode behavior checked worn (Isaac, 2026-10-18, main 3493450 / 7161952)

| Check | Result |
|---|---|
| One mode at a time: find the door, then read | detector `detect ms=` lines: 29 in Navigation, 0 from 8 to 33 s after read (10:37–10:38) |
| Find the door with no door in view | no "can't find a door"; two VLM votes (7.9 s, 6.6 s), then the silent watch; only Navigation's "obstacle ahead" was heard (a desk within 1 m, cue every 2 s, 20–100 ms after the depth sample); Isaac: right (11:23) |
| Read after find the door | first word 1.94 s after capture; Navigation stopped (1 detector line, before the stop); the text left the window when the voice ended; Isaac: right (11:24) |

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
