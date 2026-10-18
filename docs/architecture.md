# Architecture

Source: SRS §6, plus the package map and the two flows the skeleton codes against. **Four layers in one Kotlin process: input adapters feed a mode controller, the controller calls two ExecuTorch modules, and output goes to speech, spatial audio, and passthrough overlays. No layer has a path to the network.**

## Nothing leaves the headset: four layers, two ExecuTorch models

```mermaid
flowchart TB
  subgraph Input["Input (native Android XR)"]
    CAM["World camera<br/>Camera2/CameraX, camera id 0<br/>640x480 stream + still capture"]
    AR["ARCore for Jetpack XR<br/>depth, planes, anchors, hands<br/>head pose, hit tests"]
    SR["Speech in<br/>SpeechRecognizer, on-device<br/>read, describe, guide me, stop"]
  end
  subgraph Control["Control (Kotlin, coroutines)"]
    MC["ModeController<br/>Idle · Listening · Capturing · Inferring · Speaking<br/>owns one-shot commands"]
    NE["NavigationEngine<br/>fuses depth, plane labels, detections, head pose<br/>emits bearing, distance, proximity cues"]
  end
  subgraph Inference["Inference: ExecuTorch runtime, XNNPACK backend"]
    VLM["VLM 4-bit · plan.md Q6<br/>read, describe, door one-shot, arrival<br/>on demand, 6 to 8 s target"]
    DET["Detector: YOLO26-n, int8<br/>people and obstacles at ≥ 5 fps<br/>unloaded while the VLM runs"]
  end
  subgraph Output["Output (voice first)"]
    TTS["Text-to-speech<br/>en-US and es-MX; stop interrupts"]
    SA["Spatial audio<br/>ping from the anchor; SceneCore source"]
    UI["Passthrough UI<br/>outlines, transcript; Compose for XR"]
    DX["Diagnostics<br/>ms, fps, MB, backend; measured live"]
  end
  CAM -- "frames" --> MC
  CAM -- "frames (5 fps)" --> NE
  AR -- "depth, planes, pose" --> NE
  SR -- "commands" --> MC
  MC <-- "image in, text out" --> VLM
  NE <-- "frames in, boxes out" --> DET
  MC -- "cues, transcript" --> TTS
  MC --> UI
  MC --> DX
  NE -- "cues" --> MC
  NE --> SA
  NE -- "outlines" --> UI
```

Input comes from Android XR's own camera, perception, and speech stacks; Control decides what to run; the two ExecuTorch models answer; Output is speech first, visuals second. The only arrow that skips a layer is Control → Output, because inference results are spoken by Control, not by the models. Navigation cues go through `ModeController` too: `NavigationEngine` emits cues and pings, and `ModeController` speaks them (through `ModePhrasebook`) and draws the door band; R3 holds no `Speaker`.

## Package map (one package per role, one shared contract)

| Layer | Package (`com.vysion.xr.`) | Owner | Key types | Doc |
|---|---|---|---|---|
| shared | `core` | R5 (changes announced, merged first) | `Contracts.kt` (every cross-module interface and data type, including the `ModePhrasebook` and `PromptSet` seams), `Units.kt` (clock-face bearing, half-meter rounding) | [`contracts.md`](contracts.md) |
| Input + Control | `platform` | R1 | `FrameSource`, `ModeController`, `OverlayRenderer`, `platform.ui.*` panels, `Permissions` | [`modules/r1-xr-platform.md`](modules/r1-xr-platform.md) |
| Inference | `inference` | R2 | `InferenceEngine` (ExecuTorch impl), `PromptLibrary` + `LibraryPromptSet` (the `PromptSet`), `ModelCatalog`, `FakeInferenceEngine` | [`modules/r2-ml-executorch.md`](modules/r2-ml-executorch.md) |
| Input + Control | `perception` | R3 | `XrPerceptionSession`, `ProximityMonitor`, `AnchorStore`, `NavigationEngine`, `SpatialAudioPinger`, `HeadPoseSource` | [`modules/r3-spatial-perception.md`](modules/r3-spatial-perception.md) |
| Input + Output | `voice` | R4 | `CommandBus`, `CommandGrammar`, `Speaker`, `Cues` + `CuesPhrasebook` (the `ModePhrasebook`), `Earcons` | [`modules/r4-voice-accessibility.md`](modules/r4-voice-accessibility.md) |
| Output | `diagnostics` | R5 | `DiagnosticsOverlay`, `DiagnosticsCollector`, `MemorySampler`, `ManifestAudit`; debug-only `BenchmarkReceiver` (`src/debug`) | [`modules/r5-integration.md`](modules/r5-integration.md) |
| entry | (root) | R1 | `VysionApplication`, `MainActivity`, `AppGraph` (manual wiring, no DI framework; the only code that imports every role; lifecycle `start`/`stop`/`release`) | [`modules/r1-xr-platform.md`](modules/r1-xr-platform.md) |

