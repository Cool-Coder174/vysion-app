package com.vysion.xr.platform.ui

import com.vysion.xr.core.Command
import com.vysion.xr.core.Mode
import com.vysion.xr.platform.Haptic
import com.vysion.xr.platform.HapticPatterns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec-as-tests for "Phone v6 · End-to-end", the "You feel" row of each note. */
class PhoneFeedbackTest {
    private val answer = HeadsetScreen.Answer(Mode.TEXT_READER, "Meeting rooms are on floor 2.")

    @Test
    fun `listening starts with a medium thump`() {
        assertEquals(Haptic.LISTEN_START, PhoneFeedback.onScreenChange(HeadsetScreen.Ready, HeadsetScreen.Listening))
    }

    @Test
    fun `letting go to send is a light tap`() {
        assertEquals(Haptic.LISTEN_SEND, PhoneFeedback.onScreenChange(HeadsetScreen.Listening, HeadsetScreen.Thinking(Command.Read)))
    }

    @Test
    fun `listening that ends with nothing is two soft taps`() {
        assertEquals(Haptic.SOFT_DOUBLE, PhoneFeedback.onScreenChange(HeadsetScreen.Listening, HeadsetScreen.Ready))
        assertNull(PhoneFeedback.onScreenChange(answer, HeadsetScreen.Ready))
    }

    @Test
    fun `a tap into thinking does not buzz again`() {
        assertNull(PhoneFeedback.onScreenChange(HeadsetScreen.Ready, HeadsetScreen.Thinking(Command.Read)))
    }

    @Test
    fun `an answer starting is a success buzz, once`() {
        assertEquals(Haptic.ANSWER, PhoneFeedback.onScreenChange(HeadsetScreen.Thinking(Command.Read), answer))
        assertNull(PhoneFeedback.onScreenChange(answer, answer.copy(text = "Also on 2: California Lawyers for the Arts.")))
    }

    @Test
    fun `no text and errors are two soft taps`() {
        assertEquals(Haptic.SOFT_DOUBLE, PhoneFeedback.onScreenChange(HeadsetScreen.Thinking(Command.Read), HeadsetScreen.NoText))
        assertEquals(Haptic.SOFT_DOUBLE, PhoneFeedback.onScreenChange(HeadsetScreen.Thinking(null), HeadsetScreen.Error))
    }

    @Test
    fun `arriving is the success pattern`() {
        assertEquals(
            Haptic.SUCCESS,
            PhoneFeedback.onScreenChange(HeadsetScreen.Guiding(1, 3f, inView = true), HeadsetScreen.Arrived(null)),
        )
    }

    @Test
    fun `bearing updates stay quiet`() {
        assertNull(PhoneFeedback.onScreenChange(HeadsetScreen.Guiding(1, 3f, true), HeadsetScreen.Guiding(12, 2.5f, true)))
    }

    @Test
    fun `an obstacle buzzes when it appears, not while it stays`() {
        assertEquals(Haptic.WARNING, PhoneFeedback.onObstacle(wasShown = false, isShown = true))
        assertNull(PhoneFeedback.onObstacle(wasShown = true, isShown = true))
        assertNull(PhoneFeedback.onObstacle(wasShown = true, isShown = false))
    }

    @Test
    fun `thinking and looking pulse, answers do not`() {
        assertTrue(PhoneFeedback.pulses(HeadsetScreen.Thinking(Command.Describe)))
        assertTrue(PhoneFeedback.pulses(HeadsetScreen.LookingForDoor))
        assertFalse(PhoneFeedback.pulses(answer))
    }

    @Test
    fun `double patterns are two pulses and warning is three`() {
        fun pulses(kind: Haptic) = HapticPatterns.wave(kind).amplitudes.count { it > 0 }
        assertEquals(2, pulses(Haptic.STOP))
        assertEquals(2, pulses(Haptic.SUCCESS))
        assertEquals(2, pulses(Haptic.SOFT_DOUBLE))
        assertEquals(3, pulses(Haptic.WARNING))
        Haptic.entries.forEach { kind ->
            val wave = HapticPatterns.wave(kind)
            assertEquals(wave.timings.size, wave.amplitudes.size)
            assertEquals(0L, wave.timings.first()) // starts at once
            assertTrue("$kind too long", wave.totalMs <= 500)
        }
    }
}
