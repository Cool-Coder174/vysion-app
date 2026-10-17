package com.vysion.xr.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the memory path: kB → MB and the 3.5 GB PSS alarm edge (NFR Memory). */
class MemorySamplerTest {

    @Test
    fun `total pss is reported in whole megabytes`() {
        assertEquals(0, MemorySampler.kbToMb(0))
        assertEquals(0, MemorySampler.kbToMb(1023))
        assertEquals(1, MemorySampler.kbToMb(1024))
        assertEquals(2_048, MemorySampler.kbToMb(2_048 * 1024 + 512))
    }

    @Test
    fun `alarm fires once on the upward crossing of 3500 MB`() {
        assertFalse(DiagnosticsCollector.crossedMemoryAlarm(null, null))
        assertFalse(DiagnosticsCollector.crossedMemoryAlarm(null, 3_500))
        assertTrue(DiagnosticsCollector.crossedMemoryAlarm(null, 3_501))
        assertTrue(DiagnosticsCollector.crossedMemoryAlarm(3_400, 3_600))
        assertFalse(DiagnosticsCollector.crossedMemoryAlarm(3_600, 3_700)) // already above: no repeat
        assertFalse(DiagnosticsCollector.crossedMemoryAlarm(3_600, null)) // lost the sample: nothing to say
        assertTrue(DiagnosticsCollector.crossedMemoryAlarm(3_000, 3_501))
    }
}
