package com.vysion.xr.perception

import android.os.SystemClock
import android.util.Log
import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.Detection
import com.vysion.xr.core.Distances
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.HeadPoseSource
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.NavigationEngine
import com.vysion.xr.core.RelativeTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Monotonic clock for every R3 latency (H-21): never `System.currentTimeMillis()`. */
internal object Clocks {
    val elapsedRealtimeNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() }
}

/** Per-key repeat gate: `allow(key)` is true at most once per [periodNanos]. Pure, used by the plane loop. */
internal class RepeatGate(private val clock: () -> Long, private val periodNanos: Long) {
    private val last = HashMap<Any, Long>()

    fun allow(key: Any): Boolean {
        val now = clock()
        val prev = last[key]
        if (prev != null && now - prev < periodNanos) return false
        last[key] = now
        return true
    }

    fun reset() = last.clear()
}

/**
 * Implements [NavigationEngine] (core): the Navigation background loop (FR-N1..FR-N6). Constructor
 * matches `AppGraph`:
 * `(XrPerceptionSession, FrameSource, InferenceEngine, AnchorStore, HeadPoseSource, CoroutineScope)`.
 *
 * - Owner: R3. SRS due Sat 19:00 ("`NavigationCues` flow: proximity, door bearing in clock-face form,
 *   distance in meters rounded to 0.5"), Sat 21:00 (spatial ping), Sun 09:00 / 10:00 (planes, FR-N4 test).
 * - Consumers: R1 `ModeController` (`cues` → R4 `Cues.kt` words, `pause`/`resume` around every one-shot
 *   command, ADR-0004), R1 `OverlayRenderer` (`detections`).
 * - API: no XR call lives here; device input arrives through [PerceptionSource] (docs/stack.md → R3) and
 *   `FrameSource.analysisFrames` (R1). Assumptions A-5, A-6 (via [SpatialAudioPinger]).
 *
 * Structure (real code; only the device calls behind [PerceptionSource] / [AnchorPinger] are stubs):
 * - [start] launches ONE [Job] (idempotent: a second call while running is a no-op, H-39) whose
 *   `supervisorScope` runs four loops; a loop that dies logs under `vysion.reliability` and does not take
 *   the others with it (fault model in docs/nfr.md).
 * - proximity loop: `depthFrames.conflate()` → [ProximityMonitor] at 10 Hz ([PROXIMITY_PERIOD_MS]) →
 *   `Proximity` cue; logs the depth-sample-to-cue latency under `vysion.latency` (NFR ≤ 300 ms).
 * - plane loop (FR-N5, P1): planes + head pose → [PlaneSemantics] every [PLANE_PERIOD_MS], each cue type
 *   at most once per [PLANE_CUE_REPEAT_NANOS].
 * - detector loop (FR-N2): `analysisFrames.conflate()` → `engine.detect` → [detections]; frames are
 *   skipped (dropped, never queued) while paused so the detector never competes with the VLM (ADR-0004,
 *   INV-1/2). Bitmaps are owned by R1's source (S-53): never recycled here, never logged (INV-3).
 * - guidance loop (FR-N3/N4/N6): every [GUIDANCE_PERIOD_MS] while a target is set: [AnchorPinger.ping] +
 *   `DoorBearing(…, pinged)`; `pinged` is false when the spatial ping could not play (A-6), and then the
 *   period drops to [FALLBACK_GUIDANCE_PERIOD_MS] (the A-6 fallback; R1 plays `Earcon.PING` per cue).
 *   `Arrived` once when distance ≤ [Distances.ARRIVAL_RADIUS_M]; `TargetLost` when `track()` completes
 *   or no sample arrived for [TRACK_STALE_NANOS].
 * - [pause] / [resume] gate every loop through a `StateFlow<Boolean>` without cancelling the Job;
 *   [detections] is empty while paused; nothing that fell due during a pause is delivered late (the
 *   guidance cadence restarts one full period after [resume]). [stop] cancels the Job and joins it.
 * - [cues] is a `SharedFlow(extraBufferCapacity = 16, DROP_OLDEST)`: a slow speaker loses the oldest
 *   cue, never blocks a loop (H-33). R4 phrases every cue; no string is built here (INV-4).
 */
