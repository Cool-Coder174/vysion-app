package com.vysion.xr

import android.app.Activity
import android.util.Log
import android.app.Application
import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.DiagnosticsSource
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.HandGestureSource
import com.vysion.xr.core.HeadPoseSource
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.ModePhrasebook
import com.vysion.xr.core.NavigationEngine
import com.vysion.xr.core.OverlayRenderer
import com.vysion.xr.core.PromptSet
import com.vysion.xr.core.Speaker
import com.vysion.xr.diagnostics.DiagnosticsCollector
import com.vysion.xr.inference.CloudDescribeEngine
import com.vysion.xr.inference.ExecuTorchInferenceEngine
import com.vysion.xr.inference.FakeInferenceEngine
import com.vysion.xr.inference.GeminiCloudDescriber
import com.vysion.xr.inference.LibraryPromptSet
import com.vysion.xr.inference.ModelCatalog
import com.vysion.xr.inference.NetworkStatus
import com.vysion.xr.perception.GazeGeometry
import com.vysion.xr.perception.NoOpAnchorStore
import com.vysion.xr.perception.NoOpHeadPoseSource
import com.vysion.xr.perception.NoOpHandGestures
import com.vysion.xr.perception.NoOpNavigationEngine
import com.vysion.xr.perception.Tracking
import com.vysion.xr.perception.XrAnchorStore
import com.vysion.xr.perception.XrHandGestures
import com.vysion.xr.perception.XrHeadPoseSource
import com.vysion.xr.perception.XrNavigationEngine
import com.vysion.xr.perception.XrPerceptionSession
import com.vysion.xr.platform.CameraFrameSource
import com.vysion.xr.platform.DeviceProfile
import com.vysion.xr.platform.FakeFrameSource
import com.vysion.xr.platform.GestureCommands
import com.vysion.xr.platform.ModeController
import com.vysion.xr.platform.OnboardingStore
import com.vysion.xr.platform.PassthroughOverlayRenderer
import com.vysion.xr.voice.AndroidSpeaker
import com.vysion.xr.voice.CuesPhrasebook
import com.vysion.xr.voice.SpeechCommandBus
import androidx.xr.scenecore.AnchorSpace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Manual wiring of every long-lived module (ADR-0008). This is the ONLY place a module is
 * constructed, so the constructor signatures below are part of each role's contract:
 *
 * | Role | Class (package)                                   | Constructor                                   |
 * |------|---------------------------------------------------|-----------------------------------------------|
 * | R1   | platform.CameraFrameSource                        | (Application, CoroutineScope)                 |
 * | R1   | platform.FakeFrameSource                          | (Application)                                 |
 * | R1   | platform.PassthroughOverlayRenderer               | ()                                            |
 * | R1   | platform.ModeController                           | (frames, engine, commands, speaker, navigation, anchors, headPose, overlay, scope, phrasebook, prompts) |
 * | R2   | inference.ModelCatalog                            | (Application)                                 |
 * | R2   | inference.ExecuTorchInferenceEngine               | (Application, ModelCatalog, CoroutineScope)   |
 * | R2   | inference.FakeInferenceEngine                     | ()                                            |
 * | R2   | inference.CloudDescribeEngine (wraps the engine)  | (InferenceEngine, CloudDescriber?, () -> Boolean) |
 * | R2   | inference.GeminiCloudDescriber.orNull / NetworkStatus | (apiKey) / (Context)                      |
 * | R2   | inference.LibraryPromptSet (object, PromptSet)    | —                                             |
 * | R3   | perception.XrPerceptionSession                    | (Application, CoroutineScope)                 |
 * | R3   | perception.XrHeadPoseSource / NoOpHeadPoseSource  | (XrPerceptionSession) / ()                    |
 * | R3   | perception.XrAnchorStore / NoOpAnchorStore        | (XrPerceptionSession, HeadPoseSource) / ()    |
 * | R3   | perception.XrNavigationEngine / NoOpNavigationEngine | (XrPerceptionSession, FrameSource, InferenceEngine, AnchorStore, HeadPoseSource, CoroutineScope) / () |
 * | R4   | voice.SpeechCommandBus                            | (Application, CoroutineScope)                 |
 * | R4   | voice.AndroidSpeaker                              | (Application, CoroutineScope)                 |
 * | R4   | voice.CuesPhrasebook (object, ModePhrasebook)     | bind(Speaker) once                            |
 * | R5   | diagnostics.DiagnosticsCollector                  | (Application, InferenceEngine, FrameSource, CoroutineScope) |
 *
 * Fakes are wired when `BuildConfig.USE_FAKES` is true (`-PuseFakes=true`), so the app runs on a
 * laptop emulator or the S25 Ultra with no camera, XR runtime or model files. On a non-XR device
 * ([DeviceProfile.isXr] false) the perception modules are `NoOp*` regardless (ADR-0006).
 *
 * Still size: fixed by `CameraFrameSource` (1280, for OCR on small print); the inference engine fits a still to its
 * VLM's input itself, so nothing is wired between them (Contracts.kt `FrameSource.captureStill`).
 *
 * Lifecycle: [start] / [stop] follow `MainActivity.onStart` / `onStop` and can repeat (taking the
 * headset off and on stops and restarts the modules); [startPerception] runs after each [start] and waits
 * for the previous [stop]'s perception teardown, so a quick off/on never leaves the session STOPPED.
 * [release] runs once, from `onDestroy` when the activity is finishing, and is the only place the graph
 * scope is cancelled; it sets [isReleased], and `VysionApplication.graph` then builds a fresh graph for the
 * next activity (a finished activity in a cached process gets a new graph, never the released one). The
 * camera analysis executor lives as long as the process (`CameraFrameSource`; FrameSource has no release).
 */
