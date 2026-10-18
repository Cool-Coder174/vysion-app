package com.vysion.xr.inference

import com.vysion.xr.core.Detection
import com.vysion.xr.core.NormalizedBox
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure pre- and post-processing for the detector (FR-N2): letterbox geometry, pixel packing into the float CHW
 * tensor, YOLO output decoding, confidence threshold, class-wise NMS, and the mapping back to
 * [NormalizedBox] in the ORIGINAL frame's coordinates (the letterbox is undone, so R1's overlay and R3's
 * navigation never see the detector's input size).
 *
 * - Contract provided: produces `List<Detection>` for `InferenceEngine.detect` (consumed by R3 `NavigationEngine`
 *   and R1 `OverlayRenderer`).
 * - FR ids: FR-N2 (detector ≥ 5 fps, outlines in passthrough), FR-N8 (P2 door class: same decoder, new names).
 * - SRS §8 R2 due: "Export YOLO26-n (COCO) to .pte with XNNPACK, int8; run on headset; record ms/frame. Sat 13:00".
 * - Owner: R2 ML / ExecuTorch.
 * - API: the tensor facts come from `docs/stack.md` §3-R2 "YOLO26 facts" (VERIFIED, ET-PS/ET-AC): input float32
 *   NCHW `[1,3,640,640]` RGB letterboxed /255; output `[1, N, 6]` rows `[x1, y1, x2, y2, confidence, class_id]`
 *   already post-NMS, in letterboxed-input pixels; COCO80 ids (`person` = 0). That is
 *   [OutputLayout.XYXY_CONF_CLS_ROWS],
 *   the default. [OutputLayout.CXCYWH_SCORES_COLUMNS] covers a raw Ultralytics head (`[1, 4+nc, N]`, no NMS) in case
 *   a re-export (FR-N8 door fine-tune) drops the in-graph NMS.
 * - Assumption: none beyond the export facts above; `docs/stack.md` Q7 (the .pte loads on the 1.3.1 AAR).
 *
 * No Android imports: everything here runs in JVM unit tests with hand-built arrays. The one Android step,
 * `Bitmap.getPixels` into the ARGB `IntArray` that [letterboxToChw] consumes, lives in the engine.
 */
object YoloPostProcessor {

    /** How the raw output tensor is laid out. Documented per value; passed explicitly by the engine. */
    enum class OutputLayout {
        /**
         * `[1, N, 6]`, each row `[x1, y1, x2, y2, confidence, class_id]` in letterboxed-input pixels, post-NMS.
         * The verified `examples/models/yolo26` XNNPACK export at ExecuTorch v1.3.1 (`docs/stack.md` §3-R2).
         */
        XYXY_CONF_CLS_ROWS,

        /**
         * `[1, 4 + numClasses, N]`: for anchor `i`, `data[c * N + i]` with `c` = 0..3 → `cx, cy, w, h` and
         * `c` = 4.. → per-class scores; letterboxed-input pixels; no NMS in the graph. Raw Ultralytics detect head.
         */
        CXCYWH_SCORES_COLUMNS,
    }

    /**
     * Letterbox geometry: the source frame scaled by [scale] (aspect preserved) and centered in a
     * [dstWidth]×[dstHeight] canvas with [padX]/[padY] pixels of padding on the left/top (integer, floor of the
     * remainder halved; the right/bottom pad absorbs the odd pixel). One definition serves both directions, so
     * the un-pad math in [toNormalized] is consistent with [letterboxToChw] by construction.
     */
    data class Letterbox(
        val srcWidth: Int,
        val srcHeight: Int,
        val dstWidth: Int,
        val dstHeight: Int,
        val scale: Float,
        val scaledWidth: Int,
        val scaledHeight: Int,
        val padX: Int,
        val padY: Int,
    ) {
        /**
         * Undo the letterbox for a box in letterboxed pixels and normalize it to the original frame (0..1, origin
         * top-left). Clamped to the frame; `null` when nothing of the box lies inside it (fully in the padding).
         */
        fun toNormalized(x1: Float, y1: Float, x2: Float, y2: Float): NormalizedBox? {
            val left = ((min(x1, x2) - padX) / scale / srcWidth).coerceIn(0f, 1f)
            val right = ((max(x1, x2) - padX) / scale / srcWidth).coerceIn(0f, 1f)
            val top = ((min(y1, y2) - padY) / scale / srcHeight).coerceIn(0f, 1f)
            val bottom = ((max(y1, y2) - padY) / scale / srcHeight).coerceIn(0f, 1f)
            if (right <= left || bottom <= top) return null
            return NormalizedBox(left = left, top = top, right = right, bottom = bottom)
        }

        companion object {
            fun compute(srcWidth: Int, srcHeight: Int, dstWidth: Int, dstHeight: Int): Letterbox {
                require(srcWidth > 0 && srcHeight > 0 && dstWidth > 0 && dstHeight > 0) {
                    "letterbox sizes must be positive: $srcWidth x $srcHeight -> $dstWidth x $dstHeight"
                }
                val scale = min(dstWidth.toFloat() / srcWidth, dstHeight.toFloat() / srcHeight)
                val scaledWidth = min(dstWidth, max(1, (srcWidth * scale).roundToInt()))
                val scaledHeight = min(dstHeight, max(1, (srcHeight * scale).roundToInt()))
                return Letterbox(
                    srcWidth = srcWidth,
                    srcHeight = srcHeight,
                    dstWidth = dstWidth,
                    dstHeight = dstHeight,
                    scale = scale,
                    scaledWidth = scaledWidth,
                    scaledHeight = scaledHeight,
                    padX = (dstWidth - scaledWidth) / 2,
                    padY = (dstHeight - scaledHeight) / 2,
                )
            }
        }
    }