class XrNavigationEngine internal constructor(
    private val source: PerceptionSource,
    private val frames: FrameSource,
    private val engine: InferenceEngine,
    private val anchors: AnchorStore,
    private val headPose: HeadPoseSource,
    private val scope: CoroutineScope,
    private val pinger: AnchorPinger,
    private val clock: () -> Long,
    private val proximity: ProximityMonitor,
) : NavigationEngine {

    constructor(
        session: XrPerceptionSession,
        frames: FrameSource,
        engine: InferenceEngine,
        anchors: AnchorStore,
        headPose: HeadPoseSource,
        scope: CoroutineScope,
    ) : this(
        source = session,
        frames = frames,
        engine = engine,
        anchors = anchors,
        headPose = headPose,
        scope = scope,
        pinger = SpatialAudioPinger(session),
        clock = Clocks.elapsedRealtimeNanos,
        proximity = ProximityMonitor(clock = Clocks.elapsedRealtimeNanos),
    )

    private val _cues = MutableSharedFlow<NavigationCue>(
        replay = 0,
        extraBufferCapacity = CUE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val cues: Flow<NavigationCue> = _cues.asSharedFlow()

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    override val detections: Flow<List<Detection>> = _detections.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** The gate every loop waits on; flipped by [pause] / [resume], never cancels anything. */
    private val paused = MutableStateFlow(false)

    /** When the loops last (re)started; the guidance staleness clock never counts a pause as silence. */
    @Volatile
    private var resumedAtNanos: Long = 0L

    private val lifecycle = Mutex()
    private var job: Job? = null

    /** The door target: the latest relative sample from `anchors.track`, and whether tracking ended. */
    private class Target(val id: AnchorId, sinceNanos: Long) {
        val latest = MutableStateFlow<RelativeTarget?>(null)

        @Volatile
        var lastSampleNanos: Long = sinceNanos

        @Volatile
        var lost: Boolean = false
        var collector: Job? = null
    }

    private val target = MutableStateFlow<Target?>(null)

    /** Idempotent (H-39): one Job; a running engine ignores the call. Never throws. */
    override suspend fun start() {
        lifecycle.withLock {
            if (job?.isActive == true) return
            source.ensureStarted()
            paused.value = false
            resumedAtNanos = clock()
            proximity.reset()
            val j = scope.launch {
                supervisorScope {
                    launch { guarded("proximity") { proximityLoop() } }
                    launch { guarded("planes") { planeLoop() } }
                    launch { guarded("detector") { detectorLoop() } }
                    launch { guarded("guidance") { guidanceLoop() } }
                }
            }
            j.invokeOnCompletion {
                _isRunning.value = false
                _detections.value = emptyList()
            }
            job = j
            _isRunning.value = true
        }
    }

    /** ADR-0004: called by R1 before any one-shot command. Loops keep their state; outlines clear. */
    override suspend fun pause() {
        paused.value = true
        _detections.value = emptyList()
    }

    override suspend fun resume() {
        resumedAtNanos = clock()
        paused.value = false
    }

    override suspend fun stop() {
        lifecycle.withLock {
            val j = job ?: return
            job = null
            clearTarget()
            // Frees the SoundPool R3 creates in attach(); attach() recreates it lazily, so stop stays reversible.
            pinger.release()
            j.cancelAndJoin()
            paused.value = false
            _isRunning.value = false
            _detections.value = emptyList()
        }
    }

    /** FR-N3: start tracking [anchor]; the guidance loop speaks and pings from the next tick. */
    override suspend fun guideTo(anchor: AnchorId) {
        clearTarget()
        val t = Target(anchor, clock())
        t.collector = scope.launch {
            anchors.track(anchor).collect { rel ->
                t.latest.value = rel
                t.lastSampleNanos = clock()
            }
            t.lost = true // track() completed: anchor detached or tracking stopped (FR-N4 TargetLost)
        }
        target.value = t
        pinger.attach(anchor)
    }

    override suspend fun clearTarget() {
        val t = target.getAndUpdate { null } ?: return
        t.collector?.cancel()
        pinger.detach()
    }

    // ── loops ────────────────────────────────────────────────────────────────────────────────────

    private suspend fun awaitResumed() {
        paused.first { !it }
    }

    private suspend fun guarded(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fault model (docs/nfr.md): one loop dies, Navigation continues on the others.
            Log.e(TAG_RELIABILITY, "navigation $name loop stopped: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** FR-N1 at 10 Hz: latest depth map → ProximityMonitor; latency logged per cue (NFR ≤ 300 ms). */
    private suspend fun proximityLoop() {
        source.depthFrames.conflate().collect { depth ->
            awaitResumed()
            val cue = proximity.onDepth(depth)
            if (cue != null) {
                val sampledAt = depth.timestampNanos
                val latencyMs = (clock() - sampledAt) / 1_000_000.0
                _cues.tryEmit(cue)
                Log.i(
                    TAG_LATENCY,
                    // Depth copy → cue emitted only. The NFR end point is the earcon start, which R1 plays later
                    // (docs/nfr.md "depth sample timestamp vs. earcon start"); this line cannot show that delay.
                    "proximity cue emitted %.1f ms after depth copy (earcon not included; FR-N1 target <= 300 ms)"
                        .format(latencyMs),
                )
            }
            delay(PROXIMITY_PERIOD_MS) // conflate() keeps only the newest map meanwhile
        }
    }

    /**
     * FR-N5 (P1): every [PLANE_PERIOD_MS], wall-ahead / floor-ends from the latest tracked planes on the
     * current heading.
     */
    private suspend fun planeLoop() {
        val gate = RepeatGate(clock, PLANE_CUE_REPEAT_NANOS)
        while (currentCoroutineContext().isActive) {
            awaitResumed()
            val head = source.headPose.value
            if (head != null && head.tracking == Tracking.TRACKING) {
                for (cue in PlaneSemantics.evaluate(source.planes.value, head)) {
                    if (gate.allow(cue::class)) _cues.tryEmit(cue)
                }
            }
            delay(PLANE_PERIOD_MS)
        }
    }

    /** FR-N2: detector on the latest analysis frame; skipped while paused (ADR-0004). */
    private suspend fun detectorLoop() {
        frames.analysisFrames.conflate().collect { bitmap ->
            if (paused.value) return@collect // dropped, never queued: the VLM owns the engine now
            val result = try {
                engine.detect(bitmap)
            } catch (e: InferenceException) {
                Log.w(TAG_RELIABILITY, "detector failed (${e.message}); Navigation continues on depth only")
                emptyList()
            }
            if (!paused.value) _detections.value = result
        }
    }

    /**
     * FR-N3/N4/N6: one tick every [guidancePeriodMs] of RUNNING time while a target is set. The cadence
     * restarts on every resume: a tick that fell due during a pause is dropped, never delivered late
     * (same rule as the detector loop), so the first bearing after a one-shot command comes one full
     * period after `resume()` instead of right on top of the controller's own speech.
     */
    private suspend fun guidanceLoop() {
        paused.collectLatest { isPaused ->
            if (isPaused) return@collectLatest // cancelled and restarted by the next resume()
            while (currentCoroutineContext().isActive) {
                delay(guidancePeriodMs())
                if (!paused.value) target.value?.let { tick(it) }
            }
        }
    }

    /** A-6 fallback ("say the bearing more often, every 2 s") while the spatial ping cannot play. */
    private fun guidancePeriodMs(): Long =
        if (pinger.isSpatial.value == false) FALLBACK_GUIDANCE_PERIOD_MS else GUIDANCE_PERIOD_MS

    private suspend fun tick(t: Target) {
        val now = clock()
        val latest = t.latest.value
        val freshAt = maxOf(t.lastSampleNanos, resumedAtNanos)
        when {
            t.lost || now - freshAt > TRACK_STALE_NANOS -> {
                _cues.tryEmit(NavigationCue.TargetLost)
                clearTarget()
            }
            latest == null -> Unit // waiting for the first tracked sample
            latest.distanceM <= Distances.ARRIVAL_RADIUS_M -> { // FR-N6: once, then the controller runs ARRIVAL
                _cues.tryEmit(NavigationCue.Arrived)
                clearTarget()
            }
            else -> {
                pinger.ping() // FR-N3: spatial ping from the anchor on the same cadence
                // A-6: read after ping() so a ping that could not play on THIS tick already says so; null
                // (not probed yet) counts as pinged. R1 plays Earcon.PING through the Speaker when false.
                val pinged = pinger.isSpatial.value != false
                _cues.tryEmit(NavigationCue.DoorBearing(latest.clock, latest.distanceM, latest.inView, pinged))
            }
        }
    }

    companion object {
        const val TAG_LATENCY: String = "vysion.latency"
        const val TAG_RELIABILITY: String = "vysion.reliability"

        /** SRS R3: "sample the depth texture … at 10 Hz". */
        const val PROXIMITY_PERIOD_MS: Long = 100

        /** FR-N3: "speaks bearing and distance every 3 s with a spatial-audio ping". */
        const val GUIDANCE_PERIOD_MS: Long = 3_000

        /** A-6 failed (docs/assumptions.md): "non-spatial earcon …; say the bearing more often (every 2 s)". */
        const val FALLBACK_GUIDANCE_PERIOD_MS: Long = 2_000

        /** Plane semantics cadence (FR-N5); not an SRS number, not displayed. */
        const val PLANE_PERIOD_MS: Long = 500

        /** A wall/floor cue of one type repeats at most this often (FR-N5; skeleton choice, R4 may tune). */
        const val PLANE_CUE_REPEAT_NANOS: Long = 3_000_000_000L

        /** Two (spatial) guidance periods without an anchor sample (pauses excluded) → `TargetLost`. */
        const val TRACK_STALE_NANOS: Long = 2 * GUIDANCE_PERIOD_MS * 1_000_000L

        /** `cues` buffer; the oldest cue is dropped when R1 is slower than the loops (H-33). */
        const val CUE_BUFFER: Int = 16
    }
}
