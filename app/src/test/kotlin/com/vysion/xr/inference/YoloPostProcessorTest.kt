package com.vysion.xr.inference

import com.vysion.xr.inference.YoloPostProcessor.Candidate
import com.vysion.xr.inference.YoloPostProcessor.Letterbox
import com.vysion.xr.inference.YoloPostProcessor.OutputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hand-built tensors through the detector post-processing (FR-N2). No Android classes. */
class YoloPostProcessorTest {

    private val eps = 1e-4f

    @Test
    fun `letterbox of a 640x480 frame into 640x640 scales by 1 and pads 80 rows top and bottom`() {
        val lb = Letterbox.compute(640, 480, 640, 640)
        assertEquals(1f, lb.scale, eps)
        assertEquals(640, lb.scaledWidth)
        assertEquals(480, lb.scaledHeight)
        assertEquals(0, lb.padX)
        assertEquals(80, lb.padY)
    }

    @Test
    fun `letterbox of a wide frame pads the sides`() {
        val lb = Letterbox.compute(1280, 720, 640, 640)
        assertEquals(0.5f, lb.scale, eps)
        assertEquals(640, lb.scaledWidth)
        assertEquals(360, lb.scaledHeight)
        assertEquals(0, lb.padX)
        assertEquals(140, lb.padY)
        val tall = Letterbox.compute(480, 640, 640, 640)
        assertEquals(80, tall.padX)
        assertEquals(0, tall.padY)
    }

    @Test
    fun `toNormalized undoes the letterbox and clamps to the frame`() {
        val lb = Letterbox.compute(640, 480, 640, 640)
        val box = lb.toNormalized(64f, 80f + 48f, 320f, 80f + 240f)!!
        assertEquals(0.1f, box.left, eps)
        assertEquals(0.1f, box.top, eps)
        assertEquals(0.5f, box.right, eps)
        assertEquals(0.5f, box.bottom, eps)
        // A box entirely in the top padding maps to nothing.
        assertNull(lb.toNormalized(0f, 0f, 100f, 60f))
        // Partly in the padding: clamped to the frame edge.
        val clamped = lb.toNormalized(0f, 0f, 640f, 80f + 240f)!!
        assertEquals(0f, clamped.top, eps)
        assertEquals(1f, clamped.right, eps)
        // Swapped corners are tolerated.
        val swapped = lb.toNormalized(320f, 80f + 240f, 64f, 80f + 48f)!!
        assertEquals(box, swapped)
    }

    @Test
    fun `decode the verified yolo26 layout into frame coordinates`() {
        val lb = Letterbox.compute(640, 480, 640, 640)
        // Two rows: a person spanning the left half, a chair on the right; one row below threshold.
        val rows = floatArrayOf(
            0f, 80f, 320f, 560f, 0.9f, 0f,
            480f, 320f, 640f, 560f, 0.6f, 56f,
            10f, 90f, 50f, 120f, 0.1f, 2f,
        )
        val out = YoloPostProcessor.decode(rows, longArrayOf(1, 3, 6), OutputLayout.XYXY_CONF_CLS_ROWS, lb)
        assertEquals(2, out.size)
        assertEquals("person", out[0].label)
        assertEquals(0.9f, out[0].confidence, eps)
        assertEquals(0f, out[0].box.left, eps)
        assertEquals(0f, out[0].box.top, eps)
        assertEquals(0.5f, out[0].box.right, eps)
        assertEquals(1f, out[0].box.bottom, eps)
        assertEquals("chair", out[1].label)
        assertEquals(0.75f, out[1].box.left, eps)
        assertEquals(0.5f, out[1].box.top, eps)
        assertEquals(1f, out[1].box.right, eps)
        assertEquals(1f, out[1].box.bottom, eps)
    }

    @Test
    fun `decode the raw column layout picks the best class and applies NMS`() {
        val lb = Letterbox.compute(640, 640, 640, 640)
        // [1, 4 + 2 classes, 3 anchors]: anchors 0 and 1 overlap heavily as class 1; anchor 2 is class 0.
        val n = 3
        val data = FloatArray(6 * n)
        fun set(c: Int, i: Int, v: Float) {
            data[c * n + i] = v
        }
        // anchor 0: cx=320 cy=320 w=200 h=200, scores (0.1, 0.8)
        set(0, 0, 320f); set(1, 0, 320f); set(2, 0, 200f); set(3, 0, 200f); set(4, 0, 0.1f); set(5, 0, 0.8f)
        // anchor 1: nearly the same box, scores (0.2, 0.7) → suppressed by anchor 0
        set(0, 1, 325f); set(1, 1, 322f); set(2, 1, 200f); set(3, 1, 200f); set(4, 1, 0.2f); set(5, 1, 0.7f)
        // anchor 2: cx=100 cy=100 w=50 h=50, scores (0.5, 0.05)
        set(0, 2, 100f); set(1, 2, 100f); set(2, 2, 50f); set(3, 2, 50f); set(4, 2, 0.5f); set(5, 2, 0.05f)
        val names = listOf("person", "door")
        val out = YoloPostProcessor.decode(
            data, longArrayOf(1, 6, 3), OutputLayout.CXCYWH_SCORES_COLUMNS, lb, classNames = names,
        )
        assertEquals(2, out.size)
        assertEquals("door", out[0].label)
        assertEquals(0.8f, out[0].confidence, eps)
        assertEquals(220f / 640f, out[0].box.left, eps)
        assertEquals(420f / 640f, out[0].box.right, eps)
        assertEquals("person", out[1].label)
        assertEquals(75f / 640f, out[1].box.top, eps)
    }

