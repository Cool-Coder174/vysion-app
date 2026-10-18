package com.vysion.xr.voice

import com.vysion.xr.core.Earcon
import com.vysion.xr.core.Speaker
import com.vysion.xr.core.SpeechProgress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * A scriptable [TtsPort]: records every call and lets the test fire the engine callbacks. `ready` is
 * reported from [initialize]; [bindable] decides what [setLocale] answers.
 */
class FakeTtsPort(private val ready: Boolean = true) : TtsPort {
    data class Spoken(val text: String, val id: String, val flush: Boolean)

    val spoken = mutableListOf<Spoken>()
    val rates = mutableListOf<Float>()
    var stopCalls = 0
    var shutdownCalls = 0
    var accept = true
    var bindable: (Locale) -> Locale? = { it }
    lateinit var listener: TtsPort.Listener

    override fun initialize(listener: TtsPort.Listener) {
        this.listener = listener
        listener.onInit(ready)
    }

    override fun speak(text: String, utteranceId: String, flush: Boolean): Boolean {
        spoken += Spoken(text, utteranceId, flush)
        return accept
    }

    override fun stop() {
        stopCalls++
    }

    override fun setRate(rate: Float) {
        rates += rate
    }

    override fun setLocale(requested: Locale): Locale? = bindable(requested)
    override fun shutdown() {
        shutdownCalls++
    }

    fun start(index: Int) = listener.onStart(spoken[index].id)
    fun range(index: Int, start: Int, end: Int) = listener.onRangeStart(spoken[index].id, start, end)
    fun done(index: Int) = listener.onDone(spoken[index].id)
    fun error(index: Int) = listener.onError(spoken[index].id, -3)
    fun stopped(index: Int) = listener.onStop(spoken[index].id, interrupted = true)
    val last: Int get() = spoken.lastIndex
}

class RecordingEarcons : EarconPlayer {
    val played = mutableListOf<Earcon>()
    override fun play(kind: Earcon) {
        played += kind
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidSpeakerTest {

    private fun TestScope.speaker(port: FakeTtsPort = FakeTtsPort(), earcons: EarconPlayer = NoOpEarconPlayer) =
        AndroidSpeaker(port, earcons, backgroundScope)

    @Test
    fun `say suspends until the engine reports done`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        var finished = false
        launch { speaker.say("hello"); finished = true }
        runCurrent()
        assertEquals(1, port.spoken.size)
        assertEquals("hello", port.spoken[0].text)
        assertFalse(port.spoken[0].flush)
        assertTrue(speaker.isSpeaking.value)
        assertFalse(finished)
        port.start(0)
        port.done(0)
        runCurrent()
        assertTrue(finished)
        assertFalse(speaker.isSpeaking.value)
    }

    @Test
    fun `utterances are spoken one at a time in order`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        val order = mutableListOf<String>()
        for (t in listOf("a", "b", "c")) launch { speaker.say(t); order += t }
        runCurrent()
        assertEquals(listOf("a"), port.spoken.map { it.text })
        assertEquals(2, speaker.queuedCount)
        port.done(0)
        runCurrent()
        assertEquals(listOf("a", "b"), port.spoken.map { it.text })
        port.done(1)
        runCurrent()
        port.done(2)
        runCurrent()
        assertEquals(listOf("a", "b", "c"), order)
        assertEquals(0, speaker.queuedCount)
    }