    /** One decoded row before NMS, in letterboxed-input pixels. */
    data class Candidate(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val score: Float,
        val classId: Int,
    ) {
        val area: Float get() = max(0f, x2 - x1) * max(0f, y2 - y1)
    }

    /** Ultralytics' gray pad (114/255) so the letterbox border matches what the int8 calibration saw. */
    const val DEFAULT_PAD_VALUE: Float = 114f / 255f

    /** Confidence floor; below it a row is noise for an outline the user cannot act on. */
    const val DEFAULT_CONF_THRESHOLD: Float = 0.25f

    /** Class-wise NMS IoU; Ultralytics' default. */
    const val DEFAULT_IOU_THRESHOLD: Float = 0.45f

    /** Cap so a crowded frame cannot flood the overlay or the 5 fps budget. */
    const val DEFAULT_MAX_DETECTIONS: Int = 50

    /** COCO80 class names in id order (`person` = 0, `chair` = 56, `couch` = 57), per the Ultralytics dataset yaml. */
    val COCO_CLASSES: List<String> = listOf(
        "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat", "traffic light",
        "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat", "dog", "horse", "sheep", "cow",
        "elephant", "bear", "zebra", "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
        "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove", "skateboard", "surfboard",
        "tennis racket", "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
        "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
        "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse", "remote", "keyboard", "cell phone",
        "microwave", "oven", "toaster", "sink", "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
        "hair drier", "toothbrush",
    )

    /** Label for a class id; ids outside [classNames] become `"class_<id>"` so a bad export is visible, not hidden. */
    fun labelFor(classId: Int, classNames: List<String> = COCO_CLASSES): String =
        classNames.getOrNull(classId) ?: "class_$classId"

    /**
     * Pack ARGB pixels (as `Bitmap.getPixels` fills them, row-major, `0xAARRGGBB`) into the detector input:
     * float32 CHW `[3, dstHeight, dstWidth]`, RGB, each channel /255, letterboxed per [lb] with nearest-neighbor
     * sampling and [padValue] outside the image. Returns a new array of `3 * dstWidth * dstHeight` floats.
     */
    fun letterboxToChw(
        argb: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        lb: Letterbox,
        padValue: Float = DEFAULT_PAD_VALUE,
    ): FloatArray {
        require(argb.size >= srcWidth * srcHeight) { "argb has ${argb.size} pixels, need ${srcWidth * srcHeight}" }
        require(lb.srcWidth == srcWidth && lb.srcHeight == srcHeight) { "letterbox was computed for another size" }
        val w = lb.dstWidth
        val h = lb.dstHeight
        val plane = w * h
        val out = FloatArray(3 * plane) { padValue }
        val inv = 1f / lb.scale
        for (dy in lb.padY until min(h, lb.padY + lb.scaledHeight)) {
            val sy = min(srcHeight - 1, ((dy - lb.padY) * inv).toInt())
            val srcRow = sy * srcWidth
            val dstRow = dy * w
            for (dx in lb.padX until min(w, lb.padX + lb.scaledWidth)) {
                val sx = min(srcWidth - 1, ((dx - lb.padX) * inv).toInt())
                val p = argb[srcRow + sx]
                val o = dstRow + dx
                out[o] = ((p shr 16) and 0xFF) / 255f
                out[plane + o] = ((p shr 8) and 0xFF) / 255f
                out[2 * plane + o] = (p and 0xFF) / 255f
            }
        }
        return out
    }