class AppGraph(private val app: Application) {

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** `true` once [release] ran; the graph cannot be restarted and `VysionApplication.graph` replaces it. */
    @Volatile
    var isReleased: Boolean = false
        private set

    /** The last [stop]'s perception teardown; [startPerception] joins it before starting the session again. */
    @Volatile
    private var perceptionStop: Job? = null

    val deviceProfile: DeviceProfile = DeviceProfile.detect(app)
    private val useFakes: Boolean = BuildConfig.USE_FAKES

    // R2 — models, prompts and inference
    val modelCatalog: ModelCatalog = ModelCatalog(app)

    /** Prompt text (SRS §7) for the controller and the debug benchmark; lives only in `inference/PromptLibrary.kt`. */
    val prompts: PromptSet = LibraryPromptSet

    /**
     * The on-device engine, wrapped so Describe can use a cloud model when the wearer turns it on (ADR-0010 row
     * "Cloud Describe"). Without `GEMINI_API_KEY` in `local.properties` the wrapper never leaves the headset.
     */
    val inferenceEngine: InferenceEngine = CloudDescribeEngine(
        headset = if (useFakes) FakeInferenceEngine() else ExecuTorchInferenceEngine(app, modelCatalog, scope),
        cloud = cloudDescriber(app),
        isOnline = NetworkStatus(app)::isOnline,
    )

    // R1 — camera and overlay
    val frameSource: FrameSource =
        if (useFakes) FakeFrameSource(app) else CameraFrameSource(app, scope)
    val overlay: OverlayRenderer = PassthroughOverlayRenderer()

    // R4 — voice in / out (real on both devices; fakes inject via CommandBus.inject)
    val speaker: Speaker = AndroidSpeaker(app, scope)
    val commandBus: CommandBus = SpeechCommandBus(app, scope)

    // R3 — perception (XR only). Public so `MainActivity` can hand it the resumed Activity that
    // `Session.create(context = activity)` needs (docs/stack.md D7).
    val perception: XrPerceptionSession? =
        if (deviceProfile.isXr && !useFakes) XrPerceptionSession(app, scope) else null
    val headPose: HeadPoseSource =
        perception?.let { XrHeadPoseSource(it) } ?: NoOpHeadPoseSource()
    val anchorStore: AnchorStore =
        perception?.let { XrAnchorStore(it, headPose) } ?: NoOpAnchorStore()
    val navigationEngine: NavigationEngine =
        perception?.let { XrNavigationEngine(it, frameSource, inferenceEngine, anchorStore, headPose, scope) }
            ?: NoOpNavigationEngine()

    /** Eyes-free hand gestures (blind users): middle-finger pinch to talk, ring-finger tap to page, palm out to stop. */
    val handGestures: HandGestureSource = perception?.let { XrHandGestures(it) } ?: NoOpHandGestures()

    // R5 — diagnostics
    val diagnostics: DiagnosticsSource = DiagnosticsCollector(app, inferenceEngine, frameSource, scope)

    /**
     * R4's words for everything Vysion says, following `Speaker.locale` (FR-G6). Exposed for the onboarding UI,
     * which shows and speaks the same page text (designs pages 2-8); the controller gets the same instance.
     */
    val phrasebook: ModePhrasebook = CuesPhrasebook.also { it.bind(speaker) }

    /** First-launch onboarding completion: one boolean, the only preference the app writes (ADR-0009). */
    val onboarding: OnboardingStore = OnboardingStore(app)

    // R1 — the state machine that owns every one-shot command. It speaks only through R4's phrasebook
    // and prompts only through R2's prompt set (INV-4, ADR-0005); the phrasebook follows Speaker.locale.
    val modeController: ModeController = ModeController(
        frames = frameSource,
        engine = inferenceEngine,
        commands = commandBus,
        speaker = speaker,
        navigation = navigationEngine,
        anchors = anchorStore,
        headPose = headPose,
        overlay = overlay,
        scope = scope,
        phrasebook = phrasebook,
        prompts = prompts,
    )

    /**
     * World-anchored UI (augmented layer): the scene space of an anchor, for `FollowingSubspace(FollowTarget.Anchor(…))`.
     * `null` off the headset or for an unknown anchor. Main thread.
     */
    fun anchorSpace(id: AnchorId): AnchorSpace? = perception?.anchorSpaceFor(id)

