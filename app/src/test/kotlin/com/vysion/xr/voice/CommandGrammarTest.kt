package com.vysion.xr.voice

import com.vysion.xr.core.Command
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Spec-as-tests for FR-G2's grammar: every command, both languages, fuzzy variants, follow-up, wake word. */
class CommandGrammarTest {

    private val en: Locale = Speaker.EN_US
    private val es: Locale = Speaker.ES_MX

    private fun parse(s: String, locale: Locale = en) = CommandGrammar.parse(s, locale)

    @Test
    fun `every phrase the recognizer is biased toward is understood, in both languages`() {
        for ((tag, locale) in listOf("en-US" to Speaker.EN_US, "es-MX" to Speaker.ES_MX)) {
            for (phrase in CommandGrammar.biasingPhrases(tag)) {
                val command = CommandGrammar.parse(phrase, locale)
                assertTrue("\"$phrase\" ($tag) is not understood", command != null) // a question is a FollowUp: still understood
            }
        }
    }

    @Test
    fun `read in both languages including the SRS fuzzy variants`() {
        for (u in listOf("read this", "read it", "read that", "Read", "read the sign", "could you read this please")) {
            assertEquals(u, Command.Read, parse(u))
        }
        for (u in listOf("lee esto", "lee", "léelo", "léeme esto", "leer esto")) {
            assertEquals(u, Command.Read, parse(u, es))
        }
    }

    @Test
    fun `describe in both languages`() {
        val variants =
            listOf("describe", "describe this", "what's around me", "what is around me", "describe my surroundings")
        for (u in variants) {
            assertEquals(u, Command.Describe, parse(u))
        }
        for (u in listOf("describe esto", "qué hay", "descríbeme", "qué hay alrededor")) {
            assertEquals(u, Command.Describe, parse(u, es))
        }
    }

    @Test
    fun `guide me and find the door`() {
        for (u in listOf("guide me", "navigate", "navigation")) assertEquals(u, Command.GuideMe, parse(u))
        for (u in listOf("guíame", "guía", "navega")) assertEquals(u, Command.GuideMe, parse(u, es))
        for (u in listOf("find the door", "where is the door", "where's the door", "find the exit", "the door")) {
            assertEquals(u, Command.FindDoor, parse(u))
        }
        for (u in listOf("busca la puerta", "dónde está la puerta", "encuentra la puerta", "la puerta")) {
            assertEquals(u, Command.FindDoor, parse(u, es))
        }
    }

    @Test
    fun `stop repeat slower in spanish`() {
        for (u in listOf("stop", "Stop!", "stop it", "stopp")) assertEquals(u, Command.Stop, parse(u))
        for (u in listOf("para", "alto", "detente", "basta", "silencio")) assertEquals(u, Command.Stop, parse(u, es))
        for (u in listOf("repeat", "again", "say that again", "repeat that")) assertEquals(u, Command.Repeat, parse(u))
        for (u in listOf("repite", "repítelo", "otra vez", "de nuevo")) assertEquals(u, Command.Repeat, parse(u, es))
        for (u in listOf("slower", "slow down", "more slowly")) assertEquals(u, Command.Slower, parse(u))
        for (u in listOf("más despacio", "mas despacio", "más lento")) assertEquals(u, Command.Slower, parse(u, es))
        for (u in listOf("in spanish", "in Spanish please", "translate")) assertEquals(u, Command.InSpanish, parse(u))
        for (u in listOf("en español", "en espanol", "traduce", "tradúcelo")) {
            assertEquals(u, Command.InSpanish, parse(u, es))
        }
    }

    @Test
    fun `cloud on and off in both languages, and a cloudy question stays a question`() {
        val on = Command.CloudDescribe(on = true)
        val off = Command.CloudDescribe(on = false)
        for (u in listOf("cloud on", "Vysion, cloud on", "turn the cloud on", "turn on cloud")) assertEquals(u, on, parse(u))
        for (u in listOf("cloud off", "turn the cloud off", "turn off cloud")) assertEquals(u, off, parse(u))
        for (u in listOf("activa la nube", "enciende la nube", "nube activada")) assertEquals(u, on, parse(u, es))
        for (u in listOf("desactiva la nube", "apaga la nube", "nube desactivada")) assertEquals(u, off, parse(u, es))
        assertEquals(
            Command.FollowUp("is it cloudy on the left?"),
            parse("is it cloudy on the left?"),
        )
        assertEquals("cloud alone is not a switch", null, parse("cloud"))
    }

    @Test
    fun `next brief detailed`() {
        assertEquals(Command.Next, parse("next"))
        assertEquals(Command.Next, parse("next page"))
        assertEquals(Command.Next, parse("siguiente", es))
        assertEquals(Command.Next, parse("la próxima", es))
        assertEquals(Command.Brief, parse("brief"))
        assertEquals(Command.Brief, parse("shorter"))
        assertEquals(Command.Brief, parse("breve", es))
        assertEquals(Command.Brief, parse("más corto", es))
        assertEquals(Command.Detailed, parse("detailed"))
        assertEquals(Command.Detailed, parse("more detail"))
        assertEquals(Command.Detailed, parse("detallado", es))
        assertEquals(Command.Detailed, parse("con más detalle", es))
    }

