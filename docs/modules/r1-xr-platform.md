# R1 XR Platform (Kotlin, Compose for XR, SceneCore)

## Purpose

R1 is the app shell and the state machine everything else hangs off. SRS §8: **Owns** "App shell, passthrough Full Space, permissions, camera pipeline, panels, outlines, mode state machine"; **Hands off** "`FrameSource` (analysis stream + still capture), `ModeController` skeleton, `OverlayRenderer`", due **Sat 13:00**. The skeleton below compiles and runs with the fakes on a laptop emulator or the S25 Ultra; every device-touching method (CameraX bind, still capture, passthrough surface) is a `TODO(FR-xx)` stub with the verified API written next to it, and every piece of pure logic (state machine, busy policy, watchdog, door vote, crop math, fps meter) is implemented and unit-tested.

## Files you own

| Path | What it is | Status |
|---|---|---|
| `app/src/main/kotlin/com/vysion/xr/MainActivity.kt` | `ComponentActivity`; reads `VysionApplication.graph`; requests permissions with the spoken explanation; `graph.start()` on grant, `graph.stop()` in `onStop`; picks `SpatialShell` / `FlatShell` from `DeviceProfile.isXr`; logs `vysion.latency` and `vysion.reliability` | real (lifecycle only) |
| `app/src/main/kotlin/com/vysion/xr/platform/CameraFrameSource.kt` | `FrameSource` on the world camera: CameraX `ImageAnalysis` 640x480 RGBA + `ImageCapture`; `FpsMeter`, `CenterCrop` helpers | stub (`start`, `stop`, `captureStill`) + pure (`FpsMeter`, `CenterCrop.rect`) |
| `app/src/main/kotlin/com/vysion/xr/platform/FakeFrameSource.kt` | `FrameSource` that paints a 640x480 gradient with a moving rectangle at 5 fps and an 896x896 synthetic still | fake (fully working) |
| `app/src/main/kotlin/com/vysion/xr/platform/PassthroughOverlayRenderer.kt` | `OverlayRenderer` as two `StateFlow`s (`detections`, `door`) the Compose overlay draws | pure (fully working) |
| `app/src/main/kotlin/com/vysion/xr/platform/ModeController.kt` | The state machine; `ModePhrasebook` and `PromptSet` seams; `DoorVote`; `DoorHighlight` | pure (fully working, unit-tested) |
| `app/src/main/kotlin/com/vysion/xr/platform/Permissions.kt` | Permission list (exactly the manifest strings), `missing()`, `canStart()`, `register()` | pure + Android glue |
| `app/src/main/kotlin/com/vysion/xr/platform/DeviceProfile.kt` | Runtime XR check (ADR-0006), pre-existing | real |
| `app/src/main/kotlin/com/vysion/xr/platform/ui/SpatialShell.kt` | Full Space layout: `Subspace` → `SpatialRow` → overlay panel + transcript panel + `Orbiter` control panel; passthrough fallback | real (verified APIs only) |
| `app/src/main/kotlin/com/vysion/xr/platform/ui/FlatShell.kt` | 2D layout for the S25 Ultra; `rememberShellState` shared by both shells | real |
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
| provide (seam) | `interface ModePhrasebook { welcome; busy; inferenceFailed; doorNotFound; navigationUnavailable; holdStill; permissionExplanation; permissionDenied; modeAnnounced(Mode); navigationCue(NavigationCue): String? }` | R4 `voice.CuesPhrasebook` through an adapter in `AppGraph` (see Contract issues) |
| provide (seam) | `interface PromptSet { read; describe; door; arrival; translateEs?; describeBrief?; describeDetailed?; followUp(q)? }` | R2 `inference.PromptLibrary` through an adapter in `AppGraph` |
| consume | `InferenceEngine.warmUp()`, `describe(bitmap, prompt)` (under `withTimeout(VLM_TIMEOUT_MS)`), `vlmInfo` | R2 `ExecuTorchInferenceEngine` / `FakeInferenceEngine` |
| consume | `CommandBus.commands` (one `shareIn`, two lanes: Stop and everything else), `state`, `startListening()`, `stopListening()`, `inject()` | R4 `SpeechCommandBus` |
| consume | `Speaker.say()`, `stop()` (non-suspending, FR-G4), `earcon()`, `setLocale()`, `repeatLast()`, `progress`, `locale` | R4 `AndroidSpeaker` |
| consume | `NavigationEngine.start/pause/resume/stop`, `guideTo`, `clearTarget`, `cues`, `detections`, `isRunning` | R3 `XrNavigationEngine` / `NoOpNavigationEngine` |
| consume | `AnchorStore.placeFromBearing(HorizontalBearing): AnchorId?` | R3 |
| consume | `HeadPoseSource.pose` (FR-T5, P1, not read yet) | R3 |
| consume | `DiagnosticsSource` → `@Composable DiagnosticsOverlay(source: DiagnosticsSource, modifier)` | R5 (the one cross-package UI call, per the skeleton brief) |

