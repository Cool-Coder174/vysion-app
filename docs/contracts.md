# Contracts: the handoff table as code

Source: SRS §8 handoff column, §6 design decisions. The code is [`app/src/main/kotlin/com/vysion/xr/core/Contracts.kt`](../app/src/main/kotlin/com/vysion/xr/core/Contracts.kt); this page is the index. **If this page and the code disagree, the code wins and this page has a bug.**

Rules (ADR-0005):

1. A module imports from `com.vysion.xr.core` and its own package. Nothing else.
2. Every cross-module call is a `Flow` or a `suspend fun`. Every `suspend fun` is main-safe.
3. Each role ships a `Fake*` of the contract it provides so consumers build before the real one exists.
4. Changing a signature: announce it, R5 merges it alone, update this page in the same commit.

## Who provides what, who consumes it

| Contract | Provided by | Consumed by | Lands at gate | SRS handoff text |
|---|---|---|---|---|
| `FrameSource` | R1 `platform.CameraFrameSource` | R1 `ModeController`, R3 `NavigationEngine` | 2 (Sat 13:00) | "FrameSource (analysis stream + still capture)" |
| `OverlayRenderer` | R1 `platform.PassthroughOverlayRenderer` | R3 (via `NavigationEngine.detections`), R1 `ModeController` | 6 | "OverlayRenderer" |
| `ModeController` (class, not an interface) | R1 | `MainActivity`, panels | 3 | "ModeController skeleton" |
| `InferenceEngine` | R2 `inference.ExecuTorchInferenceEngine` | R1 `ModeController`, R3 `NavigationEngine`, R5 diagnostics | 4 (Sat 16:00) | "InferenceEngine with describe(bitmap, prompt) and detect(bitmap) running on the headset" |
| `PromptLibrary` (object) | R2 | R1 `ModeController` | 4 | "PromptLibrary.kt with the four prompts" |
| `NavigationEngine` | R3 `perception.XrNavigationEngine` | R1 `ModeController`, R4 (cues → words), R1 overlay | 5 (Sat 19:00) | "NavigationCues flow (proximity, bearing, distance)" |
| `AnchorStore` | R3 `perception.XrAnchorStore` | R1 `ModeController`, R3 `NavigationEngine` | 5 | "AnchorStore" |
| `HeadPoseSource` | R3 | R1 (FR-T5), R3 | 5 | implied by "head pose" |
| `CommandBus` | R4 `voice.SpeechCommandBus` | R1 `ModeController`, panels | 3 (Sat 15:00) | "CommandBus (parsed commands as a flow)" |
| `Speaker` | R4 `voice.AndroidSpeaker` | R1 `ModeController`, R3 (through R1), transcript panel | 3 | "Speaker (queue, interrupt, rate, locale)" |
| `Cues` (object) | R4 | R1, R3 (never builds strings itself) | 3 | "every cue string in Cues.kt, nothing hardcoded elsewhere" |
| `DiagnosticsSource` | R5 `diagnostics.DiagnosticsCollector` | R5 overlay, R1 panel toggle | 6 | "Diagnostics overlay fed by InferenceEngine timing hooks and ActivityManager memory" |

## Signatures (abridged; KDoc in the file carries the FR ids)

