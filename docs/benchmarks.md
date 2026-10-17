# Benchmarks (measured on the device, never on a laptop)

Protocol (SRS §7): **20 runs per model on the headset in airplane mode; report median and p90 latency, peak memory, and model file size.** `scripts/benchmark.sh` produces the raw JSON under `bench/out/`; R2 copies the summary here and into the README before the Sun 11:00 freeze. A cell that has not been measured says **not measured**. A target that was missed is shown as a miss, not hidden (NFR "Honesty").

## Models

| Model | Quantization | Backend | File size | Load time | Median latency | p90 latency | Peak PSS | Runs | Device | Date | Airplane mode |
|---|---|---|---|---|---|---|---|---|---|---|---|
| VLM (Gemma 4 edge multimodal) | not measured | not measured | not measured | not measured | not measured | not measured | not measured | 0 | Galaxy XR | | |
| Detector (YOLO26-n COCO) | not measured | not measured | not measured | not measured | not measured | not measured | not measured | 0 | Galaxy XR | | |
| VLM fallback (SmolVLM-500M), only if RK-2 triggers | not measured | not measured | not measured | not measured | not measured | not measured | not measured | 0 | Galaxy XR | | |

Latency for the VLM is "capture to first spoken word" for the READ prompt with 60 max tokens; for the detector it is ms per 640x480 frame.

## NFR targets vs measured

| NFR | Target | Measured | Pass? | Evidence |
|---|---|---|---|---|
| Command recognized → action started | ≤ 1.5 s | not measured | | |
| Text Reader capture → first spoken word | ≤ 6 s | not measured | | |
| Audio Description capture → first spoken word | ≤ 8 s | not measured | | |
| Detector throughput during Navigation | ≥ 5 fps | not measured | | |
| Proximity warning after threshold crossed | ≤ 300 ms | not measured | | |
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
