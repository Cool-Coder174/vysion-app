package com.vysion.xr.voice

import com.vysion.xr.core.Command
import com.vysion.xr.voice.SpeechEndpointer.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the Whisper path (ADR-0011): end of speech, the decoding prompt, token ids to text, cleanup. */
class WhisperPartsTest {

    private val frameMs = 32

    /** Feeds [probabilities] one frame each and returns every decision. */
    private fun SpeechEndpointer.feedAll(vararg probabilities: Float): List<Decision> = probabilities.map { feed(it) }

    private fun frames(ms: Int) = ms / frameMs

    @Test
    fun `the earcon's frames never start speech`() {
        val e = SpeechEndpointer(frameMs)
        repeat(frames(SpeechEndpointer.IGNORE_FIRST_MS)) { e.feed(1f) }
        assertFalse(e.heardSpeech)
    }

    @Test
    fun `speech starts after MIN_SPEECH_MS above the start threshold and ends after the trailing silence`() {
        val e = SpeechEndpointer(frameMs)
        repeat(frames(SpeechEndpointer.IGNORE_FIRST_MS)) { e.feed(0f) }
        repeat(frames(SpeechEndpointer.MIN_SPEECH_MS)) { assertEquals(Decision.CONTINUE, e.feed(0.9f)) }
        assertTrue(e.heardSpeech)
        repeat(10) { e.feed(0.9f) }
        val speechEnd = e.elapsedMs
        val silence = List(frames(SpeechEndpointer.TRAILING_SILENCE_MS)) { e.feed(0.05f) }
        assertEquals(Decision.END_OF_SPEECH, silence.last())
        assertTrue(silence.dropLast(1).all { it == Decision.CONTINUE })
        assertEquals(speechEnd, e.lastSpeechEndMs)
        assertEquals(speechEnd + SpeechEndpointer.TRAILING_SILENCE_MS, e.elapsedMs)
    }

    @Test
    fun `a short click does not start speech`() {
        val e = SpeechEndpointer(frameMs)
        repeat(frames(SpeechEndpointer.IGNORE_FIRST_MS)) { e.feed(0f) }
        e.feedAll(0.9f, 0.9f, 0.1f, 0.9f, 0.1f)
        assertFalse(e.heardSpeech)
    }

    @Test
    fun `between the thresholds speech goes on (hysteresis), so a soft syllable does not end the clip`() {
        val e = SpeechEndpointer(frameMs)
        repeat(frames(SpeechEndpointer.IGNORE_FIRST_MS)) { e.feed(0f) }
        repeat(frames(SpeechEndpointer.MIN_SPEECH_MS)) { e.feed(0.9f) }
        val soft = List(frames(SpeechEndpointer.TRAILING_SILENCE_MS) * 2) { e.feed(0.4f) }
        assertTrue(soft.all { it == Decision.CONTINUE })
        assertEquals(e.elapsedMs, e.lastSpeechEndMs)
    }

    @Test
    fun `a pause shorter than the trailing silence keeps the clip open`() {
        val e = SpeechEndpointer(frameMs)
        repeat(frames(SpeechEndpointer.IGNORE_FIRST_MS)) { e.feed(0f) }
        repeat(frames(SpeechEndpointer.MIN_SPEECH_MS)) { e.feed(0.9f) }
        val pause = List(frames(SpeechEndpointer.TRAILING_SILENCE_MS) - 1) { e.feed(0.1f) }
        assertTrue(pause.all { it == Decision.CONTINUE })
        e.feed(0.9f) // "read ... this"
        assertEquals(Decision.CONTINUE, e.feed(0.1f))
    }

    @Test
    fun `no speech within the timeout ends the clip unheard`() {
        val e = SpeechEndpointer(frameMs)
        val decisions = List(frames(SpeechEndpointer.NO_SPEECH_TIMEOUT_MS) + 1) { e.feed(0.2f) }
        assertEquals(Decision.NO_SPEECH, decisions.first { it != Decision.CONTINUE })
        assertFalse(e.heardSpeech)
        assertEquals(-1, e.lastSpeechEndMs)
    }

    private val vocabSize = 51_865

    /** A scripted decoder: records what was fed and answers with logits that favor one token each call. */
    private class ScriptedDecoder(private val vocabSize: Int, private val favorites: List<Pair<Long, Float>>) {
        val fed = mutableListOf<Pair<Long, Long>>()
        private val extra = mutableMapOf<Int, Map<Long, Float>>()

        fun also(call: Int, scores: Map<Long, Float>) = apply { extra[call] = scores }

        fun step(token: Long, position: Long): FloatArray {
            val call = fed.size
            fed += token to position
            val logits = FloatArray(vocabSize)
            favorites.getOrNull(call)?.let { (id, score) -> logits[id.toInt()] = score }
            extra[call]?.forEach { (id, score) -> logits[id.toInt()] = score }
            return logits
        }
    }

