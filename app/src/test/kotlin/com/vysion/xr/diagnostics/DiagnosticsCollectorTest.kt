package com.vysion.xr.diagnostics

import android.graphics.Bitmap
import com.vysion.xr.core.DiagnosticsSnapshot
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.FrameStats
import com.vysion.xr.core.InferenceStats
import com.vysion.xr.core.ModelInfo
import com.vysion.xr.inference.FakeInferenceEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Snapshot composition (FR-G5, NFR Honesty): nothing is non-null before it was measured, and each source
 * (engine StateFlows, 1 Hz memory sampler, one-time manifest audit) lands in its own field.
 * Runs on the JVM against R2's [FakeInferenceEngine] and a tiny in-test [FrameSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsCollectorTest {

    /** The smallest FrameSource that satisfies the contract; the collector only reads [stats]. */
    private class TestFrameSource : FrameSource {
        override val analysisFrames: Flow<Bitmap> = emptyFlow()
        override val stats = MutableStateFlow<FrameStats?>(null)
        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun captureStill(): Bitmap = error("captureStill is not used by diagnostics")
    }

    private val stats = InferenceStats()
    private val vlm = ModelInfo("vlm", "4-bit", "XNNPACK", 10L, 896, 896)
    private val det = ModelInfo("det", "int8", " XnnpackBackend ", 20L, 640, 640)

    @Test
    fun `compose keeps every unmeasured field null`() {
        val s = DiagnosticsCollector.compose(null, null, InferenceStats(), null, null, measuredAtMs = 7L)
        assertEquals(DiagnosticsSnapshot(measuredAtMs = 7L), s)
    }

    @Test
    fun `backend comes from model metadata, blank is unknown, distinct values are both shown`() {
        assertNull(DiagnosticsCollector.backendOf(null, null))
        assertNull(DiagnosticsCollector.backendOf(ModelInfo("v", "q", "  ", 0, 1, 1), null))
        assertEquals("XNNPACK", DiagnosticsCollector.backendOf(vlm, null))
        assertEquals("XnnpackBackend", DiagnosticsCollector.backendOf(null, det))
        assertEquals("XNNPACK / XnnpackBackend", DiagnosticsCollector.backendOf(vlm, det))
        assertEquals("XNNPACK", DiagnosticsCollector.backendOf(vlm, vlm.copy(name = "other")))
    }

    @Test
    fun `detector fps is not measured until the detector has run once`() {
        assertNull(DiagnosticsCollector.detectorFpsOf(InferenceStats(detectorFps = 0f)))
        assertNull(DiagnosticsCollector.detectorFpsOf(InferenceStats(detectorLoaded = true, detectorFps = 4f)))
        assertEquals(5.5f, DiagnosticsCollector.detectorFpsOf(InferenceStats(lastDetectMs = 30L, detectorFps = 5.5f)))
        // Unloaded during a VLM call (ADR-0004): the window reads 0.0, a measurement, not a placeholder.
        assertEquals(0f, DiagnosticsCollector.detectorFpsOf(InferenceStats(lastDetectMs = 30L, detectorFps = 0f)))
    }

    @Test
    fun `compose passes the engine latencies through unchanged`() {
        val s = DiagnosticsCollector.compose(
            vlm = vlm,
            detector = det,
            stats = stats.copy(lastVlmMs = 4_321L, medianVlmMs = 4_000L, lastDetectMs = 30L, detectorFps = 5.5f),
            appMemoryMb = 2_048,
            networkPermission = "none",
            measuredAtMs = 99L,
        )
        assertEquals(vlm, s.vlm)
        assertEquals(det, s.detector)
        assertEquals(4_321L, s.lastVlmMs)
        assertEquals(4_000L, s.medianVlmMs)
        assertEquals(5.5f, s.detectorFps)
        assertEquals(2_048, s.appMemoryMb)
        assertEquals("none", s.networkPermission)
        assertEquals(99L, s.measuredAtMs)
    }

    @Test
    fun `snapshot is all not-measured at start, then fills in as each source measures`() = runTest {
        val engine = FakeInferenceEngine()
        var memoryMb: Int? = null
        val collector = DiagnosticsCollector(
            engine = engine,
            frames = TestFrameSource(),
            scope = backgroundScope,
            sampleMemoryMb = { memoryMb },
            auditNetworkPermission = { ManifestAudit.NONE },
            wallClockMs = { 1_000L },
            memoryPeriodMs = 1_000L,
        )
        // Before the first combine: the contract's all-null default.
        assertEquals(DiagnosticsSnapshot(), collector.snapshot.value)

        runCurrent()
        val first = collector.snapshot.value
        assertNull(first.vlm)
        assertNull(first.detector)
        assertNull(first.backend)
        assertNull(first.lastVlmMs)
        assertNull(first.medianVlmMs)
        assertNull(first.detectorFps)
        assertNull(first.appMemoryMb)
        assertEquals("none", first.networkPermission) // the audit ran once
        assertEquals(1_000L, first.measuredAtMs)

        // The 1 Hz sampler picks up memory on its next tick.
        memoryMb = 2_048
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(2_048, collector.snapshot.value.appMemoryMb)

        // Model metadata appears once the engine has loaded (FakeInferenceEngine spec: non-null after warmUp()).
        engine.warmUp()
        runCurrent()
        val loaded = collector.snapshot.value
        assertEquals("fake-vlm", loaded.vlm?.name)
        assertEquals("FAKE", loaded.backend)
        assertEquals("none", loaded.networkPermission)
        assertEquals(2_048, loaded.appMemoryMb)
    }

    @Test
    fun `a failing memory sampler yields null, never zero, and does not stop the collector`() = runTest {
        val engine = FakeInferenceEngine()
        var calls = 0
        val collector = DiagnosticsCollector(
            engine = engine,
            frames = TestFrameSource(),
            scope = backgroundScope,
            sampleMemoryMb = { calls++; if (calls == 1) error("ActivityManager unavailable") else 1_500 },
            auditNetworkPermission = { error("PackageManager unavailable") },
            wallClockMs = { 0L },
            memoryPeriodMs = 1_000L,
        )
        runCurrent()
        assertNull(collector.snapshot.value.appMemoryMb)
        assertNull(collector.snapshot.value.networkPermission)
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1_500, collector.snapshot.value.appMemoryMb)
    }
}
