# Benchmarks (measured on the device, never on a laptop)

Protocol (SRS §7): **20 runs per model on the headset in airplane mode; report median and p90 latency, peak memory, and model file size.** `scripts/benchmark.sh` produces the raw JSON under `bench/out/`; R2 copies the summary here and into the README before the Sun 11:00 freeze. A cell that has not been measured says **not measured**. A target that was missed is shown as a miss, not hidden (NFR "Honesty").

## Models

| Model | Quantization | Backend | File size | Load time | Median latency | p90 latency | Peak PSS | Runs | Device | Date | Airplane mode |
|---|---|---|---|---|---|---|---|---|---|---|---|
| VLM: LFM2.5-VL-450M (Software Mansion v0.11.0, `ModuleVlmRunner`) | 8da4w | XNNPACK | 648.9 MB | 3.4 s (with tokenizer parse) | dev runs, not the protocol: 7.7–7.8 s for a 60-token answer (prefill 6.0 s = vision encoder 4.5 s + 261-token decoder 1.5 s; then 36 tok/s) | not measured | not measured | 3 (dev) | Galaxy XR | 2026-10-17 | no |
| OCR: PP-OCRv6 small (Software Mansion) | int8 | XNNPACK | 23.9 MB | 0.2 s | dev runs: 1.15 s for a 960×720 sign, 3 lines read exactly | not measured | not measured | 2 (dev) | Galaxy XR | 2026-10-17 | no |
| Detector: YOLOv8n Open Images V7, 320×320 (`ml/export/export_detector_oiv7.sh`) | fp32 | XNNPACK | 14.1 MB | 2 ms (mmap) | dev runs: 95–160 ms per `detect()` incl. Kotlin letterbox + decode + NMS (960×960 input) | not measured | not measured | 3 (dev) | Galaxy XR | 2026-10-17 | no |
| VLM fallback (smaller `LlmModule`-compatible export), only if RK-2 triggers | not measured | not measured | not measured | not measured | not measured | not measured | not measured | 0 | Galaxy XR | | |

VLM median/p90 = wall time of `InferenceEngine.describe(still, READ, 60)` from the debug `BenchmarkReceiver` (a fresh `Bitmap` per run, full generation, no TTS). The input is a live `captureStill()`, so point the headset at a printed sign for the whole run; without a camera the receiver uses a synthetic dark-gray frame (`vysion.bench: input: captureStill unavailable; synthetic …`), on which READ answers "No text found" in a few tokens: such a run is not pasted here. No bundled frame exists yet (r5 page P1 item). Capture → first spoken word is the NFR row below, taken from the `vysion.latency: read capture->first-word <ms> ms` line that R1 still has to add (r1 page P0 item); until then it stays "not measured". Detector median/p90 = wall time of one `detect()` on one frame.

## NFR targets vs measured

| NFR | Target | Measured | Pass? | Evidence |
|---|---|---|---|---|
| Command recognized → action started | ≤ 1.5 s | not measured | | |
| Text Reader capture → first spoken word | ≤ 6 s | not measured | | |
| Audio Description capture → first spoken word | ≤ 8 s | not measured | | |
| Detector throughput during Navigation | ≥ 5 fps | not measured | | |
| Proximity warning after threshold crossed | ≤ 300 ms | 16–40 ms depth copy → cue emitted (≈30 cues, 3 runs); earcon/speech not included | pass for the R3 part (development runs, Wi-Fi on, not airplane mode) | `vysion.latency: proximity cue … after the depth sample`, Galaxy XR SM-I610, 2026-10-17 |
| App resident memory with VLM loaded | ≤ 4 GB | not measured | | |
| 10 min continuous Navigation, detector ≥ 3 fps | pass | not measured | | |
| "stop" interrupts speech | ≤ 300 ms | not measured | | |

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
