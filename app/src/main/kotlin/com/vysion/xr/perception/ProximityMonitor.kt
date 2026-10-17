package com.vysion.xr.perception

import com.vysion.xr.core.Distances
import com.vysion.xr.core.NavigationCue
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Which depth pixels lie in the forward cone (FR-N1 "forward 60-degree cone"). Pure, tested.
 *
 * Pinhole model of the depth image. With `fx = (width / 2) / tan(hfov / 2)` and
 * `fy = (height / 2) / tan(vfov / 2)` (focal lengths in pixels) and the optical center at
 * `cx = (width - 1) / 2`, `cy = (height - 1) / 2`, pixel (x, y) looks along the ray
 * `((x - cx) / fx, (y - cy) / fy, 1)`. Its angle off the optical axis is
 * `θ = atan(sqrt(u² + v²))` with `u = (x - cx) / fx`, `v = (y - cy) / fy`, so the pixel is inside a
 * cone of half-angle α iff `u² + v² ≤ tan²(α)`. For α = 30° that is the disc of radius
 * `fx · tan 30° ≈ 0.577 · fx` pixels (horizontally; `fy` vertically) around the center. [bounds] is
 * the axis-aligned box of that disc, clamped to the image when the FOV is narrower than the cone;
 * [isInCone] is the exact test inside the box.
 */
object DepthCone {
    /** FR-N1: 60° cone → ±30° about the optical axis. */
    const val HALF_ANGLE_DEG: Float = 30f

    /** Inclusive pixel box. */
    data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val isEmpty: Boolean get() = right < left || bottom < top
    }

    fun focalLengthPx(sizePx: Int, fovDeg: Float): Float {
        require(fovDeg > 0f && fovDeg < 180f) { "FOV must be in (0, 180): $fovDeg" }
        return (sizePx / 2f) / tan(Math.toRadians(fovDeg / 2.0)).toFloat()
    }

    fun centerX(width: Int): Float = (width - 1) / 2f
    fun centerY(height: Int): Float = (height - 1) / 2f

    fun bounds(width: Int, height: Int, fov: FovDeg, halfAngleDeg: Float = HALF_ANGLE_DEG): PixelRect {
        val t = tan(Math.toRadians(halfAngleDeg.toDouble())).toFloat()
        val rx = focalLengthPx(width, fov.horizontal) * t
        val ry = focalLengthPx(height, fov.vertical) * t
        val cx = centerX(width)
        val cy = centerY(height)
        return PixelRect(
            left = maxOf(0, floor(cx - rx).toInt()),
            top = maxOf(0, floor(cy - ry).toInt()),
            right = minOf(width - 1, (cx + rx).toInt()),
            bottom = minOf(height - 1, (cy + ry).toInt()),
        )
    }

    fun isInCone(x: Int, y: Int, width: Int, height: Int, fov: FovDeg, halfAngleDeg: Float = HALF_ANGLE_DEG): Boolean {
        val u = (x - centerX(width)) / focalLengthPx(width, fov.horizontal)
        val v = (y - centerY(height)) / focalLengthPx(height, fov.vertical)
        val t = tan(Math.toRadians(halfAngleDeg.toDouble())).toFloat()
        return u * u + v * v <= t * t
    }

    /**
     * Smallest trusted depth inside the cone, or `null` when nothing in the cone is trusted
     * (RK-5: only distances we trust are spoken). A pixel is trusted when its depth is at least
     * [minDepthM] (0 = no data; a few cm = sensor artifact) and its confidence is at least [minConfidence].
     */
    fun minTrustedDepth(
        frame: DepthFrame,
        halfAngleDeg: Float = HALF_ANGLE_DEG,
        minConfidence: Int = ProximityMonitor.MIN_CONFIDENCE,
        minDepthM: Float = ProximityMonitor.MIN_TRUSTED_DEPTH_M,
    ): Float? {
        val box = bounds(frame.width, frame.height, frame.fov, halfAngleDeg)
        if (box.isEmpty) return null
        var best = Float.POSITIVE_INFINITY
        for (y in box.top..box.bottom) {
            for (x in box.left..box.right) {
                if (!isInCone(x, y, frame.width, frame.height, frame.fov, halfAngleDeg)) continue
                val d = frame.depthAt(x, y)
                if (d < minDepthM || d.isNaN()) continue
                if (frame.confidenceAt(x, y) < minConfidence) continue
                if (d < best) best = d
            }
        }
        return if (best == Float.POSITIVE_INFINITY) null else best
    }

    /**
     * Pixel that looks along a bearing relative to the optical axis (FR-N3 "samples depth along that
     * bearing"): `x = cx + fx · tan(relYaw)`, `y = cy - fy · tan(relPitch)` (image y grows downward).
     * `null` when the bearing is outside the image.
     */
    fun pixelForBearing(relYawDeg: Float, relPitchDeg: Float, width: Int, height: Int, fov: FovDeg): Pair<Int, Int>? {
        if (abs(relYawDeg) >= 90f || abs(relPitchDeg) >= 90f) return null
        val x = (centerX(width) + focalLengthPx(width, fov.horizontal) * tan(Math.toRadians(relYawDeg.toDouble()))).roundToInt()
        val y = (centerY(height) - focalLengthPx(height, fov.vertical) * tan(Math.toRadians(relPitchDeg.toDouble()))).roundToInt()
        if (x !in 0 until width || y !in 0 until height) return null
        return x to y
    }

    /** Median of the trusted depths in a (2r+1)² window; `null` when none is trusted. Robust to single-pixel noise. */
    fun medianTrustedDepthAround(
        frame: DepthFrame,
        x: Int,
        y: Int,
        radius: Int = 3,
        minConfidence: Int = ProximityMonitor.MIN_CONFIDENCE,
        minDepthM: Float = ProximityMonitor.MIN_TRUSTED_DEPTH_M,
    ): Float? {
        val values = ArrayList<Float>()
        for (yy in maxOf(0, y - radius)..minOf(frame.height - 1, y + radius)) {
            for (xx in maxOf(0, x - radius)..minOf(frame.width - 1, x + radius)) {
                val d = frame.depthAt(xx, yy)
                if (d < minDepthM || d.isNaN() || frame.confidenceAt(xx, yy) < minConfidence) continue
                values.add(d)
            }
        }
        if (values.isEmpty()) return null
        values.sort()
        return values[values.size / 2]
    }
}

