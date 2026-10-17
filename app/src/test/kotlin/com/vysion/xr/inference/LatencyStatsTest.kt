package com.vysion.xr.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [LatencyStats] (median/p90 ring) and [FpsWindow] (5 s sliding window) with a hand-driven clock. */
class LatencyStatsTest {

    @Test
    fun `empty stats are not measured`() {
        val s = LatencyStats(8)
        assertNull(s.last)
        assertNull(s.median())
        assertNull(s.p90())
        assertEquals(0, s.count)
        assertEquals(LatencyStats.Snapshot(null, null, null, 0), s.snapshot())
    }

    @Test
    fun `single sample is last, median and p90`() {
        val s = LatencyStats(8)
        s.record(4200)
        assertEquals(4200L, s.last)
        assertEquals(4200L, s.median())
        assertEquals(4200L, s.p90())
        assertEquals(1, s.count)
    }

    @Test
    fun `nearest-rank percentiles over ten samples`() {
        val s = LatencyStats(64)
        listOf(10L, 20, 30, 40, 50, 60, 70, 80, 90, 100).shuffled().forEach(s::record)
        // ceil(0.5 * 10) = 5th smallest = 50; ceil(0.9 * 10) = 9th smallest = 90.
        assertEquals(50L, s.median())
        assertEquals(90L, s.p90())
        assertEquals(10L, s.percentile(0.01))
        assertEquals(100L, s.percentile(1.0))
    }

    @Test
    fun `twenty-run benchmark shape`() {
        val s = LatencyStats(64)
        (1L..20L).map { it * 100 }.forEach(s::record)
        assertEquals(1000L, s.median()) // ceil(10) = 10th smallest
        assertEquals(1800L, s.p90()) // ceil(18) = 18th smallest
        assertEquals(20, s.count)
    }

    @Test
    fun `ring keeps only the newest capacity samples but count keeps growing`() {
        val s = LatencyStats(4)
        listOf(1000L, 1000, 1000, 1000).forEach(s::record)
        assertEquals(1000L, s.median())
        listOf(10L, 20, 30, 40).forEach(s::record) // evicts the four 1000s
        assertEquals(40L, s.last)
        assertEquals(20L, s.median())
        assertEquals(40L, s.p90())
        assertEquals(8, s.count)
    }

    @Test
    fun `reset returns to not measured`() {
        val s = LatencyStats(4)
        s.record(5)
        s.reset()
        assertNull(s.last)
        assertNull(s.median())
        assertEquals(0, s.count)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `capacity must be positive`() {
        LatencyStats(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `percentile must be in zero-one`() {
        LatencyStats(4).apply { record(1) }.percentile(0.0)
    }

    @Test
    fun `fps is zero until two frames exist`() {
        val w = FpsWindow(5_000_000_000L)
        assertEquals(0f, w.fps(0L))
        assertEquals(0f, w.record(0L))
        assertEquals(0f, w.fps(1_000_000_000L))
    }

    @Test
    fun `fps over the span of frames inside the window`() {
        val w = FpsWindow(5_000_000_000L)
        val frame = 200_000_000L // 5 fps
        var fps = 0f
        for (i in 0..10) fps = w.record(i * frame)
        assertEquals(5f, fps, 0.001f)
    }

    @Test
    fun `frames older than the window are dropped`() {
        val w = FpsWindow(5_000_000_000L)
        // 10 fps for 5 s, then a 10 s gap, then 2 fps.
        for (i in 0 until 50) w.record(i * 100_000_000L)
        val resume = 15_000_000_000L
        w.record(resume)
        val f = w.record(resume + 500_000_000L)
        assertEquals(2f, f, 0.001f)
        assertEquals(2f, w.fps(resume + 500_000_000L), 0.001f)
        assertEquals(0f, w.fps(resume + 500_000_000L + 6_000_000_000L)) // everything aged out
    }

    @Test
    fun `reset clears the window`() {
        val w = FpsWindow()
        w.record(0L)
        w.record(100_000_000L)
        w.reset()
        assertEquals(0f, w.fps(100_000_000L))
    }
}
