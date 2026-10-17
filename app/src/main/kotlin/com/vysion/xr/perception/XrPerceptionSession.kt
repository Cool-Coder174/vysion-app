package com.vysion.xr.perception

import android.app.Activity
import android.app.Application
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import androidx.xr.arcore.Anchor
import androidx.xr.runtime.Session
import com.vysion.xr.core.AnchorId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.ref.WeakReference
import kotlin.math.sqrt

/*
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 *  R3 · Spatial Perception — the ARCore for Jetpack XR session and the raw perception streams.
 *
 *  Everything below the "Value types" banner is pure Kotlin (no Android, no androidx.xr) so the
 *  cone math, plane semantics and relative geometry run in JVM unit tests. The session class at
 *  the bottom is the only place the XR runtime is touched, and in this skeleton every one of
 *  those touches is a stub that documents the exact call from docs/stack.md → R3.
 *
 *  Owner: R3 · SRS §8 "ARCore for Jetpack XR session, depth, planes, anchors, hands" · due Sat 12:00
 *  (session, A-5) and Sat 13:00 (spatial audio, A-6).
 * ═══════════════════════════════════════════════════════════════════════════════════════════════
 */

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Value types shared by every R3 file (pure; mirror androidx.xr.runtime.math without depending on it)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Plain 3-vector in the XR world frame, meters. OpenXR / Jetpack XR convention: +X right, +Y up,
 * **-Z forward** (`Pose.forward` is `rotation * (0, 0, -1)`). Mirrors `androidx.xr.runtime.math.Vector3`
 * so pure code and JVM tests never import the XR runtime (ADR-0005 keeps `core` the only shared package;
 * these types stay inside `perception`).
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(dot(this))
    fun normalized(): Vec3 = if (length == 0f) this else this * (1f / length)

    companion object {
        val ZERO: Vec3 = Vec3(0f, 0f, 0f)
        val UP: Vec3 = Vec3(0f, 1f, 0f)

        /** World forward at yaw 0 (-Z). */
        val FORWARD: Vec3 = Vec3(0f, 0f, -1f)
    }
}

/** 2-vector in a plane's local frame (`Plane.State.vertices` are `Vector2`), meters. */
data class Vec2(val x: Float, val y: Float)

/**
 * Field of view of the depth image in degrees. On the device it comes from
 * `RenderViewpoint.left/mono(session).state.value.fieldOfView` (`androidx.xr.runtime.math.FieldOfView`:
 * `angleLeft`, `angleRight`, `angleUp`, `angleDown`, radians per the OpenXR `XrFovf` convention,
 * left/down negative; the unit is `// stack.md: UNVERIFIED`): horizontal = angleRight - angleLeft,
 * vertical = angleUp - angleDown, both converted to degrees. Never a hardcoded constant (NFR Honesty,
 * INV-7): a depth frame without a measured FOV is not emitted.
 */
data class FovDeg(val horizontal: Float, val vertical: Float)

/** Mirrors `androidx.xr.arcore.TrackingState` (TRACKING, PAUSED, STOPPED). */
enum class Tracking { TRACKING, PAUSED, STOPPED }

/** Mirrors `androidx.xr.arcore.PlaneLabel` (UNKNOWN, WALL, FLOOR, CEILING, TABLE). */
enum class PlaneKind { WALL, FLOOR, CEILING, TABLE, UNKNOWN }

enum class HandSide { LEFT, RIGHT }

/**
 * One depth map, copied out of `Depth.State.smoothDepthMap` (`FloatBuffer`, meters, row-major,
 * index = y * width + x, `0f` = no data) and `smoothConfidenceMap` (`ByteBuffer`, 0..255, 255 = most
 * confident; `null` when the runtime does not provide it). [timestampNanos] is
 * `SystemClock.elapsedRealtimeNanos()` at copy time (monotonic, H-21) so the FR-N1 "≤ 300 ms after
 * threshold crossed" latency can be measured against the cue's emit time.
 *
 * Not a `data class`: arrays compare by identity and a depth map is never compared.
 */
