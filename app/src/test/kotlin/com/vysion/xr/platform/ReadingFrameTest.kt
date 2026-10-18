package com.vysion.xr.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingFrameTest {

    @Test
    fun `a frame as wide as the camera covers the whole image, a smaller one a centered part`() {
        val whole = ReadingFrame.boxFor(63.6f, 63.6f, 63.6f)
        assertEquals(0f, whole.left, 1e-4f)
        assertEquals(1f, whole.bottom, 1e-4f)
        val wider = ReadingFrame.boxFor(90f, 90f, 63.6f) // clamped to the image
        assertEquals(0f, wider.top, 1e-4f)
    }

    @Test
    fun `the crosshair's 30 by 20 degrees is the middle 43 by 28 percent of the still`() {
        val box = ReadingFrame.stillBox
        assertEquals(0.432f, box.right - box.left, 0.005f)
        assertEquals(0.284f, box.bottom - box.top, 0.005f)
        assertEquals(0.5f, (box.left + box.right) / 2f, 1e-4f)
        assertEquals(0.5f, (box.top + box.bottom) / 2f, 1e-4f)
    }

    @Test
    fun `the drawn frame keeps its angle at any distance`() {
        assertEquals(0.536f, ReadingFrame.widthAt(1f), 0.001f) // 2·tan 15°
        assertEquals(2 * 0.536f, ReadingFrame.widthAt(2f), 0.002f)
        assertEquals(0.353f, ReadingFrame.heightAt(1f), 0.001f) // 2·tan 10°
    }
}