## Assumptions to verify first

From `docs/assumptions.md`, verbatim:

| # | Owner | Assumption | By | Fallback (from "If an assumption fails") |
|---|---|---|---|---|
| A-1 | R1 | CameraX delivers world-camera frames (camera id 0) to a third-party app on this Galaxy XR firmware with only the `CAMERA` permission; note resolution and format | Sat 11:00 | RK-1 fallback: Read and Describe on the S25 Ultra; Navigation stays on the headset with depth and planes only |
| A-2 | R1 | Compose for XR Full Space keeps passthrough on while our panels are shown | Sat 11:00 | Use the start-mode / passthrough-opacity API documented in `docs/stack.md`; if passthrough still drops, show panels only while a command is active |
| A-12 | R1 | The single-APK runtime check for XR capability (see ADR-0006) returns false on the S25 Ultra and the 2D fallback UI shows | Sat 20:00 | fall back to a `BuildConfig` flag set from `-PdeviceProfile=phone` (ADR-0006) |

How the skeleton prepares each check: A-1 is the `TODO(FR-T1)` in `CameraFrameSource.start()` plus the `FrameStats` log (`vysion` tag) that fills the Evidence column; A-2 is `SpatialShell.forcePassthrough` (already applies the fallback, `preferredPassthroughOpacity = 1.0f`, gated on `PASSTHROUGH_CONTROL`); A-12 is the `DeviceProfile:` line `MainActivity` logs at launch.

## Task checklist

SRS §8 R1, verbatim:

- [ ] P0 Project skeleton: Compose for XR activity, Full Space with passthrough, `CAMERA`, `RECORD_AUDIO`, `SCENE_UNDERSTANDING_*`, `HAND_TRACKING` permissions requested with spoken explanations. Sat 11:00
  Files: `MainActivity.kt`, `platform/Permissions.kt`, `platform/ui/SpatialShell.kt`, `AndroidManifest.xml` (property `XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED`) · FR: FR-G1, A-2 · Verify: `scripts/install_headset.sh --logcat`; logcat `vysion: DeviceProfile: … (Android XR)`; the six permission dialogs appear after the spoken explanation (R4's `Cues` wired, see Contract issues); passthrough visible behind the transcript panel (screenshot path in the PR)
- [ ] P0 Confirm world-camera frames arrive via CameraX on the headset (camera id 0); log resolution, format, fps. Sat 12:00
  Files: `platform/CameraFrameSource.kt` (`start()` stub → bind) · FR: FR-T1, FR-N2, A-1, RK-1 · Verify: `adb logcat -s vysion | grep FrameStats` prints `FrameStats(cameraId=0, width=640, height=480, format=RGBA_8888, fps=…)` with fps ≥ 5; fill A-1 in `docs/assumptions.md`
- [ ] P0 `FrameSource`: 640x480 analysis stream to a `Flow<Bitmap>` plus `suspend fun captureStill(): Bitmap` center-cropped and resized to the VLM input size. Sat 13:00
  Files: `platform/CameraFrameSource.kt` (`captureStill()` stub → `takePicture` + `CenterCrop.apply`), `platform/FakeFrameSource.kt` · FR: FR-T1, FR-D1, INV-9 · Verify: `./gradlew testDebugUnitTest --tests '*FrameMathTest*'` green; on the headset a Read logs `vysion.latency: command->action … ms` and R2's `describe` receives an 896x896 bitmap (R2's `vysion.bench` line); `scripts/check_privacy.sh` clean
- [ ] P0 `ModeController` state machine with a `Mutex` around inference; one-shot commands interrupt Navigation and resume it. Sat 15:00
  Files: `platform/ModeController.kt`, `app/src/test/kotlin/com/vysion/xr/platform/ModeControllerTest.kt` · FR: FR-G1, FR-G3, FR-G4, FR-T1, FR-D1, FR-N3, INV-5, INV-6, INV-9, ADR-0004 (the `Mutex` itself lives in R2's engine; R1 pauses/resumes Navigation around every VLM call) · Verify: `./gradlew testDebugUnitTest --tests 'com.vysion.xr.platform.*'` green (18 controller tests incl. watchdog, busy policy, Stop, door vote); on the headset "stop" during a Read → `vysion.latency` shows `command->action` and TTS silent within 300 ms (R4's `vysion.latency` stop line)
