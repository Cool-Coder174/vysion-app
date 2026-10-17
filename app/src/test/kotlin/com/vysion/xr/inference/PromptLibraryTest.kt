package com.vysion.xr.inference

import com.vysion.xr.core.HorizontalBearing
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec-as-tests for the SRS §7 prompts and the FR-N3 door-answer allowlist. JVM only, no device. */
class PromptLibraryTest {

    @Test
    fun `the four SRS prompts are verbatim`() {
        assertEquals(
            "Read all text in this image exactly as written, top to bottom, left to right. Output only the text. " +
                "If there is no text, say: No text found.",
            PromptLibrary.READ,
        )
        assertEquals(
            "Describe this scene for someone who cannot see it, in at most three sentences. Hazards first, then what " +
                "is on the left, center, and right. Do not guess.",
            PromptLibrary.DESCRIBE,
        )
        assertEquals(
            "Is there a door in this image? Answer with one word: left, center, right, or none.",
            PromptLibrary.DOOR,
        )
        assertEquals(
            "Is this door open or closed, and which side is the handle on? One short sentence.",
            PromptLibrary.ARRIVAL,
        )
    }

    @Test
    fun `translate prompt is the one the brief fixes`() {
        assertEquals(
            "Translate the text you read into Spanish; output only the translation.",
            PromptLibrary.TRANSLATE_ES,
        )
    }

    @Test
    fun `ALL lists every fixed prompt once`() {
        assertEquals(7, PromptLibrary.ALL.size)
        assertEquals(PromptLibrary.ALL.size, PromptLibrary.ALL.values.toSet().size)
        assertSame(PromptLibrary.READ, PromptLibrary.ALL["READ"])
        assertSame(PromptLibrary.DOOR, PromptLibrary.ALL["DOOR"])
    }

    @Test
    fun `door answer takes the first allowlisted word, case-insensitive`() {
        assertEquals(HorizontalBearing.LEFT, PromptLibrary.parseDoorAnswer("left"))
        assertEquals(HorizontalBearing.CENTER, PromptLibrary.parseDoorAnswer("Center."))
        assertEquals(HorizontalBearing.RIGHT, PromptLibrary.parseDoorAnswer("  RIGHT\n"))
        assertEquals(HorizontalBearing.NONE, PromptLibrary.parseDoorAnswer("none"))
        assertEquals(HorizontalBearing.LEFT, PromptLibrary.parseDoorAnswer("The door is on the left side."))
        assertEquals(HorizontalBearing.LEFT, PromptLibrary.parseDoorAnswer("Left, slightly toward the center"))
        assertEquals(HorizontalBearing.RIGHT, PromptLibrary.parseDoorAnswer("I see a door to the right of the chair"))
    }

    @Test
    fun `anything outside the allowlist is NONE so the controller never guesses`() {
        assertEquals(HorizontalBearing.NONE, PromptLibrary.parseDoorAnswer(""))
        assertEquals(HorizontalBearing.NONE, PromptLibrary.parseDoorAnswer("   "))
        assertEquals(HorizontalBearing.NONE, PromptLibrary.parseDoorAnswer("There is a window."))
        assertEquals(HorizontalBearing.NONE, PromptLibrary.parseDoorAnswer("centre"))
        assertEquals(HorizontalBearing.NONE, PromptLibrary.parseDoorAnswer("middle"))
        assertEquals(HorizontalBearing.NONE, PromptLibrary.parseDoorAnswer("leftish"))
        assertEquals(HorizontalBearing.NONE, PromptLibrary.parseDoorAnswer("12 o'clock"))
    }

    @Test
    fun `follow-up embeds the question and collapses whitespace`() {
        val p = PromptLibrary.followUp("  what's   on\nthe table? ")
        assertTrue(p.endsWith("Question: what's on the table?"))
        assertTrue(p.contains("Do not guess."))
    }

    @Test
    fun `forLocale appends the Spanish suffix once and leaves English alone`() {
        val es = Locale.forLanguageTag("es-MX")
        val en = Locale.forLanguageTag("en-US")
        assertEquals(PromptLibrary.READ, PromptLibrary.forLocale(PromptLibrary.READ, en))
        val localized = PromptLibrary.forLocale(PromptLibrary.READ, es)
        assertNotEquals(PromptLibrary.READ, localized)
        assertTrue(localized.endsWith(PromptLibrary.SPANISH_SUFFIX))
        assertEquals(localized, PromptLibrary.forLocale(localized, es))
        assertEquals(
            PromptLibrary.DESCRIBE + PromptLibrary.SPANISH_SUFFIX,
            PromptLibrary.forLocale(PromptLibrary.DESCRIBE, es),
        )
    }

    @Test
    fun `forLocale never touches the parsed DOOR prompt or the translate prompt`() {
        val es = Locale.forLanguageTag("es-ES")
        assertEquals(PromptLibrary.DOOR, PromptLibrary.forLocale(PromptLibrary.DOOR, es))
        assertEquals(PromptLibrary.TRANSLATE_ES, PromptLibrary.forLocale(PromptLibrary.TRANSLATE_ES, es))
        assertFalse(PromptLibrary.forLocale(PromptLibrary.DOOR, es).endsWith(PromptLibrary.SPANISH_SUFFIX))
    }
}