    @Test
    fun `interrupt flushes the queue and the current utterance (hazards first)`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        val outcomes = mutableListOf<String>()
        for (t in listOf("desc 1", "desc 2", "desc 3")) launch { speaker.say(t); outcomes += "$t returned" }
        runCurrent()
        assertEquals(1, port.spoken.size)
        launch { speaker.say("obstacle ahead", interrupt = true); outcomes += "hazard returned" }
        runCurrent()
        // the two queued descriptions never reach the engine; the current one was interrupted
        assertEquals(listOf("desc 1", "obstacle ahead"), port.spoken.map { it.text })
        assertTrue(port.spoken[1].flush)
        assertEquals(1, port.stopCalls)
        assertEquals(setOf("desc 1 returned", "desc 2 returned", "desc 3 returned"), outcomes.toSet())
        port.done(1)
        runCurrent()
        assertTrue(outcomes.contains("hazard returned"))
    }

    @Test
    fun `stop is immediate, silences everything and lets every waiting say return`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        var returned = 0
        // 1 current + 4 queued + 1 waiting in send()
        for (t in listOf("a", "b", "c", "d", "e", "f")) launch { speaker.say(t); returned++ }
        runCurrent()
        assertEquals(1, port.spoken.size)
        assertEquals(AndroidSpeaker.QUEUE_CAPACITY, speaker.queuedCount)
        speaker.stop() // non-suspending
        assertFalse(speaker.isSpeaking.value)
        assertEquals(1, port.stopCalls)
        runCurrent()
        assertEquals(6, returned)
        assertEquals(1, port.spoken.size) // nothing else reached the engine
        assertEquals(0, speaker.queuedCount)
        // the engine's own late onStop for the flushed id is ignored, and new speech works again
        port.stopped(0)
        var again = false
        launch { speaker.say("after"); again = true }
        runCurrent()
        assertEquals("after", port.spoken[1].text)
        port.done(1)
        runCurrent()
        assertTrue(again)
    }

    @Test
    fun `progress mirrors onStart onRangeStart and done for the transcript panel`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        val events = mutableListOf<SpeechProgress>()
        val collector: Job = launch { speaker.progress.toList(events) }
        runCurrent()
        launch { speaker.say("hello world") }
        runCurrent()
        port.start(0)
        port.range(0, 0, 5)
        port.range(0, 6, 11)
        port.done(0)
        runCurrent()
        collector.cancel()
        assertEquals(listOf(0 to 0, 0 to 5, 6 to 11, 11 to 11), events.map { it.charStart to it.charEnd })
        assertEquals(listOf(false, false, false, true), events.map { it.done })
        assertTrue(events.all { it.text == "hello world" && it.utteranceId == port.spoken[0].id })
    }

    @Test
    fun `engine error and rejected speak both complete say promptly`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        var returned = 0
        launch { speaker.say("x"); returned++ }
        runCurrent()
        port.error(0)
        runCurrent()
        assertEquals(1, returned)
        port.accept = false
        launch { speaker.say("y"); returned++ }
        runCurrent()
        assertEquals(2, returned)
        assertFalse(speaker.isSpeaking.value)
    }

    @Test
    fun `watchdog fails an utterance whose callbacks never arrive`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        var returned = false
        launch { speaker.say("silent"); returned = true }
        runCurrent()
        advanceTimeBy(AndroidSpeaker.utteranceTimeoutMs("silent") + 1)
        runCurrent()
        assertTrue(returned)
        assertEquals(1, port.stopCalls)
        assertFalse(speaker.isSpeaking.value)
    }

    @Test
    fun `repeatLast replays the last utterance at the given rate and restores it (FR-T3)`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        launch { speaker.say("the menu says soup") }
        runCurrent()
        port.done(0)
        runCurrent()
        launch { speaker.repeatLast(Speaker.SLOWER_RATE) }
        runCurrent()
        assertEquals("the menu says soup", port.spoken[1].text)
        assertTrue(port.spoken[1].flush)
        assertEquals(Speaker.SLOWER_RATE, port.rates.last())
        port.done(1)
        runCurrent()
        assertEquals(1.0f, port.rates.last())
        // a cue spoken by repeatLast itself is not remembered as "last"
        val empty = FakeTtsPort()
        val fresh = speaker(empty)
        launch { fresh.repeatLast() }
        runCurrent()
        assertEquals(Cues.nothingToRepeat(Speaker.EN_US), empty.spoken[0].text)
        empty.done(0)
        runCurrent()
        launch { fresh.repeatLast() }
        runCurrent()
        assertEquals(Cues.nothingToRepeat(Speaker.EN_US), empty.spoken[1].text)
    }

    @Test
    fun `setLocale binds the offline voice or falls back with a spoken notice (A-8)`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        assertEquals(Speaker.EN_US, speaker.locale.value)
        // es-MX missing, es-ES present; other languages bind as asked (no engine answers en-US with es-ES)
        port.bindable = { if (it.language == "es") Locale.forLanguageTag("es-ES") else it }
        speaker.setLocale(Speaker.ES_MX)
        assertEquals("es-ES", speaker.locale.value.toLanguageTag())
        speaker.setLocale(Speaker.EN_US)
        assertEquals(Speaker.EN_US, speaker.locale.value)
        port.bindable = { null } // no Spanish voice at all
        speaker.setLocale(Speaker.ES_MX)
        runCurrent()
        assertEquals(Speaker.EN_US, speaker.locale.value)
        assertEquals(Cues.spanishVoiceUnavailable(Speaker.EN_US), port.spoken.last().text)
    }

    @Test
    fun `a Spanish request remembered before init falls back to en-US with the notice at init (A-8)`() = runTest {
        val port = FakeTtsPort(ready = false)
        val speaker = speaker(port)
        port.bindable = { if (it.language == "es") null else it } // no offline Spanish voice
        speaker.setLocale(Speaker.ES_MX)
        assertEquals(Speaker.ES_MX, speaker.locale.value) // remembered, not bound yet
        port.listener.onInit(true)
        runCurrent()
        assertEquals(Speaker.EN_US, speaker.locale.value)
        assertEquals(listOf(Cues.spanishVoiceUnavailable(Speaker.EN_US)), port.spoken.map { it.text })
        port.done(0)
        runCurrent()
        // a failed non-Spanish request keeps the voice in use and never says the Spanish notice
        port.bindable = { null }
        speaker.setLocale(Locale.forLanguageTag("en-GB"))
        runCurrent()
        assertEquals(Speaker.EN_US, speaker.locale.value)
        assertEquals(1, port.spoken.size)
    }

    @Test
    fun `release silences, shuts the port down exactly once and makes later calls no-ops`() = runTest {
        val port = FakeTtsPort()
        val speaker = speaker(port)
        val contract: Speaker = speaker // AppGraph.release() calls it through the contract (decision 3)
        var returned = 0
        for (t in listOf("a", "b")) launch { speaker.say(t); returned++ }
        runCurrent()
        assertTrue(speaker.isSpeaking.value)
        assertEquals(1, speaker.queuedCount)
        contract.release()
        assertFalse(speaker.isSpeaking.value)
        assertEquals(1, port.stopCalls)
        assertEquals(1, port.shutdownCalls)
        runCurrent()
        assertEquals(2, returned) // the current and the queued say both returned
        assertEquals(0, speaker.queuedCount)
        contract.release()
        contract.stop()
        contract.setRate(Speaker.SLOWER_RATE)
        var late = false
        launch { contract.say("late"); late = true }
        runCurrent()
        assertTrue(late) // returns at once instead of throwing on the closed queue
        assertEquals(1, port.shutdownCalls)
        assertEquals(1, port.stopCalls)
        assertEquals(listOf("a"), port.spoken.map { it.text })
        assertEquals(listOf(1.0f), port.rates) // only the onInit rate; nothing reached the shut-down engine
        port.listener.onInit(true) // a late init does not wake it either
        assertEquals(listOf(1.0f), port.rates)
    }

    @Test
    fun `before the engine is ready the locale is remembered and applied on init`() = runTest {
        val port = FakeTtsPort(ready = false)
        val speaker = speaker(port)
        port.bindable = { Speaker.ES_MX }
        speaker.setLocale(Speaker.ES_MX)
        assertEquals(Speaker.ES_MX, speaker.locale.value)
        assertTrue(port.rates.isEmpty())
        speaker.setRate(Speaker.SLOWER_RATE)
        port.listener.onInit(true)
        assertEquals(listOf(Speaker.SLOWER_RATE), port.rates)
        assertEquals(Speaker.ES_MX, speaker.locale.value)
    }

    @Test
    fun `earcons delegate and blank text is ignored`() = runTest {
        val port = FakeTtsPort()
        val earcons = RecordingEarcons()
        val speaker = speaker(port, earcons)
        speaker.earcon(Earcon.PROXIMITY)
        speaker.earcon(Earcon.DONE)
        assertEquals(listOf(Earcon.PROXIMITY, Earcon.DONE), earcons.played)
        var returned = false
        launch { speaker.say("   "); returned = true }
        runCurrent()
        assertTrue(returned)
        assertTrue(port.spoken.isEmpty())
        assertNull(speaker.isSpeaking.value.takeIf { it })
    }
}