```kotlin
// shared
data class NormalizedBox(left, top, right, bottom: Float)      // 0..1, origin top-left
data class Detection(label: String, confidence: Float, box: NormalizedBox)
enum class HorizontalBearing { LEFT, CENTER, RIGHT, NONE }
data class ModelInfo(name, quantization, backend: String, fileSizeBytes: Long, inputWidth, inputHeight: Int)
enum class Mode { TEXT_READER, AUDIO_DESCRIPTION, NAVIGATION }
enum class ModeState { IDLE, LISTENING, CAPTURING, INFERRING, SPEAKING }

// R1
interface FrameSource {
    val analysisFrames: Flow<Bitmap>; val stats: StateFlow<FrameStats?>
    suspend fun start(); suspend fun stop(); suspend fun captureStill(): Bitmap
}
interface OverlayRenderer { fun showDetections(List<Detection>); fun highlightDoor(NormalizedBox?); fun clear() }

// R2
interface InferenceEngine {
    val vlmInfo: StateFlow<ModelInfo?>; val detectorInfo: StateFlow<ModelInfo?>; val stats: StateFlow<InferenceStats>
    suspend fun warmUp()
    suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int = 60): String   // throws InferenceException
    fun describeStreaming(bitmap, prompt, maxNewTokens): Flow<String>                       // default wraps describe
    suspend fun detect(bitmap: Bitmap): List<Detection>
    suspend fun release()
}

// R3
sealed interface NavigationCue { Proximity(distanceM) · DoorBearing(clock, distanceM, inView) · WallAhead(distanceM) · FloorEnds(distanceM) · Arrived · TargetLost }
interface AnchorStore { suspend fun placeFromBearing(HorizontalBearing): AnchorId?; fun track(AnchorId): Flow<RelativeTarget>; suspend fun remove(AnchorId); suspend fun clear() }
interface NavigationEngine {
    val cues: Flow<NavigationCue>; val detections: Flow<List<Detection>>; val isRunning: StateFlow<Boolean>
    suspend fun start(); suspend fun pause(); suspend fun resume(); suspend fun stop()
    suspend fun guideTo(AnchorId); suspend fun clearTarget()
}
interface HeadPoseSource { val pose: StateFlow<HeadPose?> }

// R4
sealed interface Command { Read · Describe · GuideMe · FindDoor · Stop · Repeat · Slower · InSpanish · Next · Brief · Detailed · FollowUp(question) · SelectDoor(side) }
interface CommandBus { val commands: Flow<CommandEvent>; val state: StateFlow<ListeningState>; fun startListening(); fun stopListening(); fun inject(Command, CommandSource = PANEL) }
interface Speaker {
    val progress: Flow<SpeechProgress>; val locale: StateFlow<Locale>; val isSpeaking: StateFlow<Boolean>
    suspend fun say(text: String, interrupt: Boolean = false)   // suspends until done or interrupted
    fun stop()                                                  // ≤ 300 ms
    fun earcon(Earcon); fun setRate(Float); fun setLocale(Locale); suspend fun repeatLast(rate: Float = 1f)
}

// R5
data class DiagnosticsSnapshot(vlm, detector: ModelInfo?, backend: String?, lastVlmMs, medianVlmMs: Long?, detectorFps: Float?, appMemoryMb: Int?, networkPermission: String?, measuredAtMs: Long)
interface DiagnosticsSource { val snapshot: StateFlow<DiagnosticsSnapshot> }
```

## Sequencing rules that live between the contracts

| Rule | Where enforced | Why |
|---|---|---|
| `NavigationEngine.pause()` before any VLM call, `resume()` after | `ModeController` | ADR-0004: the detector is unloaded inside `describe`; the pause makes that visible and intentional |
| Nothing inside `InferenceEngine` calls another module | code review | deadlock-free under the single `Mutex` |
| Only `Speaker.say` produces speech; only `Cues` and the VLM produce text | code review, `scripts/check_cues.sh` (R4 adds) | SRS §8 R4 "nothing hardcoded elsewhere" |
| `Bitmap` returned by `captureStill()` is owned by the caller and recycled by it | KDoc | memory budget |
| `null` in any stats field means "not measured", never 0 | overlay rendering | NFR "Honesty" |
| `Speaker.stop()` is non-suspending | signature | FR-G4 300 ms budget cannot wait on a dispatcher |

## Pure helpers every role shares

`core/Units.kt`: `ClockFace.fromRelativeYawDeg(yawDeg): Int` (12 straight ahead, 3 right, 9 left) and `Distances.roundToHalfMeter(m)`, with the SRS thresholds as constants (`PROXIMITY_THRESHOLD_M = 1.0`, `ARRIVAL_RADIUS_M = 1.0`, `PLANE_CUE_RANGE_M = 1.5`). Tested in `app/src/test/kotlin/com/vysion/xr/core/UnitsTest.kt`.
