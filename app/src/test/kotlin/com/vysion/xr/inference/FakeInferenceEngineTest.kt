package com.vysion.xr.inference

import android.graphics.Bitmap
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The fake is what R1's `ModeController` tests and the emulator run against, so its behaviors are pinned here:
 * canned answers per prompt, two boxes, stats on every call, `failNextDescribe`, and a cancellable
 * `hangNextDescribe` for the 15 s watchdog (INV-5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeInferenceEngineTest {

    private val bitmap: Bitmap = TestBitmaps.placeholder()

    @Test
    fun `warmUp publishes fake model info and marks both models loaded`() = runTest {
        val engine = FakeInferenceEngine()
        assertNull(engine.vlmInfo.value)
        engine.warmUp()
        assertEquals("fake-vlm", engine.vlmInfo.value?.name)
        assertEquals("FAKE", engine.vlmInfo.value?.backend)
        assertEquals(896, engine.vlmInfo.value?.inputWidth)
        assertEquals("fake-detector", engine.detectorInfo.value?.name)
        assertEquals(640, engine.detectorInfo.value?.inputWidth)
        assertTrue(engine.stats.value.vlmLoaded)
        assertTrue(engine.stats.value.detectorLoaded)
        engine.release()
        assertNull(engine.vlmInfo.value)
        assertFalse(engine.stats.value.vlmLoaded)
    }

    @Test
    fun `describe answers each PromptLibrary prompt and updates the stats`() = runTest {
        val engine = FakeInferenceEngine(describeDelayMs = 1)
        engine.warmUp()
        assertEquals("EXIT. Push bar to open. Alarm will sound.", engine.describe(bitmap, PromptLibrary.READ))
        assertEquals("center", engine.describe(bitmap, PromptLibrary.DOOR))
        assertTrue(engine.describe(bitmap, PromptLibrary.DESCRIBE).contains("hallway"))
        assertTrue(engine.describe(bitmap, PromptLibrary.ARRIVAL).contains("handle"))
        assertTrue(engine.describe(bitmap, PromptLibrary.TRANSLATE_ES).startsWith("SALIDA"))
        assertEquals(
            FakeInferenceEngine.DEFAULT_FOLLOW_UP_ANSWER,
            engine.describe(bitmap, PromptLibrary.followUp("what?")),
        )
        val s = engine.stats.value
        assertEquals(6, s.vlmRuns)
        assertNotNull(s.lastVlmMs)
        assertNotNull(s.medianVlmMs)
        assertNotNull(s.p90VlmMs)
        assertTrue(s.detectorLoaded) // reloaded after each call (ADR-0004 mirrored)
        assertEquals(PromptLibrary.followUp("what?"), engine.lastPrompt)
        // FR-G6: a prompt localized by PromptLibrary.forLocale keeps its canned answer.
        val spanishRead = PromptLibrary.forLocale(PromptLibrary.READ, java.util.Locale.forLanguageTag("es-MX"))
        assertEquals("EXIT. Push bar to open. Alarm will sound.", engine.describe(bitmap, spanishRead))
    }

    @Test
    fun `canned answers can be replaced and the token cap truncates`() = runTest {
        val engine = FakeInferenceEngine(describeDelayMs = 0)
        engine.cannedAnswers[PromptLibrary.READ] = "one two three four five"
        assertEquals("one two three", engine.describe(bitmap, PromptLibrary.READ, maxNewTokens = 3))
        assertEquals("one two three four five", engine.describe(bitmap, PromptLibrary.READ))
        assertEquals(InferenceEngine.DEFAULT_MAX_NEW_TOKENS, 60)
    }

    @Test
    fun `door answer round-trips through the parser`() = runTest {
        val engine = FakeInferenceEngine(describeDelayMs = 0)
        val answer = engine.describe(bitmap, PromptLibrary.DOOR)
        assertEquals(com.vysion.xr.core.HorizontalBearing.CENTER, PromptLibrary.parseDoorAnswer(answer))
    }

    @Test
    fun `failNextDescribe throws once`() = runTest {
        val engine = FakeInferenceEngine(describeDelayMs = 0)
        engine.failNextDescribe = true
        try {
            engine.describe(bitmap, PromptLibrary.READ)
            fail("expected InferenceException")
        } catch (e: InferenceException) {
            assertEquals("fake failure", e.message)
        }
        assertFalse(engine.failNextDescribe)
        assertEquals("EXIT. Push bar to open. Alarm will sound.", engine.describe(bitmap, PromptLibrary.READ))
        assertEquals(1, engine.describeCalls)
    }

    @Test
    fun `hangNextDescribe suspends until cancelled, then the engine works again`() = runTest {
        val engine = FakeInferenceEngine(describeDelayMs = 0)
        engine.hangNextDescribe = true
        val result = withTimeoutOrNull(InferenceEngine.VLM_TIMEOUT_MS) { engine.describe(bitmap, PromptLibrary.READ) }
        assertNull(result) // the watchdog fired (virtual time) and the hang was cancellable
        assertFalse(engine.hangNextDescribe)
        assertEquals("center", engine.describe(bitmap, PromptLibrary.DOOR))
    }

    @Test
    fun `describe calls are serialized by the mutex`() = runTest {
        val engine = FakeInferenceEngine(describeDelayMs = 100)
        val a = async { engine.describe(bitmap, PromptLibrary.READ) }
        val b = async { engine.describe(bitmap, PromptLibrary.DOOR) }
        assertEquals("EXIT. Push bar to open. Alarm will sound.", a.await())
        assertEquals("center", b.await())
        assertEquals(2, engine.stats.value.vlmRuns)
    }

    @Test
    fun `detect returns two canned boxes and measures fps`() = runTest {
        val engine = FakeInferenceEngine(detectDelayMs = 1)
        engine.warmUp()
        val first = engine.detect(bitmap)
        assertEquals(listOf("person", "chair"), first.map { it.label })
        assertTrue(first.all { it.confidence in 0f..1f })
        repeat(4) { engine.detect(bitmap) }
        val s = engine.stats.value
        assertNotNull(s.lastDetectMs)
        assertTrue("fps should be measured after 5 frames", s.detectorFps > 0f)
        assertEquals(5, engine.detectCalls)
        assertNotNull("detect stamps its monotonic time", s.lastDetectAtNanos)
        engine.cannedDetections = emptyList()
        assertTrue(engine.detect(bitmap).isEmpty())
        // INV-2 mirrored: a VLM call unloads the detector and restarts its window, so no stale fps survives it.
        engine.describe(bitmap, PromptLibrary.READ)
        assertEquals(0f, engine.stats.value.detectorFps)
    }

    @Test
    fun `streaming emits the canned answer sentence by sentence`() = runTest {
        val engine = FakeInferenceEngine(describeDelayMs = 0)
        val chunks = engine.describeStreaming(bitmap, PromptLibrary.READ).toList()
        assertEquals(3, chunks.size)
        assertEquals("EXIT. ", chunks[0])
        assertEquals("EXIT. Push bar to open. Alarm will sound.", chunks.joinToString(""))
    }
}

/**
 * `android.graphics.Bitmap` is a stub in the unit-test `android.jar` (`createBitmap` returns null under
 * `isReturnDefaultValues`), and the fake never reads the pixels, so an uninitialized instance is enough.
 * Allocated without a constructor via `sun.misc.Unsafe`; R1's `ModeController` tests can reuse this helper.
 */
object TestBitmaps {
    fun placeholder(): Bitmap {
        val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, Bitmap::class.java) as Bitmap
    }
}
