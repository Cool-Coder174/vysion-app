package com.vysion.xr.inference

import android.graphics.Bitmap

/**
 * Pixel layouts the ExecuTorch multimodal runner takes (`LlmModule.prefillImages`), from ARGB `Bitmap.getPixels`.
 * Pure and JVM-tested; the arrays are local to one call and never stored or logged (INV-3).
 */
internal object VlmPixels {

    /** uint8 0..255 per channel, CHW, RGB: `prefillImages(int[])` for encoders that normalize inside the graph. */
    fun toUint8Chw(argb: IntArray): IntArray {
        val n = argb.size
        val chw = IntArray(3 * n)
        for (i in 0 until n) {
            val p = argb[i]
            chw[i] = (p shr 16) and 0xFF
            chw[n + i] = (p shr 8) and 0xFF
            chw[2 * n + i] = p and 0xFF
        }
        return chw
    }

    /** Raw 0..255 as float, CHW, RGB: `prefillImages(float[])` for encoders that normalize inside the graph. */
    fun toFloatChw(argb: IntArray): FloatArray {
        val n = argb.size
        val chw = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = argb[i]
            chw[i] = ((p shr 16) and 0xFF).toFloat()
            chw[n + i] = ((p shr 8) and 0xFF).toFloat()
            chw[2 * n + i] = (p and 0xFF).toFloat()
        }
        return chw
    }

    /** `(v / 255 − mean) / std` per channel, CHW, RGB: `prefillImages(float[])`. */
    fun toNormalizedChw(argb: IntArray, mean: List<Float>, std: List<Float>): FloatArray {
        require(mean.size == 3 && std.size == 3 && std.none { it == 0f }) { "mean/std need 3 channels, std ≠ 0" }
        val n = argb.size
        val chw = FloatArray(3 * n)
        for (i in 0 until n) {
            val p = argb[i]
            chw[i] = (((p shr 16) and 0xFF) / 255f - mean[0]) / std[0]
            chw[n + i] = (((p shr 8) and 0xFF) / 255f - mean[1]) / std[1]
            chw[2 * n + i] = ((p and 0xFF) / 255f - mean[2]) / std[2]
        }
        return chw
    }
}

/**
 * Counts streamed answer chunks and says when `maxNewTokens` is reached: ExecuTorch 1.3.1's `generate()` does not
 * forward a new-token limit, so the engine stops the runner itself. Not thread-safe: one per `generate()` call,
 * used from its callback thread only.
 */
internal class TokenCap(private val max: Int) {
    init {
        require(max > 0) { "max must be > 0: $max" }
    }

    var count: Int = 0
        private set

    val reached: Boolean get() = count >= max

    /** Records one chunk; `true` when this chunk reached the cap (stop the runner now). */
    fun add(): Boolean {
        count++
        return count == max
    }
}

/** Frames to the VLM encoder's fixed input size (device-only `android.graphics` calls). */
internal object VlmFrames {

    /**
     * [src] itself when it already is [w] x [h] (or no size is known); otherwise a new bitmap: the largest centered
     * crop with that aspect ratio, scaled to [w] x [h]. The caller recycles a returned copy, never [src].
     */
    fun fit(src: Bitmap, w: Int, h: Int): Bitmap {
        if (w <= 0 || h <= 0 || (src.width == w && src.height == h)) return src
        val cropW: Int
        val cropH: Int
        if (src.width.toLong() * h >= src.height.toLong() * w) {
            cropH = src.height
            cropW = (src.height.toLong() * w / h).toInt().coerceIn(1, src.width)
        } else {
            cropW = src.width
            cropH = (src.width.toLong() * h / w).toInt().coerceIn(1, src.height)
        }
        val cropped = Bitmap.createBitmap(src, (src.width - cropW) / 2, (src.height - cropH) / 2, cropW, cropH)
        val scaled = Bitmap.createScaledBitmap(cropped, w, h, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }
}
