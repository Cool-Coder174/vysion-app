package com.vysion.xr.perception

import android.util.Log
import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.ClockFace
import com.vysion.xr.core.Distances
import com.vysion.xr.core.HeadPoseSource
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.RelativeTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.transformWhile
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Where the VLM's one-word answer points, as an angle relative to the head's forward direction at
 * capture time (FR-N3 "samples depth along that bearing"). The still is center-cropped to the VLM
 * input, so "left / center / right" are the thirds of the world camera's horizontal field of view.
 * Until that FOV is measured on the headset (stack.md Q3 logs it), the third centers are taken as
 * ±30°, the FR-N1 cone half-angle; refine `LEFT_DEG` / `RIGHT_DEG` from the logged FOV
 * (`±hfov / 3`). Not displayed anywhere, so not subject to INV-7.
 */
object BearingAngles {
    const val LEFT_DEG: Float = -30f
    const val CENTER_DEG: Float = 0f
    const val RIGHT_DEG: Float = 30f

    /** `null` for [HorizontalBearing.NONE]: there is no door to place. */
    fun degreesFor(bearing: HorizontalBearing): Float? = when (bearing) {
        HorizontalBearing.LEFT -> LEFT_DEG
        HorizontalBearing.CENTER -> CENTER_DEG
        HorizontalBearing.RIGHT -> RIGHT_DEG
        HorizontalBearing.NONE -> null
    }
}

/**
 * Anchor pose + head pose → [RelativeTarget] (FR-N3 bearing/distance, FR-N4 out-of-view guidance).
 * Pure, tested.
 *
 * Conventions (shared with [RawHeadPose] and `core.ClockFace`): bearings are measured from the head's
 * forward direction, positive to the right; 12 o'clock = ahead, 3 = right, 6 = behind, 9 = left.
 * World bearing of a point = `atan2(dx, -dz)` (yaw 0 faces -Z, +X is right); relative bearing =
 * `wrap(worldBearing - headYaw)`. Distance is horizontal (XZ), the walking distance, rounded to 0.5 m
 * (SRS §8 R3). `inView` = |relative| ≤ [IN_VIEW_HALF_ANGLE_DEG]; passthrough FOV is wider than 90°,
 * so this is "comfortably in front", which R1 uses to decide whether to draw the door highlight.
 */
object RelativeGeometry {
    const val IN_VIEW_HALF_ANGLE_DEG: Float = 45f

    fun worldBearingDeg(from: Vec3, to: Vec3): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return HeadAngles.wrapDeg(Math.toDegrees(Math.atan2(dx.toDouble(), (-dz).toDouble())).toFloat())
    }

    fun horizontalDistanceM(from: Vec3, to: Vec3): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        return sqrt(dx * dx + dz * dz)
    }

    fun relative(headPosition: Vec3, headYawDeg: Float, target: Vec3): RelativeTarget {
        val bearing = HeadAngles.wrapDeg(worldBearingDeg(headPosition, target) - headYawDeg)
        return RelativeTarget(
            bearingDeg = bearing,
            clock = ClockFace.fromRelativeYawDeg(bearing),
            distanceM = Distances.roundToHalfMeter(horizontalDistanceM(headPosition, target)),
            inView = abs(bearing) <= IN_VIEW_HALF_ANGLE_DEG,
        )
    }
}

/**
 * Implements [AnchorStore] (core) on native ARCore for Jetpack XR anchors, so the door target
 * persists when it leaves the field of view (FR-N4). Constructor matches `AppGraph`:
 * `(XrPerceptionSession, HeadPoseSource)`.
 *
 * - FR ids: FR-N3 (place from bearing, bearing + distance), FR-N4 (persistence), FR-N6 (arrival
 *   distance via `track`), FR-N7 (P2, several candidates: one id each).
 * - SRS due Sat 18:00: "given a bearing (left/center/right from the VLM) and a depth sample along it,
 *   create an anchor; expose bearing and distance from the current head pose". Owner: R3.
 * - API (docs/stack.md → R3 "Anchors", "Hit test", all VERIFIED): `hitTest(session, Ray(origin, direction))`,
 *   `Anchor.create(session, pose)` → `AnchorCreateSuccess(anchor)`, `anchor.state.value.pose / .trackingState`,
 *   `anchor.detach()`. The device calls live in [XrPerceptionSession]; this class holds the logic.
 * - Assumption A-5; fallback RK-5 (depth from plane hit tests / detector box height), RK-6 (the door
 *   vote is R1+R2's; this store places whatever bearing it is given).
 *
 * `headPose` (the `core` contract, angles only) and the session's raw pose (which also carries the
 * position) come from the same `ArDevice` stream; `track` combines the anchor pose with the raw
 * pose because a bearing needs the head position, and reads nothing else.
 */
class XrAnchorStore internal constructor(
    private val source: PerceptionSource,
    private val headPose: HeadPoseSource,
    private val clock: () -> Long,
) : AnchorStore {

    constructor(session: XrPerceptionSession, headPose: HeadPoseSource) :
        this(session, headPose, Clocks.elapsedRealtimeNanos)

    /**
     * FR-N3: head yaw + [BearingAngles] → world bearing → depth sample along it → native anchor.
     * Returns `null` ("no usable depth", the contract's own value) when there is no pose, no depth or
     * no tracking; the controller then speaks `Cues.doorNotFound` (R4). Never throws.
     */
    override suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId? {
        val relDeg = BearingAngles.degreesFor(bearing) ?: return null
        source.ensureStarted()
        val head = source.headPose.value
        if (head == null || head.tracking != Tracking.TRACKING) {
            Log.w(XrPerceptionSession.TAG, "placeFromBearing: no tracked head pose; cannot place the door anchor (FR-N3)")
            return null
        }
        val worldYaw = HeadAngles.wrapDeg(head.yawDeg + relDeg)
        val point = source.sampleDepthAlongBearing(worldYaw, 0f) ?: return null
        val id = source.createAnchor(point) ?: return null
        val placedAt = RelativeGeometry.relative(head.position, head.yawDeg, point)
        Log.i(
            XrPerceptionSession.TAG,
            "anchor ${id.value} placed: bearing $bearing (${relDeg}°), ${placedAt.distanceM} m, clock ${placedAt.clock} (FR-N3)",
        )
        return id
    }

    /**
     * Relative target every time the anchor pose or the head pose changes; emits only while the anchor
     * is [Tracking.TRACKING] and **completes** when it is [Tracking.STOPPED] (detached or lost), which
     * [XrNavigationEngine] turns into [com.vysion.xr.core.NavigationCue.TargetLost]. While PAUSED it
     * stays silent (the engine's stale timeout handles a long pause). FR-N4: nothing here needs the
     * anchor to be in view; the native anchor keeps its world pose.
     */
    override fun track(id: AnchorId): Flow<RelativeTarget> =
        combine(source.anchorPose(id), source.headPose.filterNotNull()) { anchor, head -> anchor to head }
            .transformWhile { (anchor, head) ->
                when (anchor.tracking) {
                    Tracking.STOPPED -> false
                    Tracking.PAUSED -> true
                    Tracking.TRACKING -> {
                        if (head.tracking == Tracking.TRACKING) {
                            emit(RelativeGeometry.relative(head.position, head.yawDeg, anchor.position))
                        }
                        true
                    }
                }
            }

    override suspend fun remove(id: AnchorId) {
        source.detachAnchor(id)
    }

    override suspend fun clear() {
        source.detachAllAnchors()
    }
}
