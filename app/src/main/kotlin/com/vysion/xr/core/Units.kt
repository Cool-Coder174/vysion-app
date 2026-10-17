package com.vysion.xr.core

import kotlin.math.roundToInt

/*
 * Pure helpers that encode two SRS phrasing rules as code so every role computes them the same way:
 *   - "door bearing in clock-face form"           (SRS §8 R3; FR-N3)
 *   - "distance in meters rounded to 0.5"         (SRS §8 R3; FR-N3)
 * No Android imports here: these run in plain JVM unit tests (src/test).
 */

object ClockFace {
    /**
     * Converts a relative bearing to a clock-face hour in 1..12.
     *
     * @param yawDeg bearing relative to the head's forward direction, in degrees. 0 is straight
     *   ahead (12 o'clock), positive is to the right (3 o'clock is +90), negative to the left
     *   (9 o'clock is -90). Any magnitude is accepted; it is normalized.
     */
    fun fromRelativeYawDeg(yawDeg: Float): Int {
        val normalized = ((yawDeg % 360f) + 360f) % 360f // 0 ≤ x < 360
        val hour = (normalized / DEGREES_PER_HOUR).roundToInt() % 12
        return if (hour == 0) 12 else hour
    }

    const val DEGREES_PER_HOUR: Float = 30f
}

object Distances {
    /** Rounds to the nearest 0.5 m: 2.74 → 2.5, 2.76 → 3.0, 0.25 → 0.5. Negative input is clamped to 0. */
    fun roundToHalfMeter(meters: Float): Float {
        val clamped = if (meters < 0f) 0f else meters
        return (clamped * 2f).roundToInt() / 2f
    }

    /** FR-N1 threshold. */
    const val PROXIMITY_THRESHOLD_M: Float = 1.0f

    /** FR-N6 arrival radius. */
    const val ARRIVAL_RADIUS_M: Float = 1.0f

    /** FR-N5 plane-semantics range. */
    const val PLANE_CUE_RANGE_M: Float = 1.5f
}
