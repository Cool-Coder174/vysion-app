package com.vysion.xr.voice

import com.vysion.xr.core.OnboardingStep
import com.vysion.xr.core.Speaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The onboarding copy (designs pages 2-8) is complete in both languages and follows the cue phrasing rules. */
class OnboardingCuesTest {

    private val locales = listOf(Speaker.EN_US, Speaker.ES_MX)

    @Test
    fun `every page has a title and a spoken text in both languages`() {
        locales.forEach { locale ->
            OnboardingStep.entries.forEach { step ->
                val page = Cues.onboarding(step, locale)
                assertEquals(step, page.step)
                assertTrue("$locale $step title", page.title.isNotBlank())
                assertTrue("$locale $step spoken", page.spoken.isNotBlank())
                assertTrue("$locale $step: what is spoken starts with the title on screen", page.spoken.startsWith(page.title))
            }
        }
    }

    @Test
    fun `pages carry the designs' rows`() {
        locales.forEach { locale ->
            assertEquals(3, Cues.onboarding(OnboardingStep.PRIVACY, locale).items.size)
            assertEquals(6, Cues.onboarding(OnboardingStep.SOUNDS, locale).items.size)
            assertEquals(3, Cues.onboarding(OnboardingStep.ALL_SET, locale).items.size)
            assertTrue(Cues.onboarding(OnboardingStep.TALK, locale).hint!!.isNotBlank())
            assertTrue(Cues.onboarding(OnboardingStep.SAFETY, locale).hint!!.isNotBlank())
        }
    }

    @Test
    fun `the safety page carries the product sentence verbatim`() {
        assertEquals(Cues.productSentence(Speaker.EN_US), Cues.onboarding(OnboardingStep.SAFETY, Speaker.EN_US).body)
        assertEquals(Cues.productSentence(Speaker.ES_MX), Cues.onboarding(OnboardingStep.SAFETY, Speaker.ES_MX).body)
    }

    @Test
    fun `spanish is a translation, not a copy`() {
        OnboardingStep.entries.forEach { step ->
            assertNotEquals(Cues.onboarding(step, Speaker.EN_US).title, Cues.onboarding(step, Speaker.ES_MX).title)
        }
        assertNotEquals(Cues.stopPractice(Speaker.EN_US), Cues.stopPractice(Speaker.ES_MX))
    }

    @Test
    fun `onboarding copy follows the phrasing rules`() {
        Cues.onboardingValues().forEach { value ->
            assertEquals("\"$value\"", emptyList<String>(), Cues.violatesPhrasingRules(value))
        }
    }
}
