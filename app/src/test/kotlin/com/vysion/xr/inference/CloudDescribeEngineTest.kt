package com.vysion.xr.inference

import android.graphics.Bitmap
import com.vysion.xr.core.InferenceException
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ADR-0010 row "Cloud Describe": when Describe asks the cloud, and how it falls back to the headset (offline, a
 * cloud error, a cloud slower than the hedge, no text), on virtual time. The headset is a scripted flow standing in
 * for the on-device VLM: [HEADSET_FIRST_MS] of prefill, then its sentence.
 */
class CloudDescribeEngineTest {

    // ── CloudRoute: does Describe try the cloud at all ──────────────────────────────────────────────

    @Test
    fun `the cloud is skipped when off, not set up or offline, and online is only asked when it matters`() {
        var asked = 0
        val online = { asked++; true }
        val offline = { asked++; false }
        assertEquals(CloudRoute.OFF, CloudRoute.skip(requested = false, available = true, isOnline = online))
        assertEquals(CloudRoute.NOT_SET_UP, CloudRoute.skip(requested = true, available = false, isOnline = online))
        assertEquals("no network check when the wearer did not ask or no key exists", 0, asked)
        assertEquals(CloudRoute.OFFLINE, CloudRoute.skip(requested = true, available = true, isOnline = offline))
        assertNull(CloudRoute.skip(requested = true, available = true, isOnline = online))
    }

