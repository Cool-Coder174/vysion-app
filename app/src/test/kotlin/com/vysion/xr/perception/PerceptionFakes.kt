package com.vysion.xr.perception

import android.graphics.Bitmap
import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.Detection
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.FrameStats
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceStats
import com.vysion.xr.core.ModelInfo
import com.vysion.xr.core.RelativeTarget
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow

/*
 * Test doubles for the R3 package. Everything here is JVM-only: no device, no XR runtime. The
 * `Fake*` of other roles' contracts (FrameSource, InferenceEngine) are the minimum R3 needs and live in
 * the test source set so they never cross a package in main (ADR-0005).
 */

/** The device port, scriptable: push depth maps, planes, head poses and anchor poses from a test. */
internal class FakePerceptionSource : PerceptionSource {
    val depth = MutableSharedFlow<DepthFrame>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val depthFrames: Flow<DepthFrame> = depth
    override val planes = MutableStateFlow<List<PlaneObservation>>(emptyList())
    val handSamples = MutableSharedFlow<HandObservation>(extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val hands: Flow<HandObservation> = handSamples
    override val headPose = MutableStateFlow<RawHeadPose?>(null)

    var ensureStartedCalls = 0
    var sampledYawDeg = mutableListOf<Float>()
    var depthPoint: Vec3? = null
    var nextAnchorId: AnchorId? = AnchorId("anchor-1")
    val anchorPoses = HashMap<AnchorId, MutableStateFlow<AnchorPoseSample?>>()
    val detached = mutableListOf<AnchorId>()

    override suspend fun ensureStarted() {
        ensureStartedCalls++
    }

    override suspend fun sampleDepthAlongBearing(worldYawDeg: Float, pitchDeg: Float): Vec3? {
        sampledYawDeg += worldYawDeg
        return depthPoint
    }

    override suspend fun createAnchor(position: Vec3): AnchorId? {
        val id = nextAnchorId ?: return null
        anchorPoses.getOrPut(id) { MutableStateFlow(null) }
        return id
    }

    override fun anchorPose(id: AnchorId): Flow<AnchorPoseSample> =
        anchorPoses[id]?.filterNotNull() ?: flowOf(AnchorPoseSample(id, Vec3.ZERO, Tracking.STOPPED, 0L))

    override suspend fun detachAnchor(id: AnchorId) {
        detached += id
        anchorPoses.remove(id)?.let { it.value = AnchorPoseSample(id, Vec3.ZERO, Tracking.STOPPED, 0L) }
    }

    override suspend fun detachAllAnchors() {
        for (id in anchorPoses.keys.toList()) detachAnchor(id)
    }

    /** Scripts an anchor pose for [id] (creating the stream if needed). */
    fun anchorAt(id: AnchorId, position: Vec3, tracking: Tracking = Tracking.TRACKING, nanos: Long = 0L) {
        anchorPoses.getOrPut(id) { MutableStateFlow(null) }.value = AnchorPoseSample(id, position, tracking, nanos)
    }
}

/** R1's contract, minimal: a latest-wins frame flow the test feeds. */
internal class FakeFrameSource : FrameSource {
    val frames = MutableSharedFlow<Bitmap>(replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val analysisFrames: Flow<Bitmap> = frames
    override val stats: StateFlow<FrameStats?> = MutableStateFlow(null)
    override suspend fun start() = Unit
    override suspend fun stop() = Unit
    override suspend fun captureStill(): Bitmap = TestBitmaps.blank()
}

/** R2's contract, minimal: `detect` returns a scripted list and counts calls; optionally throws. */
internal class FakeInferenceEngine(var result: List<Detection> = emptyList()) : InferenceEngine {
    var detectCalls = 0
    var failWith: Exception? = null
    override val vlmInfo: StateFlow<ModelInfo?> = MutableStateFlow(null)
    override val detectorInfo: StateFlow<ModelInfo?> = MutableStateFlow(null)
    override val stats: StateFlow<InferenceStats> = MutableStateFlow(InferenceStats())
    override suspend fun warmUp() = Unit
    override suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int): String = ""
    override suspend fun detect(bitmap: Bitmap): List<Detection> {
        detectCalls++
        failWith?.let { throw it }
        return result
    }
    override suspend fun release() = Unit
}

/** An AnchorStore whose `track` is a channel the test pushes into and closes to simulate tracking loss. */
internal class FakeAnchorStore : AnchorStore {
    val channels = HashMap<AnchorId, Channel<RelativeTarget>>()
    val removed = mutableListOf<AnchorId>()

    fun channel(id: AnchorId): Channel<RelativeTarget> = channels.getOrPut(id) { Channel(Channel.UNLIMITED) }

    override suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId? = AnchorId("door")
    override fun track(id: AnchorId): Flow<RelativeTarget> = channel(id).receiveAsFlow()
    override suspend fun remove(id: AnchorId) {
        removed += id
    }
    override suspend fun clear() = Unit
}

internal class FakePinger : AnchorPinger {
    val attached = mutableListOf<AnchorId>()
    var pings = 0
    var detaches = 0
    override suspend fun attach(anchor: AnchorId) {
        attached += anchor
    }
    override fun ping() {
        pings++
    }
    override fun detach() {
        detaches++
    }
    override fun release() = Unit
}

/**
 * A `Bitmap` instance for `Flow<Bitmap>` fakes. `android.graphics.Bitmap` has no public constructor
 * and the JVM test jar returns `null` from `createBitmap`, so the instance is allocated without running
 * a constructor; nothing in R3 touches its pixels (R3 never reads frame contents, INV-3).
 */
internal object TestBitmaps {
    fun blank(): Bitmap {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Bitmap::class.java) as Bitmap
    }
}

/** Depth-map builder: a WxH map filled with [fillM] at full confidence, optional center override. */
internal fun depthFrame(
    width: Int = 64,
    height: Int = 48,
    fov: FovDeg = FovDeg(90f, 70f),
    fillM: Float = 5f,
    centerM: Float? = null,
    timestampNanos: Long = 0L,
    withConfidence: Boolean = true,
): DepthFrame {
    val depth = FloatArray(width * height) { fillM }
    val conf = if (withConfidence) ByteArray(width * height) { 255.toByte() } else null
    if (centerM != null) depth[(height / 2) * width + width / 2] = centerM
    return DepthFrame(width, height, depth, conf, fov, timestampNanos)
}

internal fun head(
    x: Float = 0f,
    z: Float = 0f,
    yawDeg: Float = 0f,
    pitchDeg: Float = 0f,
    rollDeg: Float = 0f,
    tracking: Tracking = Tracking.TRACKING,
    nanos: Long = 0L,
): RawHeadPose = RawHeadPose(Vec3(x, 0f, z), yawDeg, pitchDeg, rollDeg, tracking, nanos)