class VoiceSelectionTest {
    private fun voice(tag: String, network: Boolean = false, installed: Boolean = true, quality: Int = 300) =
        VoiceCandidate("v-$tag-$quality", Locale.forLanguageTag(tag), network, installed, quality)

    @Test
    fun `prefers es-MX then es-ES then any offline Spanish, never network or uninstalled`() {
        val voices = listOf(
            voice("es-MX", network = true, quality = 500),
            voice("es-MX", installed = false, quality = 500),
            voice("es-ES", quality = 400),
            voice("es-US", quality = 500),
            voice("en-US", quality = 500),
        )
        assertEquals("v-es-ES-400", VoiceSelection.pick(voices, Speaker.ES_MX)?.name)
        assertEquals("v-es-MX-300", VoiceSelection.pick(voices + voice("es-MX"), Speaker.ES_MX)?.name)
        val noSpain = voices.filter { it.locale.country != "ES" }
        assertEquals("v-es-US-500", VoiceSelection.pick(noSpain, Speaker.ES_MX)?.name)
        assertEquals("v-en-US-500", VoiceSelection.pick(voices, Speaker.EN_US)?.name)
        assertNull(VoiceSelection.pick(voices.filter { it.locale.language == "en" }, Speaker.ES_MX))
    }

    @Test
    fun `engines that report ISO-3 locales still match (Samsung SMT on the Galaxy XR)`() {
        val samsung = listOf(
            VoiceCandidate("es-ES-default", Locale("spa", "ESP", "default"), false, true, 300),
            VoiceCandidate("en-GB-SMTl02", Locale("eng", "GBR", "l02"), false, true, 400),
            VoiceCandidate("en-US-SMTl01", Locale("eng", "USA", "l01"), false, true, 300),
        )
        assertEquals("es-ES-default", VoiceSelection.pick(samsung, Speaker.ES_MX)?.name)
        assertEquals("en-US-SMTl01", VoiceSelection.pick(samsung, Speaker.EN_US)?.name)
        assertEquals("es-ES", VoiceSelection.normalize(Locale("spa", "ESP", "default")).toLanguageTag())
        assertEquals(Speaker.EN_US, VoiceSelection.normalize(Speaker.EN_US))
    }

    @Test
    fun `quality breaks ties within the same country`() {
        val voices = listOf(voice("es-MX", quality = 200), voice("es-MX", quality = 400))
        assertEquals("v-es-MX-400", VoiceSelection.pick(voices, Speaker.ES_MX)?.name)
        val spanish = VoiceSelection.preferredLocales(Speaker.ES_MX).map { it.toLanguageTag() }
        assertEquals(listOf("es-MX", "es-ES"), spanish)
        assertEquals(listOf("en-US"), VoiceSelection.preferredLocales(Speaker.EN_US).map { it.toLanguageTag() })
    }
}