    // ── The engine: routing ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `offline - the headset answers and nothing is sent`() = runTest {
        val cloud = FakeCloud { emit(CLOUD_TEXT) }
        val f = fixture(cloud, online = false)
        assertEquals(listOf(HEADSET_TEXT), f.engine.describeScene(still(), PROMPT, cloud = true).toList())
        assertEquals("no request, no JPEG", 0, cloud.calls)
        assertEquals(0, f.encoded)
        assertTrue(f.lines.any { it.contains("source=headset cloud=offline") })
    }

    @Test
    fun `cloud off or no key - the headset answers and nothing is sent`() = runTest {
        val cloud = FakeCloud { emit(CLOUD_TEXT) }
        val off = fixture(cloud, online = true)
        assertEquals(listOf(HEADSET_TEXT), off.engine.describeScene(still(), PROMPT, cloud = false).toList())
        assertEquals(0, cloud.calls)
        assertTrue(off.engine.cloudDescribeAvailable)

        val noKey = fixture(cloud = null, online = true)
        assertFalse(noKey.engine.cloudDescribeAvailable)
        assertEquals(listOf(HEADSET_TEXT), noKey.engine.describeScene(still(), PROMPT, cloud = true).toList())
        assertTrue(noKey.lines.any { it.contains("cloud=not-set-up") })
    }

    @Test
    fun `online - the cloud answers and the headset never starts`() = runTest {
        val cloud = FakeCloud {
            delay(CLOUD_FIRST_MS)
            emit("A hallway. ")
            emit("A chair on the left.")
        }
        val f = fixture(cloud, online = true)
        assertEquals(listOf("A hallway. ", "A chair on the left."), f.engine.describeScene(still(), PROMPT, cloud = true).toList())
        assertEquals(1, cloud.calls)
        assertEquals("the prompt goes to the cloud as given", PROMPT, cloud.lastPrompt)
        assertEquals("one JPEG of the still", 1, f.encoded)
        assertEquals(0, f.headset.starts)
        assertTrue(f.lines.any { it.contains("describe source=cloud first-chunk") && it.contains("model=fake-cloud") })
    }

    // ── CloudHedge: the fallback decision ───────────────────────────────────────────────────────────

    @Test
    fun `a cloud error falls back to the headset at once, not at the hedge`() = runTest {
        val headset = ScriptedHeadset(this)
        val out = CloudHedge.race(
            cloud = flow { delay(200); throw InferenceException("cloud HTTP 503 UNAVAILABLE") },
            headset = headset.flow(),
            hedgeAfterMs = HEDGE_MS,
        ).toList()
        assertEquals(listOf(HEADSET_TEXT), out)
        assertEquals("headset started when the cloud failed (200 ms), not at the hedge", 200L, headset.startedAt)
        assertEquals(200L + HEADSET_FIRST_MS, currentTime)
    }

    @Test
    fun `a cloud with no text by the hedge loses to the headset and is cancelled`() = runTest {
        val headset = ScriptedHeadset(this)
        var cloudCancelled = false
        val out = CloudHedge.race(
            cloud = flow<String> {
                try {
                    awaitCancellation() // a request that never answers (captive portal, dead Wi-Fi)
                } catch (e: CancellationException) {
                    cloudCancelled = true
                    throw e
                }
            },
            headset = headset.flow(),
            hedgeAfterMs = HEDGE_MS,
        ).toList()
        assertEquals(listOf(HEADSET_TEXT), out)
        assertEquals(HEDGE_MS, headset.startedAt)
        assertTrue("the slow request is dropped", cloudCancelled)
        assertEquals("first word budget: hedge + headset", HEDGE_MS + HEADSET_FIRST_MS, currentTime)
    }

    @Test
    fun `a cloud that answers after the hedge but before the headset still wins`() = runTest {
        val headset = ScriptedHeadset(this)
        val events = mutableListOf<CloudHedge.Event>()
        val out = CloudHedge.race(
            cloud = flow { delay(HEDGE_MS + 500); emit(CLOUD_TEXT) },
            headset = headset.flow(),
            hedgeAfterMs = HEDGE_MS,
            onEvent = { events += it },
        ).toList()
        assertEquals(listOf(CLOUD_TEXT), out)
        assertEquals(HEDGE_MS, headset.startedAt)
        assertTrue("the headset generation is cancelled", headset.cancelled)
        assertEquals(
            listOf(CloudHedge.Event.HeadsetStarted, CloudHedge.Event.Won(CloudHedge.Source.CLOUD)),
            events,
        )
    }

    @Test
    fun `a cloud that ends with no text falls back to the headset`() = runTest {
        val headset = ScriptedHeadset(this)
        val out = CloudHedge.race(
            cloud = flow { emit("  ") }, // blocked prompt or usage-only stream: nothing to say
            headset = headset.flow(),
            hedgeAfterMs = HEDGE_MS,
        ).toList()
        assertEquals(listOf(HEADSET_TEXT), out)
        assertEquals(0L, headset.startedAt)
    }

    @Test
    fun `a headset failure while the cloud is pending waits for the cloud`() = runTest {
        val out = CloudHedge.race(
            cloud = flow { delay(HEDGE_MS + 3_000); emit(CLOUD_TEXT) },
            headset = flow { throw InferenceException("VLM not loaded") },
            hedgeAfterMs = HEDGE_MS,
        ).toList()
        assertEquals(listOf(CLOUD_TEXT), out)
    }

    @Test
    fun `both failing throws, so the controller speaks the failure`() = runTest {
        try {
            CloudHedge.race(
                cloud = flow { throw InferenceException("cloud HTTP 403 PERMISSION_DENIED") },
                headset = flow { delay(100); throw InferenceException("VLM not loaded") },
                hedgeAfterMs = HEDGE_MS,
            ).toList()
            fail("expected InferenceException")
        } catch (e: InferenceException) {
            assertEquals("cloud and headset both failed", e.message)
        }
    }

    @Test
    fun `a cloud answer that breaks part way throws instead of passing a cut sentence as complete`() = runTest {
        val seen = mutableListOf<String>()
        try {
            CloudHedge.race(
                cloud = flow {
                    emit("A hallway with")
                    delay(50)
                    throw InferenceException("cloud describe failed: SocketException: reset")
                },
                headset = ScriptedHeadset(this).flow(),
                hedgeAfterMs = HEDGE_MS,
            ).collect { seen += it }
            fail("expected InferenceException")
        } catch (e: InferenceException) {
            assertEquals(listOf("A hallway with"), seen)
        }
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────────────────────────

    private class Fixture(val engine: CloudDescribeEngine, val headset: FakeInferenceEngine, val lines: List<String>) {
        var encoded = 0
    }

    private fun TestScope.fixture(cloud: CloudDescriber?, online: Boolean): Fixture {
        val headset = FakeInferenceEngine(describeDelayMs = HEADSET_FIRST_MS).apply { streamChunks = listOf(HEADSET_TEXT) }
        val lines = mutableListOf<String>()
        lateinit var fixture: Fixture
        val engine = CloudDescribeEngine(
            headset = headset,
            cloud = cloud,
            isOnline = { online },
            encodeJpeg = { fixture.encoded++; ByteArray(JPEG_BYTES) },
            encodeContext = EmptyCoroutineContext,
            hedgeAfterMs = HEDGE_MS,
            log = { tag, line -> lines += "$tag: $line" },
        )
        fixture = Fixture(engine, headset, lines)
        return fixture
    }

    /** The FakeInferenceEngine's `describeCalls` counts finished runs; this counts starts. */
    private val FakeInferenceEngine.starts: Int get() = if (lastPrompt == null) 0 else 1

    private class FakeCloud(private val script: suspend FlowCollector<String>.() -> Unit) : CloudDescriber {
        override val model: String = "fake-cloud"
        var calls = 0
        var lastPrompt: String? = null

        override fun stream(jpeg: ByteArray, prompt: String): Flow<String> = flow {
            calls++
            lastPrompt = prompt
            assertEquals(JPEG_BYTES, jpeg.size)
            script()
        }
    }

    /** The on-device VLM: [HEADSET_FIRST_MS] of prefill, then one sentence; records when it started and a cancel. */
    private class ScriptedHeadset(private val scope: TestScope) {
        var startedAt: Long? = null
        var cancelled = false

        fun flow(): Flow<String> = flow {
            startedAt = scope.currentTime
            try {
                delay(HEADSET_FIRST_MS)
                emit(HEADSET_TEXT)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private companion object {
        const val PROMPT = "<prompt:describe>"
        const val CLOUD_TEXT = "A hallway, a chair on the left."
        const val HEADSET_TEXT = "A hallway."
        const val HEDGE_MS = CloudDescribeEngine.HEDGE_AFTER_MS
        const val CLOUD_FIRST_MS = 600L
        const val HEADSET_FIRST_MS = 6_000L
        const val JPEG_BYTES = 3

        /** A real `Bitmap` instance as an opaque value (same trick as ModeControllerTest's TestFrameSource). */
        fun still(): Bitmap = Bitmap::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }
}
