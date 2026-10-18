package com.vysion.xr.voice

import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandEvent
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Speaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scriptable [RecognizerPort]: records calls and hands the test the listener to fire results. */
class FakeRecognizerPort(override val onDeviceAvailable: Boolean = true) : RecognizerPort {
    val sessions = mutableListOf<String>()
    var stopCalls = 0
    var cancelCalls = 0
    var destroyCalls = 0
    var listener: RecognizerPort.Listener? = null

    override fun startListening(languageTag: String, listener: RecognizerPort.Listener) {
        sessions += languageTag
        this.listener = listener
    }

    override fun stopListening() {
        stopCalls++
    }

    override fun cancel() {
        cancelCalls++
    }

    override fun destroy() {
        destroyCalls++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechCommandBusTest {

    private fun TestScope.bus(
        port: FakeRecognizerPort = FakeRecognizerPort(),
        earcons: EarconPlayer = NoOpEarconPlayer,
    ) = SpeechCommandBus(port, earcons, backgroundScope)

    @Test
    fun `inject emits immediately with the given source and a monotonic timestamp`() = runTest {
        val bus = bus()
        val received = mutableListOf<CommandEvent>()
        val collector = launch { bus.commands.toList(received) }
        runCurrent()
        val before = System.nanoTime()
        bus.inject(Command.Read)
        bus.inject(Command.Stop, CommandSource.TEST)
        runCurrent()
        collector.cancel()
        assertEquals(listOf(Command.Read, Command.Stop), received.map { it.command })
        assertEquals(listOf(CommandSource.PANEL, CommandSource.TEST), received.map { it.source })
        assertTrue(received.all { it.recognizedAtNanos >= before })
    }

    @Test
    fun `a busy collector keeps the newest 8 commands (H-33 DROP_OLDEST)`() = runTest {
        val bus = bus()
        val received = mutableListOf<CommandEvent>()
        val collector = launch { bus.commands.toList(received) }
        runCurrent() // subscribed, now waiting
        repeat(10) { i -> bus.inject(if (i == 9) Command.Stop else Command.FollowUp("q$i")) }
        runCurrent()
        collector.cancel()
        assertTrue("size ${received.size}", received.size <= SpeechCommandBus.COMMAND_BUFFER)
        assertEquals(Command.Stop, received.last().command) // the newest is never the one dropped
    }

    @Test
    fun `voice results go through the grammar and come out as VOICE events`() = runTest {
        val port = FakeRecognizerPort()
        val earcons = RecordingEarcons()
        val bus = bus(port, earcons)
        val received = mutableListOf<CommandEvent>()
        val collector = launch { bus.commands.toList(received) }
        runCurrent()
        assertEquals(ListeningState.IDLE, bus.state.value)
        bus.startListening()
        assertEquals(ListeningState.LISTENING, bus.state.value)
        assertEquals(listOf("en-US"), port.sessions)
        bus.startListening() // idempotent
        assertEquals(1, port.sessions.size)
        port.listener!!.onReadyForSpeech()
        assertEquals(listOf(Earcon.LISTENING), earcons.played)
        port.listener!!.onResults(listOf("Vysion, read this", "vision reed this"))
        runCurrent()
        collector.cancel()
        assertEquals(ListeningState.IDLE, bus.state.value)
        assertEquals(1, received.size)
        assertEquals(Command.Read, received[0].command)
        assertEquals(CommandSource.VOICE, received[0].source)
        assertTrue(received[0].recognizedAtNanos > 0)
    }

    @Test
    fun `second hypothesis is used when the first is not a command, none plays the error earcon`() = runTest {
        val port = FakeRecognizerPort()
        val earcons = RecordingEarcons()
        val bus = bus(port, earcons)
        val received = mutableListOf<CommandEvent>()
        val collector = launch { bus.commands.toList(received) }
        runCurrent()
        bus.startListening()
        port.listener!!.onResults(listOf("red this", "read this"))
        runCurrent()
        assertEquals(Command.Read, received.single().command)
        bus.startListening()
        port.listener!!.onResults(listOf("hmm", "umm"))
        runCurrent()
        collector.cancel()
        assertEquals(1, received.size)
        assertEquals(listOf(Earcon.ERROR), earcons.played)
        assertEquals(ListeningState.IDLE, bus.state.value)
    }

    @Test
    fun `the 8 s cap cancels a session that never ends`() = runTest {
        val port = FakeRecognizerPort()
        val bus = bus(port)
        bus.startListening()
        advanceTimeBy(SpeechCommandBus.LISTENING_CAP_MS - 1)
        runCurrent()
        assertEquals(ListeningState.LISTENING, bus.state.value)
        assertEquals(0, port.cancelCalls)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(ListeningState.IDLE, bus.state.value)
        assertEquals(1, port.cancelCalls)
        // a session that ended normally is not cancelled later by a stale cap
        bus.startListening()
        port.listener!!.onResults(listOf("stop"))
        advanceTimeBy(SpeechCommandBus.LISTENING_CAP_MS + 1)
        runCurrent()
        assertEquals(1, port.cancelCalls)
    }

    @Test
    fun `end of speech from the port moves to PROCESSING and the cap no longer cancels a slow transcription`() = runTest {
        val port = FakeRecognizerPort()
        val bus = bus(port)
        val received = mutableListOf<CommandEvent>()
        val collector = launch { bus.commands.toList(received) }
        runCurrent()
        bus.startListening()
        advanceTimeBy(SpeechCommandBus.LISTENING_CAP_MS - 1_000)
        port.listener!!.onEndOfSpeech() // the VAD ended the clip; Whisper is still working
        assertEquals(ListeningState.PROCESSING, bus.state.value)
        advanceTimeBy(SpeechCommandBus.LISTENING_CAP_MS)
        runCurrent()
        assertEquals(0, port.cancelCalls)
        assertEquals(ListeningState.PROCESSING, bus.state.value)
        bus.cancelListening() // Cancel only applies while LISTENING
        assertEquals(0, port.cancelCalls)
        port.listener!!.onResults(listOf("read this"))
        runCurrent()
        collector.cancel()
        assertEquals(listOf(Command.Read), received.map { it.command })
        assertEquals(ListeningState.IDLE, bus.state.value)
        port.listener!!.onEndOfSpeech() // late or duplicate: ignored outside LISTENING
        assertEquals(ListeningState.IDLE, bus.state.value)
    }

    @Test
    fun `stopListening moves to PROCESSING and results close the session`() = runTest {
        val port = FakeRecognizerPort()
        val bus = bus(port)
        bus.stopListening() // nothing to stop
        assertEquals(0, port.stopCalls)
        bus.startListening()
        bus.stopListening()
        assertEquals(ListeningState.PROCESSING, bus.state.value)
        assertEquals(1, port.stopCalls)
        port.listener!!.onError(RecognizerPort.ERROR_NO_MATCH)
        assertEquals(ListeningState.IDLE, bus.state.value)
    }

    @Test
    fun `no on-device recognition means UNAVAILABLE and startListening is a no-op (A-7, RK-4)`() = runTest {
        val port = FakeRecognizerPort(onDeviceAvailable = false)
        val bus = bus(port)
        assertEquals(ListeningState.UNAVAILABLE, bus.state.value)
        bus.startListening()
        assertEquals(ListeningState.UNAVAILABLE, bus.state.value)
        assertTrue(port.sessions.isEmpty())
        // inject still works: the pinch / panel path
        val received = mutableListOf<CommandEvent>()
        val collector = launch { bus.commands.toList(received) }
        runCurrent()
        bus.inject(Command.Describe, CommandSource.PINCH)
        runCurrent()
        collector.cancel()
        assertEquals(CommandSource.PINCH, received.single().source)
    }

    @Test
    fun `language errors demote to UNAVAILABLE, other errors return to IDLE`() = runTest {
        val port = FakeRecognizerPort()
        val earcons = RecordingEarcons()
        val bus = bus(port, earcons)
        bus.startListening()
        port.listener!!.onError(RecognizerPort.ERROR_SPEECH_TIMEOUT)
        assertEquals(ListeningState.IDLE, bus.state.value)
        assertTrue(earcons.played.isEmpty()) // a timeout is not the user's fault
        bus.startListening()
        port.listener!!.onError(RecognizerPort.ERROR_LANGUAGE_UNAVAILABLE)
        assertEquals(ListeningState.UNAVAILABLE, bus.state.value)
        assertEquals(listOf(Earcon.ERROR), earcons.played)
    }

    @Test
    fun `setLocale changes the recognition language and release destroys the recognizer`() = runTest {
        val port = FakeRecognizerPort()
        val bus = bus(port)
        bus.setLocale(Speaker.ES_MX)
        bus.startListening()
        assertEquals(listOf("es-MX"), port.sessions)
        port.listener!!.onResults(listOf("lee esto"))
        assertNotNull(port.listener)
        bus.release()
        assertEquals(1, port.destroyCalls)
        bus.startListening() // a destroyed recognizer is never started again
        assertEquals(listOf("es-MX"), port.sessions)
    }

    @Test
    fun `setLocale through the contract changes the language of the next session only (FR-G6)`() = runTest {
        val port = FakeRecognizerPort()
        val bus: CommandBus = bus(port) // ModeController sees only the contract (decision 3)
        bus.startListening()
        bus.setLocale(Speaker.ES_MX) // the session already open keeps en-US
        port.listener!!.onResults(listOf("stop"))
        bus.startListening()
        port.listener!!.onResults(listOf("lee esto"))
        bus.setLocale(Speaker.EN_US)
        bus.startListening()
        assertEquals(listOf("en-US", "es-MX", "en-US"), port.sessions)
        // release through the contract: destroys once, ends the open session, and its cap never fires
        bus.release()
        bus.release()
        assertEquals(1, port.destroyCalls)
        assertEquals(ListeningState.IDLE, bus.state.value)
        advanceTimeBy(SpeechCommandBus.LISTENING_CAP_MS + 1)
        runCurrent()
        assertEquals(0, port.cancelCalls)
        bus.startListening()
        assertEquals(3, port.sessions.size)
    }
}
