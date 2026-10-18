package com.vysion.xr.inference

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmRuntimeTest {

    private val red = 0xFFFF0000.toInt()
    private val gray = 0xFF808080.toInt()

    @Test
    fun `uint8 CHW puts each channel in its own plane`() {
        val chw = VlmPixels.toUint8Chw(intArrayOf(red, gray))
        assertArrayEquals(intArrayOf(255, 128, 0, 128, 0, 128), chw)
    }

    @Test
    fun `normalized CHW applies mean and std per channel`() {
        val chw = VlmPixels.toNormalizedChw(intArrayOf(red), listOf(0.5f, 0.5f, 0.5f), listOf(0.5f, 0.5f, 0.5f))
        assertArrayEquals(floatArrayOf(1f, -1f, -1f), chw, 1e-6f)
    }

    @Test
    fun `raw float CHW keeps 0 to 255`() {
        assertArrayEquals(floatArrayOf(255f, 128f, 0f, 128f, 0f, 128f), VlmPixels.toFloatChw(intArrayOf(red, gray)), 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalization needs three channels`() {
        VlmPixels.toNormalizedChw(intArrayOf(red), listOf(0.5f), listOf(0.5f))
    }

    @Test
    fun `token cap fires exactly once at the limit`() {
        val cap = TokenCap(3)
        assertFalse(cap.add())
        assertFalse(cap.add())
        assertFalse(cap.reached)
        assertTrue(cap.add())
        assertTrue(cap.reached)
        assertEquals(3, cap.count)
    }
}
