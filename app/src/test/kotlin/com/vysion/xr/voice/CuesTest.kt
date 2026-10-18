package com.vysion.xr.voice

import com.vysion.xr.core.Mode
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Spec-as-tests for the phrasing guide: every NavigationCue in both locales, the rules, and completeness. */
class CuesTest {

    private val en: Locale = Speaker.EN_US
    private val es: Locale = Speaker.ES_MX

    @Test
    fun `door bearing is the SRS example in both locales`() {
        val cue = NavigationCue.DoorBearing(clock = 2, distanceM = 3.0f, inView = true)
        assertEquals("door, 2 o'clock, 3 meters", Cues.phrase(cue, en))
        assertEquals("puerta, a las 2, 3 metros", Cues.phrase(cue, es))
    }

    @Test
    fun `meters are rounded to a half and spoken without decimals`() {
        assertEquals("2 and a half meters", Cues.meters(2.6f, en))
        assertEquals("2 metros y medio", Cues.meters(2.6f, es))
        assertEquals("1 meter", Cues.meters(1.1f, en))
        assertEquals("1 metro", Cues.meters(1.1f, es))
        assertEquals("1 and a half meters", Cues.meters(1.5f, en))
        assertEquals("1 metro y medio", Cues.meters(1.5f, es))
        assertEquals("half a meter", Cues.meters(0.4f, en))
        assertEquals("medio metro", Cues.meters(0.4f, es))
        assertEquals("less than half a meter", Cues.meters(0.1f, en))
        assertEquals("menos de medio metro", Cues.meters(0.1f, es))
        assertEquals("3 meters", Cues.meters(2.76f, en))
        assertEquals("3 metros", Cues.meters(2.76f, es))
    }

