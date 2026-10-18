package com.vysion.xr.platform.ui

import com.vysion.xr.platform.Haptic

/**
 * Which haptic the phone plays when the screen changes ("Phone v6 · End-to-end", the "You feel" row). Pure Kotlin,
 * unit-tested in `PhoneFeedbackTest`. Gestures play their own haptic at the moment of the touch (FlatShell); this
 * covers what the app does on its own: an answer arriving, a failure, arrival, an obstacle.
 */
object PhoneFeedback {
    /** "Faint pulse with each tick": the same 1.5 s as the THINKING earcon (ModeController). */
    const val PULSE_EVERY_MS: Long = 1_500

    /** The haptic for a change from [from] to [to], or `null` when the change has none. */
    fun onScreenChange(from: HeadsetScreen, to: HeadsetScreen): Haptic? {
        if (sameKind(from, to)) return null
        return when (to) {
            HeadsetScreen.Listening -> Haptic.LISTEN_START
            is HeadsetScreen.Answer, is HeadsetScreen.Place -> Haptic.ANSWER
            is HeadsetScreen.Arrived -> Haptic.SUCCESS
            HeadsetScreen.NoText, HeadsetScreen.Error -> Haptic.SOFT_DOUBLE
            is HeadsetScreen.Lost -> Haptic.SOFT_DOUBLE
            // Leaving Listening for Thinking is the send; the press itself already ticked.
            is HeadsetScreen.Thinking, HeadsetScreen.LookingForDoor ->
                if (from == HeadsetScreen.Listening) Haptic.LISTEN_SEND else null
            // Listening that ends with nothing to run is "Didn't catch that".
            HeadsetScreen.Ready -> if (from == HeadsetScreen.Listening) Haptic.SOFT_DOUBLE else null
            is HeadsetScreen.Guiding -> null
        }
    }

    /** "Three strong pulses" when an obstacle card appears; nothing while it stays. */
    fun onObstacle(wasShown: Boolean, isShown: Boolean): Haptic? = if (isShown && !wasShown) Haptic.WARNING else null

    /** Thinking and looking pulse with the tick so the hand knows the app is still working. */
    fun pulses(screen: HeadsetScreen): Boolean = screen is HeadsetScreen.Thinking || screen == HeadsetScreen.LookingForDoor

    private fun sameKind(a: HeadsetScreen, b: HeadsetScreen): Boolean = a::class == b::class
}
