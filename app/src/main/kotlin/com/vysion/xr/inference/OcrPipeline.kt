package com.vysion.xr.inference

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The pure half of the Text Reader's OCR path (FR-T1): PP-OCRv6 as one ExecuTorch program with two methods,
 * `detect` (DBNet: `[1,3,H,W]` → text probability `[1,1,H,W]`) and `recognize` (SVTR: `[1,3,48,W]` →
 * `[1,W/8,V]` softmax, index 0 the CTC blank). Pre- and post-processing follow Software Mansion's reference
 * client (`react-native-executorch`, `paddleOcr.ts`, MIT): ImageNet-normalized RGB for the detector, `x/127.5 − 1`
 * for the recognizer, DB thresholds 0.3 / 0.6, unclip 1.5. Boxes are axis-aligned (signs, plates and pages are
 * upright in a head-mounted camera), so no OpenCV is needed. Everything here is JVM-tested; the two `execute`
 * calls live in `ExecuTorchInferenceEngine`. Pixel arrays are local to one call, never stored or logged (INV-3).
 */
internal object OcrPipeline {

    /** A text region in source-image pixels; [score] is the mean text probability inside it. */
    data class TextBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Float) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** The detector input and how to map its pixels back to the source image. */
    class DetInput(val chw: FloatArray, val width: Int, val height: Int, val scaleX: Float, val scaleY: Float)

    /** One recognizer input: `[1, 3, height, width]` float, width a multiple of [Ocr.REC_STRIDE]. */
    class RecInput(val chw: FloatArray, val width: Int, val height: Int)

    private val IMAGENET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val IMAGENET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)

    /**
     * Scale the frame so its long side is at most [maxSide], snap both sides up to a multiple of [multiple]
     * (DBNet downsamples by 32), sample bilinearly and normalize with ImageNet mean/std, CHW RGB.
     */
    fun detInput(argb: IntArray, srcW: Int, srcH: Int, maxSide: Int, multiple: Int): DetInput {
        require(srcW > 0 && srcH > 0 && argb.size >= srcW * srcH) { "bad source $srcW x $srcH" }
        val scale = min(1f, maxSide.toFloat() / max(srcW, srcH))
        val w = snapUp((srcW * scale).roundToInt(), multiple, maxSide)
        val h = snapUp((srcH * scale).roundToInt(), multiple, maxSide)
        val plane = w * h
        val out = FloatArray(3 * plane)
        val sx = srcW.toFloat() / w
        val sy = srcH.toFloat() / h
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = bilinear(argb, srcW, srcH, (x + 0.5f) * sx - 0.5f, (y + 0.5f) * sy - 0.5f)
                val o = y * w + x
                out[o] = (((p shr 16) and 0xFF) / 255f - IMAGENET_MEAN[0]) / IMAGENET_STD[0]
                out[plane + o] = (((p shr 8) and 0xFF) / 255f - IMAGENET_MEAN[1]) / IMAGENET_STD[1]
                out[2 * plane + o] = ((p and 0xFF) / 255f - IMAGENET_MEAN[2]) / IMAGENET_STD[2]
            }
        }
        return DetInput(out, w, h, scaleX = srcW.toFloat() / w, scaleY = srcH.toFloat() / h)
    }

    /**
     * DB post-processing: binarize at [binThreshold], label 8-connected components, keep those whose mean
     * probability is at least [boxThreshold] and whose sides are at least [minSide], expand each bounding box by
     * DB's unclip distance `area × ratio / perimeter`, and map it to source pixels with [scaleX] / [scaleY].
     * Returns at most [maxCandidates] boxes, highest score first.
     */
    fun boxes(
        prob: FloatArray,
        w: Int,
        h: Int,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        srcW: Int = (w * scaleX).roundToInt(),
        srcH: Int = (h * scaleY).roundToInt(),
        binThreshold: Float = 0.3f,
        boxThreshold: Float = 0.6f,
        unclipRatio: Float = 1.5f,
        minSide: Int = 3,
        maxCandidates: Int = 1000,
    ): List<TextBox> {
        require(prob.size >= w * h) { "prob has ${prob.size} values, need ${w * h}" }
        val label = IntArray(w * h) // 0 = unvisited, -1 = background or done
        val stack = IntArray(w * h)
        val out = ArrayList<TextBox>()
        for (start in 0 until w * h) {
            if (label[start] != 0) continue
            if (prob[start] <= binThreshold) {
                label[start] = -1
                continue
            }
            var top = 0
            stack[top++] = start
            label[start] = -1
            var minX = w
            var minY = h
            var maxX = -1
            var maxY = -1
            var sum = 0.0
            var count = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                sum += prob[i]
                count++
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if ((dx == 0 && dy == 0) || nx < 0 || nx >= w) continue
                        val j = ny * w + nx
                        if (label[j] == 0) {
                            label[j] = -1
                            if (prob[j] > binThreshold) stack[top++] = j
                        }
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (min(bw, bh) < minSide) continue
            val score = (sum / count).toFloat()
            if (score < boxThreshold) continue
            val d = bw.toFloat() * bh * unclipRatio / (2f * (bw + bh))
            out += TextBox(
                left = ((minX - d) * scaleX).roundToInt().coerceIn(0, srcW),
                top = ((minY - d) * scaleY).roundToInt().coerceIn(0, srcH),
                right = ((maxX + 1 + d) * scaleX).roundToInt().coerceIn(0, srcW),
                bottom = ((maxY + 1 + d) * scaleY).roundToInt().coerceIn(0, srcH),
                score = score,
            )
        }
        return out.sortedByDescending { it.score }.take(maxCandidates)
    }

    /**
     * Crop [box] from the source, scale it to [height] keeping the aspect ratio, cap the width at [maxWidth]
     * (a wider line is squeezed: [split] keeps that to ≤ 15 %), snap the width up to the CTC stride and pad with
     * neutral gray; normalize `x / 127.5 − 1`, CHW RGB.
     */
    fun recInput(argb: IntArray, srcW: Int, srcH: Int, box: TextBox, height: Int, maxWidth: Int, stride: Int): RecInput {
        require(box.width > 0 && box.height > 0) { "empty box $box" }
        val contentW = (height.toFloat() * box.width / box.height).roundToInt().coerceIn(1, maxWidth)
        val w = snapUp(contentW, stride, maxWidth)
        val plane = w * height
        val gray = 128f / 127.5f - 1f
        val out = FloatArray(3 * plane) { gray }
        val sx = box.width.toFloat() / contentW
        val sy = box.height.toFloat() / height
        for (y in 0 until height) {
            for (x in 0 until contentW) {
                val p = bilinear(argb, srcW, srcH, box.left + (x + 0.5f) * sx - 0.5f, box.top + (y + 0.5f) * sy - 0.5f)
                val o = y * w + x
                out[o] = ((p shr 16) and 0xFF) / 127.5f - 1f
                out[plane + o] = ((p shr 8) and 0xFF) / 127.5f - 1f
                out[2 * plane + o] = (p and 0xFF) / 127.5f - 1f
            }
        }
        return RecInput(out, w, height)
    }

    /**
     * A line too wide for the recognizer (more than 15 % over [maxWidth] at [height]) is cut into equal pieces
     * read one after another, as the reference client does; a glyph on a cut may be misread.
     */
    fun split(box: TextBox, height: Int, maxWidth: Int): List<TextBox> {
        val aspectW = height.toFloat() * box.width / max(1, box.height)
        if (aspectW <= maxWidth * WIDE_SQUISH_TOLERANCE) return listOf(box)
        val pieces = kotlin.math.ceil(aspectW / maxWidth).toInt()
        return (0 until pieces).map { k ->
            box.copy(left = box.left + box.width * k / pieces, right = box.left + box.width * (k + 1) / pieces)
        }
    }

    /**
     * Greedy CTC over `[timesteps, vocab]` probabilities: argmax per step, drop the blank (0) and repeats, map
     * index i to `charset[i - 1]` ([charset] excludes the blank). Confidence is the mean max probability over the
     * non-blank steps (0 when nothing was read).
     */
    fun ctcGreedy(probs: FloatArray, timesteps: Int, vocab: Int, charset: List<String>): Pair<String, Float> {
        require(probs.size >= timesteps * vocab) { "probs has ${probs.size} values, need ${timesteps * vocab}" }
        val sb = StringBuilder()
        var last = -1
        var sum = 0f
        var n = 0
        for (t in 0 until timesteps) {
            val o = t * vocab
            var best = 0
            var bestP = probs[o]
            for (c in 1 until vocab) {
                val p = probs[o + c]
                if (p > bestP) {
                    bestP = p
                    best = c
                }
            }
            if (best >= 1) {
                sum += bestP
                n++
                if (best != last) charset.getOrNull(best - 1)?.let(sb::append)
            }
            last = best
        }
        return sb.toString() to if (n == 0) 0f else sum / n
    }

    /**
     * Reading order for upright text: boxes whose vertical extents overlap by at least [overlap] of the shorter
     * height share a line; lines top to bottom, boxes left to right within a line.
     */
    fun <T> readingOrder(items: List<T>, overlap: Float = LINE_OVERLAP_FRACTION, box: (T) -> TextBox): List<List<T>> {
        val lines = ArrayList<MutableList<T>>()
        val extents = ArrayList<IntArray>() // [top, bottom] per line
        for (item in items.sortedBy { box(it).top }) {
            val b = box(item)
            val index = extents.indexOfFirst { e ->
                val shared = min(e[1], b.bottom) - max(e[0], b.top)
                shared >= overlap * min(e[1] - e[0], b.height)
            }
            if (index < 0) {
                lines += mutableListOf(item)
                extents += intArrayOf(b.top, b.bottom)
            } else {
                lines[index] += item
                extents[index][0] = min(extents[index][0], b.top)
                extents[index][1] = max(extents[index][1], b.bottom)
            }
        }
        return lines.indices.sortedBy { extents[it][0] }.map { i -> lines[i].sortedBy { box(it).left } }
    }

    private const val WIDE_SQUISH_TOLERANCE = 1.15f
    private const val LINE_OVERLAP_FRACTION = 0.3f

    /** Smallest multiple of [multiple] ≥ [size], at least [multiple], at most the largest multiple ≤ [cap]. */
    fun snapUp(size: Int, multiple: Int, cap: Int): Int {
        val top = max(multiple, cap / multiple * multiple)
        return ((max(1, size) + multiple - 1) / multiple * multiple).coerceIn(multiple, top)
    }

    /** ARGB at a fractional source position, clamped to the edges, bilinear per channel. */
    private fun bilinear(argb: IntArray, w: Int, h: Int, fx: Float, fy: Float): Int {
        val x0 = fx.toInt().coerceIn(0, w - 1)
        val y0 = fy.toInt().coerceIn(0, h - 1)
        val x1 = min(x0 + 1, w - 1)
        val y1 = min(y0 + 1, h - 1)
        val ax = (fx - x0).coerceIn(0f, 1f)
        val ay = (fy - y0).coerceIn(0f, 1f)
        val p00 = argb[y0 * w + x0]
        val p10 = argb[y0 * w + x1]
        val p01 = argb[y1 * w + x0]
        val p11 = argb[y1 * w + x1]
        fun ch(shift: Int): Int {
            val c00 = (p00 shr shift) and 0xFF
            val c10 = (p10 shr shift) and 0xFF
            val c01 = (p01 shr shift) and 0xFF
            val c11 = (p11 shr shift) and 0xFF
            val top = c00 + (c10 - c00) * ax
            val bottom = c01 + (c11 - c01) * ax
            return (top + (bottom - top) * ay).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** OCR model facts (PP-OCRv6 small, Software Mansion's ExecuTorch export). */
internal object Ocr {
    /** Recognizer input height (PP-OCRv6 SVTR, fixed by the export). */
    const val REC_HEIGHT: Int = 48

    /** SVTR maps 8 input columns to one CTC step: `W = 8 × T`. */
    const val REC_STRIDE: Int = 8

    /** Widest recognizer input tried; a wider line is split (the export's dynamic-width bound is not published). */
    const val REC_MAX_WIDTH: Int = 640

    /** Detector long side; the camera's 640×480 analysis frame fits as is, a still is scaled down. */
    const val DET_MAX_SIDE: Int = 960

    /** DBNet's downsampling: detector sides are multiples of this. */
    const val DET_MULTIPLE: Int = 32

    /** Lines read with a lower mean character probability are dropped (noise, half-visible text). */
    const val MIN_LINE_CONFIDENCE: Float = 0.5f
}