- [ ] P0 Floating control panel (mic, mode, diagnostics toggle) and large-print transcript panel per R4's spec. Sat 18:00
  Files: `platform/ui/ControlPanel.kt`, `platform/ui/TranscriptPanel.kt`, `platform/ui/SpatialShell.kt`, `platform/ui/FlatShell.kt` · FR: FR-G1, FR-G2 (mic button), FR-G3 (mode shown), FR-G5 (toggle), FR-T2 · Verify: TalkBack on, every control announces its `a11y_*` label; transcript text 48 sp white on black (screenshot); a Read updates the panel and it scrolls while TTS speaks (R4's `progress`)
- [ ] P0 `OverlayRenderer`: detection outlines and the door highlight drawn in passthrough from normalized boxes. Sat 21:00
  Files: `platform/PassthroughOverlayRenderer.kt`, `platform/ui/OverlayCanvas.kt`, `platform/ui/SpatialShell.kt` (overlay panel; `SpatialExternalSurface` UNVERIFIED) · FR: FR-N2, FR-N3, FR-N4 · Verify: with `-PuseFakes=true` the two canned boxes (person, chair) are drawn; on the headset a person walking across the room gets an outline that follows at ≥ 5 fps (overlay "detector fps"), the door band appears on `DoorBearing(inView = true)` and disappears on `inView = false`
- [ ] P1 Anchor the transcript panel where the user was looking at capture time. Sun 09:00
  Files: `platform/ui/SpatialShell.kt` (`SubspaceModifier.offset` / anchoring from `HeadPoseSource.pose` at capture) · FR: FR-T2 (anchoring) · Verify: after a Read the panel sits in the direction the user faced at capture, not where the head is now (video clip)
- [ ] P1 Pinch-and-hold as the push-to-talk gesture via hand tracking. Sun 10:00
  Files: `platform/ui/ControlPanel.kt` (same `startListening()`/`stopListening()` pair), a pinch detector fed by R3's `XrPerceptionSession` hands (`Hand.left/right(session).state`, docs/stack.md Q15) · FR: FR-G2 (pinch) · Verify: logcat `vysion` shows `CommandEvent(source=PINCH)`; thumb–index < 0.05 m starts listening, release stops

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

Rows that touch R1's code without naming R1: RK-6 (door one-shot wrong side; the two-frame vote is implemented in `ModeController.findDoor`, owners R2/R3, decide by Sun 09:00) and RK-10 (API names in stubs drift from the alpha artifacts; every Compose-for-XR and CameraX name used here was re-checked against the cached AARs with `javap`, owner R5).

## How to test without the headset

- **Fakes.** `-PuseFakes=true` wires `FakeFrameSource` (5 fps synthetic 640x480 frames with a sliding rectangle; `captureStill()` returns a fresh 896x896 synthetic sign; `stats` non-null after `start()`, fps measured by `FpsMeter`) and R2's `FakeInferenceEngine` (canned answers per prompt after `describeDelayMs`; `failNextDescribe` / `hangNextDescribe` for the error paths). `PassthroughOverlayRenderer` is the same object on every path. On a laptop emulator (`system-images;android-34;android-xr-preview-playstore;arm64-v8a`) or the S25 Ultra the whole loop runs: welcome → "read" from the panel → THINKING earcon → canned text in the transcript panel → TTS.
- **Unit tests** (`./gradlew testDebugUnitTest --tests 'com.vysion.xr.platform.*'`):
  - `ModeControllerTest`: welcome on start; Read walks IDLE→CAPTURING→INFERRING→SPEAKING→IDLE and sets the transcript; Describe announces the mode once; `failNextDescribe` speaks `inferenceFailed` and returns to IDLE; `hangNextDescribe` hits the 15 s watchdog with a THINKING earcon at 4/8/12 s and speaks the failure within 2 s; Stop during INFERRING returns to IDLE without advancing virtual time; Stop during SPEAKING silences; busy policy drops a second command with the busy phrase; a command during SPEAKING preempts; door vote agreement (anchor + `guideTo`), disagreement, "none", and no-depth each say `doorNotFound`; GuideMe starts Navigation and a one-shot pauses/resumes it; GuideMe on a NoOp engine speaks `navigationUnavailable`; navigation cues are spoken through the phrasebook and drive the overlay; `stop()` tears down collectors.
  - `DoorVoteTest`: allowlist parse (case, punctuation, prose, two sides, spelling variants), agreement rules, door highlight thirds.
  - `FrameMathTest`: `FpsMeter` window behaviour, `CenterCrop.rect` aspect/centering, rejection of empty sizes.
  - The tests need no mocking library: `Bitmap` instances come from its package-private constructor under the mockable android.jar (`unitTests.isReturnDefaultValues = true`).
- **What cannot be tested here**: CameraX binding (A-1), passthrough compositing of a transparent `SpatialPanel` and `SpatialExternalSurface` (A-2), TalkBack inside spatial panels (Q14), pinch (Q15).

## Definition of done

Per [`CONTRIBUTING.md`](../../CONTRIBUTING.md): FR id in the PR title, `./gradlew assembleDebug testDebugUnitTest` and `scripts/check_privacy.sh` green, checkbox ticked above in the same PR, numbers only from the device into `docs/benchmarks.md`. Device evidence R1 must attach:

1. `adb logcat -s vysion` lines: `DeviceProfile: … (Android XR)` (A-12 on the phone: `(2D fallback)`), `FrameStats(cameraId=0, width=…, height=…, format=…, fps=…)` (A-1).
2. A screenshot of passthrough with the transcript panel and the control panel visible (A-2, FR-G1), and one of the 2D layout on the S25 Ultra (ADR-0006).
3. `adb logcat -s vysion.latency` showing `command->action N ms` ≤ 1500 for a panel Read and a voice Read, and R4's stop line ≤ 300 ms.
4. `adb logcat -s vysion.reliability` empty during a clean run, and one `recovered from InferenceException` line after a forced failure.
5. A clip of outlines following a walking person and the door band appearing on `DoorBearing`.

## Contract issues

1. **Cue and prompt strings cannot be imported by `platform` (ADR-0005), but `ModeController` must speak and prompt.** Worked around locally with two seams in `ModeController.kt`: `interface ModePhrasebook` and `interface PromptSet`, both trailing constructor parameters that default to blank `Unwired` objects (a blank cue is skipped, so the app launches silently but without crashing until wired). R4 has already written `voice.CuesPhrasebook` with the matching methods (each taking a `Locale`), and R2's `PromptLibrary` has every prompt. **Proposed change (fixer / R5, in `AppGraph.kt`, root package, which may import everything):**
   ```kotlin
   modeController = ModeController(
       …, scope = scope,
       phrasebook = object : ModePhrasebook {
           private val locale: Locale get() = speaker.locale.value
           override val welcome get() = CuesPhrasebook.welcome(locale)
           override val busy get() = CuesPhrasebook.busy(locale)
           override val inferenceFailed get() = CuesPhrasebook.inferenceFailed(locale)
           override val doorNotFound get() = CuesPhrasebook.doorNotFound(locale)
           override val navigationUnavailable get() = CuesPhrasebook.navigationUnavailable(locale)
           override val holdStill get() = CuesPhrasebook.holdStill(locale)
           override val permissionExplanation get() = ""   // R4: add Cues.Key.PERMISSION_EXPLANATION
           override val permissionDenied get() = ""        // R4: add Cues.Key.PERMISSION_DENIED
           override fun modeAnnounced(mode: Mode) = CuesPhrasebook.modeChanged(mode, locale)
           override fun navigationCue(cue: NavigationCue): String? = CuesPhrasebook.navigationCue(cue, locale)
       },
       prompts = object : PromptSet {
           override val read = PromptLibrary.READ
           override val describe = PromptLibrary.DESCRIBE
           override val door = PromptLibrary.DOOR
           override val arrival = PromptLibrary.ARRIVAL
           override val translateEs = PromptLibrary.TRANSLATE_ES
           override val describeBrief = PromptLibrary.DESCRIBE_BRIEF
           override val describeDetailed = PromptLibrary.DESCRIBE_DETAILED
           override fun followUp(question: String) = PromptLibrary.followUp(question)
       },
   )
   ```
   Longer term, R4's proposal (move `ModePhrasebook` into `core/Contracts.kt` and let `Cues` implement it) removes the adapter; R1 agrees. The two permission cues (`permissionExplanation`, `permissionDenied`) do not exist in `Cues` yet and are requested from R4 (FR-G1 "requested with spoken explanations").
2. **`FrameSource.captureStill()` has no size parameter and `FrameSource` cannot see `InferenceEngine.vlmInfo`.** The still must be "resized to the VLM input size" (SRS §6) but the constructor is `(Application, CoroutineScope)`. Local workaround: `CameraFrameSource.stillEdge` (`@Volatile var`, default 896 from `docs/model-plan.md`, UNVERIFIED) that `AppGraph` may set from `inferenceEngine.vlmInfo` after `warmUp()`. Proposed additive change: `suspend fun captureStill(width: Int = 0, height: Int = 0): Bitmap` (0 = source default), or `ModeController` passes `engine.vlmInfo.value?.inputWidth` through a setter on the interface.
3. **`AppGraph.stop()` cancels the graph scope for good.** After `MainActivity.onStop` a later `onStart` calls `graph.start()` again, but every module's `scope` is dead, so nothing runs until the process restarts. `ModeController.start()` is restartable (it creates a child scope per run); proposed change in `AppGraph`: recreate `scope` in `start()` (or make `stop()` cancel children only, `scope.coroutineContext.cancelChildren()`), owner R5.
4. **`NavigationCue.DoorBearing` carries no box.** `OverlayRenderer.highlightDoor(NormalizedBox?)` needs pixels, R3 reports hours. Local workaround: `DoorHighlight.boxFor(clock, inView)` draws a vertical band in the left/center/right third. Proposed additive change: `data class DoorBearing(val clock: Int, val distanceM: Float, val inView: Boolean, val box: NormalizedBox? = null)` filled by R3 when the detector (or the P2 door class, FR-N8) sees the door.
5. **`FrameSource.start()` has no `LifecycleOwner`, which CameraX `bindToLifecycle` needs.** Local plan (documented in `CameraFrameSource.start()`): a `LifecycleRegistry` owned by the source and driven from `start()`/`stop()` on `Dispatchers.Main`. No contract change needed; noted so R5 does not expect the activity to bind the camera.
6. **Still retention for P1 follow-ups.** The brief says "recycle the Bitmap in `finally`" (done, INV-9), but FR-T4 ("in Spanish" re-prompts the same frame), FR-D2 (follow-up without recapture) and nfr.md ("keep the frame for one retry on repeat") need the last still kept until the next capture. Proposed P1 change inside R1 only: `ModeController` keeps `lastStill` and recycles it on the next capture or on `stop()`; `Command.InSpanish` today only switches the TTS locale (`Speaker.setLocale(ES_MX)`), `FollowUp`/`Brief`/`Detailed` are routed but do not re-prompt until the still is retained.
7. **"Stop" and guidance.** `Command.Stop` silences speech and cancels the in-flight one-shot (FR-G4); it does not end Navigation guidance, so the next `DoorBearing` cue speaks 3 s later. If the blindfold test (R4, Sat 21:00) shows users expect "stop" to end guidance, R1 adds `navigation.clearTarget()` to the Stop lane; open question for R4/R1 at gate 5.
8. **Visible button captions.** `res/values/strings.xml` holds only `a11y_*` labels and R1 was told not to edit it (R4 maintains TalkBack labels), so the panel shows the `a11y_*` text as captions and the mic shows the first clause of `a11y_mic_button`. Proposed: R4 adds `label_mic`, `label_read`, `label_describe`, `label_navigate`, `label_stop`, `label_diagnostics` to `strings.xml`; R1 switches `ControlPanel.kt` to them (six lines).
9. **Two consumers of one `CommandBus.commands`.** `ModeController` subscribes once (`shareIn(runScope, Eagerly)`) and fans out to a Stop lane and a command lane (INV-6). R4's bus is a hot `MutableSharedFlow(extraBufferCapacity = 8, DROP_OLDEST)`, so either shape works; noted so nobody turns `commands` into a cold per-collector flow.
10. **`DiagnosticsOverlay` import.** `platform.ui` imports `com.vysion.xr.diagnostics.DiagnosticsOverlay` by name per the skeleton brief; it is the one cross-package import in this package and should move behind a composable slot passed from `MainActivity` (root package) if ADR-0005 is enforced by a grep.

## Dependencies needed

None. Everything used is already pinned in `gradle/libs.versions.toml`: CameraX 1.6.2 (`camera-core`, `camera-camera2`, `camera-lifecycle`; `camera-view` for the phone `PreviewView`, FR-N2 TODO), `androidx.xr.compose` 1.0.0-beta01, `androidx.xr.scenecore` / `androidx.xr.runtime` 1.0.0-rc02 (`ManifestPermission` comes transitively from `runtime-manifest`), `lifecycle-runtime-compose` 2.11.0 (`collectAsStateWithLifecycle`, `lifecycleScope`), `activity-compose` 1.13.0, `kotlinx-coroutines-test` 1.11.0 (tests). No mocking library: see "How to test without the headset".