    @Test
    fun `clock face hours including the Spanish singular`() {
        assertEquals("12 o'clock", Cues.clockFace(12, en))
        assertEquals("a las 12", Cues.clockFace(12, es))
        assertEquals("a la 1", Cues.clockFace(1, es))
        assertEquals("1 o'clock", Cues.clockFace(1, en))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `clock outside 1 to 12 is rejected`() {
        Cues.clockFace(0, en)
    }

    @Test
    fun `every NavigationCue has words in both locales`() {
        val cues = listOf(
            NavigationCue.Proximity(0.8f),
            NavigationCue.DoorBearing(11, 1.5f, inView = false),
            NavigationCue.WallAhead(1.2f),
            NavigationCue.FloorEnds(1.4f),
            NavigationCue.Arrived,
            NavigationCue.TargetLost,
        )
        assertEquals("obstacle ahead", Cues.phrase(cues[0], en))
        assertEquals("obstáculo adelante", Cues.phrase(cues[0], es))
        assertEquals("door, 11 o'clock, 1 and a half meters", Cues.phrase(cues[1], en))
        assertEquals("puerta, a las 11, 1 metro y medio", Cues.phrase(cues[1], es))
        assertEquals("wall ahead", Cues.phrase(cues[2], en))
        assertEquals("pared adelante", Cues.phrase(cues[2], es))
        assertEquals("floor ends", Cues.phrase(cues[3], en))
        assertEquals("el piso termina", Cues.phrase(cues[3], es))
        assertEquals("you have arrived", Cues.phrase(cues[4], en))
        assertEquals("has llegado", Cues.phrase(cues[4], es))
        assertEquals("I lost the door, turn slowly", Cues.phrase(cues[5], en))
        assertEquals("Perdí la puerta, gira despacio", Cues.phrase(cues[5], es))
        for (cue in cues) for (locale in listOf(en, es)) {
            assertTrue("$cue $locale", Cues.violatesPhrasingRules(Cues.phrase(cue, locale)).isEmpty())
        }
    }

    @Test
    fun `violatesPhrasingRules flags hedging decimals and degrees`() {
        assertEquals(listOf("hedging word 'probably'"), Cues.violatesPhrasingRules("probably a door"))
        assertEquals(listOf("hedging word 'i think'"), Cues.violatesPhrasingRules("I think it's a chair"))
        assertEquals(listOf("hedging word 'quizas'"), Cues.violatesPhrasingRules("quizás una puerta"))
        assertEquals(listOf("hedging word 'tal vez'"), Cues.violatesPhrasingRules("tal vez una silla"))
        assertEquals(
            listOf("decimal number: say 'and a half' instead"),
            Cues.violatesPhrasingRules("door, 2 o'clock, 2.5 meters"),
        )
        val degrees = Cues.violatesPhrasingRules("door at 60 degrees")
        assertEquals(listOf("bearing in degrees: use clock-face hours"), degrees)
        assertEquals(2, Cues.violatesPhrasingRules("maybe 30 grados").size)
        assertTrue(Cues.violatesPhrasingRules("door, 2 o'clock, 3 meters").isEmpty())
        assertTrue(Cues.violatesPhrasingRules("unlikely words inside other words are fine").isEmpty())
    }

    @Test
    fun `every key has a value in both locales and none breaks the rules`() {
        for (key in Cues.Key.values()) {
            val english = Cues.text(key, en)
            val spanish = Cues.text(key, es)
            assertTrue("$key en", english.isNotBlank())
            assertTrue("$key es", spanish.isNotBlank())
            assertEquals("$key en: $english", emptyList<String>(), Cues.violatesPhrasingRules(english))
            assertEquals("$key es: $spanish", emptyList<String>(), Cues.violatesPhrasingRules(spanish))
        }
        assertEquals(Cues.Key.values().size * 2, Cues.allValues().size)
        // unknown languages fall back to en-US
        assertEquals(Cues.welcome(en), Cues.welcome(Locale.FRENCH))
    }

    @Test
    fun `welcome lists the three commands and stop (FR-G1)`() {
        val welcome = Cues.welcome(en)
        for (phrase in listOf("read this", "describe", "find the door", "stop")) {
            assertTrue(phrase, welcome.contains(phrase))
        }
        val bienvenida = Cues.welcome(es)
        for (phrase in listOf("lee esto", "describe", "busca la puerta", "alto")) {
            assertTrue(phrase, bienvenida.contains(phrase))
        }
        // and the grammar understands exactly what the welcome tells the user to say
        assertEquals(com.vysion.xr.core.Command.Read, CommandGrammar.parse("read this", en))
        assertEquals(com.vysion.xr.core.Command.FindDoor, CommandGrammar.parse("busca la puerta", es))
        assertEquals(com.vysion.xr.core.Command.Stop, CommandGrammar.parse("alto", es))
    }

    @Test
    fun `mode announcements name each mode in one short word or phrase (FR-G3)`() {
        // Short on purpose: the answer queues behind the announcement, and "Text Reader mode" (about 2 s of speech)
        // held the first word back 0.8 s on the worn headset.
        assertEquals("Reading", Cues.modeAnnouncement(Mode.TEXT_READER, en))
        assertEquals("Describing", Cues.modeAnnouncement(Mode.AUDIO_DESCRIPTION, en))
        assertEquals("Finding the door", Cues.modeAnnouncement(Mode.NAVIGATION, en))
        assertEquals("Buscando la puerta", Cues.modeAnnouncement(Mode.NAVIGATION, es))
    }

    @Test
    fun `CuesPhrasebook follows the bound locale and leaves Arrived to the ARRIVAL prompt`() {
        CuesPhrasebook.locale = { es }
        try {
            assertEquals(Cues.welcome(es), CuesPhrasebook.welcome)
            assertEquals(Cues.busy(es), CuesPhrasebook.busy)
            assertEquals(Cues.inferenceFailed(es), CuesPhrasebook.inferenceFailed)
            assertEquals(Cues.doorNotFound(es), CuesPhrasebook.doorNotFound)
            assertEquals(Cues.navigationUnavailableOnThisDevice(es), CuesPhrasebook.navigationUnavailable)
            assertEquals(Cues.holdStill(es), CuesPhrasebook.holdStill)
            assertEquals(Cues.permissionExplanation(es), CuesPhrasebook.permissionExplanation)
            assertEquals(Cues.permissionDenied(es), CuesPhrasebook.permissionDenied)
            assertEquals("Buscando la puerta", CuesPhrasebook.modeAnnounced(Mode.NAVIGATION))
            assertEquals("obstáculo adelante", CuesPhrasebook.navigationCue(NavigationCue.Proximity(0.7f)))
            assertEquals(null, CuesPhrasebook.navigationCue(NavigationCue.Arrived))
        } finally {
            CuesPhrasebook.locale = { en }
        }
        assertEquals(Cues.welcome(en), CuesPhrasebook.welcome)
    }

    @Test
    fun `the RK-6 door phrase is verbatim and error aliases inferenceFailed`() {
        assertEquals("I can't find a door, turn slowly", Cues.doorNotFound(en))
        assertEquals(Cues.inferenceFailed(es), Cues.error(es))
        assertEquals("Hold still", Cues.holdStill(en))
        assertEquals("obstacle ahead", Cues.obstacleAhead(en))
    }

    @Test
    fun `product sentence matches res values strings xml when the file is reachable`() {
        val expected = Cues.productSentence(en)
        assertEquals(
            "Vysion XR helps you read, understand, and move through a space. " +
                "It is not validated for unassisted travel by blind users.",
            expected,
        )
        // Gradle runs unit tests from the module directory; guard so the test is honest elsewhere.
        val xml = listOf("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml")
            .map(::File).firstOrNull { it.exists() } ?: return
        val text = xml.readText()
        assertTrue("strings.xml product_sentence drifted from Cues", text.contains(expected))
        assertFalse(expected.isEmpty())
    }
}
