# Non-functional requirements

Source: SRS §5. **Every number below is a target we measure and print on the diagnostics overlay; targets we miss are reported as misses, not hidden.** The "Measured by" column is the mechanism that produces the number at runtime, and "Printed where" is where a judge sees it. A number that has no mechanism is not allowed in the README or the pitch (SRS "Honesty" row).

| Area | Requirement | Target | Measured by | Printed where | Owner |
|---|---|---|---|---|---|
| Latency | Command recognized to action started | ≤ 1.5 s | monotonic timestamp at `CommandBus` emit vs. `ModeController` state change to `CAPTURING`/`LISTENING` | logcat tag `vysion.latency`; benchmark table | R4, R1 |
| Latency | Text Reader, capture to first spoken word | ≤ 6 s | `captureStill()` return time vs. `Speaker.progress` first `onStart` | overlay "last VLM ms" + benchmark table | R1, R2, R4 |
| Latency | Audio Description, capture to first spoken word | ≤ 8 s | same as above with the describe prompt | overlay + benchmark table | R1, R2, R4 |
| Latency | Detector throughput during Navigation | ≥ 5 fps | frame counter inside `InferenceEngine.detect()`, 5 s sliding window | overlay "detector fps" | R2 |
| Latency | Proximity warning after threshold crossed | ≤ 300 ms | depth sample timestamp vs. earcon start | logcat `vysion.latency`; benchmark table | R3, R4 |
| Offline | Manifest declares no INTERNET permission; full demo runs in airplane mode | 100% of features | `PackageManager.getPackageInfo(GET_PERMISSIONS)` at runtime; CI grep (`scripts/check_privacy.sh`) | overlay "Network permission: none" | R5 |
| Memory | App resident memory with VLM loaded | ≤ 4 GB | `ActivityManager.getProcessMemoryInfo` total PSS, sampled every 1 s | overlay "memory MB" | R5, R2 |
| Memory | Detector unloaded while the VLM is generating, reloaded after | automatic | `InferenceStats.detectorLoaded` toggles inside the `Mutex`-guarded VLM call | overlay (model state) | R2 |
| Thermal | 10 min of continuous Navigation without detector dropping below 3 fps | pass | `scripts/benchmark.sh --soak 600` records fps every 5 s | benchmark table | R5, R2 |
| Privacy | Camera frames never written to storage; no analytics, no logging of frame content | enforced in code review | `scripts/check_privacy.sh` (greps for `Bitmap.compress`, `FileOutputStream` near frames, `Log.*` of pixel data); PR template checkbox | CONTRIBUTING review checklist | all |
| Accessibility | All features reachable by voice alone; transcript 48 dp+ at 7:1 contrast; TalkBack labels on every control | 100% | R4 blindfold test (5 read, 5 describe, 3 guide); TalkBack walkthrough | `docs/modules/r4-voice-accessibility.md` test log | R4, R1 |
| Reliability | Any failed inference speaks an error within 2 s and returns to idle; the app is never silent for more than 10 s during a task | 100% | `ModeController` catches `InferenceException`, calls `Speaker.say(Cues.error)`; watchdog timer in `INFERRING` state | logcat `vysion.reliability` | R1, R4 |
| Reproducibility | Fresh clone to running app on the headset using README only, with pinned ExecuTorch and Jetpack XR versions and a model download script | ≤ 30 min | a teammate who did not write it follows README on a clean machine, timed | README "Setup" + `docs/setup.md` | R5 |
| Honesty | No hardcoded numbers on the overlay or in the pitch | 100% | every overlay field is bound to a `StateFlow` that is `null` until measured; `null` renders as "not measured" | overlay | R5 |

## Fault model (what the app does when something fails)

| Fault | Tolerated? | Behavior | Blast radius |
|---|---|---|---|
| VLM inference throws or times out (> 15 s) | yes | speak `Cues.inferenceFailed` within 2 s, return to `IDLE`, keep the frame for one retry on "repeat" | one command |
| Detector throws | yes | detector marked unloaded, Navigation continues on depth only (SRS fallback), overlay shows "detector: off" | Navigation outlines |
| Camera stream stops (no frame for 2 s) | yes | speak `Cues.cameraLost`, `FrameStats` goes `null`, mode stays usable for voice | Read/Describe/detector |
| ARCore depth missing or noisy | yes | proximity falls back to plane hit tests and detector box height; only distances we trust are spoken | proximity cue |
| On-device speech recognition unavailable | yes | pinch-and-hold and panel buttons remain the P0 path; recognition becomes P1 | voice commands |
| TTS voice for es-MX missing | yes | fall back to es-ES, then en-US with a spoken notice | Spanish output |
| Memory pressure (PSS > 3.5 GB) | yes | unload detector, refuse new VLM call with `Cues.busy` until PSS < 3 GB | Navigation |
| Headset tracking loss during demo | partial | switch to recorded backup video; S25 Ultra as second device | live demo |
| Process death | no recovery of state | app restarts to `IDLE` with the welcome; no persisted state exists to corrupt | session |

## What is deliberately not measured

- Battery drain per mode (no requirement in the SRS; the demo is 90 s).
- Accuracy of the VLM text (no ground truth; the honesty rule says we show the transcript, not a score).
