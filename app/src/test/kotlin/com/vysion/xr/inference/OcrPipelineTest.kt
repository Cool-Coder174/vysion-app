package com.vysion.xr.inference

import com.vysion.xr.inference.OcrPipeline.TextBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrPipelineTest {

    @Test
    fun `detector input snaps to multiples of 32, keeps the long side under the cap and normalizes ImageNet`() {
        val white = IntArray(640 * 480) { 0xFFFFFFFF.toInt() }
        val input = OcrPipeline.detInput(white, 640, 480, maxSide = 960, multiple = 32)
        assertEquals(640, input.width)
        assertEquals(480, input.height)
        assertEquals((1f - 0.485f) / 0.229f, input.chw[0], 1e-4f)
        assertEquals((1f - 0.406f) / 0.225f, input.chw[2 * 640 * 480], 1e-4f)

        val big = OcrPipeline.detInput(IntArray(1920 * 1000), 1920, 1000, maxSide = 960, multiple = 32)
        assertEquals(960, big.width)
        assertEquals(512, big.height) // 500 → 512
        assertEquals(2f, big.scaleX, 1e-6f)
    }

    @Test
    fun `boxes finds each confident blob, expands it by the unclip distance and maps it to the source`() {
        val w = 40
        val h = 20
        val prob = FloatArray(w * h)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, p: Float) {
            for (y in y0 until y1) for (x in x0 until x1) prob[y * w + x] = p
        }
        fill(2, 2, 12, 6, 0.9f) // a confident 10 x 4 word
        fill(20, 10, 30, 14, 0.4f) // above the binarize threshold, below the box score → dropped
        fill(35, 2, 36, 18, 0.9f) // 1 px wide → below minSide
        val boxes = OcrPipeline.boxes(prob, w, h, scaleX = 2f, scaleY = 2f, srcW = 80, srcH = 40)
        assertEquals(1, boxes.size)
        val b = boxes.single()
        // d = 10 * 4 * 1.5 / (2 * 14) ≈ 2.14 → [2 - 2.14, 12 + 2.14] x [2 - 2.14, 6 + 2.14], times 2, clamped
        assertEquals(0, b.left)
        assertEquals(28, b.right)
        assertEquals(0, b.top)
        assertEquals(16, b.bottom)
        assertEquals(0.9f, b.score, 1e-5f)
    }

    @Test
    fun `recognizer input is 48 high, width snapped to the stride and capped, gray padded`() {
        val src = IntArray(200 * 50) { 0xFF000000.toInt() }
        val input = OcrPipeline.recInput(src, 200, 50, TextBox(0, 0, 100, 24, 1f), height = 48, maxWidth = 640, stride = 8)
        assertEquals(48, input.height)
        assertEquals(200, input.width) // 48 * 100 / 24 = 200, already a multiple of 8
        assertEquals(-1f, input.chw[0], 1e-6f) // black → -1
        val narrow = OcrPipeline.recInput(src, 200, 50, TextBox(0, 0, 10, 24, 1f), height = 48, maxWidth = 640, stride = 8)
        assertEquals(24, narrow.width) // 20 → 24; the last 4 columns are gray padding
        assertEquals(128f / 127.5f - 1f, narrow.chw[23], 1e-6f)
    }

    @Test
    fun `a line far wider than the recognizer is split into equal pieces`() {
        val line = TextBox(0, 0, 3000, 48, 1f)
        val pieces = OcrPipeline.split(line, height = 48, maxWidth = 640)
        assertEquals(5, pieces.size)
        assertEquals(0, pieces.first().left)
        assertEquals(3000, pieces.last().right)
        val slightlyWide = TextBox(0, 0, 700, 48, 1f)
        assertEquals(listOf(slightlyWide), OcrPipeline.split(slightlyWide, 48, 640)) // within 15 %: squeezed, not cut
    }

    @Test
    fun `ctc greedy drops blanks and repeats and averages the confidence`() {
        val charset = listOf("a", "b") // vocab 3 with the blank at 0
        val steps = listOf(1, 1, 0, 1, 2, 2) // a a _ a b b → "aab"
        val probs = FloatArray(steps.size * 3)
        steps.forEachIndexed { t, c -> probs[t * 3 + c] = if (c == 0) 0.9f else 0.8f }
        val (text, conf) = OcrPipeline.ctcGreedy(probs, steps.size, 3, charset)
        assertEquals("aab", text)
        assertEquals(0.8f, conf, 1e-6f)
        assertEquals("" to 0f, OcrPipeline.ctcGreedy(FloatArray(6) { if (it % 3 == 0) 1f else 0f }, 2, 3, charset))
    }

    @Test
    fun `reading order groups boxes into lines top to bottom, left to right`() {
        val words = listOf(
            "floor" to TextBox(200, 105, 300, 140, 1f),
            "Meeting" to TextBox(0, 0, 150, 40, 1f),
            "Rooms" to TextBox(160, 5, 300, 42, 1f),
            "Second" to TextBox(0, 100, 180, 138, 1f),
        )
        val lines = OcrPipeline.readingOrder(words) { it.second }.map { line -> line.joinToString(" ") { it.first } }
        assertEquals(listOf("Meeting Rooms", "Second floor"), lines)
    }

    @Test
    fun `snapUp rounds up to the multiple within the cap`() {
        assertEquals(32, OcrPipeline.snapUp(1, 32, 960))
        assertEquals(512, OcrPipeline.snapUp(500, 32, 960))
        assertEquals(960, OcrPipeline.snapUp(2000, 32, 960))
        assertTrue(OcrPipeline.snapUp(641, 8, 640) == 640)
    }
}
