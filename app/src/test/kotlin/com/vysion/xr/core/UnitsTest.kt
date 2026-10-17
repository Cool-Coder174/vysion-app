package com.vysion.xr.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Spec-as-tests for the two SRS phrasing rules in [ClockFace] and [Distances]. Runs on the JVM, no device needed. */
class UnitsTest {

    @Test
    fun `straight ahead is twelve o clock`() {
        assertEquals(12, ClockFace.fromRelativeYawDeg(0f))
        assertEquals(12, ClockFace.fromRelativeYawDeg(360f))
        assertEquals(12, ClockFace.fromRelativeYawDeg(-360f))
        assertEquals(12, ClockFace.fromRelativeYawDeg(359f))
    }

    @Test
    fun `right is three o clock and left is nine`() {
        assertEquals(3, ClockFace.fromRelativeYawDeg(90f))
        assertEquals(9, ClockFace.fromRelativeYawDeg(-90f))
        assertEquals(9, ClockFace.fromRelativeYawDeg(270f))
        assertEquals(6, ClockFace.fromRelativeYawDeg(180f))
        assertEquals(6, ClockFace.fromRelativeYawDeg(-180f))
    }

    @Test
    fun `thirty degrees per hour and the SRS example door at two o clock`() {
        assertEquals(1, ClockFace.fromRelativeYawDeg(30f))
        assertEquals(2, ClockFace.fromRelativeYawDeg(60f))
        assertEquals(2, ClockFace.fromRelativeYawDeg(52f))
        assertEquals(11, ClockFace.fromRelativeYawDeg(-30f))
        assertEquals(10, ClockFace.fromRelativeYawDeg(-61f))
    }

    @Test
    fun `distances round to the nearest half meter`() {
        assertEquals(2.5f, Distances.roundToHalfMeter(2.74f))
        assertEquals(3.0f, Distances.roundToHalfMeter(2.76f))
        assertEquals(3.0f, Distances.roundToHalfMeter(3.0f))
        assertEquals(0.5f, Distances.roundToHalfMeter(0.25f))
        assertEquals(0.0f, Distances.roundToHalfMeter(0.2f))
        assertEquals(0.0f, Distances.roundToHalfMeter(-1f))
    }

    @Test
    fun `normalized box exposes derived geometry`() {
        val box = NormalizedBox(left = 0.25f, top = 0.5f, right = 0.75f, bottom = 1.0f)
        assertEquals(0.5f, box.width)
        assertEquals(0.5f, box.height)
        assertEquals(0.5f, box.centerX)
        assertEquals(0.75f, box.centerY)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalized box rejects values outside the unit square`() {
        NormalizedBox(left = -0.1f, top = 0f, right = 1f, bottom = 1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `normalized box rejects inverted edges`() {
        NormalizedBox(left = 0.9f, top = 0f, right = 0.1f, bottom = 1f)
    }
}
