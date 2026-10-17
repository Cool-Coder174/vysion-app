package com.vysion.xr.perception

import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.RelativeTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-N3/FR-N4 as tests: head yaw + anchor offset → bearing, clock, distance, inView. JVM only. */
class RelativeGeometryTest {

    @Test
    fun `an anchor straight ahead is twelve o clock, in view, three meters`() {
        val t = RelativeGeometry.relative(Vec3.ZERO, 0f, Vec3(0f, 0f, -3f))
        assertEquals(RelativeTarget(bearingDeg = 0f, clock = 12, distanceM = 3.0f, inView = true), t)
    }

    @Test
    fun `an anchor to the right is three o clock and out of the 45 degree view`() {
        val t = RelativeGeometry.relative(Vec3.ZERO, 0f, Vec3(2f, 0f, 0f))
        assertEquals(90f, t.bearingDeg, 1e-4f)
        assertEquals(3, t.clock)
        assertEquals(2.0f, t.distanceM, 1e-6f)
        assertFalse(t.inView)
    }

    @Test
    fun `turning 180 degrees puts the door behind at six o clock and guidance still has it`() {
        // FR-N4: the anchor left the field of view; the relative target is computed from head pose alone.
        val t = RelativeGeometry.relative(Vec3.ZERO, 180f, Vec3(0f, 0f, -3f))
        assertEquals(180f, t.bearingDeg, 1e-4f)
        assertEquals(6, t.clock)
        assertFalse(t.inView)
        assertEquals(3.0f, t.distanceM, 1e-6f)
        // turning back restores twelve o'clock
        assertEquals(12, RelativeGeometry.relative(Vec3.ZERO, 360f, Vec3(0f, 0f, -3f)).clock)
    }

    @Test
    fun `the SRS example, door at two o clock`() {
        // head facing -Z, door 60° to the right at 3 m
        val door = Vec3(3f * 0.8660254f, 0f, -3f * 0.5f)
        val t = RelativeGeometry.relative(Vec3.ZERO, 0f, door)
        assertEquals(60f, t.bearingDeg, 1e-3f)
        assertEquals(2, t.clock)
        assertEquals(3.0f, t.distanceM, 1e-6f)
        assertFalse(t.inView)
        // turned 60° right toward it: now straight ahead and in view
        assertEquals(12, RelativeGeometry.relative(Vec3.ZERO, 60f, door).clock)
        assertTrue(RelativeGeometry.relative(Vec3.ZERO, 60f, door).inView)
    }

    @Test
    fun `head turned right makes a door ahead read to the left`() {
        val t = RelativeGeometry.relative(Vec3.ZERO, 60f, Vec3(0f, 0f, -3f))
        assertEquals(-60f, t.bearingDeg, 1e-4f)
        assertEquals(10, t.clock)
    }

    @Test
    fun `distance is horizontal and rounded to half meters, from the head position`() {
        val t = RelativeGeometry.relative(Vec3(1f, 0f, 1f), 0f, Vec3(1f, 1.2f, 1f - 2.74f))
        assertEquals(2.5f, t.distanceM, 1e-6f)
        assertEquals(12, t.clock)
    }

    @Test
    fun `bearing angles for the VLM answer`() {
        assertEquals(-30f, BearingAngles.degreesFor(HorizontalBearing.LEFT))
        assertEquals(0f, BearingAngles.degreesFor(HorizontalBearing.CENTER))
        assertEquals(30f, BearingAngles.degreesFor(HorizontalBearing.RIGHT))
        assertNull(BearingAngles.degreesFor(HorizontalBearing.NONE))
    }

    @Test
    fun `wrapDeg normalizes to the half-open range`() {
        assertEquals(-170f, HeadAngles.wrapDeg(190f), 1e-5f)
        assertEquals(170f, HeadAngles.wrapDeg(-190f), 1e-5f)
        assertEquals(180f, HeadAngles.wrapDeg(180f), 1e-5f)
        assertEquals(180f, HeadAngles.wrapDeg(-180f), 1e-5f)
        assertEquals(0f, HeadAngles.wrapDeg(720f), 1e-5f)
    }

    @Test
    fun `yaw pitch roll from the pose forward and up vectors`() {
        val level = HeadAngles.fromForwardUp(Vec3.FORWARD, Vec3.UP)
        assertEquals(0f, level.first, 1e-4f)
        assertEquals(0f, level.second, 1e-4f)
        assertEquals(0f, level.third, 1e-4f)

        assertEquals(90f, HeadAngles.fromForwardUp(Vec3(1f, 0f, 0f), Vec3.UP).first, 1e-4f) // facing +X = turned right
        assertEquals(-90f, HeadAngles.fromForwardUp(Vec3(-1f, 0f, 0f), Vec3.UP).first, 1e-4f)
        assertEquals(180f, HeadAngles.fromForwardUp(Vec3(0f, 0f, 1f), Vec3.UP).first, 1e-4f)

        val s = 0.70710677f
        assertEquals(45f, HeadAngles.fromForwardUp(Vec3(0f, s, -s), Vec3(0f, s, s)).second, 1e-3f) // looking up

        val tiltedRight = HeadAngles.fromForwardUp(Vec3.FORWARD, Vec3(0.5f, 0.8660254f, 0f)) // top of the head moved right
        assertEquals(30f, tiltedRight.third, 1e-3f)
    }

    @Test
    fun `forward vector for a yaw`() {
        val f0 = HeadAngles.forwardForYaw(0f)
        assertEquals(0f, f0.x, 1e-6f)
        assertEquals(-1f, f0.z, 1e-6f)
        val f90 = HeadAngles.forwardForYaw(90f)
        assertEquals(1f, f90.x, 1e-6f)
        assertEquals(0f, f90.z, 1e-6f)
    }
}
