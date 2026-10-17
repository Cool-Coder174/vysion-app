# R1 XR Platform (Kotlin, Compose for XR, SceneCore)

## Purpose

R1 is the app shell and the state machine everything else hangs off. SRS §8: **Owns** "App shell, passthrough Full Space, permissions, camera pipeline, panels, outlines, mode state machine"; **Hands off** "`FrameSource` (analysis stream + still capture), `ModeController` skeleton, `OverlayRenderer`", due **Sat 13:00**. The skeleton below compiles and runs with the fakes on a laptop emulator or the S25 Ultra; every device-touching method (CameraX bind, still capture, passthrough surface) is a `TODO(FR-xx)` stub with the verified API written next to it, and every piece of pure logic (state machine, busy policy, watchdog, door vote, crop math, fps meter) is implemented and unit-tested.

## Files you own

| Path | What it is | Status |
|---|---|---|
| `app/src/main/kotlin/com/vysion/xr/MainActivity.kt` | `ComponentActivity`; reads `VysionApplication.graph`; requests permissions with the spoken explanation; on grant `startGraph()`: `graph.start()` once per `onStart`, then `lifecycleScope.launch { graph.startPerception(this) }` when `graph.perception != null` (joins the previous `stop()`'s perception teardown, open issue 8; `startGraph()` runs again from `onPermissionResult`, which retries a session that failed on a missing XR grant); `graph.stop()` in `onStop` (reversible), `graph.release()` in `onDestroy` when finishing; picks `SpatialShell` / `FlatShell` from `DeviceProfile.isXr` and passes R5's `DiagnosticsOverlay` into them as a slot; logs `vysion.latency` and `vysion.reliability` | real (lifecycle only) |
| `app/src/main/kotlin/com/vysion/xr/platform/CameraFrameSource.kt` | `FrameSource` on the world camera: CameraX `ImageAnalysis` 640x480 RGBA + `ImageCapture`; `FpsMeter`, `CenterCrop` helpers | stub (`start`, `stop`, `captureStill`) + pure (`FpsMeter`, `CenterCrop.rect`) |
| `app/src/main/kotlin/com/vysion/xr/platform/FakeFrameSource.kt` | `FrameSource` that paints a 640x480 gradient with a moving rectangle at 5 fps and an 896x896 synthetic still | fake (fully working) |
| `app/src/main/kotlin/com/vysion/xr/platform/PassthroughOverlayRenderer.kt` | `OverlayRenderer` as two `StateFlow`s (`detections`, `door`) the Compose overlay draws | pure (fully working) |
| `app/src/main/kotlin/com/vysion/xr/platform/ModeController.kt` | The state machine (speaks through `core.ModePhrasebook`, prompts through `core.PromptSet`); `DoorVote`; `DoorHighlight` | pure (fully working, unit-tested) |
| `app/src/main/kotlin/com/vysion/xr/platform/Permissions.kt` | Permission list (exactly the manifest strings), `missing()`, `canStart()`, `register()` | pure + Android glue |
| `app/src/main/kotlin/com/vysion/xr/platform/DeviceProfile.kt` | Runtime XR check (ADR-0006), pre-existing | real |
| `app/src/main/kotlin/com/vysion/xr/platform/ui/SpatialShell.kt` | `SpatialShell(graph, diagnostics)`: Full Space layout, `Subspace` → `SpatialRow` → overlay panel + transcript panel + `Orbiter` control panel; passthrough fallback | real (verified APIs only) |
| `app/src/main/kotlin/com/vysion/xr/platform/ui/FlatShell.kt` | `FlatShell(graph, diagnostics)`: 2D layout for the S25 Ultra; `rememberShellState` shared by both shells | real |
| `app/src/main/kotlin/com/vysion/xr/platform/ui/ControlPanel.kt` | Mic (hold), three mode buttons, stop, diagnostics toggle; TalkBack labels from `strings.xml` | real |
| `app/src/main/kotlin/com/vysion/xr/platform/ui/TranscriptPanel.kt` | 48 sp white on black (21:1), auto-scroll from `Speaker.progress` | real |
| `app/src/main/kotlin/com/vysion/xr/platform/ui/OverlayCanvas.kt` | Draws `NormalizedBox` outlines and the door highlight on a `Canvas` | real |
| `app/src/test/kotlin/com/vysion/xr/platform/ModeControllerTest.kt` | JUnit 4 + coroutines-test: `ModeControllerTest`, `DoorVoteTest`, `FrameMathTest` | tests |

## Contracts you provide / consume

| Direction | Kotlin signature (from `core/Contracts.kt`) | Other side |
|---|---|---|
| provide | `interface FrameSource { val analysisFrames: Flow<Bitmap>; val stats: StateFlow<FrameStats?>; suspend fun start(); suspend fun stop(); suspend fun captureStill(): Bitmap }` via `CameraFrameSource(Application, CoroutineScope)` / `FakeFrameSource(Application)` | R3 `XrNavigationEngine` (`analysisFrames.conflate()`), R5 `DiagnosticsCollector` (`stats`, `captureStill` for benchmarks), R1 `ModeController` |
| provide | `interface OverlayRenderer { fun showDetections(List<Detection>); fun highlightDoor(NormalizedBox?); fun clear() }` via `PassthroughOverlayRenderer()` | R1 `ModeController` forwards `NavigationEngine.detections`; the Compose `OverlayCanvas` collects the flows |
| provide (class) | `class ModeController(frames, engine, commands, speaker, navigation, anchors, headPose, overlay, scope, phrasebook = Unwired, prompts = Unwired)` with `state: StateFlow<ModeState>`, `mode: StateFlow<Mode?>`, `transcript: StateFlow<String?>`, `commandLatencyNanos`, `lastError`, `start()`, `stop()`, `speakPermissionExplanation()`, `speakPermissionDenied()` | `AppGraph`, `MainActivity`, the panels |
| consume (seam in `core`) | `interface ModePhrasebook { welcome; busy; inferenceFailed; doorNotFound; navigationUnavailable; holdStill; permissionExplanation; permissionDenied; modeAnnounced(Mode); navigationCue(NavigationCue): String? }`, default `ModePhrasebook.Unwired` | R4 `voice.CuesPhrasebook`, wired by `AppGraph` as `CuesPhrasebook.also { it.bind(speaker) }` |
| consume (seam in `core`) | `interface PromptSet { read; describe; door; arrival; translateEs?; describeBrief?; describeDetailed?; followUp(q)? }`, default `PromptSet.Unwired` | R2 `inference.LibraryPromptSet`, wired by `AppGraph` as `graph.prompts` |
| consume | `InferenceEngine.warmUp()`, `describe(bitmap, prompt)` (under `withTimeout(VLM_TIMEOUT_MS)`), `describeStreaming` (conditional P0 above, not used yet), `vlmInfo` | R2 `ExecuTorchInferenceEngine` / `FakeInferenceEngine` |
| consume | `CommandBus.commands` (one `shareIn`, two lanes: Stop and everything else), `state`, `startListening()`, `stopListening()`, `inject()`, `setLocale(ES_MX)` on "in Spanish" (FR-G6) | R4 `SpeechCommandBus` |
| consume | `Speaker.say()`, `stop()` (non-suspending, FR-G4), `earcon()` (incl. `PING` for an un-pinged `DoorBearing`, A-6), `setLocale()`, `repeatLast()`, `progress`, `locale` | R4 `AndroidSpeaker` |
| consume | `NavigationEngine.start/pause/resume/stop`, `guideTo`, `clearTarget`, `cues` (`DoorBearing.pinged`), `detections`, `isRunning` | R3 `XrNavigationEngine` / `NoOpNavigationEngine` |
| consume | `AnchorStore.placeFromBearing(HorizontalBearing): AnchorId?` (called after both door answers; the head yaw R3 uses is the call-time one, open issue 10) | R3 |
| consume | `HeadPoseSource.pose` (FR-T5, P1, not read yet) | R3 |
| consume | `DiagnosticsSource` → `@Composable DiagnosticsOverlay(source: DiagnosticsSource, modifier)`, entering `platform.ui` as the `diagnostics: @Composable () -> Unit` slot that `MainActivity` (root package) fills | R5 |

## Assumptions to verify first

From `docs/assumptions.md`, verbatim:

| # | Owner | Assumption | By | Fallback (from "If an assumption fails") |
|---|---|---|---|---|
| A-1 | R1 | CameraX delivers world-camera frames (camera id 0) to a third-party app on this Galaxy XR firmware with only the `CAMERA` permission; note resolution and format | Sat 11:00 | RK-1 fallback: Read and Describe on the S25 Ultra; Navigation stays on the headset with depth and planes only |
| A-2 | R1 | Compose for XR Full Space keeps passthrough on while our panels are shown | Sat 11:00 | Use the start-mode / passthrough-opacity API documented in `docs/stack.md`; if passthrough still drops, show panels only while a command is active |
| A-12 | R1 | The single-APK runtime check for XR capability (see ADR-0006) returns false on the S25 Ultra and the 2D fallback UI shows | Sat 20:00 | fall back to a `BuildConfig` flag set from `-PdeviceProfile=phone` (ADR-0006) |

How the skeleton prepares each check: A-1 is the `TODO(FR-T1)` in `CameraFrameSource.start()`; once frames arrive, R5's `DiagnosticsCollector` logs `vysion: camera 0: 640x480 RGBA_8888 at <fps> fps (A-1 evidence)` from `FrameSource.stats` (size and format read from each `ImageProxy`), which fills the Evidence column; A-2 is `SpatialShell.forcePassthrough` (already applies the fallback, `preferredPassthroughOpacity = 1.0f`, gated on `PASSTHROUGH_CONTROL`); A-12 is the `DeviceProfile:` line `MainActivity` logs at launch.

## Task checklist

SRS §8 R1, verbatim:

- [ ] P0 Project skeleton: Compose for XR activity, Full Space with passthrough, `CAMERA`, `RECORD_AUDIO`, `SCENE_UNDERSTANDING_*`, `HAND_TRACKING` permissions requested with spoken explanations. Sat 11:00
  Files: `MainActivity.kt`, `platform/Permissions.kt`, `platform/ui/SpatialShell.kt`, `AndroidManifest.xml` (property `XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED`) · FR: FR-G1, A-2 · Verify: `scripts/install_headset.sh --logcat`; logcat `vysion: DeviceProfile: … (Android XR)`; the six permission dialogs appear after the spoken explanation (`Cues.permissionExplanation` through `CuesPhrasebook`, wired by `AppGraph`); passthrough visible behind the transcript panel (screenshot path in the PR)
- [ ] P0 Confirm world-camera frames arrive via CameraX on the headset (camera id 0); log resolution, format, fps. Sat 12:00
  Files: `platform/CameraFrameSource.kt` (`start()` stub → bind; `LifecycleRegistry` owner snippet in its KDoc and in the cheat-sheet below) · FR: FR-T1, FR-N2, A-1, RK-1 · Also log, once, the horizontal FOV of the center-cropped still that R3's `BearingAngles` needs (stack.md Q3, corrected; nobody else can measure it): `hfov = 2·atan(SENSOR_INFO_PHYSICAL_SIZE.width / (2·LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]))` from the Camera2 characteristics of id "0", then `hfovCrop = 2·atan((H/W)·tan(hfov/2))` for the square crop of a W×H frame; `vysion: still hfov=<deg> deg (A-1)` · Verify: `adb logcat -s vysion | grep -E "camera 0:|still hfov"` prints `camera 0: 640x480 RGBA_8888 at <fps> fps (A-1 evidence)` (R5's `DiagnosticsCollector`, from `FrameSource.stats`) with fps ≥ 5, and the hfov line; fill A-1 in `docs/assumptions.md`