Rules (SRS §6, "design decisions that keep five people from colliding"):

1. One `InferenceEngine` owns both models behind a `Mutex`; the detector is unloaded while the VLM generates and reloaded lazily by the next `detect()` (not eagerly after `describe`), so peak memory is one model plus the runtime.
2. Camera runs two streams: a 640x480 analysis stream for the detector and an on-demand still capture, center-cropped and resized to the VLM's input size.
3. `ModeController` is a state machine: Idle, Listening, Capturing, Inferring, Speaking, back to Idle. Navigation is a background loop that any one-shot command can interrupt and that resumes afterward.
4. Every module exposes a Kotlin `Flow` or a `suspend fun`; no module reaches into another's internals. `core/Contracts.kt` is the handoff table as code. `ModeController` speaks through `ModePhrasebook` (R4's `CuesPhrasebook`) and prompts through `PromptSet` (R2's `LibraryPromptSet`), both passed in by `AppGraph`, so `platform` never imports `voice` or `inference` (ADR-0005).

## ModeController state machine

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Listening: pinch-and-hold / mic button (FR-G2)
  Listening --> Idle: no command, or "stop"
  Listening --> Capturing: Read / Describe / FindDoor parsed
  Listening --> Idle: GuideMe → NavigationEngine.start() in background
  Capturing --> Inferring: captureStill() returned
  Capturing --> Speaking: "hold still" (FR-T5, P1)
  Inferring --> Speaking: VLM text ready, or InferenceException (speak error ≤ 2 s)
  Speaking --> Idle: utterance done, or "stop" (≤ 300 ms, FR-G4)
  note right of Idle
    Navigation loop (proximity + detector) keeps running while Idle.
    Any one-shot command pauses it (NavigationEngine.pause) and
    Speaking → Idle resumes it (NavigationEngine.resume).
  end note
```

Watchdog: `INFERRING` has a 15 s timeout; on expiry the controller speaks `Cues.inferenceFailed` and returns to `Idle` (NFR reliability: never silent > 10 s during a task; the "thinking" earcon repeats every 4 s while inferring).

## Flow 1: Text Reader, end to end (gate 5)

```mermaid
sequenceDiagram
  participant U as User
  participant CB as CommandBus (R4)
  participant MC as ModeController (R1)
  participant FS as FrameSource (R1)
  participant IE as InferenceEngine (R2)
  participant SP as Speaker (R4)
  participant TP as TranscriptPanel (R1)
  U->>CB: "Vysion, read this"
  CB-->>MC: Command.Read
  MC->>SP: earcon(THINKING)
  MC->>FS: captureStill()
  FS-->>MC: Bitmap (center-cropped to VLM input)
  MC->>IE: describe(bitmap, prompts.read)
  Note over IE: Mutex: unload detector → VLM generate (detector reloads lazily on the next detect)
  IE-->>MC: whole answer (today); describeStreaming sentence by sentence if the RK-2 median > 6 s (R1 conditional P0)
  MC->>TP: show(text) (48 dp+, 7:1)
  MC->>SP: say(text)
  SP-->>TP: progress (auto-scroll synced to speech)
  Note over MC,SP: first word ≤ 6 s after captureStill returns (vysion.latency read capture->first-word, R1 P0 item)
  U->>CB: "in Spanish"
  CB-->>MC: Command.InSpanish
  MC->>SP: setLocale(es-MX) (and CommandBus.setLocale)
  Note over MC,IE: P1, not implemented: describe(sameBitmap, prompts.translateEs) needs the retained still (r1 page, plan.md 5.2); today the still is recycled after the Read
```

## Flow 2: Find the door (gate 7)

```mermaid
sequenceDiagram
  participant CB as CommandBus (R4)
  participant MC as ModeController (R1)
  participant FS as FrameSource (R1)
  participant IE as InferenceEngine (R2)
  participant AS as AnchorStore (R3)
  participant NE as NavigationEngine (R3)
  participant SP as Speaker (R4)
  participant OR as OverlayRenderer (R1)
  CB-->>MC: Command.FindDoor
  MC->>NE: pause()
  MC->>FS: captureStill() ×2
  MC->>IE: describe(frame1, DOOR), describe(frame2, DOOR)
  alt both answers agree and are not "none" (RK-6)
    MC->>AS: placeFromBearing(LEFT|CENTER|RIGHT)
    AS-->>MC: AnchorId (depth sampled along the bearing)
    Note over AS: offset added to the head yaw at call time, after both answers (contracts.md P-1 proposes capture time)
    MC->>NE: guideTo(anchorId); resume()
    loop every 3 s while guiding (FR-N3; 2 s while the spatial ping fails, A-6)
      NE->>NE: spatial ping from the anchor (SpatialAudioPinger)
      NE-->>MC: DoorBearing(clock, distanceM, inView, pinged)
      MC->>OR: highlightDoor(DoorHighlight.boxFor(clock, inView))
      MC->>SP: earcon(PING) only if pinged == false; say(phrasebook.navigationCue) → "door, 2 o'clock, 3 meters"
    end
    NE-->>MC: Arrived (anchor within 1 m, FR-N6)
    MC->>IE: describe(frame, ARRIVAL)
    MC->>SP: say(handle side, open/closed)
    MC->>NE: clearTarget()
  else disagree, or "none"
    MC->>SP: say(Cues.doorNotFound) → "I can't find a door, turn slowly"
    MC->>NE: resume()
  end
```

## Threading model

| Work | Dispatcher | Why |
|---|---|---|
| Camera callbacks → `Flow<Bitmap>` | CameraX executor → `Dispatchers.Default` conversion | keep the camera thread free; drop frames with `conflate()` so the detector never queues |
| `InferenceEngine.describe/detect` | `Dispatchers.Default` limited to 1 via the `Mutex` | one model at a time; peak memory is one model + runtime |
| Depth sampling at 10 Hz, anchors, head pose | `Dispatchers.Default`, one `perception` scope | ARCore session APIs are called from the owning scope only |
| `Speaker`, `CommandBus` | `Dispatchers.Main` for Android TTS/SpeechRecognizer callbacks | both APIs are main-thread bound |
| Compose panels | Main | standard |

Every long-running loop is a `Job` in the `AppGraph` scope. `MainActivity.onStop` calls `AppGraph.stop()`, which cancels the `ModeController` loops and stops Navigation, the frames and the perception session but keeps the scope, the loaded models and the TTS engine, so `onStart` → `AppGraph.start()` (plus `AppGraph.startPerception(activity)`, which first joins the last perception teardown) restarts without a model reload (`InferenceEngine.warmUp()` is idempotent; the camera's analysis executor lives as long as the process). `MainActivity.onDestroy` calls `AppGraph.release()` once, only when `isFinishing`: it releases the `SpeechRecognizer`, the `TextToSpeech` engine and the ExecuTorch modules and then cancels the scope; `VysionApplication.graph` builds a fresh graph if the process is reused for a new activity. Nothing survives the process.

## Memory strategy

```
peak = runtime + max(VLM, detector) + camera buffers + Compose
       ≈ 0.2 GB + VLM resident (estimated 1.5–2.5 GB for a 2B 4-bit model, 2.5–3.5 GB for option C
         Gemma 3 4B, ml/README §3; not measured until gate 4) + 0.05 GB + 0.1 GB
       budget 4 GB (NFR), alarm at 3.5 GB PSS: DiagnosticsCollector logs `PSS alarm` under vysion.reliability;
       the operator takes the RK-2/RK-3 decision (the app does not unload or refuse on its own)
```

The number that matters is the measured VLM PSS; until it exists the overlay prints "not measured".

## Privacy boundary

- No `android.permission.INTERNET` in the manifest (CI grep + runtime audit printed on the overlay).
- Camera frames live only in memory (`Bitmap` held by the active command, recycled after). There is no code path that writes a `Bitmap` to disk; `scripts/check_privacy.sh` greps for `Bitmap.compress`, `FileOutputStream`, `MediaStore` and `Log.*` of frame data.
- The only files the app writes are the copied model files under `filesDir/models/`.
- No analytics, no crash reporter, no third-party SDK with a network path.
