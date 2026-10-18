package com.vysion.xr.platform

import com.vysion.xr.core.NormalizedBox
import kotlin.math.tan

/**
 * The reading crosshair (augmented Read): a frame [WIDTH_DEG] × [HEIGHT_DEG] around the wearer's line of sight. The
 * headset UI draws it head-locked in the middle of the view; Read sends the same part of the still to OCR
 * ([stillBox]), so what the wearer points their head at is what gets read. Pure; JVM-tested.
 */
object ReadingFrame {
    /** Wide enough to aim with the head (a sign 2 m away fits in ~1 m), small enough to pick one sign of several. */
    const val WIDTH_DEG: Float = 30f
    const val HEIGHT_DEG: Float = 20f

    /**
     * Field of view of the square still, both axes: A-1 measured the world camera's 79.2° horizontal field of view; the
     * 4:3 frame is center-cropped square (`CameraFrameSource.captureStill`), leaving 2·atan(0.75·tan 39.6°) ≈ 63.6°.
     */
    const val STILL_FOV_DEG: Float = 63.6f

    /** The crosshair's part of the still, in normalized image coordinates. */
    val stillBox: NormalizedBox = boxFor(WIDTH_DEG, HEIGHT_DEG, STILL_FOV_DEG)

    /**
     * The normalized image rectangle a centered frame of [widthDeg] × [heightDeg] covers in a camera image with
     * [fovDeg] on both axes (pinhole: offsets scale with the tangent of the angle). Clamped to the image.
     */
    fun boxFor(widthDeg: Float, heightDeg: Float, fovDeg: Float): NormalizedBox {
        val edge = tanHalf(fovDeg)
        val halfW = (0.5f * tanHalf(widthDeg) / edge).coerceAtMost(0.5f)
        val halfH = (0.5f * tanHalf(heightDeg) / edge).coerceAtMost(0.5f)
        return NormalizedBox(0.5f - halfW, 0.5f - halfH, 0.5f + halfW, 0.5f + halfH)
    }

    /** Width in meters of the drawn frame at [distanceM], so it covers [WIDTH_DEG] at any distance. */
    fun widthAt(distanceM: Float): Float = 2f * distanceM * tanHalf(WIDTH_DEG)

    /** Height in meters of the drawn frame at [distanceM]. */
    fun heightAt(distanceM: Float): Float = 2f * distanceM * tanHalf(HEIGHT_DEG)

    private fun tanHalf(deg: Float): Float = tan(Math.toRadians(deg / 2.0)).toFloat()
}
