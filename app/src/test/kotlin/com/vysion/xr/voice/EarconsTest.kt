package com.vysion.xr.voice

import com.vysion.xr.core.Earcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The tone constants and the pure synthesizer; playback itself is a device stub (TODO(FR-N1)). */
class EarconsTest {

    @Test
    fun `every earcon kind has a tone and only PROXIMITY rises`() {
        for (kind in Earcon.values()) assertTrue(kind.name, Earcons.toneFor(kind).durationMs > 0)
        val rising = Earcon.values().filter { Earcons.toneFor(it).let { t -> t.endHz > t.startHz } }
        assertEquals(listOf(Earcon.PROXIMITY), rising)
        assertTrue(Earcons.PROXIMITY.totalMs <= 300) // FR-N1: fits inside the 300 ms proximity budget
        assertEquals(240, Earcons.THINKING.totalMs)
    }

    @Test
    fun `pcm length and amplitude follow the tone`() {
        val pcm = EarconSynth.pcm(Earcons.LISTENING)
        assertEquals(EarconSynth.SAMPLE_RATE_HZ * 120 / 1000, pcm.size)
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak <= (Short.MAX_VALUE * EarconSynth.AMPLITUDE).toInt() + 1)
        assertTrue("peak $peak", peak >= (Short.MAX_VALUE * EarconSynth.AMPLITUDE * 0.9).toInt())
        assertEquals(0, pcm.first().toInt()) // fade-in starts from silence
        val thinking = EarconSynth.pcm(Earcons.THINKING)
        assertEquals(EarconSynth.SAMPLE_RATE_HZ * Earcons.THINKING.totalMs / 1000, thinking.size)
        val gapStart = EarconSynth.SAMPLE_RATE_HZ * 80 / 1000 + 10
        assertEquals(0, thinking[gapStart].toInt()) // silence inside the gap
    }

    @Test
    fun `frequency is right and the proximity sweep gets faster`() {
        fun zeroCrossings(pcm: ShortArray, from: Int, to: Int): Int =
            (from + 1 until to).count { (pcm[it - 1] < 0) != (pcm[it] < 0) }

        val listening = EarconSynth.pcm(Earcons.LISTENING)
        val hz = zeroCrossings(listening, 0, listening.size) / 2.0 / (Earcons.LISTENING.durationMs / 1000.0)
        assertTrue("$hz Hz", abs(hz - 880) < 880 * 0.05)

        val sweep = EarconSynth.pcm(Earcons.PROXIMITY)
        val first = zeroCrossings(sweep, 0, sweep.size / 2)
        val second = zeroCrossings(sweep, sweep.size / 2, sweep.size)
        assertTrue("$first < $second", second > first * 1.3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a tone must have positive frequency and duration`() {
        Tone(startHz = 0, endHz = 440, durationMs = 10)
    }
}