    /**
     * The reading crosshair's depth (augmented layer): meters from the head to the surface straight along the gaze, from
     * R3's depth map, a few times a second while collected. `null` off the headset, without head tracking, or where no
     * trusted depth exists; the crosshair then keeps its last distance.
     */
    /**
     * World labels (augmented layer): meters from the wearer's head to an anchor, in 10 cm steps, so a label can grow
     * with distance and stay readable. `null` off the headset or while the anchor is not tracked.
     */
    fun anchorDistanceM(id: AnchorId): Flow<Float?> = perception?.let { session ->
        combine(session.anchorPose(id), session.headPose) { anchor, head ->
            if (anchor.tracking != Tracking.TRACKING || head == null) null else (anchor.position - head.position).length
        }.map { m -> m?.let { (it * 10f).roundToInt() / 10f } }.distinctUntilChanged() // 10 cm steps, not every frame
    } ?: flowOf(null)

        val gazeDistanceM: Flow<Float?> = perception?.let { session ->
        flow {
            while (true) {
                emit(runCatching { session.gazeDistanceM() }.getOrNull())
                delay(GAZE_SAMPLE_MS)
            }
        }
    } ?: flowOf(null)

    /** Hand gestures → the same commands as voice and panels (`platform.GestureCommands`). */
    val gestureCommands: GestureCommands =
        GestureCommands(handGestures, commandBus, speaker, phrasebook, modeController.mode, scope)

    /**
     * Called from `MainActivity.onStart` after permissions are granted, and once when first-launch
     * onboarding finishes (then with [speakWelcome] false: the last onboarding page already said it).
     */
    fun start(speakWelcome: Boolean = true) {
        modeController.start(speakWelcome)
        gestureCommands.start()
    }

    /**
     * R3: (re)start the ARCore for Jetpack XR session with the resumed [activity] (stack.md D7); no-op off the
     * headset. Waits for the previous [stop]'s `session.stop()` first: the session mutex serializes the two
     * calls but does not order them, and a stop that won the race would leave perception STOPPED while the
     * activity is visible. Called from `MainActivity.lifecycleScope` after [start].
     */
    suspend fun startPerception(activity: Activity) {
        val session = perception ?: return
        perceptionStop?.join()
        try {
            session.start(activity)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Jetpack XR throws "Session already exists for context" / "Session has been destroyed" when the activity
            // restarts faster than its old Session is torn down. Uncaught, it killed the app from lifecycleScope
            // (headset crash log, 2026-10-17). Navigation retries through ensureStarted(); the rest of the app runs.
            Log.w(RELIABILITY_TAG, "perception start skipped: ${e.message}")
        }
    }

    /**
     * Called from `MainActivity.onStop` (headset taken off, app backgrounded). Stops every loop the
     * controller owns, Navigation and the camera; keeps the graph scope, the loaded models and the TTS
     * engine so [start] can resume without a multi-second model reload.
     */
    fun stop() {
        gestureCommands.stop()
        modeController.stop()
        perceptionStop = perception?.let { session -> scope.launch { session.stop() } }
    }

    /**
     * Called once from `MainActivity.onDestroy` when the activity is finishing. Releases the native
     * resources that must not leak (`SpeechRecognizer.destroy()`, `TextToSpeech.shutdown()`, the
     * ExecuTorch modules) and cancels the graph scope. The graph cannot be restarted afterwards: [isReleased]
     * turns true first, so `VysionApplication.graph` hands the next activity a fresh graph.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        modeController.stop()
        commandBus.release()
        speaker.release()
        scope.launch {
            perception?.stop()
            inferenceEngine.release()
        }.invokeOnCompletion { scope.cancel() }
    }
}

/** Same tag as `MainActivity`'s recovered-failure lines (docs/nfr.md reliability evidence). */
private const val RELIABILITY_TAG = "vysion.reliability"

/** How often the reading crosshair re-measures the distance to what the wearer looks at. */
private const val GAZE_SAMPLE_MS = 200L

private suspend fun XrPerceptionSession.gazeDistanceM(): Float? = GazeGeometry.distanceAlongGaze(this)

/**
 * The SHA-1 of the APK's signing certificate as Google Cloud's Android key restriction expects it in `X-Android-Cert`
 * (40 upper-case hex digits, no colons). `null` if the package manager cannot tell; the cloud call then fails with 403
 * and Describe stays on the headset.
 */
/**
 * The Gemini client for cloud Describe (ADR-0010), or `null` without a key. Also used by the debug smoke receiver
 * (`--es model cloud`) so its requests carry the same app restriction headers as the engine's.
 */
fun cloudDescriber(app: Application): GeminiCloudDescriber? =
    GeminiCloudDescriber.orNull(BuildConfig.GEMINI_API_KEY, app.packageName, signingCertSha1(app))

private fun signingCertSha1(app: Application): String? = runCatching {
    val info = app.packageManager.getPackageInfo(
        app.packageName,
        android.content.pm.PackageManager.PackageInfoFlags.of(android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
    )
    val cert = info.signingInfo?.apkContentsSigners?.firstOrNull()?.toByteArray() ?: return@runCatching null
    java.security.MessageDigest.getInstance("SHA-1").digest(cert).joinToString("") { "%02X".format(it) }
}.getOrNull()