- [ ] P0 `FrameSource`: 640x480 analysis stream to a `Flow<Bitmap>` plus `suspend fun captureStill(): Bitmap` center-cropped and resized to the VLM input size. Sat 13:00
  Files: `platform/CameraFrameSource.kt` (`captureStill()` stub → `takePicture` + `CenterCrop.apply`), `platform/FakeFrameSource.kt` · FR: FR-T1, FR-D1, INV-9 · Verify: `./gradlew testDebugUnitTest --tests '*FrameMathTest*'` green; on the headset a Read logs `vysion.latency: command->action … ms` and `captureStill()` logs, once, `vysion: still <w>x<h> rotation=<r> -> 896x896 (A-1)` (rotation snippet in the `captureStill` KDoc; the engine logs no bitmap size); `scripts/benchmark.sh --model vlm` also prints `vysion.bench: input: FrameSource.captureStill 896x896` (R5's debug receiver calls `captureStill`); `scripts/check_privacy.sh` clean
- [ ] P0 `ModeController` state machine with a `Mutex` around inference; one-shot commands interrupt Navigation and resume it. Sat 15:00
  Files: `platform/ModeController.kt`, `app/src/test/kotlin/com/vysion/xr/platform/ModeControllerTest.kt` · FR: FR-G1, FR-G3, FR-G4, FR-T1, FR-D1, FR-D3, FR-N3, FR-N6, INV-5, INV-6, INV-9, ADR-0004 (the `Mutex` itself lives in R2's engine; R1 pauses/resumes Navigation around every VLM call) · Already in the skeleton: hazard cues on their own lane, `voiceCommandsUnavailable` once per run on `CommandBus.state == UNAVAILABLE` (A-7/RK-4), every prompt through `PromptSet.forLocale` (FR-G6), DoorVote rejects negated sides (RK-6), `markActionStarted` before the mode announcement. Still to add (blocks R3's FR-N3 evidence, R4's A-7 evidence and gate 7): `ModeController` logs every `CommandEvent` (`Log.i("vysion", event.toString())` in `onCommand`, e.g. `CommandEvent(command=Read, source=VOICE, …)`), every `NavigationCue` (`Log.i("vysion", "cue $cue")` at the top of `onCue`, e.g. `cue DoorBearing(clock=2, …)`) and the door vote (`Log.i("vysion", "door vote a=$a b=$b -> $bearing")` in `findDoor` right after `DoorVote.agree`, the parsed `HorizontalBearing?` values, never the raw answer text). None of these lines carries frame data (INV-3). Gate 7 evidence greps `adb logcat -s vysion | grep -E "door vote|anchor .* placed|cue (DoorBearing|Arrived)"`. The unit-test android.jar returns defaults, so `Log` is safe in `ModeControllerTest` · Verify: `./gradlew testDebugUnitTest --tests 'com.vysion.xr.platform.*'` green (controller tests incl. watchdog, busy policy, Stop, door vote, restart, hazard lane, "brief/detailed pick describeBrief/describeDetailed", "Arrived runs prompts.arrival once"); `adb logcat -s vysion | grep -E "CommandEvent|cue "` shows one line per command and cue; on the headset "stop" during a Read → `vysion.latency` shows `command->action` and TTS silent within 300 ms (R4's `vysion.latency: stop→silence` line)
- [ ] P0 FR-T1 / FR-D1 latency evidence: capture → first spoken word. Sat 19:00 (gate 5; R1 is FR-T1's lead, `docs/requirements.md`)
  Files: `platform/ModeController.kt` (`captureAndDescribe`), `ModeControllerTest.kt` · FR: FR-T1, FR-D1, NFR latency (docs/nfr.md: `captureStill()` return vs the first `Speaker.progress`) · How: take `t0 = System.nanoTime()` right after `frames.captureStill()` returns. Before the first `say` of the VLM text, start a collector on `speaker.progress` with `CoroutineStart.UNDISPATCHED` (`progress` has no replay, so subscribe before speaking) in the one-shot's scope; on the first emission whose `text` is the utterance being spoken (`AndroidSpeaker` emits `SpeechProgress(id, text, 0, 0, done = false)` from `onStart`), log `Log.i("vysion.latency", "read capture->first-word <ms> ms")` (`describe capture->first-word` for Describe; nothing for the arrival prompt) and stop collecting. For the unit test, also expose the value as a `StateFlow<Long?>` next to `commandLatencyNanos` and drive the test `Speaker`'s progress. Monotonic clock only (H-21) · Verify: `adb logcat -s vysion.latency | grep 'capture->first-word'` on 5 Reads of a printed sign, each ≤ 6000 ms (Describe ≤ 8000 ms) or the miss recorded; the median goes into the `docs/benchmarks.md` row "Text Reader capture → first spoken word" and the README. The overlay's "last VLM ms" is the engine's wall time, not this number
- [ ] P0 (conditional) FR-T1: speak while the VLM is still generating. Decide at gate 4 (Sat 16:00): if the RK-2 benchmark's READ median (`vysion.latency: vlm ms=… median=…`) is above 6000 ms, land it by gate 5 (Sat 19:00); otherwise it is P1
  Files: `platform/ModeController.kt` (`captureAndDescribe`, Read and Describe), `ModeControllerTest.kt` · FR: FR-T1, FR-D1 · How: call `engine.describeStreaming(still, localizedPrompt)` instead of `describe`; append tokens to a buffer; each time the buffer holds a sentence end (`.`, `?`, `!`, newline) `say(sentence)` and append it to `transcript`; speak the remainder at the end. R2's flow is buffered (`Channel.UNLIMITED`), so a suspended `say` never drops tokens. Watchdog: put only the wait for the first token under `withTimeout(VLM_TIMEOUT_MS)` (the engine already bounds generation with the same timeout inside its mutex); never count TTS time. THINKING earcon until the first sentence; Stop cancels the collection (the engine then calls `LlmModule.stop()`); a stream that ends with no text is an `InferenceException` · Verify: a `ModeControllerTest` case with `FakeInferenceEngine` (its `describeStreaming` already emits sentence by sentence, 10 ms apart) asserts that the first `say` happens before the last sentence is emitted; on the headset the `capture->first-word` line above drops below the `vlm ms` line of the same Read
- [ ] P0 Floating control panel (mic, mode, diagnostics toggle) and large-print transcript panel per R4's spec. Sat 18:00
  Files: `platform/ui/ControlPanel.kt`, `platform/ui/TranscriptPanel.kt`, `platform/ui/SpatialShell.kt`, `platform/ui/FlatShell.kt` · FR: FR-G1, FR-G2 (mic button), FR-G3 (mode shown), FR-G5 (toggle), FR-T2 · Verify: TalkBack on, every control announces its `a11y_*` label; after a Read, `adb exec-out screencap -p > transcript.png` (path in the PR) shows the answer in 48 sp white on black; the panel scrolls while TTS speaks (R4's `progress`). Optional JVM check: a test that `TranscriptPanel`'s text style has `fontSize >= 48.sp` and colours `#FFFFFF` on `#000000`
- [ ] P0 `OverlayRenderer`: detection outlines and the door highlight drawn in passthrough from normalized boxes. Sat 21:00
  Files: `platform/PassthroughOverlayRenderer.kt`, `platform/ui/OverlayCanvas.kt`, `platform/ui/SpatialShell.kt` (overlay panel; `SpatialExternalSurface` UNVERIFIED) · FR: FR-N2, FR-N3, FR-N4 · Verify: under `-PuseFakes=true` the overlay stays empty (`NoOpNavigationEngine` emits no detections; nothing collects `FakeFrameSource.analysisFrames`), so verify drawing with `ModeControllerTest` "navigation cues are spoken through the phrasebook and drive the overlay" and on the headset: a person walking across the room gets an outline that follows at ≥ 5 fps (overlay "detector fps"), the door band appears on `DoorBearing(inView = true)` and disappears on `inView = false`; a LEFT / RIGHT door (clock 11 / 1, R3's ±30°) draws the left / right third, a CENTER door (clock 12) the middle third (`DoorHighlight.boxFor`)
- [ ] P1 Anchor the transcript panel where the user was looking at capture time. Sun 09:00
  Files: `platform/ui/SpatialShell.kt` (`SubspaceModifier.offset` / anchoring from `HeadPoseSource.pose` at capture) · FR: FR-T2 (anchoring) · Verify: after a Read the panel sits in the direction the user faced at capture, not where the head is now (video clip)
- [ ] P1 Pinch-and-hold as the push-to-talk gesture via hand tracking. Sun 10:00
  Files: `platform/ui/ControlPanel.kt` (same `startListening()`/`stopListening()` pair), a pinch detector fed by R3's `XrPerceptionSession` hands (`Hand.left/right(session).state`, docs/stack.md Q15) · FR: FR-G2 (pinch) · Verify: logcat `vysion` shows `CommandEvent(source=PINCH)` (the ModeController command log above); thumb–index < 0.05 m starts listening, release stops

Skeleton-added R1 tasks from `plan.md` (not in SRS §8 R1, but no other role can do them):

- [ ] P1 plan.md 5.2: keep the last still (recycle it on the next capture or on `stop()`) and re-prompt on the same `Bitmap` for "in Spanish" (`prompts.translateEs`) and `FollowUp` (`prompts.followUp(question)`). Sat 22:00
  Files: `platform/ModeController.kt` (`lastStill`; the `TODO(FR-D2)` branch; `Command.InSpanish`), `ModeControllerTest.kt` · FR: FR-T4, FR-D2, INV-9 · Verify: `./gradlew testDebugUnitTest --tests 'com.vysion.xr.platform.ModeControllerTest'` with new tests asserting `FakeInferenceEngine.lastPrompt` and a single `captureStill` for Read then "in Spanish" / a follow-up
- [ ] P1 plan.md 6.6: hold still (FR-T5): `headPose.pose.value.angularSpeedDegPerS` above the threshold at capture → `phrasebook.holdStill`, wait, retry once. Sun 09:00
  Files: `platform/ModeController.kt` (the `@Suppress("unused") headPose` parameter), `ModeControllerTest.kt` · FR: FR-T5 (owners R3, R1; R3 provides `HeadPoseSource`) · Verify: a `ModeControllerTest` with a fake `HeadPoseSource` above the threshold speaks `holdStill` and captures once more
- [ ] P1 Fault model (docs/nfr.md): speak `phrasebook.cameraLost` when `frames.stats` has had no frame for 2 s while a one-shot needs the camera, and `phrasebook.modelNotFound` when a one-shot fails because the model is not loaded (`engine.stats.vlmLoaded == false`). Sun 09:00
  Files: `platform/ModeController.kt`, `ModeControllerTest.kt` · FR: NFR reliability; README troubleshooting "model not found" · Verify: two `ModeControllerTest` cases with `TestPhrasebook` tokens; on the headset with no `vlm.pte` a Read speaks `Cues.modelNotFound`

## API cheat-sheet

Copied from `docs/stack.md` → "R1 — camera, Compose for XR, passthrough, Full Space"; every line carries its source and verification tag. Names marked VERIFIED were additionally confirmed with `javap` against the AARs in `~/.gradle/caches` (AGENTS.md §8) on 2026-10-17.

**CameraX 1.6.2** (VERIFIED BS-PS, BS-AC; sources https://developer.android.com/reference/androidx/camera/camera2/interop/Camera2CameraInfo , https://developer.android.com/reference/androidx/camera/core/ImageProxy , https://developer.android.com/reference/kotlin/androidx/camera/lifecycle/package-summary )

```kotlin
/** Camera2 id "0" = world-facing per Google's statement (secondary source, https://skarredghost.com/2025/02/07/android-xr-camera-access-2/ ; unverified on device, A-1). */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)   // stack.md D6: lint-enforced opt-in
val worldCameraSelector: CameraSelector = CameraSelector.Builder()
    .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).getCameraId() == "0" } }
    .build()

val analysis = ImageAnalysis.Builder()
    .setResolutionSelector(ResolutionSelector.Builder()
        .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
        .build())
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)      // = 2
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)      // = 0
    .build()
    .also { ia -> ia.setAnalyzer(analysisExecutor) { image: ImageProxy -> image.use { _frames.tryEmit(it.toBitmap()) } } }

val still = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()   // = 1

suspend fun bind(owner: LifecycleOwner) {
    val provider = ProcessCameraProvider.awaitInstance(context)   // import androidx.camera.lifecycle.awaitInstance
    provider.unbindAll()
    provider.bindToLifecycle(owner, worldCameraSelector, analysis, still)
}

suspend fun captureStill(): Bitmap = suspendCancellableCoroutine { cont ->
    still.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image.use { it.toBitmap() }) }
        override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
    })
}
```
`ImageProxy.toBitmap()` supports YUV_420_888, JPEG, RGBA_8888; rotation via `imageInfo.rotationDegrees` (VERIFIED BS-AC; "added in 1.3.0" is UNVERIFIED). Hour-one probe: log `CameraManager.cameraIdList`; if id "0" yields nothing, `CameraSelector.DEFAULT_BACK_CAMERA` selects `LENS_FACING_BACK`, not literally id 0.

| Need | Exact API | Tag |
|---|---|---|
| Lifecycle owner for `bindToLifecycle` (`FrameSource.start()` has no Activity) | `LifecycleRegistry(owner: LifecycleOwner)`, `currentState` get/set (`Lifecycle.State`), `LifecycleOwner.lifecycle`; drive it on `Dispatchers.Main` only; INITIALIZED → CREATED (bind once) → STARTED in `start()`, back to CREATED in `stop()`, never DESTROYED (stop is reversible). Snippet in the `CameraFrameSource.start()` KDoc | VERIFIED (javap, lifecycle-runtime-android / lifecycle-common-jvm 2.11.0, 2026-10-17; stack.md "Corrections") |
| Upright still | read `image.imageInfo.rotationDegrees` inside `onCaptureSuccess` before the proxy closes; `Bitmap.createBitmap(raw, 0, 0, w, h, Matrix().apply { postRotate(r.toFloat()) }, true)` when `r != 0`, recycle `raw` if a new bitmap came back; then `CenterCrop.apply`. Log once `vysion: still <w>x<h> rotation=<r> -> <edge>x<edge> (A-1)` (snippet in the `captureStill()` KDoc) | API VERIFIED (BS-AC); whether Galaxy XR stills are rotated: UNVERIFIED until that line |

**Compose for XR 1.0.0-beta01** (VERIFIED XR-PS, XR-AC; API file https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/xr/compose/compose/api/1.0.0-beta01.txt ; guides https://developer.android.com/develop/xr/jetpack-xr-sdk/develop-ui , https://developer.android.com/develop/xr/jetpack-xr-sdk/check-spatial-capabilities , https://developer.android.com/develop/xr/jetpack-xr-sdk/add-environments , https://developer.android.com/develop/xr/jetpack-xr-sdk/transition-home-space-to-full-space )

| Need | Exact API | Tag |
|---|---|---|
| Session in Compose | `androidx.xr.compose.platform.LocalSession: ProvidableCompositionLocal<Session?>` | VERIFIED |
| XR vs 2D | `LocalSpatialConfiguration.current.hasXrSpatialFeature` or `SpatialConfiguration.hasXrSpatialFeature(context)`; there is **no** `LocalHasXrSpatialFeature` | VERIFIED |
| Capabilities | `LocalSpatialCapabilities.current.isSpatialUiEnabled / isContent3dEnabled / isAppEnvironmentEnabled / isPassthroughControlEnabled / isSpatialAudioEnabled` | VERIFIED |
| Containers | `Subspace(modifier, content: SpatialBoxScope.() -> Unit)`; `SpatialPanel(modifier: SubspaceModifier, shape: SpatialShape, interactionPolicy: InteractionPolicy?, content)`; `SpatialRow`, `SpatialColumn`, `SpatialBox`, `SpatialMainPanel` (`SpatialColumn` has two overloads; a bare trailing-lambda call is ambiguous, pass named args or use `SpatialRow`) | VERIFIED |
| Modifiers | `SubspaceModifier.width(dp).height(dp).size().offset().padding().movable().resizable().fillMaxWidth()` | VERIFIED |
| Orbiter | `Orbiter(position: OrbiterPosition, shape: SpatialShape = …, content)` with `OrbiterPosition.BottomCenter()`; the `OrbiterAlignment`/`OrbiterAnchorPoint`/`ContentEdge` overloads are `@Deprecated` | VERIFIED |
| Full Space | `session.scene.requestFullSpace()` / `requestHomeSpace()` (scenecore) or `suspend ComponentActivity.requestFullSpace(): SpaceRequestResult` | VERIFIED |
| Passthrough | `session.scene.spatialEnvironment.preferredPassthroughOpacity = 1.0f`; `NO_PASSTHROUGH_OPACITY_PREFERENCE` releases; `currentPassthroughOpacity`; `addPassthroughOpacityChangedListener(Consumer<Float>)`; gate on `session.scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)` | VERIFIED |
| Drawing in passthrough | `SpatialExternalSurface(stereoMode = StereoMode.Mono, modifier) { }` | **UNVERIFIED** (kept in a comment in `SpatialShell.kt`) |

```kotlin
@Composable fun VysionRoot() {
    val isXr = LocalSpatialConfiguration.current.hasXrSpatialFeature
    val caps = LocalSpatialCapabilities.current
    if (isXr && caps.isSpatialUiEnabled) {
        Subspace {
            SpatialRow {
                SpatialPanel(SubspaceModifier.width(1400.dp).height(824.dp).movable().resizable()) {
                    TranscriptPanel()                                         // 48dp+ text, #FFFFFF on #000000 = 21:1
                    Orbiter(position = OrbiterPosition.BottomCenter()) { ControlBar() }
                }
            }
        }
    } else FlatUi()                                                           // S25 Ultra fallback
}

fun forcePassthrough(session: Session) {
    val env = session.scene.spatialEnvironment
    if (session.scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)) {
        env.preferredPassthroughOpacity = 1.0f
        env.addPassthroughOpacityChangedListener { opacity -> diagnostics.passthrough = opacity }
    }
}
```

**Runtime permissions** (standard Android; constants in `androidx.xr.runtime.manifest.ManifestPermission`, VERIFIED XR-PS/XR-AC; https://developer.android.com/develop/xr/permissions , https://developer.android.com/reference/androidx/xr/runtime/manifest/ManifestPermission )

```kotlin
val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
    ManifestPermission.SCENE_UNDERSTANDING_COARSE, ManifestPermission.SCENE_UNDERSTANDING_FINE,
    ManifestPermission.HAND_TRACKING, ManifestPermission.HEAD_TRACKING)
registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants -> /* speak result, then configure() */ }.launch(perms)
```
Since runtime 1.0.0-alpha05, `Session.create`/`configure` throw `SecurityException` (no `PermissionsNotGranted` result).

**Manifest** (docs/stack.md §2.7, VERIFIED): `<property android:name="android.window.PROPERTY_XR_ACTIVITY_START_MODE" android:value="XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED" />`, `<uses-feature android:name="android.software.xr.api.spatial" android:required="false" />` (D4), all six permissions declared.

**Design numbers** (docs/stack.md → R4 row, https://developer.android.com/design/ui/xr/guides/visual-design , https://www.w3.org/WAI/WCAG22/Understanding/contrast-enhanced.html ): targets ≥ 56 dp, icons ≥ 48 dp, panels at 1.75 m; `#FFFFFF` on `#000000` = 21.0:1 (≥ 7:1). TalkBack inside a `SpatialPanel` is docs/stack.md Q14 (R4, Sun 09:00).

## Fallbacks

| # | Risk (verbatim from `docs/risks.md`) | Mitigation | Owner | Decide by |
|---|---|---|---|---|
| RK-1 | World-camera frames not available to our app on this Galaxy XR build (permission denied, blank stream, unsupported format) | Test in hour one. Fallback: run Read and Describe on the S25 Ultra from the same track; keep Navigation on the headset using ARCore depth and planes only | R1 | Sat 12:00 |

**How the RK-1 / A-1 fallback runs on the day.** Install the same debug APK on the S25 Ultra (`scripts/install_headset.sh --serial <s25-serial>`; `adb devices` lists the serial) and sideload the models there too (`scripts/push_models.sh --serial <s25-serial>`). On the phone `DeviceProfile.isXr` is false, so `FlatShell` shows and the perception modules are `NoOp*`; Read and Describe run through the same `CameraFrameSource` (whether Camera2 id "0" is the S25 Ultra's main back camera is UNVERIFIED: the `camera 0:` line confirms). In the demo the wearer takes the phone for the Text Reader and Audio Description beats (`docs/demo.md` 0:10 and 0:30), points it at the sign or the table and says "read this" / "describe" (or taps the panel button), then puts it down and does Navigation on the headset with depth and planes only (no door finding, no outlines: both need the headset camera). Evidence row for A-1: the headset's missing `camera 0:` line plus, from the phone, `vysion: DeviceProfile: … (2D fallback)`, `camera 0: …` and `vysion.latency: command->action N ms`.

Rows that touch R1's code without naming R1: RK-6 (door one-shot wrong side; the two-frame vote is implemented in `ModeController.findDoor`, owners R2/R3, decide by Sun 09:00) and RK-10 (API names in stubs drift from the alpha artifacts; every Compose-for-XR and CameraX name used here was re-checked against the cached AARs with `javap`, owner R5).

## How to test without the headset

- **Fakes.** `-PuseFakes=true` wires `FakeFrameSource` (5 fps synthetic 640x480 frames with a sliding rectangle; `captureStill()` returns a fresh 896x896 synthetic sign; `stats` non-null after `start()`, fps measured by `FpsMeter`) and R2's `FakeInferenceEngine` (canned answers per prompt after `describeDelayMs`; `failNextDescribe` / `hangNextDescribe` for the error paths). `PassthroughOverlayRenderer` is the same object on every path. On a laptop emulator (`system-images;android-34;android-xr-preview-playstore;arm64-v8a`) or the S25 Ultra the whole loop runs: welcome → "read" from the panel → THINKING earcon (logged stub until FR-N1) → canned text in the transcript panel → `Speaker.say` (audible once FR-G4 lands; until then logcat shows `TODO(FR-G4) AndroidTtsPort.speak … dropping utterance`). Navigation reports unavailable and the overlay stays empty under fakes (`NoOp*` perception).
- **Unit tests** (`./gradlew testDebugUnitTest --tests 'com.vysion.xr.platform.*'`):
  - `ModeControllerTest`: welcome on start; Read walks IDLE→CAPTURING→INFERRING→SPEAKING→IDLE and sets the transcript; Describe announces the mode once; `failNextDescribe` speaks `inferenceFailed` and returns to IDLE; `hangNextDescribe` hits the 15 s watchdog with a THINKING earcon at 4/8/12 s and speaks the failure within 2 s; Stop during INFERRING returns to IDLE without advancing virtual time; Stop during SPEAKING silences; busy policy drops a second command with the busy phrase; a command during SPEAKING preempts; door vote agreement (anchor + `guideTo`), disagreement, "none", and no-depth each say `doorNotFound`; GuideMe starts Navigation and a one-shot pauses/resumes it; GuideMe on a NoOp engine speaks `navigationUnavailable`; navigation cues are spoken through the phrasebook and drive the overlay; `stop()` tears down collectors; a `DoorBearing` with `pinged = false` plays `Earcon.PING` before its words (A-6); "in Spanish" sets `ES_MX` on both the `Speaker` and the `CommandBus` and later prompts go through `PromptSet.forLocale` (FR-G6); a hazard during a guidance utterance plays PROXIMITY without waiting; Arrived runs the arrival prompt once (FR-N6); brief/detailed pick the describe variants (FR-D3); `voiceCommandsUnavailable` is spoken once per run after the welcome (A-7/RK-4); `stop()` then `start()` (even mid-Read, with a slow camera teardown) speaks the welcome again, keeps it in SPEAKING, starts the frames only after the teardown, and every lane (commands, cues, detections, Stop) works again. State transitions are recorded with a collector on `UnconfinedTestDispatcher`: a `backgroundScope` collector on the standard dispatcher misses the last transition, because `advanceUntilIdle()` returns once only background work is left.
  - `DoorVoteTest`: allowlist parse (case, punctuation, prose, two sides, spelling variants, negated sides such as "There is no door on the left"), agreement rules, door highlight thirds.
  - `FrameMathTest`: `FpsMeter` window behaviour, `CenterCrop.rect` aspect/centering, rejection of empty sizes.
  - The tests need no mocking library: `Bitmap` instances come from its package-private constructor under the mockable android.jar (`unitTests.isReturnDefaultValues = true`).
- **What cannot be tested here**: CameraX binding (A-1), passthrough compositing of a transparent `SpatialPanel` and `SpatialExternalSurface` (A-2), TalkBack inside spatial panels (Q14), pinch (Q15).

## Definition of done

Per [`CONTRIBUTING.md`](../../CONTRIBUTING.md): FR id in the PR title, `./gradlew assembleDebug testDebugUnitTest`, `scripts/check_privacy.sh` and `scripts/check_cues.sh` green, checkbox ticked above in the same PR, numbers only from the device into `docs/benchmarks.md`. Device evidence R1 must attach:

1. `adb logcat -s vysion` lines: `DeviceProfile: … (Android XR)` (A-12 on the phone: `(2D fallback)`), `camera 0: <w>x<h> <format> at <fps> fps (A-1 evidence)` (logged by R5's `DiagnosticsCollector` from `FrameSource.stats`), `still <w>x<h> rotation=<r> -> <edge>x<edge> (A-1)` and `still hfov=<deg> deg (A-1)` (A-1).
2. A screenshot of passthrough with the transcript panel and the control panel visible (A-2, FR-G1), and one of the 2D layout on the S25 Ultra (ADR-0006).
3. `adb logcat -s vysion.latency` showing `command->action N ms` ≤ 1500 for a panel Read and a voice Read, `read capture->first-word N ms` ≤ 6000 (FR-T1), and R4's `stop→silence` line ≤ 300 ms.
4. `adb logcat -s vysion.reliability` empty during a clean run, and one `recovered from InferenceException` line after a forced failure.
5. A clip of outlines following a walking person and the door band appearing on `DoorBearing`.

## Contract issues

Resolved (2026-10-17, orchestrator contract decisions; R1 follow-ups in the same pass):

- **RESOLVED: phrasebook and prompt seams.** `ModePhrasebook` and `PromptSet` (with their `Unwired` placeholders) moved from `platform/ModeController.kt` to `core/Contracts.kt`; `voice.CuesPhrasebook` (permission cues included) and the tests import them from `core`, so `platform` imports no other role's package (ADR-0005).
- **RESOLVED: prompts reach the controller without an adapter.** R2 added `object LibraryPromptSet : PromptSet` in `inference/PromptLibrary.kt`; `AppGraph` exposes `val prompts: PromptSet = LibraryPromptSet` and passes `phrasebook = CuesPhrasebook.also { it.bind(speaker) }`, `prompts = prompts` to `ModeController`.
- **RESOLVED: restart after `onStop`.** `AppGraph.stop()` is reversible (stops the controller and perception, keeps scope, models and TTS) and `AppGraph.release()` runs once from `onDestroy` when finishing; `graph.perception` is public and `MainActivity` starts it after permissions. `ModeController.stop()` then `start()` is covered by "stop then start restarts collectors and speaks the welcome again" (the cancelled one-shot no longer resets the new state, the restart waits for the camera teardown, the mode is cleared on stop).
- **RESOLVED: recognition follows "in Spanish".** `CommandBus.setLocale` exists (default no-op); `Command.InSpanish` now calls `commands.setLocale(Speaker.ES_MX)` next to `speaker.setLocale(Speaker.ES_MX)` (FR-G6). Nothing switches back to `EN_US`: the grammar has no "in English" command.
- **RESOLVED: A-6 ping fallback.** `NavigationCue.DoorBearing.pinged` exists; when it is `false` the controller plays `Earcon.PING` through the `Speaker` before the cue's words.
- **RESOLVED: `DiagnosticsOverlay` import.** `SpatialShell` and `FlatShell` take a `diagnostics: @Composable () -> Unit` slot that `MainActivity` (root package) fills with R5's `DiagnosticsOverlay(graph.diagnostics)`, so `platform.ui` no longer imports `com.vysion.xr.diagnostics`.

Still open:

1. **CLOSED (R5, 2026-10-17): the still size.** `FrameSource.captureStill()` has no size parameter and `FrameSource` cannot see `InferenceEngine.vlmInfo`; `AppGraph` now collects `inferenceEngine.vlmInfo` and sets `CameraFrameSource.stillEdge` to `inputWidth` once the VLM has loaded (default 896 until then, `docs/model-plan.md`, UNVERIFIED). The additive contract change `captureStill(width: Int = 0, height: Int = 0)` stays an option if a non-square VLM input appears.
2. **`NavigationCue.DoorBearing` carries no box.** `OverlayRenderer.highlightDoor(NormalizedBox?)` needs pixels, R3 reports hours. Local workaround: `DoorHighlight.boxFor(clock, inView)` draws a vertical band in the left/center/right third: 12 o'clock center, 9–11 left, 1–3 right (R3's `inView` is ±45°, so an in-view door is at 11, 12 or 1, and a LEFT/RIGHT answer at ±30° lands on 11/1; before 2026-10-17 11 and 1 drew the center band, so LEFT and RIGHT looked the same). Proposed additive change: `val box: NormalizedBox? = null` on `DoorBearing`, filled by R3 when the detector (or the P2 door class, FR-N8) sees the door.
3. **`FrameSource.start()` has no `LifecycleOwner`, which CameraX `bindToLifecycle` needs.** Local plan (snippet in the `CameraFrameSource.start()` KDoc and the cheat-sheet row "Lifecycle owner", names checked with javap): a `LifecycleRegistry` owned by the source and driven from `start()`/`stop()` on `Dispatchers.Main`. No contract change needed; noted so R5 does not expect the activity to bind the camera.
4. **Still retention for P1 follow-ups.** The brief says "recycle the Bitmap in `finally`" (done, INV-9), but FR-T4 ("in Spanish" re-prompts the same frame), FR-D2 (follow-up without recapture) and nfr.md ("keep the frame for one retry on repeat") need the last still kept until the next capture. Proposed P1 change inside R1 only: `ModeController` keeps `lastStill` and recycles it on the next capture or on `stop()`; `Command.InSpanish` today switches the TTS and recognition locale only (no re-prompt; `docs/architecture.md` Flow 1 marks the `TRANSLATE_ES` step as P1), `FollowUp` is routed but does not re-prompt until the still is retained; `Brief`/`Detailed` only pick the prompt of the next Describe.
5. **"Stop" and guidance.** `Command.Stop` silences speech and cancels the in-flight one-shot (FR-G4); it does not end Navigation guidance, so the next `DoorBearing` cue speaks 3 s later. If the blindfold test (R4, Sat 21:00) shows users expect "stop" to end guidance, R1 adds `navigation.clearTarget()` to the Stop lane (only when a door target is set); R4 + R1 decide at that test and land it by gate 6 (Sat 22:00). Until then R3's 180° test ends guidance by walking to the door (`Arrived` → `clearTarget()`), not by "stop" (r3 page, Contract issue 8).
6. **Visible button captions.** `res/values/strings.xml` holds only `a11y_*` labels and R1 was told not to edit it (R4 maintains TalkBack labels), so the panel shows the `a11y_*` text as captions and the mic shows the first clause of `a11y_mic_button`. Proposed: R4 adds `label_mic`, `label_read`, `label_describe`, `label_navigate`, `label_stop`, `label_diagnostics` to `strings.xml`; R1 switches `ControlPanel.kt` to them (six lines).
7. **Two consumers of one `CommandBus.commands`.** `ModeController` subscribes once per run (`shareIn(runScope, Eagerly)`) and fans out to a Stop lane and a command lane (INV-6). R4's bus is a hot `MutableSharedFlow(extraBufferCapacity = 8, DROP_OLDEST)`, so either shape works; noted so nobody turns `commands` into a cold per-collector flow.
8. **CLOSED (R5, 2026-10-17): perception stop/start ordering on a quick off/on.** `AppGraph.stop()` keeps the `perception.stop()` `Job`; `MainActivity` calls `lifecycleScope.launch { graph.startPerception(this) }`, which joins that `Job` before `session.start(activity)`, so a fast `onStop` → `onStart` can no longer leave the session STOPPED.
9. **Configuration changes.** `MainActivity` declares `android:configChanges` (orientation, screen size and layout, density, keyboard), so rotating the S25 Ultra or resizing the Home Space window recomposes in place instead of running `onStop`/`onStart` (which cancelled an in-flight Read and re-spoke the welcome).
10. **OPEN: capture-time pose for the door anchor.** `findDoor` calls `anchors.placeFromBearing(bearing)` after both VLM answers, and R3 adds the offset to the head yaw at that moment, not at capture. Proposed additive contract change (r3 page, Contract issue 6; `docs/contracts.md` "Open contract proposals"): R1 records `val capturedAt = System.nanoTime()` right after the first `captureStill()` and passes `placeFromBearing(bearing, capturedAt)`. No R1 change until R5 merges the contract change.
11. **OPEN: one XR session or two (Q21).** `SpatialShell` reads `LocalSession.current`; R3's `XrPerceptionSession` creates its own `Session`. On the first headset run, log `System.identityHashCode(LocalSession.current)` under `vysion` from `SpatialShell` so R3 can compare it with its own session (r3 page, Contract issue 7). If R3 must use the Compose session, `MainActivity` / `AppGraph.startPerception` pass it in (announced like a contract change).

## Dependencies needed

None. Everything used is already pinned in `gradle/libs.versions.toml`: CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`; `camera-view` for the phone `PreviewView`, FR-N2 TODO), `androidx.xr.compose` 1.0.0-beta01, `androidx.xr.scenecore` / `androidx.xr.runtime` 1.0.0-rc02 (`ManifestPermission` comes transitively from `runtime-manifest`), `lifecycle-runtime-compose` 2.11.0 (`collectAsStateWithLifecycle`, `lifecycleScope`), `activity-compose` 1.13.0, `kotlinx-coroutines-test` 1.11.0 (tests). No mocking library: see "How to test without the headset".
