package com.vysion.xr.inference

/**
 * Bounded ring of latencies with median and p90, feeding `InferenceStats.lastVlmMs / medianVlmMs / p90VlmMs`
 * (FR-G5 diagnostics overlay; SRS §7 measurement protocol "median and p90 latency").
 *
 * - Contract provided: none directly; the engine copies [snapshot] into `com.vysion.xr.core.InferenceStats`.
 * - FR ids: FR-G5 (overlay fields), NFR-latency (Text Reader ≤ 6 s, Describe ≤ 8 s, reported as median/p90).
 * - SRS §8 R2 due: "timing hooks for the overlay. Sat 16:00".
 * - Owner: R2 ML / ExecuTorch.
 * - API: none (pure Kotlin, JVM-tested). Callers measure with `System.nanoTime()` or
 *   `SystemClock.elapsedRealtimeNanos()` (AGENTS.md §5 "monotonic clocks only", H-21) and record milliseconds.
 * - Assumption: none.
 *
 * Percentiles use the nearest-rank method (`sorted[ceil(p·n) − 1]`), so every reported value is a latency that
 * was actually observed, never an interpolated one (NFR "Honesty"). The window is the last [capacity] samples;
 * [count] keeps growing past it so the overlay can show how many runs the numbers rest on.
 */
class LatencyStats(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive: $capacity" }
    }

    private val ring = LongArray(capacity)
    private var size = 0
    private var next = 0

    /** Total samples ever recorded (may exceed [capacity]). Maps to `InferenceStats.vlmRuns`. */
    var count: Int = 0
        private set

    /** The most recent sample, `null` until one is recorded ("not measured"). */
    val last: Long?
        @Synchronized get() = if (size == 0) null else ring[(next - 1 + capacity) % capacity]

    @Synchronized
    fun record(ms: Long) {
        ring[next] = ms
        next = (next + 1) % capacity
        if (size < capacity) size++
        count++
    }

    /** Nearest-rank 50th percentile over the window, `null` when empty. */
    fun median(): Long? = percentile(0.5)

    /** Nearest-rank 90th percentile over the window, `null` when empty. */
    fun p90(): Long? = percentile(0.9)

    /**
     * Nearest-rank percentile: the smallest value such that at least `p` of the window is ≤ it.
     * @param p in (0, 1].
     */
    @Synchronized
    fun percentile(p: Double): Long? {
        require(p > 0.0 && p <= 1.0) { "p must be in (0, 1]: $p" }
        if (size == 0) return null
        val sorted = ring.copyOf(size).also { it.sort() }
        val rank = kotlin.math.ceil(p * size).toInt().coerceIn(1, size)
        return sorted[rank - 1]
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(last = last, median = median(), p90 = p90(), count = count)

    @Synchronized
    fun reset() {
        size = 0
        next = 0
        count = 0
    }

    /** `null` fields mean "not measured" and render as such (INV-7). */
    data class Snapshot(
        val last: Long?,
        val median: Long?,
        val p90: Long?,
        val count: Int,
    )

    companion object {
        /** 64 samples: three 20-run benchmark passes fit; memory is one `LongArray`. */
        const val DEFAULT_CAPACITY: Int = 64
    }
}

/**
 * Frames per second over a sliding time window, for `InferenceStats.detectorFps` (FR-N2 "5 fps or better",
 * NFR-latency "frame counter inside `InferenceEngine.detect()`, 5 s sliding window").
 *
 * Pure: the caller passes monotonic timestamps (`System.nanoTime()`), so unit tests drive it with a fake clock.
 * The rate is `(frames − 1) / (newest − oldest)` over the frames inside the window: an average over the span
 * actually observed, `0f` until two frames exist (the overlay treats `0f` as "no detector activity").
 */
class FpsWindow(private val windowNanos: Long = DEFAULT_WINDOW_NANOS) {

    init {
        require(windowNanos > 0) { "windowNanos must be positive: $windowNanos" }
    }

    private val stamps = ArrayDeque<Long>()

    /** Record one completed frame at [nowNanos] (monotonic) and return the fps over the window. */
    @Synchronized
    fun record(nowNanos: Long): Float {
        stamps.addLast(nowNanos)
        prune(nowNanos)
        return fpsLocked()
    }

    /** Current fps without recording; frames older than the window are dropped first. */
    @Synchronized
    fun fps(nowNanos: Long): Float {
        prune(nowNanos)
        return fpsLocked()
    }

    @Synchronized
    fun reset() = stamps.clear()

    private fun prune(nowNanos: Long) {
        val cutoff = nowNanos - windowNanos
        while (stamps.isNotEmpty() && stamps.first() < cutoff) stamps.removeFirst()
    }

    private fun fpsLocked(): Float {
        if (stamps.size < 2) return 0f
        val span = stamps.last() - stamps.first()
        if (span <= 0L) return 0f
        return (stamps.size - 1) * NANOS_PER_SECOND / span.toFloat()
    }

    companion object {
        const val NANOS_PER_SECOND: Float = 1_000_000_000f

        /** NFR-latency: "5 s sliding window". */
        const val DEFAULT_WINDOW_NANOS: Long = 5_000_000_000L
    }
}
