# Benchmarks (measured on the device, never on a laptop)

Protocol (SRS §7): **20 runs per model on the headset in airplane mode; report median and p90 latency, peak memory, and model file size.** `scripts/benchmark.sh` produces the raw JSON under `bench/out/`; R2 copies the summary here and into the README before the Sun 11:00 freeze. A cell that has not been measured says **not measured**. A target that was missed is shown as a miss, not hidden (NFR "Honesty").

## Models

| Model | Quantization | Backend | File size | Load time | Median latency | p90 latency | Peak PSS | Runs | Device | Date | Airplane mode |
|---|---|---|---|---|---|---|---|---|---|---|---|
| VLM: LFM2.5-VL-450M (Software Mansion v0.11.0, `ModuleVlmRunner`) | 8da4w | XNNPACK | 648.9 MB | 3.4 s (with tokenizer parse) | dev runs, not the protocol: 7.7–7.8 s for a 60-token answer (prefill 6.0 s = vision encoder 4.5 s + 261-token decoder 1.5 s; then 36 tok/s) | not measured | not measured | 3 (dev) | Galaxy XR | 2026-10-17 | no |
| OCR: PP-OCRv6 small (Software Mansion) | int8 | XNNPACK | 23.9 MB | 0.2 s | dev runs: 1.15 s for a 960×720 sign, 3 lines read exactly | not measured | not measured | 2 (dev) | Galaxy XR | 2026-10-17 | no |
| Detector: YOLOv8n Open Images V7, 320×320 (`ml/export/export_detector_oiv7.sh`) | fp32 | XNNPACK | 14.1 MB | 2 ms (mmap) | dev runs: 95–160 ms per `detect()` incl. Kotlin letterbox + decode + NMS (960×960 input) | not measured | not measured | 3 (dev) | Galaxy XR | 2026-10-17 | no |
| VLM fallback (smaller `LlmModule`-compatible export), only if RK-2 triggers | not measured | not measured | not measured | not measured | not measured | not measured | not measured | 0 | Galaxy XR | | |

VLM median/p90 = wall time of `InferenceEngine.describe(still, READ, 60)` from the debug `BenchmarkReceiver` (a fresh `Bitmap` per run, full generation, no TTS). The input is a live `captureStill()`, so point the headset at a printed sign for the whole run; without a camera the receiver uses a synthetic dark-gray frame (`vysion.bench: input: captureStill unavailable; synthetic …`), on which READ answers "No text found" in a few tokens: such a run is not pasted here. No bundled frame exists yet (r5 page P1 item). Capture → first spoken word is the NFR row below, taken from the `vysion.latency: read capture->first-word <ms> ms` / `describe capture->first-word <ms> ms` lines (`MainActivity`, from `ModeController.firstWordLatency`; the VLM answer is spoken sentence by sentence while it generates, so this is prefill + the first sentence, not the whole answer). Detector median/p90 = wall time of one `detect()` on one frame.

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

| Step | Measured |
|---|---|
| Wi-Fi location fix (fused provider, Location on) | not measured (Location is off on the headset; a fix needs the app in the foreground, i.e. worn) |
| "where am I" recognized → first spoken word (`vysion.latency: whereami command->first-word`) | not measured (needs a wearer) |
| Offline (airplane mode): tiles from memory + template | not checked on the device (JVM tests only: `ModeControllerTest`, `CloudDescribeEngineTest`, `CuesTest`) |

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
