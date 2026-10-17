package com.vysion.xr.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** FR-T5 as tests: angular speed from consecutive yaw/pitch samples. JVM only. */
class AngularSpeedTest {

    private val ms = 1_000_000L

    @Test
    fun `ten degrees of yaw in 100 ms is 100 degrees per second`() {
        assertEquals(100f, AngularSpeed.degPerS(0f, 0f, 0L, 10f, 0f, 100 * ms), 1e-3f)
    }

    @Test
    fun `yaw wraps the short way across 180`() {
        assertEquals(20f, AngularSpeed.degPerS(179f, 0f, 0L, -179f, 0f, 100 * ms), 1e-3f)
    }

    @Test
    fun `pitch and yaw combine as a euclidean angle`() {
        assertEquals(5f, AngularSpeed.degPerS(0f, 0f, 0L, 3f, 4f, 1000 * ms), 1e-3f)
    }

    @Test
    fun `a zero or negative time step yields zero, never infinity`() {
        assertEquals(0f, AngularSpeed.degPerS(0f, 0f, 5L, 10f, 0f, 5L), 0f)
        assertEquals(0f, AngularSpeed.degPerS(0f, 0f, 10L, 10f, 0f, 5L), 0f)
    }

    @Test
    fun `tracker reports zero on the first sample, speed on the next, and null while not tracking`() {
        val tracker = HeadPoseTracker()
        val first = tracker.next(head(yawDeg = 0f, nanos = 0L))
        assertNotNull(first)
        assertEquals(0f, first!!.angularSpeedDegPerS, 0f)

        val second = tracker.next(head(yawDeg = 30f, pitchDeg = 0f, nanos = 200 * ms))!!
        assertEquals(150f, second.angularSpeedDegPerS, 1e-3f)
        assertEquals(30f, second.yawDeg, 0f)
        assertEquals(200 * ms, second.timestampNanos)

        assertNull(tracker.next(head(yawDeg = 90f, tracking = Tracking.PAUSED, nanos = 300 * ms)))
        // the baseline was reset: the next tracked sample is a fresh start
        assertEquals(0f, tracker.next(head(yawDeg = 90f, nanos = 400 * ms))!!.angularSpeedDegPerS, 0f)
    }
}