    @Test
    fun `select door among candidates`() {
        assertEquals(Command.SelectDoor(HorizontalBearing.LEFT), parse("the one on the left"))
        assertEquals(Command.SelectDoor(HorizontalBearing.RIGHT), parse("the door on the right"))
        assertEquals(Command.SelectDoor(HorizontalBearing.CENTER), parse("the one in the middle"))
        assertEquals(Command.SelectDoor(HorizontalBearing.CENTER), parse("the center one"))
        assertEquals(Command.SelectDoor(HorizontalBearing.LEFT), parse("left"))
        assertEquals(Command.SelectDoor(HorizontalBearing.LEFT), parse("la de la izquierda", es))
        assertEquals(Command.SelectDoor(HorizontalBearing.RIGHT), parse("la puerta de la derecha", es))
        assertEquals(Command.SelectDoor(HorizontalBearing.CENTER), parse("la del centro", es))
    }

    @Test
    fun `wake word and filler are stripped in both spellings`() {
        assertEquals(Command.Read, parse("Vysion, read this"))
        assertEquals(Command.Read, parse("vision read this"))
        assertEquals(Command.Read, parse("hey vysion please read this"))
        assertEquals(Command.Describe, parse("Vysion, describe."))
        assertEquals(Command.FindDoor, parse("Vysion, find the door."))
        assertEquals(Command.Read, parse("oye visión lee esto", es))
        assertNull(parse("vysion"))
        assertNull(parse("hey vysion"))
    }

    @Test
    fun `fuzzy matching tolerates recognizer slips but not short words`() {
        assertEquals(Command.Describe, parse("describ this"))
        assertEquals(Command.Describe, parse("discribe the room"))
        assertEquals(Command.Repeat, parse("repeet"))
        assertEquals(Command.Slower, parse("slowr"))
        assertEquals(Command.Stop, parse("stahp"))
        assertNull(parse("leg esto", es)) // "lee" is exact-only
        assertNull(parse("pare", es)) // "para" is exact-only
        assertNull(parse("xyz"))
        assertNull(parse(""))
        assertNull(parse("   ?  "))
    }

    @Test
    fun `stop survives recognizer slips in a bare command, a similar word in a sentence is not Stop`() {
        // FR-G4 / INV-6: in <= SAFETY_FUZZY_MAX_TOKENS tokens, 1 edit, or 2 edits keeping the "st" onset
        for (u in listOf("stahp", "stap it", "Vysion, stahp!", "please stopp", "stahp reading")) {
            assertEquals(u, Command.Stop, parse(u))
        }
        assertEquals(2, CommandGrammar.levenshtein("stop", "stahp"))
        // the exact word works in a sentence of any length
        assertEquals(Command.Stop, parse("please stop reading that now"))
        // short words that share letters but not the onset are not Stop
        for (u in listOf("so", "to", "up")) assertNull(u, parse(u))
        assertNull(parse("leg esto", es)) // "esto" is 2 edits from "stop" but has no "st" onset
        assertEquals(Command.Slower, parse("slow")) // 2 edits from "stop", 0 from "slow"
        assertEquals(Command.Read, parse("lee esto", es))
        // in a longer utterance only the exact word counts: these stay questions (FR-D2)
        assertEquals(Command.FollowUp("is there a step ahead?"), parse("is there a step ahead?"))
        assertEquals(Command.FollowUp("what's on the top shelf?"), parse("what's on the top shelf?"))
    }

    @Test
    fun `follow-up questions that are not commands`() {
        assertEquals(Command.FollowUp("what's on the table?"), parse("what's on the table?"))
        assertEquals(Command.FollowUp("what's on the table?"), parse("Vysion, what's on the table?"))
        assertEquals(Command.FollowUp("is there a chair"), parse("is there a chair"))
        assertEquals(Command.FollowUp("¿qué color es la pared?"), parse("¿qué color es la pared?", es))
        assertEquals(Command.FollowUp("cuántas sillas hay"), parse("cuántas sillas hay", es))
        // commands that look like questions stay commands
        assertEquals(Command.FindDoor, parse("where is the door?"))
        assertEquals(Command.Describe, parse("what's around me?"))
        // a lone question word is noise, not a question
        assertNull(parse("what"))
        assertNull(parse("the"))
    }

    @Test
    fun `stop is never shadowed and mixed sentences resolve sensibly`() {
        assertEquals(Command.Stop, parse("stop reading"))
        assertEquals(Command.Read, parse("read the door sign"))
        assertEquals(Command.GuideMe, parse("guide me to the door"))
        assertEquals(Command.Read, parse("lee esto para mí", es))
    }

    @Test
    fun `locale only breaks ties`() {
        // "describe" exists in both tables; either locale yields the same command
        assertEquals(Command.Describe, parse("describe", es))
        assertEquals(Command.Read, parse("read this", es))
        assertEquals(Command.Read, parse("lee esto", en))
    }

    @Test
    fun `levenshtein is the textbook distance`() {
        assertEquals(0, CommandGrammar.levenshtein("read", "read"))
        assertEquals(1, CommandGrammar.levenshtein("read", "red"))
        assertEquals(2, CommandGrammar.levenshtein("describe", "describeme"))
        assertEquals(3, CommandGrammar.levenshtein("kitten", "sitting"))
        assertEquals(4, CommandGrammar.levenshtein("", "stop"))
    }

    @Test
    fun `tokens are folded and the prefix is dropped`() {
        assertEquals(listOf("read", "this"), CommandGrammar.tokens("Hey Vysion, READ this!"))
        assertEquals(listOf("que", "hay"), CommandGrammar.tokens("¿Qué hay?"))
        assertEquals(listOf("whats", "around", "me"), CommandGrammar.tokens("what's around me"))
        assertTrue(CommandGrammar.tokens("vysion").isEmpty())
    }
}
