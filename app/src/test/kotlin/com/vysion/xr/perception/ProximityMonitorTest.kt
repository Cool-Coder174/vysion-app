package com.vysion.xr.perception

import com.vysion.xr.core.NavigationCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-N1 as tests: the 60° cone math and the 1.0 m / 2 s rule. JVM only. */
class ProximityMonitorTest {

    private val fov = FovDeg(90f, 70f)

    @Test
    fun `focal length and cone bounds follow the pinhole model`() {
        // 100 px wide, 90° horizontal FOV: fx = 50 / tan(45°) = 50 px; cone radius = 50 · tan 30° ≈ 28.87 px
        assertEquals(50f, DepthCone.focalLengthPx(100, 90f), 0.01f)
        val box = DepthCone.bounds(100, 100, FovDeg(90f, 90f))
        assertEquals(20, box.left) // floor(49.5 - 28.87)
        assertEquals(78, box.right) // (49.5 + 28.87).toInt()
        assertEquals(20, box.top)
        assertEquals(78, box.bottom)
    }

    @Test
    fun `a narrow field of view clamps the cone to the image`() {
        val box = DepthCone.bounds(100, 100, FovDeg(40f, 40f))
        assertEquals(DepthCone.PixelRect(0, 0, 99, 99), box)
    }

    @Test
    fun `center pixel is inside the cone and the image edge is outside at 90 degrees`() {
        assertTrue(DepthCone.isInCone(49, 49, 100, 100, FovDeg(90f, 90f)))
        assertFalse(DepthCone.isInCone(0, 49, 100, 100, FovDeg(90f, 90f))) // 44.7° off axis
        assertFalse(DepthCone.isInCone(0, 0, 100, 100, FovDeg(90f, 90f)))
        // the box corner is outside the disc even though it is inside the bounds
        assertFalse(DepthCone.isInCone(20, 20, 100, 100, FovDeg(90f, 90f)))
    }

    @Test
    fun `minTrustedDepth ignores zero, low-confidence and out-of-cone pixels`() {
        val frame = depthFrame(fov = fov, fillM = 5f, centerM = 0.4f)
        assertEquals(0.4f, DepthCone.minTrustedDepth(frame)!!, 1e-6f)

        val cx = frame.width / 2
        val cy = frame.height / 2
        frame.confidence!![cy * frame.width + cx] = 50.toByte() // below MIN_CONFIDENCE
        assertEquals(5f, DepthCone.minTrustedDepth(frame)!!, 1e-6f)

        frame.depthM[0] = 0.3f // corner pixel: outside the cone
        assertEquals(5f, DepthCone.minTrustedDepth(frame)!!, 1e-6f)

        frame.depthM[cy * frame.width + cx + 1] = 0f // no data
        assertEquals(5f, DepthCone.minTrustedDepth(frame)!!, 1e-6f)
    }

    @Test
    fun `a map with no trusted pixel yields null`() {
        val frame = depthFrame(fov = fov, fillM = 0f)
        assertNull(DepthCone.minTrustedDepth(frame))
        val monitor = ProximityMonitor(clock = { 0L })
        assertNull(monitor.onDepth(frame))
        assertNull(monitor.lastMinDepthM)
    }

    @Test
    fun `missing confidence map counts every pixel as trusted`() {
        val frame = depthFrame(fov = fov, fillM = 5f, centerM = 0.8f, withConfidence = false)
        assertEquals(0.8f, DepthCone.minTrustedDepth(frame)!!, 1e-6f)
    }

    @Test
    fun `emits below one meter and then debounces for two seconds`() {
        var now = 0L
        val monitor = ProximityMonitor(clock = { now })
        fun frameAt(t: Long, m: Float) = depthFrame(fov = fov, centerM = m, timestampNanos = t)

        assertEquals(NavigationCue.Proximity(0.5f), monitor.onDepth(frameAt(0L, 0.5f)))
        now = 1_000_000_000L
        assertNull(monitor.onDepth(frameAt(now, 0.5f))) // 1 s later: debounced
        assertEquals(0.5f, monitor.lastMinDepthM!!, 1e-6f) // but still measured
        now = 1_999_999_999L
        assertNull(monitor.onDepth(frameAt(now, 0.4f)))
        now = 2_000_000_000L
        assertEquals(NavigationCue.Proximity(0.4f), monitor.onDepth(frameAt(now, 0.4f))) // FR-N1: every 2 s
        now = 2_500_000_000L
        assertNull(monitor.onDepth(frameAt(now, 0.4f)))
    }

    @Test
    fun `nothing is emitted at or above the threshold`() {
        val monitor = ProximityMonitor(clock = { 0L })
        assertNull(monitor.onDepth(depthFrame(fov = fov, centerM = 1.0f)))
        assertNull(monitor.onDepth(depthFrame(fov = fov, centerM = 3.0f)))
        assertNotNull(monitor.lastMinDepthM)
    }

    @Test
    fun `a stale depth map is ignored`() {
        val monitor = ProximityMonitor(clock = { 1_000_000_000L })
        assertNull(monitor.onDepth(depthFrame(fov = fov, centerM = 0.5f, timestampNanos = 0L))) // 1 s old
        assertNotNull(monitor.onDepth(depthFrame(fov = fov, centerM = 0.5f, timestampNanos = 900_000_000L)))
    }

    @Test
    fun `reset forgets the debounce`() {
        var now = 0L
        val monitor = ProximityMonitor(clock = { now })
        assertNotNull(monitor.onDepth(depthFrame(fov = fov, centerM = 0.5f)))
        monitor.reset()
        now = 100_000_000L
        assertNotNull(monitor.onDepth(depthFrame(fov = fov, centerM = 0.5f, timestampNanos = now)))
    }

    @Test
    fun `pixelForBearing maps a bearing to a column and rejects bearings outside the image`() {
        // 64 px wide, 90° FOV: fx = 32 px, cx = 31.5
        assertEquals(32 to 24, DepthCone.pixelForBearing(0f, 0f, 64, 48, fov))
        val right30 = DepthCone.pixelForBearing(30f, 0f, 64, 48, fov)!!
        assertEquals(50, right30.first) // 31.5 + 32 · tan 30°
        assertNull(DepthCone.pixelForBearing(45f, 0f, 64, 48, fov)) // 63.5 → 64, off the image
        assertNull(DepthCone.pixelForBearing(0f, 90f, 64, 48, fov))
    }

    @Test
    fun `median depth around a pixel is robust to a single outlier`() {
        val frame = depthFrame(fov = fov, fillM = 2f, centerM = 0.2f)
        assertEquals(2f, DepthCone.medianTrustedDepthAround(frame, frame.width / 2, frame.height / 2, radius = 1)!!, 1e-6f)
        val empty = depthFrame(fov = fov, fillM = 0f)
        assertNull(DepthCone.medianTrustedDepthAround(empty, 5, 5))
    }
}
