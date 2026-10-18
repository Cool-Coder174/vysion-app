package com.vysion.xr.diagnostics

import android.app.Application
import android.util.Log
import com.vysion.xr.core.DiagnosticsSnapshot
import com.vysion.xr.core.DiagnosticsSource
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceStats
import com.vysion.xr.core.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * The one [DiagnosticsSource] in the app (Contracts.kt → R5; docs/contracts.md "DiagnosticsSource ...
 * provided by R5 `diagnostics.DiagnosticsCollector`, consumed by the R5 overlay and the R1 panel toggle").
 *
 * - Implements: [DiagnosticsSource]. FR-G5 (P0, OD rubric). SRS §8 R5: "Diagnostics overlay fed by
 *   `InferenceEngine` timing hooks and `ActivityManager` memory; shows backend, model, median ms, fps,
 *   MB, 'Network permission: none'", due Sat 18:00. Lands at gate 6 (plan.md task 6.3).
 * - Owner: R5. Constructor signature is fixed by `AppGraph.kt`:
 *   `DiagnosticsCollector(Application, InferenceEngine, FrameSource, CoroutineScope)`.
 * - APIs: none beyond `ActivityManager` ([MemorySampler]) and `PackageManager` ([ManifestAudit]),
 *   docs/stack.md §3 → R5. Everything else is `kotlinx.coroutines.flow`.
 * - Assumptions: A-14 (the pinned build is green) and, for non-null model rows, R2's engine publishing
 *   `vlmInfo` / `detectorInfo` / `stats` (A-13, A-15).
 *
 * What it does: combines `engine.vlmInfo`, `engine.detectorInfo`, `engine.stats`, a 1 Hz memory sample
 * and a one-time manifest audit into one [DiagnosticsSnapshot]. Every field stays `null` until it has
 * been measured (NFR Honesty, INV-7): the overlay renders `null` as "not measured" and no default number
 * exists anywhere in this class. `measuredAtMs` is the only wall-clock value and it is a label, never a
 * latency (H-21: latencies use monotonic clocks, and those are measured inside the engine, not here).
 *
 * It also logs `FrameSource.stats` changes (camera id, size, format, fps; metadata only, never frame
 * content) under tag `vysion` as the A-1 evidence line, and raises the PSS alarm from
 * docs/architecture.md ("alarm at 3.5 GB PSS") under `vysion.reliability` once per crossing.
 *
 * Never throws from the constructor (the app must launch, AGENTS.md): every collection runs inside
 * [scope] and catches its own failures. Only `AppGraph.release()` cancels [scope]; `AppGraph.stop()` (onStop)
 * keeps it, so sampling continues at 1 Hz while the app is stopped.
 */
class DiagnosticsCollector internal constructor(
    private val engine: InferenceEngine,
    private val frames: FrameSource,
    private val scope: CoroutineScope,
    private val sampleMemoryMb: () -> Int?,
    private val auditNetworkPermission: () -> String?,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val memoryPeriodMs: Long = MEMORY_PERIOD_MS,
    /** Monotonic clock (H-21) for aging the detector fps; same clock as `InferenceStats.lastDetectAtNanos`. */
    private val monotonicNanos: () -> Long = System::nanoTime,
) : DiagnosticsSource {

    /** The `AppGraph.kt` constructor: real sampler and audit on the device. */
    constructor(
        app: Application,
        engine: InferenceEngine,
        frames: FrameSource,
        scope: CoroutineScope,
    ) : this(
        engine = engine,
        frames = frames,
        scope = scope,
        sampleMemoryMb = MemorySampler(app)::sampleMb,
        auditNetworkPermission = ManifestAudit(app)::networkPermission,
    )

    private val _snapshot = MutableStateFlow(DiagnosticsSnapshot())

    override val snapshot: StateFlow<DiagnosticsSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch { collectSnapshots() }
        scope.launch { logCameraStats() }
    }

    private suspend fun collectSnapshots() {
        try {
            combine(
                engine.vlmInfo,
                engine.detectorInfo,
                engine.stats,
                memorySamples(),
                manifestAudit(),
            ) { vlm, detector, stats, memoryMb, networkPermission ->
                compose(vlm, detector, stats, memoryMb, networkPermission, wallClockMs(), monotonicNanos())
            }.collect { next ->
                val previous = _snapshot.value
                _snapshot.value = next
                // NFR Memory: budget 4 GB, alarm at 3.5 GB PSS (docs/architecture.md). The app does not react
                // on its own: this line is the signal, and the operator takes the RK-2/RK-3 decision (docs/nfr.md).
                if (crossedMemoryAlarm(previous.appMemoryMb, next.appMemoryMb)) {
                    Log.w(
                        RELIABILITY_TAG,
                        "PSS alarm: ${next.appMemoryMb} MB > $PSS_ALARM_MB MB (NFR Memory budget 4 GB)",
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A stub engine (TODO bodies) or a broken flow must not take the app down; the overlay keeps
            // showing "not measured" for everything that never arrived.
            Log.e(TAG, "DiagnosticsCollector stopped; overlay fields stay 'not measured'", t)
        }
    }

    /** One sample per [memoryPeriodMs] (1 Hz, Contracts.kt). A failed sample is `null`, never 0. */
    private fun memorySamples(): Flow<Int?> = flow {
        while (true) {
            emit(runCatching(sampleMemoryMb).getOrNull())
            delay(memoryPeriodMs)
        }
    }

    /** Once per process: the manifest cannot change while the app runs (ADR-0003). */
    private fun manifestAudit(): Flow<String?> = flow {
        emit(runCatching(auditNetworkPermission).getOrNull())
    }

    /** A-1 evidence: camera id, resolution, format and fps as measured by R1's FrameSource. Metadata only. */
    private suspend fun logCameraStats() {
        try {
            frames.stats.filterNotNull().distinctUntilChanged().collect { s ->
                Log.i(TAG, "camera ${s.cameraId}: ${s.width}x${s.height} ${s.format} at ${s.fps} fps (A-1 evidence)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "FrameSource.stats unavailable; no camera line for A-1 yet", t)
        }
    }

    companion object {
        private const val TAG = "vysion"
        private const val RELIABILITY_TAG = "vysion.reliability"

        /** FpsWindow.DEFAULT_WINDOW_NANOS (R2): no detect for a whole 5 s window → the overlay reads 0.0 fps. */
        const val DETECTOR_FPS_WINDOW_NANOS: Long = 5_000_000_000L

        /** NFR Memory: "sampled every 1 s". */
        const val MEMORY_PERIOD_MS: Long = 1_000L

        /** docs/architecture.md memory strategy: "budget 4 GB (NFR), alarm at 3.5 GB PSS". */
        const val PSS_ALARM_MB: Int = 3_500

        /**
         * Pure composition (unit-tested). Rules, all in service of NFR Honesty:
         * - `backend` comes from the model files' metadata ([ModelInfo.backend]); blank means unknown → `null`.
         *   Two different backends are both shown ("XNNPACK / QNN") so a QNN stretch build is never mistaken
         *   for the shipping XNNPACK one (ADR-0002).
         * - `detectorFps` is `null` until the detector has run once ([InferenceStats.lastDetectMs] is `null`);
         *   after that the engine's 5 s window value, aged by [detectorFpsOf]: 0.0 while the detector is
         *   unloaded during a VLM call (ADR-0004, the engine resets its window) or once no detect ran for a
         *   whole window (Navigation stopped, every detect throwing). A measurement, never a stale number.
         * - `detectorLoaded` (INV-2 monitor) is `null` until the detector's [ModelInfo] is known, then
         *   [InferenceStats.detectorLoaded].
         * - `lastVlmMs` / `medianVlmMs` are passed through: the engine measures them on a monotonic clock.
         */
        internal fun compose(
            vlm: ModelInfo?,
            detector: ModelInfo?,
            stats: InferenceStats,
            appMemoryMb: Int?,
            networkPermission: String?,
            measuredAtMs: Long,
            nowNanos: Long = System.nanoTime(),
        ): DiagnosticsSnapshot = DiagnosticsSnapshot(
            vlm = vlm,
            detector = detector,
            backend = backendOf(vlm, detector),
            lastVlmMs = stats.lastVlmMs,
            medianVlmMs = stats.medianVlmMs,
            detectorFps = detectorFpsOf(stats, nowNanos),
            appMemoryMb = appMemoryMb,
            networkPermission = networkPermission,
            measuredAtMs = measuredAtMs,
            detectorLoaded = if (detector == null) null else stats.detectorLoaded,
        )

        internal fun backendOf(vlm: ModelInfo?, detector: ModelInfo?): String? =
            listOfNotNull(vlm?.backend, detector?.backend)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .takeIf { it.isNotEmpty() }
                ?.joinToString(" / ")

        /**
         * `null` = the detector never ran (not measured); otherwise the engine's 5 s sliding-window fps (FR-N2),
         * or 0 when the last detect is older than [DETECTOR_FPS_WINDOW_NANOS] at [nowNanos] (a dead detector loop
         * must not keep printing its last fps, nor pass the soak row). Also used by the debug BenchmarkRunner.
         */
        internal fun detectorFpsOf(stats: InferenceStats, nowNanos: Long = System.nanoTime()): Float? {
            if (stats.lastDetectMs == null) return null
            val last = stats.lastDetectAtNanos ?: return stats.detectorFps
            return if (nowNanos - last > DETECTOR_FPS_WINDOW_NANOS) 0f else stats.detectorFps
        }

        /** Edge-triggered: true only on the sample that crosses [PSS_ALARM_MB] upwards. */
        internal fun crossedMemoryAlarm(previousMb: Int?, currentMb: Int?): Boolean =
            currentMb != null && currentMb > PSS_ALARM_MB && (previousMb == null || previousMb <= PSS_ALARM_MB)
    }
}
