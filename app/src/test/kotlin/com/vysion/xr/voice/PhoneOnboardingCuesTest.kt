package com.vysion.xr.voice

import com.vysion.xr.core.OnboardingStep
import com.vysion.xr.core.PhoneOnboardingStep
import com.vysion.xr.core.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone onboarding copy (designs "Phone v6", section 1) is complete in both languages and follows the phrasing rules. */
class PhoneOnboardingCuesTest {

    private val locales = listOf(Speaker.EN_US, Speaker.ES_MX)

    @Test
    fun `every page has a title and a spoken text in both languages`() {
        locales.forEach { locale ->
            PhoneOnboardingStep.entries.forEach { step ->
                val page = Cues.phoneOnboarding(step, locale)
                assertEquals(step, page.step)
                assertTrue("$locale $step title", page.title.isNotBlank())
                assertTrue("$locale $step spoken", page.spoken.isNotBlank())
                assertTrue("$locale $step: what is spoken starts with the title on screen", page.spoken.startsWith(page.title))
                page.body?.let { assertTrue("$locale $step: the body is spoken too", page.spoken.contains(it)) }
            }
        }
    }

    @Test
    fun `the welcome pill is spoken and the last page carries the six sounds of the headset flow`() {
        locales.forEach { locale ->
            val welcome = Cues.phoneOnboarding(PhoneOnboardingStep.WELCOME, locale)
            assertTrue(welcome.hint!!.isNotBlank())
            assertTrue(welcome.spoken.contains(welcome.hint!!))
            val sounds = Cues.phoneOnboarding(PhoneOnboardingStep.ALL_SET, locale).items
            assertEquals(6, sounds.size)
            assertEquals(Cues.onboarding(OnboardingStep.SOUNDS, locale).items.map { it.title }, sounds.map { it.title })
        }
    }

    @Test
    fun `the stop practice names the phone gesture, not the headset one`() {
        assertTrue(Cues.phoneStopPractice(Speaker.EN_US).contains("two fingers"))
        assertTrue(Cues.phoneStopPractice(Speaker.ES_MX).contains("dos dedos"))
    }

    @Test
    fun `spanish is a translation, not a copy`() {
        PhoneOnboardingStep.entries.forEach { step ->
            assertNotEquals(Cues.phoneOnboarding(step, Speaker.EN_US).title, Cues.phoneOnboarding(step, Speaker.ES_MX).title)
        }
        assertNotEquals(Cues.phoneStopPractice(Speaker.EN_US), Cues.phoneStopPractice(Speaker.ES_MX))
    }

    @Test
    fun `phone onboarding copy follows the phrasing rules`() {
        Cues.phoneOnboardingValues().forEach { value ->
            assertEquals("\"$value\"", emptyList<String>(), Cues.violatesPhrasingRules(value))
        }
    }
}
