package com.vysion.xr.perception

import com.vysion.xr.core.HeadPose
import com.vysion.xr.core.HeadPoseSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlin.math.sqrt

/**
 * Angular speed of the head from two consecutive samples (FR-T5 "hold still" retry). Pure, tested.
 *
 * Yaw deltas are wrapped to (-180, 180] so a turn from 359° to 1° counts as 2°, not 358°. Yaw and
 * pitch deltas are combined as a Euclidean angle (roll does not blur a still capture as much and is
 * ignored). Timestamps are monotonic nanos (H-21); a non-positive dt yields 0, never infinity.
 */
object AngularSpeed {
    fun degPerS(
        prevYawDeg: Float,
        prevPitchDeg: Float,
        prevNanos: Long,
        yawDeg: Float,
        pitchDeg: Float,
        nanos: Long,
    ): Float {
        val dtS = (nanos - prevNanos) / 1_000_000_000.0
        if (dtS <= 0.0) return 0f
        val dYaw = HeadAngles.wrapDeg(yawDeg - prevYawDeg)
        val dPitch = pitchDeg - prevPitchDeg
        return (sqrt((dYaw * dYaw + dPitch * dPitch).toDouble()) / dtS).toFloat()
    }
}

/**
 * Turns the raw pose stream into the `core` [HeadPose], adding `angularSpeedDegPerS` from the
 * previous sample. Pure and stateful; one instance per stream, called sequentially. A sample that is
 * not [Tracking.TRACKING] yields `null` (R1 must not trust a paused pose, RK-5 "only what we trust")
 * and resets the speed baseline.
 */
class HeadPoseTracker {
    private var prev: RawHeadPose? = null

    fun next(raw: RawHeadPose): HeadPose? {
        if (raw.tracking != Tracking.TRACKING) {
            prev = null
            return null
        }
        val p = prev
        val speed = if (p == null) {
            0f
        } else {
            AngularSpeed.degPerS(p.yawDeg, p.pitchDeg, p.timestampNanos, raw.yawDeg, raw.pitchDeg, raw.timestampNanos)
        }
        prev = raw
        return HeadPose(
            yawDeg = raw.yawDeg,
            pitchDeg = raw.pitchDeg,
            rollDeg = raw.rollDeg,
            angularSpeedDegPerS = speed,
            timestampNanos = raw.timestampNanos,
        )
    }

    fun reset() {
        prev = null
    }
}

/**
 * Implements [HeadPoseSource] (core) for R1 (FR-T5, P1) and R3 (FR-N3/FR-N4) from
 * [XrPerceptionSession.headPose]. Constructor matches `AppGraph`: `(XrPerceptionSession)`.
 *
 * - FR ids: FR-T5 (angular speed), FR-N3, FR-N4. SRS due: Sat 18:00 with `AnchorStore` ("bearing and
 *   distance from the current head pose"). Owner: R3.
 * - API: docs/stack.md §3 → R3 "Head pose": `ArDevice.getInstance(session).state.value.devicePose`
 *   (the feeder lives in [XrPerceptionSession]; this class only maps).
 * - Assumption: A-5 (device tracking configured with `DeviceTrackingMode.SPATIAL` + `HEAD_TRACKING`).
 *
 * The mapping runs eagerly in the session's scope so `pose.value` is current the moment R1 reads it at
 * capture time. It survives `XrPerceptionSession.stop()` / `start()` (headset off and on: the raw pose
 * goes `null`, which also resets the speed baseline); only `AppGraph.release()` cancels the scope.
 */
class XrHeadPoseSource internal constructor(
    source: PerceptionSource,
    scope: CoroutineScope,
) : HeadPoseSource {

    constructor(session: XrPerceptionSession) : this(session, session.scope)

    private val tracker = HeadPoseTracker()

    override val pose: StateFlow<HeadPose?> = source.headPose
        .map { raw ->
            if (raw == null) {
                tracker.reset() // session stopped: no speed across the gap (FR-T5)
                null
            } else {
                tracker.next(raw)
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)
}
