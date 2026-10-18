package com.vysion.xr.platform

import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandEvent
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.HandGesture
import com.vysion.xr.core.HandGestureSource
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModePhrasebook
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.Speaker
import com.vysion.xr.core.SpeechProgress
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GestureCommandsTest {

    private class Gestures : HandGestureSource {
        val flow = MutableSharedFlow<HandGesture>(extraBufferCapacity = 16)
        override val gestures: Flow<HandGesture> = flow
    }

    private class Bus : CommandBus {
        val log = mutableListOf<String>()
        override val commands: Flow<CommandEvent> = emptyFlow()
        override val state = MutableStateFlow(ListeningState.IDLE)
        override fun startListening() { log += "start" }
        override fun stopListening() { log += "stop" }
        override fun cancelListening() { log += "cancel" }
        override fun inject(command: Command, source: CommandSource) { log += "inject:$command:$source" }
    }

    private class Voice : Speaker {
        val said = mutableListOf<String>()
        val earcons = mutableListOf<Earcon>()
        override val progress: Flow<SpeechProgress> = emptyFlow()
        override val locale = MutableStateFlow(Speaker.EN_US)
        override val isSpeaking = MutableStateFlow(false)
        override suspend fun say(text: String, interrupt: Boolean) { said += text }
        override fun stop() = Unit
        override fun earcon(kind: Earcon) { earcons += kind }
        override fun setRate(rate: Float) = Unit
        override fun setLocale(locale: Locale) = Unit
        override suspend fun repeatLast(rate: Float) = Unit
    }

    private object Words : ModePhrasebook {
        override val welcome = ""
        override val busy = ""
        override val inferenceFailed = ""
        override val doorNotFound = ""
        override val navigationUnavailable = ""
        override val holdStill = ""
        override val permissionExplanation = ""
        override val permissionDenied = ""
        override val voiceCommandsUnavailable = ""
        override fun modeAnnounced(mode: Mode) = "<mode:$mode>"
        override fun pageFocused(mode: Mode) = "<page:$mode>"
        override fun navigationCue(cue: NavigationCue): String? = null
    }

    @Test
    fun `gestures become the same commands as voice and panels`() = runTest {
        val gestures = Gestures()
        val bus = Bus()
        val voice = Voice()
        val mode = MutableStateFlow<Mode?>(null)
        val controller = GestureCommands(gestures, bus, voice, Words, mode, backgroundScope)
        controller.start()
        runCurrent()

        gestures.flow.emit(HandGesture.TALK_START)
        gestures.flow.emit(HandGesture.TALK_END)
        gestures.flow.emit(HandGesture.ACTIVATE)
        runCurrent()
        assertEquals(listOf("start", "stop", "inject:Read:PINCH"), bus.log)

        gestures.flow.emit(HandGesture.NEXT_PAGE)
        runCurrent()
        assertEquals(Mode.AUDIO_DESCRIPTION, controller.page.value)
        assertEquals(listOf("<page:AUDIO_DESCRIPTION>"), voice.said)
        assertEquals(listOf(Earcon.PING), voice.earcons)

        gestures.flow.emit(HandGesture.ACTIVATE)
        gestures.flow.emit(HandGesture.STOP)
        runCurrent()
        assertEquals(listOf("inject:Describe:PINCH", "cancel", "inject:Stop:PINCH"), bus.log.drop(3))
    }

    @Test
    fun `pages cycle Read, Describe, Find door and follow the mode used by any input`() = runTest {
        val gestures = Gestures()
        val mode = MutableStateFlow<Mode?>(null)
        val controller = GestureCommands(gestures, Bus(), Voice(), Words, mode, backgroundScope)
        controller.start()
        runCurrent()
        mode.value = Mode.NAVIGATION // the user said "find the door"
        runCurrent()
        assertEquals(Mode.NAVIGATION, controller.page.value)
        gestures.flow.emit(HandGesture.NEXT_PAGE)
        runCurrent()
        assertEquals(Mode.TEXT_READER, controller.page.value) // wraps around
        controller.stop()
        gestures.flow.emit(HandGesture.NEXT_PAGE)
        runCurrent()
        assertEquals(Mode.TEXT_READER, controller.page.value) // stopped: gestures ignored
    }
}
