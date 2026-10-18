# Contracts: the handoff table as code

Source: SRS §8 handoff column, §6 design decisions. The code is [`app/src/main/kotlin/com/vysion/xr/core/Contracts.kt`](../app/src/main/kotlin/com/vysion/xr/core/Contracts.kt); the constructors and the lifecycle are in [`app/src/main/kotlin/com/vysion/xr/AppGraph.kt`](../app/src/main/kotlin/com/vysion/xr/AppGraph.kt). This page is the index. **If this page and the code disagree, the code wins and this page has a bug.**

Rules (ADR-0005):

1. A module imports from `com.vysion.xr.core` and its own package. Nothing else. Only the root package (`AppGraph`, `MainActivity`, `VysionApplication`) imports every role, because it wires them. Test sources may import another role's public `Fake*` (ADR-0005: consumers test against the provider's fake; today `ModeControllerTest` and `DiagnosticsCollectorTest` use `inference.FakeInferenceEngine`).
2. Every cross-module call is a `Flow` or a `suspend fun`. Every `suspend fun` is main-safe. Exceptions: the non-suspending `Speaker.stop()` (FR-G4) and the plain-value seams `ModePhrasebook` / `PromptSet`.
3. Each role ships a `Fake*` (or `NoOp*`) of the contract it provides so consumers build before the real one exists, except `CommandBus` / `Speaker` (the real `SpeechCommandBus` / `AndroidSpeaker` run anywhere on stub ports; tests use private doubles) and `DiagnosticsSource` (only the overlay consumes it). The two seams ship an `Unwired` placeholder instead (blank values, nothing spoken or prompted).
4. Changing a signature: announce it, R5 merges it alone, update this page in the same commit.

## Who provides what, who consumes it