    /**
     * Decode one output tensor into detections in the original frame's normalized coordinates.
     *
     * @param data the tensor's data (`Tensor.getDataAsFloatArray()`), batch of one.
     * @param shape the tensor's shape (`Tensor.shape()`), interpreted per [layout].
     * @param lb the letterbox the input was built with, so boxes map back to the frame.
     */
    fun decode(
        data: FloatArray,
        shape: LongArray,
        layout: OutputLayout,
        lb: Letterbox,
        confThreshold: Float = DEFAULT_CONF_THRESHOLD,
        iouThreshold: Float = DEFAULT_IOU_THRESHOLD,
        maxDetections: Int = DEFAULT_MAX_DETECTIONS,
        classNames: List<String> = COCO_CLASSES,
        onlyClasses: Map<Int, String>? = null,
    ): List<Detection> {
        val keep = onlyClasses?.keys?.sorted()?.toIntArray()
        val decoded = when (layout) {
            OutputLayout.XYXY_CONF_CLS_ROWS -> decodeRows(data, shape, confThreshold)
                .let { rows -> if (keep == null) rows else rows.filter { it.classId in onlyClasses } }
            OutputLayout.CXCYWH_SCORES_COLUMNS -> decodeColumns(data, shape, confThreshold, keep)
        }
        // Ids that share a label ("Person", "Man", "Woman" → "person") are one class for NMS, so one body gets one box.
        val canonical = onlyClasses?.entries?.groupBy({ it.value }, { it.key })?.values
            ?.flatMap { ids -> ids.map { it to ids.min() } }?.toMap()
        val candidates = if (canonical == null) decoded else decoded.map { c -> c.copy(classId = canonical[c.classId] ?: c.classId) }
        return nms(candidates, iouThreshold)
            .asSequence()
            .take(maxDetections)
            .mapNotNull { c ->
                lb.toNormalized(c.x1, c.y1, c.x2, c.y2)?.let { box ->
                    val label = onlyClasses?.get(c.classId) ?: labelFor(c.classId, classNames)
                    Detection(label = label, confidence = c.score.coerceIn(0f, 1f), box = box)
                }
            }
            .toList()
    }

    /** [OutputLayout.XYXY_CONF_CLS_ROWS]: `[1, N, cols≥6]`. */
    internal fun decodeRows(data: FloatArray, shape: LongArray, confThreshold: Float): List<Candidate> {
        require(shape.size == 3 && shape[0] == 1L) { "expected [1, N, 6], got ${shape.toList()}" }
        val n = shape[1].toInt()
        val cols = shape[2].toInt()
        require(cols >= 6) { "expected ≥ 6 columns [x1,y1,x2,y2,conf,cls], got $cols" }
        require(data.size >= n * cols) { "data has ${data.size} floats, shape needs ${n * cols}" }
        val out = ArrayList<Candidate>()
        for (i in 0 until n) {
            val o = i * cols
            val score = data[o + 4]
            if (score < confThreshold) continue
            out += Candidate(data[o], data[o + 1], data[o + 2], data[o + 3], score, data[o + 5].toInt())
        }
        return out
    }

    /**
     * [OutputLayout.CXCYWH_SCORES_COLUMNS]: `[1, 4 + nc, N]`, best class per anchor, among [onlyClassIds] when given
     * (a 601-class Open Images model is asked only about door, stairs, people and furniture, so a door is not lost
     * to "Door handle" on the same anchor, and 590 class rows are never read).
     */
    internal fun decodeColumns(
        data: FloatArray,
        shape: LongArray,
        confThreshold: Float,
        onlyClassIds: IntArray? = null,
    ): List<Candidate> {
        require(shape.size == 3 && shape[0] == 1L) { "expected [1, 4+nc, N], got ${shape.toList()}" }
        val channels = shape[1].toInt()
        val n = shape[2].toInt()
        require(channels > 4) { "expected 4 box channels + ≥ 1 class, got $channels" }
        require(data.size >= channels * n) { "data has ${data.size} floats, shape needs ${channels * n}" }
        val out = ArrayList<Candidate>()
        val classIds = onlyClassIds?.filter { it in 0 until channels - 4 }?.toIntArray()
            ?: IntArray(channels - 4) { it }
        for (i in 0 until n) {
            var best = -1
            var bestScore = -1f
            for (cls in classIds) {
                val s = data[(cls + 4) * n + i]
                if (s > bestScore) {
                    bestScore = s
                    best = cls
                }
            }
            if (bestScore < confThreshold) continue
            val cx = data[i]
            val cy = data[n + i]
            val w = data[2 * n + i]
            val h = data[3 * n + i]
            out += Candidate(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f, bestScore, best)
        }
        return out
    }

    /** Greedy class-wise non-maximum suppression; result sorted by score descending. Idempotent on post-NMS input. */
    fun nms(candidates: List<Candidate>, iouThreshold: Float): List<Candidate> {
        val sorted = candidates.sortedByDescending { it.score }
        val kept = ArrayList<Candidate>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val a = sorted[i]
            kept += a
            for (j in i + 1 until sorted.size) {
                if (suppressed[j]) continue
                val b = sorted[j]
                if (b.classId == a.classId && iou(a, b) > iouThreshold) suppressed[j] = true
            }
        }
        return kept
    }

    fun iou(a: Candidate, b: Candidate): Float {
        val ix = max(0f, min(a.x2, b.x2) - max(a.x1, b.x1))
        val iy = max(0f, min(a.y2, b.y2) - max(a.y1, b.y1))
        val inter = ix * iy
        val union = a.area + b.area - inter
        return if (union <= 0f) 0f else inter / union
    }
}