    @Test
    fun `the prompt is fixed, the language is English or Spanish only, and decoding stops at end-of-text`() {
        val d = ScriptedDecoder(
            vocabSize,
            listOf(
                WhisperDecoding.SPANISH to 5f, // call 0: language logits
                0L to 0f, 0L to 0f, // calls 1-2: ignored (forced prompt)
                1_000L to 9f, // call 3: first text token
                2_000L to 9f,
                WhisperDecoding.END_OF_TEXT to 9f,
            ),
        ).also(0, mapOf(50_260L to 99f)) // Chinese scores highest but is not a choice
        val result = WhisperDecoding.greedy(28, step = d::step)
        assertEquals("es", result.language)
        assertEquals(listOf(1_000L, 2_000L), result.text)
        assertEquals(
            listOf(
                WhisperDecoding.START_OF_TRANSCRIPT to 0L, WhisperDecoding.SPANISH to 1L,
                WhisperDecoding.TRANSCRIBE to 2L, WhisperDecoding.NO_TIMESTAMPS to 3L, 1_000L to 4L, 2_000L to 5L,
            ),
            d.fed,
        )
        assertEquals(6, result.steps)
    }

    @Test
    fun `the app's language wins a close call, a clear call goes to the language heard`() {
        fun languageFor(spanishMinusEnglish: Float, preferSpanish: Boolean): String {
            val d = ScriptedDecoder(vocabSize, listOf(0L to 0f, 0L to 0f, 0L to 0f, WhisperDecoding.END_OF_TEXT to 1f))
                .also(0, mapOf(WhisperDecoding.ENGLISH to 10f, WhisperDecoding.SPANISH to 10f + spanishMinusEnglish))
            val result = WhisperDecoding.greedy(28, preferSpanish, bias = 2f, step = d::step)
            assertEquals(spanishMinusEnglish, result.spanishMargin, 1e-6f)
            return result.language
        }
        assertEquals("es", languageFor(-1f, preferSpanish = true)) // close: the app's Spanish wins
        assertEquals("en", languageFor(-3f, preferSpanish = true)) // clearly English
        assertEquals("en", languageFor(1f, preferSpanish = false))
        assertEquals("es", languageFor(3f, preferSpanish = false))
    }

    @Test
    fun `the first text token is never end-of-text or a bare space (suppress blank)`() {
        val d = ScriptedDecoder(
            vocabSize,
            listOf(WhisperDecoding.ENGLISH to 1f, 0L to 0f, 0L to 0f, WhisperDecoding.END_OF_TEXT to 9f, WhisperDecoding.END_OF_TEXT to 9f),
        ).also(3, mapOf(WhisperDecoding.BLANK to 8f, 7_000L to 3f))
        val result = WhisperDecoding.greedy(28, step = d::step)
        assertEquals("en", result.language)
        assertEquals(listOf(7_000L), result.text) // "stop" instead of an empty answer
    }

    @Test
    fun `special and timestamp tokens are never taken, and the text is capped`() {
        val favorites = listOf(WhisperDecoding.ENGLISH to 1f, 0L to 0f, 0L to 0f) + List(10) { 500L + it to 2f }
        val d = ScriptedDecoder(vocabSize, favorites).also(4, mapOf(50_400L to 50f, WhisperDecoding.NO_TIMESTAMPS to 50f))
        val result = WhisperDecoding.greedy(3, step = d::step)
        assertEquals(listOf(500L, 501L, 502L), result.text)
        assertEquals(6, d.fed.size) // prompt 4 + 2: no call for logits nobody reads after the cap
    }

    @Test
    fun `byte-level BPE pieces decode to UTF-8 text`() {
        // GPT-2 stand-ins: "Ġ" (U+0120) is the byte 0x20; "Ã©" is the UTF-8 bytes C3 A9, an "é".
        val tokenizer = WhisperTokenizer(arrayOf("Read", "\u0120this", ".", "\u0120qu", "\u00c3\u00a9", "\u00c2\u00bf"))
        assertEquals("Read this.", tokenizer.decode(listOf(0L, 1L, 2L)))
        assertEquals("¿ qué", tokenizer.decode(listOf(5L, 3L, 4L)))
        assertEquals("Read", tokenizer.decode(listOf(0L, 99L))) // unknown ids are skipped
    }

    @Test
    fun `Whisper's special tokens and sound annotations are dropped`() {
        assertEquals("Read this.", WhisperText.clean("<|en|><|transcribe|><|notimestamps|> Read this.<|endoftext|>"))
        assertEquals("¿Qué hay aquí?", WhisperText.clean("<|es|><|transcribe|><|0.00|> ¿Qué hay aquí?<|1.20|><|endoftext|>"))
        assertEquals("", WhisperText.clean("<|en|><|transcribe|><|notimestamps|> [BLANK_AUDIO]<|endoftext|>"))
        assertEquals("stop", WhisperText.clean(" (beep)  *click*   stop "))
    }

    @Test
    fun `cleaned Whisper text parses to the command`() {
        assertEquals(Command.Read, CommandGrammar.parse(WhisperText.clean("<|en|><|transcribe|><|notimestamps|> Read this.<|endoftext|>")))
        assertEquals(Command.Stop, CommandGrammar.parse(WhisperText.clean("<|en|><|transcribe|><|notimestamps|> Stop!<|endoftext|>")))
    }
}