| Contract | Provided by | Consumed by | Lands at gate | SRS handoff text |
|---|---|---|---|---|
| `FrameSource` | R1 `platform.CameraFrameSource` | R1 `ModeController`, R3 `NavigationEngine` | 2 (Sat 13:00) | "FrameSource (analysis stream + still capture)" |
| `OverlayRenderer` | R1 `platform.PassthroughOverlayRenderer` | R3 (via `NavigationEngine.detections`), R1 `ModeController` | 6 | "OverlayRenderer" |
| `ModeController` (class, not an interface) | R1 | `MainActivity`, panels | 3 | "ModeController skeleton" |
| `InferenceEngine` | R2 `inference.ExecuTorchInferenceEngine` | R1 `ModeController`, R3 `NavigationEngine`, R5 diagnostics | 4 (Sat 16:00) | "InferenceEngine with describe(bitmap, prompt) and detect(bitmap) running on the headset" |
| `PromptSet` (seam in `core`) | R2 `inference.LibraryPromptSet` (object; reads the text from `inference.PromptLibrary`, the only file that holds prompts) | R1 `ModeController(prompts = …)`; R5 debug `BenchmarkReceiver` reads `graph.prompts.read`. Wired by `AppGraph`: `val prompts: PromptSet = LibraryPromptSet` | 4 | "PromptLibrary.kt with the four prompts" |
| `NavigationEngine` | R3 `perception.XrNavigationEngine` | R1 `ModeController`, R4 (cues → words), R1 overlay | 5 (Sat 19:00) | "NavigationCues flow (proximity, bearing, distance)" |
| `AnchorStore` | R3 `perception.XrAnchorStore` | R1 `ModeController`, R3 `NavigationEngine` | 5 | "AnchorStore" |
| `HeadPoseSource` | R3 | R1 (FR-T5), R3 | 5 | implied by "head pose" |
| `HandGestureSource` | R3 `perception.XrHandGestures` | R1 `GestureCommands` | added 2026-10-17 | eyes-free control for blind users (FR-G2) |
| `CommandBus` | R4 `voice.SpeechCommandBus` | R1 `ModeController`, panels; `AppGraph.release()` (`release()`) | 3 (Sat 15:00) | "CommandBus (parsed commands as a flow)" |
| `Speaker` | R4 `voice.AndroidSpeaker` | R1 `ModeController`, R3 (through R1), transcript panel; `AppGraph.release()` (`release()`) | 3 | "Speaker (queue, interrupt, rate, locale)" |
| `ModePhrasebook` (seam in `core`) | R4 `voice.CuesPhrasebook` (object; every sentence comes from `voice.Cues`, follows `Speaker.locale` after `bind(speaker)`) | R1 `ModeController(phrasebook = …)`; R3 never builds strings, R1 turns each `NavigationCue` into words through `navigationCue(cue)`. Wired by `AppGraph`: `phrasebook = CuesPhrasebook.also { it.bind(speaker) }` | 3 | "every cue string in Cues.kt, nothing hardcoded elsewhere" |
| `DiagnosticsSource` | R5 `diagnostics.DiagnosticsCollector` | R5 overlay, R1 panel toggle | 6 | "Diagnostics overlay fed by InferenceEngine timing hooks and ActivityManager memory" |
| Lifecycle (`AppGraph.start()` / `startPerception()` / `stop()` / `release()`) | root `AppGraph` (ADR-0008) | `MainActivity`: `start()` from `onStart` once permissions allow, followed by `lifecycleScope.launch { graph.startPerception(this) }` (joins the previous `stop()`'s perception teardown, then starts R3 `XrPerceptionSession`; no-op off-XR or with fakes); `stop()` from `onStop`; `release()` once from `onDestroy` when `isFinishing`. `start`/`stop` follow `onStart`/`onStop` and are **reversible** (headset off and on restarts the app); `release()` runs **once**, sets `isReleased`, and `VysionApplication.graph` then builds a fresh graph for the next activity | 3 | (skeleton; SRS §6 "every module exposes a Flow or a suspend fun") |

## Signatures (abridged; KDoc in the file carries the FR ids)

Copied from `Contracts.kt`; bodies and KDoc dropped, several members per line.

```kotlin
// shared
data class NormalizedBox(val left: Float, val top: Float, val right: Float, val bottom: Float)   // 0..1, origin top-left
data class Detection(val label: String, val confidence: Float, val box: NormalizedBox)
enum class HorizontalBearing { LEFT, CENTER, RIGHT, NONE }
data class ModelInfo(val name: String, val quantization: String, val backend: String, val fileSizeBytes: Long, val inputWidth: Int, val inputHeight: Int)
enum class Mode { TEXT_READER, AUDIO_DESCRIPTION, NAVIGATION }
enum class ModeState { IDLE, LISTENING, CAPTURING, INFERRING, SPEAKING }

// R1
data class FrameStats(val cameraId: String, val width: Int, val height: Int, val format: String, val fps: Float)
interface FrameSource {
    val analysisFrames: Flow<Bitmap>; val stats: StateFlow<FrameStats?>
    suspend fun start(); suspend fun stop(); suspend fun captureStill(): Bitmap
}
interface OverlayRenderer { fun showDetections(detections: List<Detection>); fun highlightDoor(box: NormalizedBox?); fun clear() }

// R2
class InferenceException(message: String, cause: Throwable? = null) : Exception(message, cause)
data class InferenceStats(val vlmLoaded: Boolean = false, val detectorLoaded: Boolean = false, val lastVlmMs: Long? = null, val medianVlmMs: Long? = null, val p90VlmMs: Long? = null, val vlmRuns: Int = 0, val lastDetectMs: Long? = null, val detectorFps: Float = 0f, val lastDetectAtNanos: Long? = null)
interface InferenceEngine {
    val vlmInfo: StateFlow<ModelInfo?>; val detectorInfo: StateFlow<ModelInfo?>; val stats: StateFlow<InferenceStats>   // info null until that model LOADED
    suspend fun warmUp()                                         // idempotent; ModeController.start() calls it on every onStart
    suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS): String   // throws InferenceException
    fun describeStreaming(bitmap: Bitmap, prompt: String, maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS): Flow<String>   // default wraps describe
    suspend fun detect(bitmap: Bitmap): List<Detection>
    suspend fun readText(bitmap: Bitmap): List<String>? = null   // FR-T1 OCR fast path: lines in reading order, [] = no text, null = no OCR model (use describe + READ)
    suspend fun release()                                        // once, from AppGraph.release() (onDestroy when finishing), never onStop
    companion object { const val DEFAULT_MAX_NEW_TOKENS: Int = 60; const val VLM_TIMEOUT_MS: Long = 15_000 }
}

// R3
sealed interface NavigationCue {
    data class Proximity(val distanceM: Float)
    data class DoorBearing(val clock: Int, val distanceM: Float, val inView: Boolean, val pinged: Boolean = true)   // pinged=false: A-6 failed, consumer plays Earcon.PING
    data class WallAhead(val distanceM: Float); data class FloorEnds(val distanceM: Float)
    data object Arrived; data object TargetLost
}
@JvmInline value class AnchorId(val value: String)
data class RelativeTarget(val bearingDeg: Float, val clock: Int, val distanceM: Float, val inView: Boolean)
data class HeadPose(val yawDeg: Float, val pitchDeg: Float, val rollDeg: Float, val angularSpeedDegPerS: Float, val timestampNanos: Long)
interface HeadPoseSource { val pose: StateFlow<HeadPose?> }
enum class HandGesture { TALK_START, TALK_END, ACTIVATE, NEXT_PAGE, STOP }   // middle pinch hold/tap, ring tap, palm out; never index, never palm-toward-user
interface HandGestureSource { val gestures: Flow<HandGesture> }
// ModePhrasebook.pageFocused(mode): String = modeAnnounced(mode)   // spoken on NEXT_PAGE: page name + how to run it
interface AnchorStore {
    suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId?; fun track(id: AnchorId): Flow<RelativeTarget>
    suspend fun remove(id: AnchorId); suspend fun clear()
}
interface NavigationEngine {
    val cues: Flow<NavigationCue>; val detections: Flow<List<Detection>>; val isRunning: StateFlow<Boolean>
    suspend fun start(); suspend fun pause(); suspend fun resume(); suspend fun stop()
    suspend fun guideTo(anchor: AnchorId); suspend fun clearTarget()
}

// R4
sealed interface Command { Read · Describe · GuideMe · FindDoor · Stop · Repeat · Slower · InSpanish · Next · Brief · Detailed · FollowUp(val question: String) · SelectDoor(val side: HorizontalBearing) }
enum class ListeningState { IDLE, LISTENING, PROCESSING, UNAVAILABLE }
enum class CommandSource { VOICE, PINCH, PANEL, TEST }
data class CommandEvent(val command: Command, val source: CommandSource, val recognizedAtNanos: Long)
interface CommandBus {
    val commands: Flow<CommandEvent>; val state: StateFlow<ListeningState>
    fun startListening(); fun stopListening(); fun inject(command: Command, source: CommandSource = CommandSource.PANEL)
    fun cancelListening() = stopListening()   // designs' Cancel while LISTENING: R4 discards; default delivers
    fun setLocale(locale: Locale) {}   // FR-G6: recognition follows "in Spanish"; default no-op
    fun release() {}                   // SpeechRecognizer.destroy(), once from AppGraph.release(); default no-op
}
enum class Earcon { LISTENING, THINKING, DONE, ERROR, PROXIMITY, PING }
data class SpeechProgress(val utteranceId: String, val text: String, val charStart: Int, val charEnd: Int, val done: Boolean)
interface Speaker {
    val progress: Flow<SpeechProgress>; val locale: StateFlow<Locale>; val isSpeaking: StateFlow<Boolean>
    suspend fun say(text: String, interrupt: Boolean = false)   // suspends until done or interrupted
    fun stop()                                                  // ≤ STOP_LATENCY_BUDGET_MS, non-suspending
    fun earcon(kind: Earcon); fun setRate(rate: Float); fun setLocale(locale: Locale); suspend fun repeatLast(rate: Float = 1.0f)
    fun release() {}                                            // TextToSpeech.shutdown(), once from AppGraph.release(); default no-op
    companion object {
        val EN_US: Locale = Locale.forLanguageTag("en-US"); val ES_MX: Locale = Locale.forLanguageTag("es-MX")
        const val SLOWER_RATE: Float = 0.7f; const val STOP_LATENCY_BUDGET_MS: Long = 300
    }
}

// Seams ModeController (R1) speaks and prompts through; R4 and R2 implement them, AppGraph passes them (ADR-0005)
interface ModePhrasebook {
    val welcome: String; val busy: String; val inferenceFailed: String; val doorNotFound: String
    val navigationUnavailable: String; val holdStill: String; val permissionExplanation: String; val permissionDenied: String
    val voiceCommandsUnavailable: String get() = ""; val cameraLost: String get() = ""; val modelNotFound: String get() = ""   // additive, default blank
    fun modeAnnounced(mode: Mode): String
    fun navigationCue(cue: NavigationCue): String?   // null = nothing to say (e.g. Arrived runs the ARRIVAL prompt)
    fun onboarding(step: OnboardingStep): OnboardingPage   // designs pages 2-8: shown AND read aloud; default blank page
    val stopPractice: String get() = ""                    // onboarding page 4: Vysion talks until the user stops it
    object Unwired : ModePhrasebook                  // every value "", navigationCue → null; blank is skipped, never spoken
}
enum class OnboardingStep { WELCOME, PRIVACY, TALK, STOP, SOUNDS, SAFETY, ALL_SET }
data class OnboardingItem(val title: String, val detail: String? = null)
data class OnboardingPage(val step: OnboardingStep, val title: String, val body: String? = null, val items: List<OnboardingItem> = emptyList(),
                          val hint: String? = null, val footnote: String? = null, val spoken: String)
interface PromptSet {
    val read: String; val describe: String; val door: String; val arrival: String   // the four P0 prompts
    val translateEs: String? get() = null; val describeBrief: String? get() = null; val describeDetailed: String? get() = null
    fun followUp(question: String): String? = null                                   // P1 variants: null = not available yet
    fun forLocale(prompt: String, locale: Locale): String = prompt                   // FR-G6: R2 appends "Respond in Spanish." (DOOR exempt)
    val noTextSentinel: String? get() = null                                         // the READ prompt's "No text found"; the UI shows "move closer"
    object Unwired : PromptSet                                                       // the four P0 prompts are ""
}

// R5
data class DiagnosticsSnapshot(val vlm: ModelInfo? = null, val detector: ModelInfo? = null, val backend: String? = null, val lastVlmMs: Long? = null, val medianVlmMs: Long? = null, val detectorFps: Float? = null, val appMemoryMb: Int? = null, val networkPermission: String? = null, val measuredAtMs: Long = 0L, val detectorLoaded: Boolean? = null)   // detectorLoaded: INV-2 monitor
interface DiagnosticsSource { val snapshot: StateFlow<DiagnosticsSnapshot> }
```

`AppGraph` (root package, not in `Contracts.kt`; see its KDoc table for every constructor):

```kotlin
class AppGraph(private val app: Application) {
    val prompts: PromptSet = LibraryPromptSet
    val perception: XrPerceptionSession?          // null on a non-XR device or with -PuseFakes=true
    val isReleased: Boolean                       // true after release(); VysionApplication.graph then builds a new graph
    fun start()      // MainActivity.onStart (after permissions): ModeController.start()
    suspend fun startPerception(activity: Activity)   // after start(): joins the last stop()'s perception teardown, then session.start
    fun stop()       // MainActivity.onStop: ModeController.stop() + perception.stop(); keeps scope, models, TTS. Reversible.
    fun release()    // MainActivity.onDestroy when isFinishing, once: CommandBus.release(), Speaker.release(),
                     // perception.stop(), InferenceEngine.release(), then cancels the graph scope
    // init: CameraFrameSource.stillEdge follows InferenceEngine.vlmInfo.inputWidth (the still is the VLM input size)
}
```

## Sequencing rules that live between the contracts

| Rule | Where enforced | Why |
|---|---|---|
| `NavigationEngine.pause()` before any VLM call, `resume()` after | `ModeController` | ADR-0004: the detector is unloaded inside `describe`; the pause makes that visible and intentional |
| The detector is unloaded at the start of every VLM call and reloaded **lazily** by the next `detect()`, not eagerly after `describe` returns (the two door-vote calls do not churn it) | R2 `ExecuTorchInferenceEngine` (`describe` KDoc; nfr.md Memory row) | ADR-0004 / INV-2: peak memory is one model plus the runtime. The `InferenceEngine` KDoc in `Contracts.kt` still says "unloaded and reloaded after"; the lazy reload satisfies it, and the one-word KDoc fix waits for R5's next contract merge |
| Nothing inside `InferenceEngine` calls another module | code review | deadlock-free under the single `Mutex` |
| Only `Speaker.say` produces speech; only `voice/Cues.kt` (through `ModePhrasebook`) and the VLM produce text; a blank phrasebook value is skipped, never spoken | code review, `scripts/check_cues.sh` (CI step), `ModeController.say` | SRS §8 R4 "nothing hardcoded elsewhere"; INV-4 |
| Prompt text lives only in `inference/PromptLibrary.kt`; everything else reads it through `PromptSet` (`AppGraph.prompts`) | code review | SRS §7; ADR-0005 (no `platform` → `inference` import) |
| `Bitmap` returned by `captureStill()` is owned by the caller and recycled by it | KDoc | memory budget |
| `null` in any stats field means "not measured", never 0 | overlay rendering | NFR "Honesty" |
| `Speaker.stop()` is non-suspending | signature | FR-G4 300 ms budget cannot wait on a dispatcher |
| `AppGraph.stop()` (`onStop`) is reversible: it stops the `ModeController` loops, Navigation, the frames and the perception session, and keeps the graph scope, the loaded models and the TTS engine; `AppGraph.start()` after it restarts the app | `AppGraph`, `MainActivity` | taking the headset off and on must not cost a multi-second model reload |
| `AppGraph.release()` runs once, from `onDestroy` when `isFinishing` (the manifest's `configChanges` keeps rotation and window resizes from recreating the activity): `CommandBus.release()`, `Speaker.release()`, `InferenceEngine.release()`, then the graph scope is cancelled; `VysionApplication.graph` hands the next activity a fresh graph | `AppGraph`, `VysionApplication`, `MainActivity` | `SpeechRecognizer.destroy()` and `TextToSpeech.shutdown()` are mandatory and cannot be undone |
| `InferenceEngine.warmUp()` is idempotent (a loaded model is never reloaded or loaded twice) | R2 `ExecuTorchInferenceEngine` (`if (vlm != null) return`, guards in `loadVlmLocked` / `loadDetectorLocked`) | `ModeController.start()` calls it on every `onStart`; a second `LlmModule` beside the first would double PSS (RK-3) |
| "in Spanish" sets both `Speaker.setLocale(Speaker.ES_MX)` and `CommandBus.setLocale(Speaker.ES_MX)`, and every later prompt goes through `PromptSet.forLocale` | `ModeController` (wired; `ModeControllerTest` "in Spanish switches the voice and the recognizer to es-MX together") | FR-G6: recognition, speech and VLM output follow the same language |
| `DoorBearing.pinged == false` → the consumer plays `Earcon.PING` through `Speaker.earcon` with the spoken bearing | R3 `XrNavigationEngine` pings first on each guidance tick, then emits `DoorBearing(…, pinged = pinger.isSpatial != false)` (false while the ping cannot play); R1 `ModeController.onCue` draws the band (`DoorHighlight`), plays `Earcon.PING` when `pinged` is false, then speaks `phrasebook.navigationCue(cue)` (all wired and tested) | A-6 fallback: R3 holds no `Speaker` and draws nothing |
| Hazard cues (Proximity, WallAhead, FloorEnds) never wait behind a guidance utterance | `ModeController` (separate hazard lane; `ModeControllerTest`) | SRS §3 "hazards first"; FR-N1 ≤ 300 ms |
| `CommandBus.state == UNAVAILABLE` → `ModePhrasebook.voiceCommandsUnavailable` once per run, after the welcome | `ModeController` (`ModeControllerTest`) | A-7 / RK-4: the audio twin of the gray mic |

## Open contract proposals (not merged; R5 merges each alone, AGENTS.md §6)

Each is additive (a default value or a default body), so no caller breaks. Until R5 merges one, code against the signatures above.

| # | Proposal | Why | Who uses it | Status |
|---|---|---|---|---|
| P-1 | `suspend fun placeFromBearing(bearing: HorizontalBearing, capturedAtNanos: Long? = null): AnchorId?` on `AnchorStore` | FR-N3: the VLM's left / center / right is relative to the head at `captureStill()` time, but the store reads the head yaw when it is called, after both door answers (up to 2 × 15 s later); a user who turns meanwhile gets an anchor in the wrong direction | R1 passes `System.nanoTime()` taken right after the first `captureStill()`; R3 keeps a 30 s ring buffer of head poses stamped with `System.nanoTime()` (not `RawHeadPose.timestampNanos`, which is `elapsedRealtimeNanos`) and uses the sample nearest to it, else the current pose; `NoOpAnchorStore` and the test fakes ignore it | open (r3 page Contract issue 6, r1 page open issue 10) |
| P-2 | `val noTextFound: String get() = ""` on `ModePhrasebook`; `val noTextSentinel: String? get() = null` on `PromptSet` (`LibraryPromptSet` returns `PromptLibrary.NO_TEXT_SENTINEL`) | FR-T1: today the READ answer "No text found." is spoken in the model's own words; this lets R1 speak R4's fixed line instead (en-US "No text found.", es-MX "No encontré texto.", `Key.NO_TEXT_FOUND`), matching the answer case-insensitively against the sentinel | R1 `ModeController.captureAndDescribe` (Read only); R4 `CuesPhrasebook`; R2 `LibraryPromptSet` | half done 2026-10-17: `PromptSet.noTextSentinel` landed (the UI shows the designs' "move closer" screen); the spoken `noTextFound` line is still open, P1 (r4 page "Who says what") |
| P-3 | `val box: NormalizedBox? = null` on `NavigationCue.DoorBearing` | the door highlight is a left / center / right band from the clock hour, not the door's outline | R3 fills it when the detector (or the P2 door class, FR-N8) sees the door; R1 draws it instead of `DoorHighlight` | open (r1 page open issue 2) |
| P-4 | `data class PointingRegion(val box: NormalizedBox)` and a `PointingSource` | FR-D4 (P2) region-only describe | R3 provides, R1 crops | not started (r3 page Contract issue 5) |

## Pure helpers every role shares

`core/Units.kt`: `ClockFace.fromRelativeYawDeg(yawDeg): Int` (12 straight ahead, 3 right, 9 left) and `Distances.roundToHalfMeter(m)`, with the SRS thresholds as constants (`PROXIMITY_THRESHOLD_M = 1.0`, `ARRIVAL_RADIUS_M = 1.0`, `PLANE_CUE_RANGE_M = 1.5`). Tested in `app/src/test/kotlin/com/vysion/xr/core/UnitsTest.kt`.