class DepthFrame(
    val width: Int,
    val height: Int,
    val depthM: FloatArray,
    val confidence: ByteArray?,
    val fov: FovDeg,
    val timestampNanos: Long,
) {
    init {
        require(width > 0 && height > 0) { "DepthFrame needs a positive size: ${width}x$height" }
        require(depthM.size == width * height) { "depthM has ${depthM.size} values, expected ${width * height}" }
        require(confidence == null || confidence.size == width * height) { "confidence size mismatch" }
    }

    /** Depth at a pixel, meters; `0f` means no data. */
    fun depthAt(x: Int, y: Int): Float = depthM[y * width + x]

    /** Confidence 0..255 at a pixel; 255 when the runtime provides no confidence map. */
    fun confidenceAt(x: Int, y: Int): Int = confidence?.let { it[y * width + x].toInt() and 0xFF } ?: 255
}

/**
 * One tracked plane from `Plane.subscribe(session)` / `plane.state.value` (FR-N5).
 *
 * Frame conventions (`// stack.md: UNVERIFIED` which axis of `centerPose` is the normal; the
 * ARCore convention is local +Y = normal, +X / +Z span the plane and `vertices` are (x, z) in that
 * local frame): [center] = `centerPose.translation`, [normal] = `centerPose.up`, [axisX] =
 * `centerPose.right`, [axisZ] = `-centerPose.forward` (local +Z), all in world meters; [polygon] =
 * `vertices` as (x, z) in plane-local meters. The first device run (A-5) confirms the mapping by
 * logging a FLOOR plane's normal, which must be ≈ (0, 1, 0).
 */
data class PlaneObservation(
    val id: String,
    val kind: PlaneKind,
    val tracking: Tracking,
    val center: Vec3,
    val normal: Vec3,
    val axisX: Vec3,
    val axisZ: Vec3,
    val polygon: List<Vec2>,
    val timestampNanos: Long,
)

/**
 * One hand from `Hand.left/right(session).state.value` (FR-G2 pinch is R1's P1; FR-D4 pointing is
 * R3's P2). Joints are `handJoints[HandJointType.THUMB_TIP]`, `[INDEX_TIP]`, `[INDEX_PROXIMAL]`
 * transformed with `session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace)`
 * (stack.md R3 "Hands"). `null` joint = not tracked this frame.
 */
data class HandObservation(
    val side: HandSide,
    val tracking: Tracking,
    val thumbTip: Vec3?,
    val indexTip: Vec3?,
    val indexProximal: Vec3?,
    val timestampNanos: Long,
)

/**
 * Head pose from `ArDevice.getInstance(session).state.value.devicePose` (needs
 * `DeviceTrackingMode.SPATIAL` + `HEAD_TRACKING`; stack.md R3 "Head pose").
 *
 * Angle conventions used by every R3 file (and by `HeadPose` in `core`, which this maps to):
 * - [yawDeg]: heading, 0 = facing world -Z, **positive = turned to the right** (clockwise seen from
 *   above), normalized to (-180, 180]. Matches `RelativeTarget.bearingDeg` ("positive to the right").
 * - [pitchDeg]: positive = looking up. [rollDeg]: positive = right ear down.
 * Built from the pose's forward and up vectors by [HeadAngles.fromForwardUp] (pure, tested).
 */
data class RawHeadPose(
    val position: Vec3,
    val yawDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val tracking: Tracking,
    val timestampNanos: Long,
) {
    /** Horizontal forward unit vector for [yawDeg]: yaw 0 → (0, 0, -1); yaw +90 → (1, 0, 0) = right. */
    val forwardFlat: Vec3 get() = HeadAngles.forwardForYaw(yawDeg)
}

/** Pose of a native anchor from `anchor.state.value.pose / .trackingState` (FR-N3, FR-N4). */
data class AnchorPoseSample(
    val id: AnchorId,
    val position: Vec3,
    val tracking: Tracking,
    val timestampNanos: Long,
)

/** Lifecycle of the XR session. `UNAVAILABLE` carries no number and renders as "not available" (INV-7). */
enum class SessionState { NOT_STARTED, STARTING, RUNNING, UNAVAILABLE, STOPPED }

