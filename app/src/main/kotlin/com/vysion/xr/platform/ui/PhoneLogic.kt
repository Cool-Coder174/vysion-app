package com.vysion.xr.platform.ui

import com.vysion.xr.core.Command
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState

/**
 * The pure decisions behind the phone shell (designs "Phone v6 · End-to-end"): which mode a swipe lands on, what the
 * shutter shows, and which reading frame is drawn over the camera. Kept out of the composables so they are JVM-tested
 * (`PhoneLogicTest`); `FlatShell` only draws what these return.
 */
internal object PhoneLogic {

    /** The mode [by] steps from [mode], wrapping at both ends: the switcher is a ring, like a camera's picker. */
    fun shifted(mode: Mode, by: Int): Mode {
        val modes = Mode.entries
        val size = modes.size
        return modes[(((modes.indexOf(mode) + by) % size) + size) % size]
    }

    /** What the shutter button looks like and does. */
    enum class ShutterFace {
        /** White disc: tap runs the current mode, hold talks. */
        RUN,

        /** Blue disc with the microphone: the mic is open; letting go sends. */
        TALK,

        /** Purple busy ring around a white square: capturing or thinking; tap cancels. */
        BUSY,

        /** Red square: Vysion is speaking; tap stops it. */
        STOP,

        /** Green disc with a check: arrived at the door. */
        DONE,
    }

    fun shutter(screen: HeadsetScreen, state: ModeState, listening: ListeningState): ShutterFace = when {
        listening == ListeningState.LISTENING -> ShutterFace.TALK
        screen is HeadsetScreen.Thinking || screen == HeadsetScreen.LookingForDoor -> ShutterFace.BUSY
        state != ModeState.IDLE -> ShutterFace.STOP
        screen is HeadsetScreen.Arrived -> ShutterFace.DONE
        else -> ShutterFace.RUN
    }

    /** The reading frame over the camera while Read works (designs R2 Capturing, R3 Thinking, R4 Reading). */
    enum class ReadFrame {
        NONE,

        /** Yellow corner brackets: the still is being taken; hold the phone still. */
        BRACKETS,

        /** Dashed purple outline: the text inside is being read. */
        DASHED,

        /** Solid yellow outline with the "Reading this sign" callout: the answer is being spoken. */
        SOLID,
    }

    fun readFrame(screen: HeadsetScreen, state: ModeState, selected: Mode): ReadFrame {
        if (selected != Mode.TEXT_READER) return ReadFrame.NONE
        return when {
            screen is HeadsetScreen.Thinking && (screen.asked == Command.Read || screen.asked == Command.Next) ->
                if (state == ModeState.CAPTURING) ReadFrame.BRACKETS else ReadFrame.DASHED
            screen is HeadsetScreen.Answer && screen.mode == Mode.TEXT_READER -> ReadFrame.SOLID
            else -> ReadFrame.NONE
        }
    }

    /**
     * Where the selected label of the mode switcher should sit: the switcher scrolls so the current mode is centered
     * under the shutter. Returns the horizontal shift to apply to a row whose labels start at [starts] and are
     * [widths] wide, given the row is [viewportWidth] wide and the selection sits at [position] (fractional while it
     * animates between two labels).
     */
    fun switcherShift(starts: IntArray, widths: IntArray, viewportWidth: Int, position: Float): Int {
        if (starts.isEmpty()) return 0
        val last = starts.lastIndex
        val p = position.coerceIn(0f, last.toFloat())
        val lo = p.toInt().coerceIn(0, last)
        val hi = (lo + 1).coerceAtMost(last)
        val frac = p - lo
        val centerLo = starts[lo] + widths[lo] / 2f
        val centerHi = starts[hi] + widths[hi] / 2f
        val center = centerLo + (centerHi - centerLo) * frac
        return (viewportWidth / 2f - center).toInt()
    }
}