/**
 * FR-N1 proximity logic, pure and tested: fed one [DepthFrame] at a time (10 Hz, from
 * [XrNavigationEngine]), emits [NavigationCue.Proximity] when the smallest trusted depth in the
 * forward 60° cone is below [Distances.PROXIMITY_THRESHOLD_M] (1.0 m), at most once per
 * [DEBOUNCE_NANOS] (2 s). The clock is injected (monotonic nanos, H-21) so the debounce is testable.
 *
 * - Owner: R3. SRS due Sat 15:00 ("sample the depth texture in the forward 60-degree cone at 10 Hz;
 *   emit Proximity(distanceM) when under 1.0 m with 2 s debounce").
 * - Device input: [XrPerceptionSession.depthFrames] (docs/stack.md → R3 "Depth"). Assumption A-5;
 *   fallback RK-5 (plane hit tests + detector box height) decided Sat 15:00.
 * - Frames older than [maxFrameAgeNanos] are ignored so a map buffered during a pause never warns
 *   about an obstacle that is no longer there (NFR latency ≤ 300 ms is measured in the engine).
 * - `distanceM` is the measured minimum, unrounded; R4 words it ("obstacle ahead", `Cues.kt`).
 *   No spoken string lives here (INV-4).
 */
class ProximityMonitor(
    private val clock: () -> Long,
    private val thresholdM: Float = Distances.PROXIMITY_THRESHOLD_M,
    private val debounceNanos: Long = DEBOUNCE_NANOS,
    private val halfAngleDeg: Float = DepthCone.HALF_ANGLE_DEG,
    private val minConfidence: Int = MIN_CONFIDENCE,
    private val minDepthM: Float = MIN_TRUSTED_DEPTH_M,
    private val maxFrameAgeNanos: Long = MAX_FRAME_AGE_NANOS,
) {
    private var lastEmitNanos: Long? = null

    /** The last trusted minimum seen (even when debounced); `null` until measured (INV-7). */
    var lastMinDepthM: Float? = null
        private set

    fun onDepth(frame: DepthFrame): NavigationCue.Proximity? {
        val now = clock()
        if (now - frame.timestampNanos > maxFrameAgeNanos) return null // stale (buffered during a pause)
        val min = DepthCone.minTrustedDepth(frame, halfAngleDeg, minConfidence, minDepthM) ?: return null
        lastMinDepthM = min
        if (min >= thresholdM) return null
        val last = lastEmitNanos
        if (last != null && now - last < debounceNanos) return null // FR-N1: repeat no more than every 2 s
        lastEmitNanos = now
        return NavigationCue.Proximity(min)
    }

    fun reset() {
        lastEmitNanos = null
        lastMinDepthM = null
    }

    companion object {
        /** FR-N1: "repeat no more than every 2 s". */
        const val DEBOUNCE_NANOS: Long = 2_000_000_000L

        /** Confidence floor on the 0..255 `smoothConfidenceMap`; tune on the device during the A-5 check (RK-5). */
        const val MIN_CONFIDENCE: Int = 128

        /** Below this the sensor is reporting an artifact, not a surface (0 = no data). */
        const val MIN_TRUSTED_DEPTH_M: Float = 0.1f

        /** A depth map older than this is not acted on; keeps the ≤ 300 ms NFR honest after a pause. */
        const val MAX_FRAME_AGE_NANOS: Long = 500_000_000L
    }
}