/**
 * Opaque handle to the SceneCore `Entity` parented under an anchor's `AnchorSpace`, the point at
 * which [SpatialAudioPinger] plays (stack.md R3 rows "Spatial audio" and "Entities"). `native` holds
 * the `androidx.xr.scenecore.Entity` once FR-N3 lands; it is `Any?` here so the pinger stays
 * compilable while the SceneCore entity API is alpha (RK-10).
 */
class AnchorEntity internal constructor(val anchorId: AnchorId, internal val native: Any?)

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// Pure helpers: head angles
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/** Angle helpers shared by [RawHeadPose], [RelativeGeometry] and [PlaneSemantics]. Pure, tested. */
object HeadAngles {
    /** Normalizes any angle to (-180, 180]. */
    fun wrapDeg(deg: Float): Float {
        var d = deg % 360f
        if (d <= -180f) d += 360f
        if (d > 180f) d -= 360f
        return d
    }

    /** Horizontal forward unit vector for a yaw (0 → -Z, +90 → +X). */
    fun forwardForYaw(yawDeg: Float): Vec3 {
        val r = Math.toRadians(yawDeg.toDouble())
        return Vec3(Math.sin(r).toFloat(), 0f, -Math.cos(r).toFloat())
    }

    /**
     * Yaw / pitch / roll (degrees, conventions in [RawHeadPose]) from a pose's forward and up unit
     * vectors (`Pose.forward`, `Pose.up`). Returns (yaw, pitch, roll).
     */
    fun fromForwardUp(forward: Vec3, up: Vec3): Triple<Float, Float, Float> {
        val f = forward.normalized()
        val yaw = Math.toDegrees(Math.atan2(f.x.toDouble(), (-f.z).toDouble())).toFloat()
        val pitch = Math.toDegrees(Math.asin(f.y.toDouble().coerceIn(-1.0, 1.0))).toFloat()
        // right = forward × up; with no roll it is horizontal (right.y == 0) and up.y > 0.
        val right = f.cross(up.normalized())
        val roll = Math.toDegrees(Math.atan2((-right.y).toDouble(), up.y.toDouble())).toFloat()
        return Triple(wrapDeg(yaw), pitch, wrapDeg(roll))
    }
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The port the other R3 classes consume (so unit tests can fake the device)
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * What [XrNavigationEngine], [XrAnchorStore], [XrHeadPoseSource] and [SpatialAudioPinger] need from
 * the session. [XrPerceptionSession] is the only production implementation; the tests use
 * `FakePerceptionSource`. Internal on purpose: it never crosses the package (ADR-0005).
 */
internal interface PerceptionSource {
    /** Depth maps, latest-wins (a depth map is ~100 KB; nothing may queue them). */
    val depthFrames: Flow<DepthFrame>

    /** All currently known planes; replaced wholesale on every runtime update. */
    val planes: StateFlow<List<PlaneObservation>>

    val hands: Flow<HandObservation>

    /** `null` until `DeviceTrackingMode.SPATIAL` delivers the first pose. */
    val headPose: StateFlow<RawHeadPose?>

    /** Idempotent; never throws (the skeleton app must launch, AGENTS.md §5). */
    suspend fun ensureStarted()

    /**
     * FR-N3 "samples depth along that bearing": world point hit along [worldYawDeg] / [pitchDeg]
     * from the current head position, or `null` when no trusted depth exists there (RK-5: only
     * distances we trust).
     */
    suspend fun sampleDepthAlongBearing(worldYawDeg: Float, pitchDeg: Float): Vec3?

    /** Native anchor at [position]; `null` on `AnchorCreateTrackingUnavailable` / `ResourcesExhausted`. */
    suspend fun createAnchor(position: Vec3): AnchorId?

    /**
     * Pose stream of one anchor. Emits a sample with [Tracking.STOPPED] as its last value when the
     * anchor is detached or the runtime stops tracking it; an unknown id emits one STOPPED sample.
     */
    fun anchorPose(id: AnchorId): Flow<AnchorPoseSample>