    @Test
    fun `class-wise NMS keeps overlapping boxes of different classes`() {
        val a = Candidate(0f, 0f, 100f, 100f, 0.9f, 0)
        val b = Candidate(5f, 5f, 105f, 105f, 0.8f, 0) // same class, IoU ≈ 0.82 → suppressed
        val c = Candidate(5f, 5f, 105f, 105f, 0.7f, 1) // other class → kept
        val d = Candidate(300f, 300f, 400f, 400f, 0.95f, 0) // far away → kept, and first by score
        val kept = YoloPostProcessor.nms(listOf(a, b, c, d), 0.45f)
        assertEquals(listOf(d, a, c), kept)
        assertEquals(kept, YoloPostProcessor.nms(kept, 0.45f)) // idempotent on post-NMS input
    }

    @Test
    fun `iou of identical, disjoint and half-overlapping boxes`() {
        val a = Candidate(0f, 0f, 10f, 10f, 1f, 0)
        assertEquals(1f, YoloPostProcessor.iou(a, a), eps)
        assertEquals(0f, YoloPostProcessor.iou(a, Candidate(20f, 20f, 30f, 30f, 1f, 0)), eps)
        assertEquals(1f / 3f, YoloPostProcessor.iou(a, Candidate(5f, 0f, 15f, 10f, 1f, 0)), eps)
    }

    @Test
    fun `max detections caps the output and labels outside COCO stay visible`() {
        val lb = Letterbox.compute(640, 640, 640, 640)
        val rows = FloatArray(5 * 6)
        for (i in 0 until 5) {
            val o = i * 6
            rows[o] = i * 100f; rows[o + 1] = 0f; rows[o + 2] = i * 100f + 50f; rows[o + 3] = 50f
            rows[o + 4] = 0.5f + i * 0.05f; rows[o + 5] = 200f + i
        }
        val out = YoloPostProcessor.decode(
            rows, longArrayOf(1, 5, 6), OutputLayout.XYXY_CONF_CLS_ROWS, lb, maxDetections = 2,
        )
        assertEquals(2, out.size)
        assertEquals("class_204", out[0].label) // highest score first
        assertEquals("class_203", out[1].label)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects a batch that is not one`() {
        YoloPostProcessor.decode(
            FloatArray(12),
            longArrayOf(2, 1, 6),
            OutputLayout.XYXY_CONF_CLS_ROWS,
            Letterbox.compute(640, 640, 640, 640),
        )
    }

    @Test
    fun `letterboxToChw packs RGB planes, scales by nearest neighbor and pads with gray`() {
        // 2x1 source: red then blue. Into a 4x4 canvas: scale 2 → 4x2 image rows 1..2, pad rows 0 and 3.
        val argb = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt())
        val lb = Letterbox.compute(2, 1, 4, 4)
        assertEquals(2f, lb.scale, eps)
        assertEquals(4, lb.scaledWidth)
        assertEquals(2, lb.scaledHeight)
        assertEquals(1, lb.padY)
        val chw = YoloPostProcessor.letterboxToChw(argb, 2, 1, lb, padValue = 0.5f)
        assertEquals(3 * 16, chw.size)
        val plane = 16
        fun r(x: Int, y: Int) = chw[y * 4 + x]
        fun g(x: Int, y: Int) = chw[plane + y * 4 + x]
        fun b(x: Int, y: Int) = chw[2 * plane + y * 4 + x]
        // Pad rows.
        for (x in 0 until 4) {
            assertEquals(0.5f, r(x, 0), eps); assertEquals(0.5f, g(x, 0), eps); assertEquals(0.5f, b(x, 0), eps)
            assertEquals(0.5f, r(x, 3), eps)
        }
        // Image rows: left half red, right half blue, green zero.
        for (y in 1..2) {
            assertEquals(1f, r(0, y), eps); assertEquals(1f, r(1, y), eps); assertEquals(0f, b(0, y), eps)
            assertEquals(0f, r(2, y), eps); assertEquals(1f, b(2, y), eps); assertEquals(1f, b(3, y), eps)
            assertEquals(0f, g(0, y), eps); assertEquals(0f, g(3, y), eps)
        }
    }

    @Test
    fun `COCO has eighty names with person first and chair at 56`() {
        assertEquals(80, YoloPostProcessor.COCO_CLASSES.size)
        assertEquals("person", YoloPostProcessor.labelFor(0))
        assertEquals("chair", YoloPostProcessor.labelFor(56))
        assertEquals("couch", YoloPostProcessor.labelFor(57))
        assertEquals("toothbrush", YoloPostProcessor.labelFor(79))
        assertEquals("class_80", YoloPostProcessor.labelFor(80))
        assertTrue(YoloPostProcessor.COCO_CLASSES.toSet().size == 80)
    }
}
