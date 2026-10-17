# Demo script, diagnostics panel, submission checklist

Source: SRS §11. The demo is **90 seconds, three modes, one airplane-mode moment, and measured numbers on screen the whole time.** The person wearing the headset and the person speaking are two different teammates.

## Demo script (90 s)

| t | Beat | Who | Lines / actions |
|---|---|---|---|
| 0:00 | Problem (10 s) | speaker | "Reading a sign, understanding a room, finding the exit. Three things a headset with cameras should do without sending a single frame to a server." |
| 0:10 | Text Reader (20 s) | wearer | Looks at a printed menu or sign, says **"Vysion, read this."** Spoken transcript plus the large-print panel on the mirror screen. Then **"in Spanish."** (P1: today it switches the voice only; the Spanish re-read needs the retained still). If A-1 failed: the wearer holds the S25 Ultra at the sign for this beat and the next (`docs/assumptions.md` A-1 row) |
| 0:30 | Audio Description (20 s) | wearer | Turns to a cluttered table, says **"Vysion, describe."** Three sentences, hazards first. |
| 0:50 | Navigation (25 s) | wearer | Says **"Vysion, find the door."** Spatial ping, "door, 2 o'clock, 3 meters", wearer walks, outline follows, arrival cue with handle side. If A-1 or A-13 failed, this beat becomes "Vysion, guide me" toward a wall: the proximity tone and "obstacle ahead" (FR-N1), plus "wall ahead" if FR-N5 (P1) landed; no door, and no outlines when A-1 failed |
| 1:15 | Proof (15 s) | speaker | Holds up the headset's airplane-mode status on the mirror, points at the diagnostics panel: model, backend, median ms, fps, memory, "Network permission: none." One sentence on the architecture slide. Done. |

Rehearse twice before Sun 13:00. Keep the whole thing under 90 s; the recorded backup is the same script.

## Diagnostics panel fields (all measured at runtime)

| Field | Source | Contract |
|---|---|---|
| Model and quantization | `InferenceEngine` metadata | `ModelInfo.name`, `ModelInfo.quantization` |
| Backend | ExecuTorch runtime delegate name | `ModelInfo.backend` |
| Last VLM latency, median of session | timing hooks around the runner call | `InferenceStats.lastVlmMs`, `InferenceStats.medianVlmMs` |
| Detector fps, 5 s window | frame counter in `detect()` | `InferenceStats.detectorFps` |
| App memory, MB | `Debug.getPss()` total PSS of the app process, every 1 s (`MemorySampler`; not rate-limited, unlike `ActivityManager.getProcessMemoryInfo`) | `DiagnosticsSnapshot.appMemoryMb` |
| Network permission | read from the manifest at runtime, prints "none" | `DiagnosticsSnapshot.networkPermission` |

A field whose value has not been measured yet renders as **"not measured"**, never as a placeholder number (NFR "Honesty").

## Three-slide pitch

1. **Problem**: the three tasks, the users, the assistive-not-safety sentence.
2. **Architecture**: the four-layer diagram from [`docs/architecture.md`](architecture.md) (export the Mermaid to PNG), "nothing leaves the headset".
3. **Measured results**: the benchmark table from [`docs/benchmarks.md`](benchmarks.md), numbers from the device, misses shown as misses.

## Submission checklist

- [ ] Repo public, `main` green, README complete with measured numbers, prompts, limitations
- [ ] `scripts/fetch_models.sh` tested from a fresh clone by someone who did not write it
- [ ] Prebuilt APK and `.pte` files attached to a release tag
- [ ] Demo video (backup) uploaded and linked from the README
- [ ] Architecture diagram exported into the README
- [ ] Three-slide pitch: problem, architecture, measured results
- [ ] Headset charged, airplane mode on, demo room scouted for a door, a sign, and a cluttered table
- [ ] Submission form filled before the deadline (confirm time and format with organizers Saturday morning)
- [ ] Team photo

## Backup video

Headset screen capture + a room camera, all three modes, same script. Record by Sun 12:00 (gate 9). Store the link in the README "Demo" section; do not commit the video to the repo.