    suspend fun detachAnchor(id: AnchorId)
    suspend fun detachAllAnchors()
}

// ─────────────────────────────────────────────────────────────────────────────────────────────────
// The session
// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Owns the ARCore for Jetpack XR [Session] and turns its `StateFlow`s into the raw streams the rest
 * of `perception` consumes ([PerceptionSource]). Provides nothing to other packages directly: R1
 * reaches head pose through [XrHeadPoseSource], navigation through [XrNavigationEngine], anchors
 * through [XrAnchorStore] (all `core` contracts).
 *
 * - FR ids: FR-N1 (depth), FR-N3/FR-N4 (anchors, head pose), FR-N5 (planes), FR-G2 P1 / FR-D4 P2 (hands).
 * - SRS due: Sat 12:00 "ARCore for Jetpack XR session … depth, plane, hand, and device-pose configs enabled".
 * - Owner: R3. API: docs/stack.md §3 → R3 (every call named below was verified by javap against the
 *   rc02 `arcore` / `scenecore` jars and the runtime jar on 2026-10-17, except lines marked UNVERIFIED).
 * - Assumption: A-5 (depth + planes return data with `SCENE_UNDERSTANDING_*` granted; fallback RK-5),
 *   A-6 (spatial audio audible; fallback: plain earcon, see [SpatialAudioPinger]).
 *
 * Constructor matches `AppGraph`: `(Application, CoroutineScope)`. The constructor touches nothing
 * on the device; it only registers an `ActivityLifecycleCallbacks` so [start] (no-arg) can find the
 * resumed `Activity` that `Session.create` needs (see "Contract issues" in docs/modules/r3-spatial-perception.md).
 *
 * Threading (docs/architecture.md): every session API is called from [scope] (`Dispatchers.Default`);
 * `Session.create` runs under `Dispatchers.IO` (stack.md D7).
 */
class XrPerceptionSession(
    private val app: Application,
    internal val scope: CoroutineScope,
) : PerceptionSource {

    private val _state = MutableStateFlow(SessionState.NOT_STARTED)

    /** Shown by the diagnostics overlay as text, never as a number (INV-7). */
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** `null` until probed with `XrDevice.getCurrentDevice(app).isDepthEstimationModeSupported(SMOOTH_ONLY)`. */
    private val _depthSupported = MutableStateFlow<Boolean?>(null)
    val depthSupported: StateFlow<Boolean?> = _depthSupported.asStateFlow()

    /** `null` until probed with `session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)` (A-6). */
    private val _spatialAudioSupported = MutableStateFlow<Boolean?>(null)
    val spatialAudioSupported: StateFlow<Boolean?> = _spatialAudioSupported.asStateFlow()

    // Latest-wins buffers: a depth map or a hand sample is never queued (H-33).
    private val _depthFrames = MutableSharedFlow<DepthFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _planes = MutableStateFlow<List<PlaneObservation>>(emptyList())
    private val _hands = MutableSharedFlow<HandObservation>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val _headPose = MutableStateFlow<RawHeadPose?>(null)

    /**
     * FR-N1 depth stream, ≤ runtime rate; [XrNavigationEngine] samples it at 10 Hz.
     *
     * Feeder to implement (stack.md R3 "Depth"), launched in [scope] by [start]:
     * ```
     * val stereo = XrDevice.getCurrentDevice(app).isRenderingModeSupported(RenderingMode.STEREO)
     * val depth = if (stereo) Depth.left(session) else Depth.mono(session)
     * val view = if (stereo) RenderViewpoint.left(session) else RenderViewpoint.mono(session)
     * depth.state.collect { d ->
     *     val map = d.smoothDepthMap ?: return@collect            // IllegalStateException if SMOOTH not configured
     *     val fov = view.state.value.fieldOfView                   // angleLeft/Right/Up/Down (radians, UNVERIFIED unit)
     *     val floats = FloatArray(d.width * d.height).also { map.rewind(); map.get(it) }
     *     val conf = d.smoothConfidenceMap?.let { b -> ByteArray(floats.size).also { b.rewind(); b.get(it) } }
     *     _depthFrames.tryEmit(DepthFrame(d.width, d.height, floats, conf, FovDeg(hDeg, vDeg), SystemClock.elapsedRealtimeNanos()))
     * }
     * ```
     * A-5 evidence line to log once from this feeder: `vysion: depth WxH, min trusted depth m` (the
     * values are measured; never log the map itself, INV-3).
     */
    override val depthFrames: Flow<DepthFrame> = _depthFrames.asSharedFlow()

    /**
     * FR-N5 plane stream.
     *
     * Feeder to implement (stack.md R3 "Planes"):
     * ```
     * Plane.subscribe(session).collect { set ->
     *     _planes.value = set.map { p ->
     *         val s = p.state.value
     *         PlaneObservation(id = p.hashCode().toString(), kind = s.label.toPlaneKind(), tracking = s.trackingState.toTracking(),
     *             center = s.centerPose.translation.v3(), normal = s.centerPose.up.v3(),
     *             axisX = s.centerPose.right.v3(), axisZ = (s.centerPose.forward * -1f).v3(),
     *             polygon = s.vertices.map { Vec2(it.x, it.y) }, timestampNanos = SystemClock.elapsedRealtimeNanos())
     *     }
     * }
     * ```
     */
    override val planes: StateFlow<List<PlaneObservation>> = _planes.asStateFlow()

    /**
     * Hand stream (R1 consumes pinch through its own `HandSource` later; R3's FR-D4 pointing is P2).
     *
     * Feeder to implement (stack.md R3 "Hands"): `Hand.left(session).state` and `Hand.right(session).state`,
     * joints transformed with `session.scene.perceptionSpace.transformPoseTo(pose, session.scene.activitySpace)`.
     */
    override val hands: Flow<HandObservation> = _hands.asSharedFlow()

    /**
     * Head pose (FR-N3/FR-N4 guidance, FR-T5 "hold still").
     *
     * Feeder to implement (stack.md R3 "Head pose"):
     * ```
     * ArDevice.getInstance(session).state.collect { s ->
     *     val p = s.devicePose
     *     val (yaw, pitch, roll) = HeadAngles.fromForwardUp(p.forward.v3(), p.up.v3())
     *     _headPose.value = RawHeadPose(p.translation.v3(), yaw, pitch, roll, s.trackingState.toTracking(), SystemClock.elapsedRealtimeNanos())
     * }
     * ```
     */
    override val headPose: StateFlow<RawHeadPose?> = _headPose.asStateFlow()

    /** The runtime session once [start] succeeds. Read by [SpatialAudioPinger] through [xrSession]. */
    private var session: Session? = null
    internal val xrSession: Session? get() = session

    /** Native anchors by id; populated by [createAnchor]. */
    private val nativeAnchors = HashMap<AnchorId, Anchor>()
    private val anchorPoses = HashMap<AnchorId, MutableStateFlow<AnchorPoseSample?>>()
    private var nextAnchorSeq = 0
    private val lifecycle = Mutex()

    /** The resumed activity, captured so the no-arg [start] can create the session (see KDoc above). */
    @Volatile
    private var resumedActivity: WeakReference<Activity>? = null

    init {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /**
     * Creates and configures the session (SRS R3 task 1, Sat 12:00; plan.md F1 task 1.5; A-5).
     * Idempotent. Never throws: on any failure [state] becomes [SessionState.UNAVAILABLE] and the
     * NoOp-equivalent behavior continues (R1 speaks `Cues.navigationUnavailable`, an R4 string).
     *
     * Real implementation (docs/stack.md → R3; every line VERIFIED unless marked):
     * ```
     * val result = withContext(Dispatchers.IO) { Session.create(context = activity) }   // D7: named arg, off main, never applicationContext
     * val s = when (result) {
     *     is SessionCreateSuccess -> result.session
     *     else -> { _state.value = SessionState.UNAVAILABLE; return }   // UnsupportedDevice / ApkRequired / TimedOut / UnknownError
     * }
     * val cfg = Config.Builder(s.config)
     *     .setPlaneTracking(PlaneTrackingMode.HORIZONTAL_AND_VERTICAL)   // SCENE_UNDERSTANDING_COARSE
     *     .setDepthEstimation(DepthEstimationMode.SMOOTH_ONLY)           // SCENE_UNDERSTANDING_FINE
     *     .setHandTracking(HandTrackingMode.BOTH)                        // HAND_TRACKING
     *     .setDeviceTracking(DeviceTrackingMode.SPATIAL)                 // HEAD_TRACKING
     *     .setAnchorPersistence(AnchorPersistenceMode.LOCAL)
     *     .build()
     * try {
     *     when (s.configure(cfg)) { is SessionConfigureSuccess -> Unit; else -> /* Q5: drop modes one at a time */ }
     * } catch (e: SecurityException) { /* a permission is missing: UNAVAILABLE */ }
     *   catch (e: UnsupportedOperationException) { /* a mode is unsupported: XrDevice.isDepthEstimationModeSupported() says which */ }
     * session = s; _state.value = SessionState.RUNNING
     * scope.launch { depth feeder }; scope.launch { plane feeder }; scope.launch { hand feeder }; scope.launch { head feeder }
     * ```
     */
    suspend fun start(activity: Activity) {
        lifecycle.withLock {
            if (_state.value == SessionState.RUNNING || _state.value == SessionState.STARTING) return
            _state.value = SessionState.STARTING
            Log.w(
                TAG,
                "TODO(FR-N1) XrPerceptionSession.start: Session.create(context = activity) on Dispatchers.IO, " +
                    "Config.Builder(session.config).setPlaneTracking/.setDepthEstimation/.setHandTracking/" +
                    ".setDeviceTracking/.setAnchorPersistence, session.configure(cfg), then launch the four feeders",
            )
            // Skeleton: no runtime session exists, so the streams stay silent and nothing is measured.
            _state.value = SessionState.UNAVAILABLE
        }
    }

    /** No-arg form used by [XrNavigationEngine.start] and [XrAnchorStore]: uses the resumed activity. */
    override suspend fun ensureStarted() {
        val activity = resumedActivity?.get()
        if (activity == null) {
            Log.w(TAG, "XrPerceptionSession.ensureStarted: no resumed Activity yet; Session.create needs one (D7)")
            return
        }
        start(activity)
    }

    /**
     * Releases every anchor and the session. The runtime session has no public `destroy` (it follows
     * the activity lifecycle, `Session.create(..., lifecycleOwner = activity)`), so stop only detaches
     * anchors and forgets the reference. Never throws.
     */
    suspend fun stop() {
        lifecycle.withLock {
            detachAllAnchors()
            Log.w(TAG, "TODO(FR-N1) XrPerceptionSession.stop: cancel the feeder jobs; the Session follows the Activity lifecycle")
            session = null
            _state.value = SessionState.STOPPED
        }
    }

    /**
     * FR-N3 depth sample along a bearing. Two real strategies, in order (both documented so the
     * implementer picks after the Q6 device check):
     * 1. `hitTest(session, Ray(origin = head.translation, direction = dir))` (`androidx.xr.arcore.Interaction.hitTest`,
     *    VERIFIED) → `firstOrNull()?.hitPose?.translation`. Needs plane tracking; works without a depth map.
     * 2. Depth map: `DepthCone.pixelForBearing(relYawDeg, relPitchDeg, frame)` →
     *    `DepthCone.medianTrustedDepthAround(frame, x, y, radius = 3)` → `head.position + dir * depth`
     *    (both helpers in ProximityMonitor.kt, pure, tested).
     */
    override suspend fun sampleDepthAlongBearing(worldYawDeg: Float, pitchDeg: Float): Vec3? {
        Log.w(
            TAG,
            "TODO(FR-N3) XrPerceptionSession.sampleDepthAlongBearing: hitTest(session, Ray(head.translation, dir)) " +
                "or DepthCone.medianTrustedDepthAround on the latest DepthFrame",
        )
        return null // "no usable depth" is a contract value; the controller speaks Cues.doorNotFound (R4)
    }

    /**
     * Real implementation (stack.md R3 "Anchors", VERIFIED):
     * ```
     * when (val r = Anchor.create(session, Pose(Vector3(position.x, position.y, position.z)))) {
     *     is AnchorCreateSuccess -> { val id = AnchorId("door-${nextAnchorSeq++}"); nativeAnchors[id] = r.anchor
     *         anchorPoses[id] = MutableStateFlow(null); scope.launch { r.anchor.state.collect { st -> anchorPoses[id]?.value =
     *             AnchorPoseSample(id, st.pose.translation.v3(), st.trackingState.toTracking(), SystemClock.elapsedRealtimeNanos()) } }
     *         id }
     *     else -> null   // AnchorCreateResourcesExhausted / AnchorCreateTrackingUnavailable (sealed, keep else)
     * }
     * ```
     */
    override suspend fun createAnchor(position: Vec3): AnchorId? {
        Log.w(TAG, "TODO(FR-N3) XrPerceptionSession.createAnchor: Anchor.create(session, Pose(Vector3(x, y, z))) -> AnchorCreateSuccess")
        return null
    }

    override fun anchorPose(id: AnchorId): Flow<AnchorPoseSample> {
        val flow = anchorPoses[id]
            ?: return flowOf(AnchorPoseSample(id, Vec3.ZERO, Tracking.STOPPED, 0L)) // unknown id → one STOPPED sample
        return flow.filterNotNull()
    }

    /** `anchor.detach()` (VERIFIED) and a final STOPPED sample so `track()` flows complete (FR-N4 TargetLost). */
    override suspend fun detachAnchor(id: AnchorId) {
        val poses = anchorPoses.remove(id)
        if (nativeAnchors.remove(id) != null) {
            Log.w(TAG, "TODO(FR-N3) XrPerceptionSession.detachAnchor: anchor.detach()")
        }
        if (poses != null) {
            poses.value = AnchorPoseSample(id, poses.value?.position ?: Vec3.ZERO, Tracking.STOPPED, 0L)
        }
    }

    override suspend fun detachAllAnchors() {
        for (id in anchorPoses.keys.toList()) detachAnchor(id)
    }

    // ── SceneCore spatial audio hook for SpatialAudioPinger (A-6; stack.md R3 "Spatial audio", "Entities") ──

    /**
     * Real implementation (VERIFIED names):
     * ```
     * val anchor = nativeAnchors[id] ?: return null
     * val space = AnchorSpace.create(session, anchor)                       // scenecore rc02
     * val entity = Entity.create(session, name)                              // 1-4 arg overloads exist
     * space.addChild(entity)
     * return AnchorEntity(id, entity)
     * ```
     */
    internal fun createAnchorEntity(id: AnchorId, name: String): AnchorEntity? {
        Log.w(TAG, "TODO(FR-N3) XrPerceptionSession.createAnchorEntity: AnchorSpace.create(session, anchor) + Entity.create(session, \"$name\") + addChild")
        return null
    }

    /** `(entity.native as Entity).dispose()`; the `AnchorSpace` is disposed with the anchor. */
    internal fun disposeAnchorEntity(entity: AnchorEntity) {
        Log.w(TAG, "TODO(FR-N3) XrPerceptionSession.disposeAnchorEntity: entity.dispose()")
    }

    /**
     * Real implementation (VERIFIED signature; defaults are not recoverable from bytecode, pass all four):
     * ```
     * if (!session.scene.spatialCapabilities.contains(SpatialCapability.SPATIAL_AUDIO)) return -1   // A-6 fallback
     * return SpatialSoundPool.play(session = session, soundPool = soundPool, soundID = soundId, params = PointSourceParams(),
     *     entity = entity.native as Entity, volume = 1f, priority = 0, loop = 0, rate = 1f)
     * ```
     * Returns the SoundPool stream id, or -1 when spatial audio is unavailable.
     */
    internal fun playPointSource(entity: AnchorEntity, soundPool: SoundPool, soundId: Int): Int {
        Log.w(TAG, "TODO(FR-N3) XrPerceptionSession.playPointSource: SpatialSoundPool.play(session, soundPool, soundID, PointSourceParams(), entity, 1f, 0, 0, 1f)")
        return -1
    }

    companion object {
        internal const val TAG = "vysion"
    }
}
