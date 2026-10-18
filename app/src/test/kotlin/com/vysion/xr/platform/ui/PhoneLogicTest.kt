package com.vysion.xr.platform.ui

import com.vysion.xr.core.Command
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The phone shell's pure decisions (PhoneLogic.kt): swipe order, shutter face, reading frame, switcher centering. */
class PhoneLogicTest {

    @Test
    fun `swiping cycles Read, Describe, Navigate and wraps both ways`() {
        assertEquals(Mode.AUDIO_DESCRIPTION, PhoneLogic.shifted(Mode.TEXT_READER, 1))
        assertEquals(Mode.NAVIGATION, PhoneLogic.shifted(Mode.AUDIO_DESCRIPTION, 1))
        assertEquals(Mode.TEXT_READER, PhoneLogic.shifted(Mode.NAVIGATION, 1))
        assertEquals(Mode.NAVIGATION, PhoneLogic.shifted(Mode.TEXT_READER, -1))
        assertEquals(Mode.TEXT_READER, PhoneLogic.shifted(Mode.TEXT_READER, 3))
    }

    @Test
    fun `the shutter talks while listening, whatever else is going on`() {
        val face = PhoneLogic.shutter(HeadsetScreen.Thinking(Command.Read), ModeState.INFERRING, ListeningState.LISTENING)
        assertEquals(PhoneLogic.ShutterFace.TALK, face)
    }

    @Test
    fun `the shutter is busy while capturing or thinking and a stop while speaking`() {
        assertEquals(PhoneLogic.ShutterFace.BUSY, PhoneLogic.shutter(HeadsetScreen.Thinking(Command.Describe), ModeState.INFERRING, ListeningState.IDLE))
        assertEquals(PhoneLogic.ShutterFace.BUSY, PhoneLogic.shutter(HeadsetScreen.LookingForDoor, ModeState.IDLE, ListeningState.IDLE))
        assertEquals(PhoneLogic.ShutterFace.STOP, PhoneLogic.shutter(HeadsetScreen.Answer(Mode.TEXT_READER, "Exit"), ModeState.SPEAKING, ListeningState.IDLE))
        assertEquals(PhoneLogic.ShutterFace.RUN, PhoneLogic.shutter(HeadsetScreen.Answer(Mode.TEXT_READER, "Exit"), ModeState.IDLE, ListeningState.IDLE))
        assertEquals(PhoneLogic.ShutterFace.DONE, PhoneLogic.shutter(HeadsetScreen.Arrived(null), ModeState.IDLE, ListeningState.IDLE))
        assertEquals(PhoneLogic.ShutterFace.RUN, PhoneLogic.shutter(HeadsetScreen.Ready, ModeState.IDLE, ListeningState.IDLE))
    }

    @Test
    fun `the reading frame follows Read from brackets to dashed to solid, and only in Read mode`() {
        val thinking = HeadsetScreen.Thinking(Command.Read)
        assertEquals(PhoneLogic.ReadFrame.BRACKETS, PhoneLogic.readFrame(thinking, ModeState.CAPTURING, Mode.TEXT_READER))
        assertEquals(PhoneLogic.ReadFrame.DASHED, PhoneLogic.readFrame(thinking, ModeState.INFERRING, Mode.TEXT_READER))
        assertEquals(PhoneLogic.ReadFrame.SOLID, PhoneLogic.readFrame(HeadsetScreen.Answer(Mode.TEXT_READER, "Exit"), ModeState.SPEAKING, Mode.TEXT_READER))
        assertEquals(PhoneLogic.ReadFrame.NONE, PhoneLogic.readFrame(HeadsetScreen.Thinking(Command.Describe), ModeState.INFERRING, Mode.TEXT_READER))
        assertEquals(PhoneLogic.ReadFrame.NONE, PhoneLogic.readFrame(thinking, ModeState.INFERRING, Mode.AUDIO_DESCRIPTION))
        assertEquals(PhoneLogic.ReadFrame.NONE, PhoneLogic.readFrame(HeadsetScreen.Ready, ModeState.IDLE, Mode.TEXT_READER))
    }

    @Test
    fun `the switcher centers the selected label and interpolates between two`() {
        val starts = intArrayOf(0, 130, 290)
        val widths = intArrayOf(100, 130, 150)
        // Centers: 50, 195, 365. Viewport 400 → middle at 200.
        assertEquals(150, PhoneLogic.switcherShift(starts, widths, 400, 0f))
        assertEquals(5, PhoneLogic.switcherShift(starts, widths, 400, 1f))
        assertEquals(-165, PhoneLogic.switcherShift(starts, widths, 400, 2f))
        // Halfway between Read and Describe: center 122.5 → shift 77.
        assertEquals(77, PhoneLogic.switcherShift(starts, widths, 400, 0.5f))
        // Out-of-range positions clamp instead of indexing past the ends.
        assertEquals(-165, PhoneLogic.switcherShift(starts, widths, 400, 9f))
        assertEquals(0, PhoneLogic.switcherShift(IntArray(0), IntArray(0), 400, 0f))
    }
}
