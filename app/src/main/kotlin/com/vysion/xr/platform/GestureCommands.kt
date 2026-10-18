package com.vysion.xr.platform

import android.util.Log
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.HandGesture
import com.vysion.xr.core.HandGestureSource
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModePhrasebook
import com.vysion.xr.core.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Eyes-free control for blind users: hand gestures ([HandGestureSource]) become the same commands the voice and the
 * panels send, so nothing needs gaze. Pages are Read → Describe → Find door; [page] is the current one (the rail
 * shows it) and follows the mode last used by any input.
 *
 * - TALK_START / TALK_END: open and close the microphone (a push-to-talk the system cannot steal).
 * - ACTIVATE: run the current page.
 * - NEXT_PAGE: move on, ping, and say the page's name and how to run it ([ModePhrasebook.pageFocused]).
 * - STOP: close the microphone and stop everything (`Command.Stop`, which `ModeController` handles within 300 ms).
 */
class GestureCommands(
    private val gestures: HandGestureSource,
    private val commands: CommandBus,
    private val speaker: Speaker,
    private val phrasebook: ModePhrasebook,
    private val currentMode: StateFlow<Mode?>,
    private val scope: CoroutineScope,
) {
    private val _page = MutableStateFlow(Mode.TEXT_READER)

    /** The page a middle-finger tap runs. */
    val page: StateFlow<Mode> = _page.asStateFlow()

    private var job: Job? = null

    /** Idempotent; `AppGraph.start`. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            launch { currentMode.filterNotNull().collect { _page.value = it } }
            gestures.gestures.collect { handle(it) }
        }
    }

    /** Reversible; `AppGraph.stop`. */
    fun stop() {
        job?.cancel()
        job = null
    }

    internal fun handle(gesture: HandGesture) {
        Log.i(TAG, "gesture $gesture") // threshold tuning evidence; no hand data, just the event
        when (gesture) {
            HandGesture.TALK_START -> commands.startListening()
            HandGesture.TALK_END -> commands.stopListening()
            HandGesture.ACTIVATE -> commands.inject(commandFor(_page.value), CommandSource.PINCH)
            HandGesture.NEXT_PAGE -> {
                val next = PAGES[(PAGES.indexOf(_page.value) + 1) % PAGES.size]
                _page.value = next
                speaker.earcon(Earcon.PING)
                scope.launch { speaker.say(phrasebook.pageFocused(next), interrupt = true) }
            }
            HandGesture.STOP -> {
                commands.cancelListening()
                commands.inject(Command.Stop, CommandSource.PINCH)
            }
        }
    }

    companion object {
        private const val TAG = "vysion.gesture"

        val PAGES: List<Mode> = listOf(Mode.TEXT_READER, Mode.AUDIO_DESCRIPTION, Mode.NAVIGATION)

        fun commandFor(page: Mode): Command = when (page) {
            Mode.TEXT_READER -> Command.Read
            Mode.AUDIO_DESCRIPTION -> Command.Describe
            Mode.NAVIGATION -> Command.FindDoor
        }
    }
}
