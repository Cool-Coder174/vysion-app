# Vysion XR

**Read a sign, understand a room, find the door. On a headset, with every model running on the headset, without sending a single frame to a server.**

Vysion XR is a native Android XR app for the Samsung Galaxy XR with three voice-driven modes: **Text Reader**, **Audio Description** and **Navigation**. A vision-language model (4-bit; the exact model is decided Sat 11:00, `plan.md` Q6, default Gemma 3 4B via optimum-executorch) and a light object detector (YOLO26-n, int8) run entirely on the headset through [ExecuTorch](https://github.com/pytorch/executorch) with the XNNPACK backend. Everything runs without a connection, so the demo works in airplane mode; network is allowed only for optional features that have an on-device fallback (ADR-0010). A live diagnostics panel shows measured latency, memory and backend.

> **Vysion XR helps you read, understand, and move through a space. It is not validated for unassisted travel by blind users.**

Built by a team of five at the ExecuTorch Hackathon (Hacker//Squad, San Francisco, Oct 17–18 2026).

**Status: skeleton.** This repository currently contains the contracts, stubs, fakes, scripts and documentation that let five people (or five coding agents) start their modules in parallel. Nothing has run on a Galaxy XR yet; every number below says "not measured" until it is measured on the device ([`docs/benchmarks.md`](docs/benchmarks.md)). The roadmap with gates is [`plan.md`](plan.md).

## What it does

| Mode | Say | What happens | Output |
|---|---|---|---|
| Text Reader | "Vysion, read this" (or pinch-and-hold) | One world-camera frame → VLM with the read-verbatim prompt | Spoken text within 6 s; large-print transcript panel; "repeat", "slower", "in Spanish" |
| Audio Description | "Vysion, describe" / "what's around me" | One frame → VLM with the describe prompt (3 sentences, hazards first, left to right) | Spoken within 8 s; follow-up questions reuse the same frame |
| Navigation | "Vysion, guide me" / "find the door" | Continuous depth + planes (ARCore for Jetpack XR) + detector at ≥ 5 fps; VLM one-shot locates the door; a native anchor holds it | Spatial-audio pings from the target, "door, 2 o'clock, 3 meters", outline in passthrough, proximity warning under 1 m |

Design rules (from the SRS): every visual element has an audio twin; voice first, gestures second, buttons third; clock-face bearings and meters; hazards before descriptions; "stop" interrupts anything within 300 ms; nothing is spoken that was not measured or seen.

## Demo

- Live demo script (90 s, three modes, one airplane-mode moment): [`docs/demo.md`](docs/demo.md)
- Backup video: **not recorded yet** (gate 9, Sun 12:00)
- Three-slide pitch: problem · architecture · measured results

## Architecture

Four layers in one Kotlin process. Input comes from Android XR's own camera, perception and speech stacks; Control decides what to run; two ExecuTorch models answer; Output is speech first, visuals second. No layer has a path to the network. Full diagrams, flows and the state machine: [`docs/architecture.md`](docs/architecture.md).

```mermaid
flowchart LR
  subgraph Input
    CAM[World camera<br/>CameraX, id 0]
    AR[ARCore for Jetpack XR<br/>depth · planes · anchors · hands]
    SR[SpeechRecognizer<br/>on-device]
  end
  subgraph Control
    MC[ModeController]
    NE[NavigationEngine]
  end
  subgraph ExecuTorch["ExecuTorch · XNNPACK"]
    VLM[VLM 4-bit · plan.md Q6]
    DET[YOLO26-n int8]
  end
  subgraph Output
    TTS[TextToSpeech]
    SA[Spatial audio]
    UI[Passthrough UI]
    DX[Diagnostics]
  end
  CAM --> MC --> VLM --> MC
  CAM --> NE --> DET --> NE
  AR --> NE
  SR --> MC
  MC --> TTS
  MC --> UI
  MC --> DX
  NE --> SA
  NE --> UI
```

| Component | Runs where | Library |
|---|---|---|
| VLM (read, describe, door, arrival) | headset CPU | ExecuTorch `LlmModule` (multimodal), XNNPACK |
| Detector (people, obstacles) | headset CPU | ExecuTorch `Module`, XNNPACK |
| Depth, planes, anchors, hands, head pose | headset | ARCore for Jetpack XR |
| Panels, passthrough, spatial audio | headset | Jetpack Compose for XR + SceneCore |
| Speech in / out | headset | Android `SpeechRecognizer` (on-device) / `TextToSpeech` |
| Network | optional features only | `INTERNET` + `ACCESS_NETWORK_STATE` (ADR-0010); every network feature has an on-device fallback, and Read, Describe and Find the door never need it; printed on the overlay |

## Measured numbers

Protocol: 20 runs per model on the headset in airplane mode; median and p90 latency, peak memory, file size. Numbers come from the device, never from a laptop. Full tables: [`docs/benchmarks.md`](docs/benchmarks.md).

Latency here is the wall time of one engine call from the debug `BenchmarkReceiver` (VLM: `describe(still, READ, 60)` on a live `captureStill()` pointed at a printed sign, full generation, no TTS; without a camera the receiver falls back to a synthetic gray frame, and such a run is not reported). Capture → first spoken word is the target table below, taken from the `vysion.latency: read capture->first-word <ms> ms` line, which R1 still has to add (`docs/modules/r1-xr-platform.md`); until it exists that row stays "not measured".

| Model | Quantization | Backend | File size | Median `describe` / `detect` wall time | p90 wall time | Peak memory |
|---|---|---|---|---|---|---|
| VLM (`plan.md` Q6) | not measured | not measured | not measured | not measured | not measured | not measured |
| Detector (YOLO26-n COCO) | not measured | not measured | not measured | not measured | not measured | not measured |

| Target (SRS §5) | Measured |
|---|---|
| Command recognized → action ≤ 1.5 s | not measured |
| Text Reader capture → first word ≤ 6 s | not measured |
| Audio Description capture → first word ≤ 8 s | not measured |
| Detector ≥ 5 fps during Navigation | not measured |
| Proximity warning ≤ 300 ms | not measured |
| App memory with VLM loaded ≤ 4 GB | not measured |
| 10 min Navigation without dropping below 3 fps | not measured |

## Prompts

All prompts live in [`PromptLibrary.kt`](app/src/main/kotlin/com/vysion/xr/inference/PromptLibrary.kt) and nowhere else.

- **Read:** "Read all text in this image exactly as written, top to bottom, left to right. Output only the text. If there is no text, say: No text found."
- **Describe:** "Describe this scene for someone who cannot see it, in at most three sentences. Hazards first, then what is on the left, center, and right. Do not guess."
- **Door:** "Is there a door in this image? Answer with one word: left, center, right, or none."
- **Arrival:** "Is this door open or closed, and which side is the handle on? One short sentence."

## Setup: fresh clone to running app on the headset (target ≤ 30 min)

Detailed walkthrough and troubleshooting: [`docs/setup.md`](docs/setup.md). Every version is pinned in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and explained, with its source, in [`docs/stack.md`](docs/stack.md).

1. **Prerequisites:** JDK 17, Android SDK with the platform and build-tools named in `docs/setup.md`, `platform-tools` (adb). No Android Studio required.
2. **Clone and point Gradle at the SDK:**
   ```bash
   git clone https://github.com/Cool-Coder174/vysion-app.git vysion-xr && cd vysion-xr
   echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # Linux: $HOME/Android/Sdk
   ./gradlew assembleDebug testDebugUnitTest
   ```
3. **Models.** `ml/models.lock` lists every artifact with its sha256. `scripts/fetch_models.sh` downloads them into `app/src/main/assets/models/` (release builds); during development, sideload instead so the multi-GB models stay out of the APK: `scripts/push_models.sh` (it also pushes `models.lock`, which the app needs to resolve sideloaded files when the APK was built without `scripts/fetch_models.sh`). Export recipes: [`ml/README.md`](ml/README.md). Prebuilt `.pte` files will be attached to the release tag.
4. **Install on the headset** (developer mode + ADB over USB-C or Wi-Fi, see `docs/setup.md`):
   ```bash
   scripts/install_headset.sh --logcat            # add --wifi <ip>:<port> for ADB over Wi-Fi (port from the pairing screen)
   ```
5. **Airplane mode on.** Every benchmark and the demo run offline; `scripts/benchmark.sh` refuses otherwise.
6. **No headset?** `./gradlew installDebug -PuseFakes=true` wires the fake camera and fake inference engine so the app runs on an emulator or the Galaxy S25 Ultra (which also serves as the 2D fallback demo device).

### Pinned versions

Verified live on 2026-10-17 against Maven metadata and the reference docs; sources and verification records in [`docs/stack.md`](docs/stack.md) §1.

| Component | Version |
|---|---|
| ExecuTorch Android AAR `org.pytorch:executorch-android` (XNNPACK flavor, includes `LlmModule`) | **1.3.1** (PyPI `executorch==1.3.1` for exports) |
| ARCore for Jetpack XR `androidx.xr.arcore:arcore` · SceneCore · XR runtime | **1.0.0-rc02** |
| Jetpack Compose for XR `androidx.xr.compose:compose` | **1.0.0-beta01** |
| CameraX | 1.6.2 |
| Compose BOM | 2026.09.00 |
| Android Gradle Plugin / Gradle / Kotlin / JDK | 9.4.1 / 9.6.1 / 2.4.21 / 17 |
| compileSdk / minSdk / targetSdk | 37 / 34 (Galaxy XR is Android 14) / 36 |
| Android SDK packages | `platforms;android-37.0`, `build-tools;36.0.0`, `platform-tools` |

## Project layout

```
app/src/main/kotlin/com/vysion/xr/
  core/         Contracts.kt — every cross-module interface and type (the SRS handoff table as code)
  platform/     R1: camera, mode state machine, overlay, panels, device profile
  inference/    R2: ExecuTorch engine, prompts, model catalog, fakes
  perception/   R3: ARCore session, proximity, anchors, navigation cues, spatial audio
  voice/        R4: speech recognition, command grammar, TTS, cues, earcons
  diagnostics/  R5: overlay, memory sampler, manifest audit, benchmark receiver (debug)
docs/           requirements, NFRs, architecture, contracts, model plan, schedule, risks, demo,
                assumptions, benchmarks, ADRs, per-role module pages, stack pins, setup
ml/             models.lock, export recipes and scripts
scripts/        fetch_models, push_models, install_headset, benchmark, check_privacy, check_cues
plan.md         roadmap and gates (engineer-workflow); executor.md: phase specs; History/: archive
```

## Working on it

- **Humans:** [`CONTRIBUTING.md`](CONTRIBUTING.md) (branches, 15-minute merge cadence, definition of done, review checklist). Roles and tasks: [`docs/modules/`](docs/modules/). Schedule and gates: [`docs/schedule.md`](docs/schedule.md).
- **Coding agents:** [`AGENTS.md`](AGENTS.md) is the operating manual (read order, repo map, hard rules, commands, where every fact lives). `CLAUDE.md` summarizes it.
- **Hard rules, enforced by CI or review:** network only with an on-device fallback (ADR-0010); camera frames never persisted or logged; every spoken string in `voice/Cues.kt`; every prompt in `inference/PromptLibrary.kt`; every displayed number measured on the device; modules talk only through `core` contracts.
- **Backlog in code:** `grep -rn "TODO(" app/src/main/kotlin` lists every stub with its FR id.

## Limitations

- An assistive aid, not a safety device. Not validated for unassisted travel by blind users.
- English and Spanish only. No cloud, no accounts, no phone companion app.
- Depends on third-party camera access to the world-facing camera on the Galaxy XR (assumption A-1 in [`docs/assumptions.md`](docs/assumptions.md)); if unavailable, Read and Describe run on the S25 Ultra and Navigation stays on the headset with depth and planes only (no door finding, no detector outlines).
- "Find the door" needs both the headset camera (A-1) and the VLM on the headset (A-13). If either fails, door finding is cut from the demo and Navigation shows proximity and wall / floor cues only.
- Door finding is a VLM one-shot with a two-frame vote; multiple doors and a fine-tuned door detector are stretch goals.
- **VLM choice is open (decided Saturday morning).** The SRS names Gemma 4 edge through the Android multimodal runner; live verification against ExecuTorch 1.3.1 showed that export is not loadable by `LlmModule` ([`docs/stack.md`](docs/stack.md) D5). The default replacement is Gemma 3 4B via optimum-executorch; the alternative is a Kotlin port of the Gemma 4 runner. See [`docs/model-plan.md`](docs/model-plan.md).
- Navigation outlines pause while the VLM answers a one-shot command (the detector is unloaded to keep peak memory to one model).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `fetch_models.sh` exits 2 | lockfile rows still have `url: TBD` | build with `ml/export/*.sh` and run `--from <dir>`, or download the release assets |
| App says "model not found" | no `.pte` in the external files dir or assets, no lockfile on the device, or a sideloaded file failed its size/sha256 check (`adb logcat -s vysion.security`) | `scripts/push_models.sh` (dev; also pushes `models.lock`) or `scripts/fetch_models.sh` + reinstall; re-push a truncated file |
| Gradle: unsupported class file version | wrong JDK | use JDK 17 (`JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home` on the macOS hosts) |
| Jetpack XR artifact not found | `google()` repository missing or version typo | compare `gradle/libs.versions.toml` with `docs/stack.md`; run `./gradlew --refresh-dependencies` |
| No camera frames on the headset | permission not granted, or firmware does not expose camera id 0 | check logcat `vysion` for `camera 0: <w>x<h> <format> at <fps> fps (A-1 evidence)`; see A-1 fallback |
| Voice commands need Wi-Fi | on-device recognition unavailable | pinch-and-hold and panel buttons are the P0 path; never demo with Wi-Fi on |
| `benchmark.sh` refuses to run | airplane mode off | `adb shell cmd connectivity airplane-mode enable` |

## Team

| Role | Owns | Name |
|---|---|---|
| R1 XR Platform | app shell, passthrough, camera, panels, overlay, mode state machine | |
| R2 ML / ExecuTorch | model exports, `.pte` files, `InferenceEngine`, prompts, measurement | |
| R3 Spatial Perception | ARCore session, depth, planes, anchors, navigation cues, spatial audio | |
| R4 Voice & Accessibility UX | speech in/out, grammar, cues, panel spec, TalkBack, user testing | |
| R5 Integration, Docs & Demo | repo, scripts, diagnostics overlay, S25 build, README, benchmarks, video, pitch | |

## License

MIT, see [`LICENSE`](LICENSE). The SRS this repository implements is in [`docs/srs/`](docs/srs/).
