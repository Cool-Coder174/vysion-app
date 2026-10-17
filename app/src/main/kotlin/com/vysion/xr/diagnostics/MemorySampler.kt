package com.vysion.xr.diagnostics

import android.app.Application
import android.os.Debug
import android.util.Log

/**
 * Resident memory of this process in MB for the overlay line "App memory" (FR-G5) and the
 * NFR Memory row "App resident memory with VLM loaded ≤ 4 GB".
 *
 * - Provides: the `appMemoryMb` field of [com.vysion.xr.core.DiagnosticsSnapshot]; sampled at 1 Hz by
 *   [DiagnosticsCollector] (Contracts.kt: "Total PSS of this process from `Debug.getPss()`, sampled every
 *   second"). Also used by the debug `BenchmarkReceiver` soak samples (`pss_mb`) and the 3.5 GB PSS alarm.
 * - Owner: R5. SRS §8: "Diagnostics overlay fed by ... ActivityManager memory", due Sat 18:00.
 * - API: `android.os.Debug.getPss()` (API 1, kB, the calling process only), docs/stack.md §3 → R5.
 * - Assumption: A-15 / A-11 (the `.pte` is `mmap`ed from a real file path), which is exactly why PSS is
 *   the right number, see below.
 *
 * Why `Debug.getPss()` and not `ActivityManager.getProcessMemoryInfo`: since Android Q the ActivityManager
 * call is throttled for apps; AOSP returns the cached sample of the calling process for
 * `MEMORY_INFO_THROTTLE_TIME` (5 minutes by default). The overlay row, the PSS alarm and the soak `pss_mb`
 * column could then show the pre-VLM-load number for the whole demo. `Debug.getPss()` reads this process's
 * smaps directly on every call and is not rate-limited; it is the same total PSS.
 *
 * Why total PSS and not the Runtime heap:
 * - ExecuTorch loads the `.pte` with `LOAD_MODE_MMAP` (docs/stack.md §3 → R2). The weights are resident
 *   file-backed pages, not `malloc` memory and not Java objects. `Runtime.totalMemory()` is the Java heap
 *   only and `Debug.getNativeHeapAllocatedSize()` is malloc only: both would print a number that excludes
 *   the model, the single largest allocation (NFR Honesty).
 * - PSS (proportional set size) counts resident pages including file-backed mmapped ones, with shared
 *   pages divided among sharers, so it neither hides the model nor double-counts shared libraries. It is
 *   also the "TOTAL PSS" line of `dumpsys meminfo`, which `scripts/benchmark.sh` samples from outside,
 *   so the overlay and the benchmark table are the same metric.
 * - `Debug.getRss()` is API 35 and the Galaxy XR is API 34 (docs/stack.md D3, D9), so RSS is not used.
 *
 * Cost: reading smaps takes a few milliseconds; it runs on the collector's `Dispatchers.Default` 1 Hz tick,
 * never on main.
 *
 * Never throws: any failure returns `null`, which renders as "not measured" (INV-7). A zero reading (the
 * JVM unit-test stub, or a failed read) is also `null`: a live process never has 0 kB PSS.
 */
class MemorySampler(
    // Kept so DiagnosticsCollector and the debug BenchmarkReceiver construct it unchanged;
    // Debug.getPss needs no Context.
    @Suppress("UNUSED_PARAMETER") app: Application,
) {

    /** Total PSS of this process in MB, or `null` if it could not be measured. Safe to call from any thread. */
    fun sampleMb(): Int? = try {
        val kb = Debug.getPss()
        if (kb <= 0L) null else kbToMb(kb)
    } catch (t: Throwable) {
        Log.w(TAG, "MemorySampler.sampleMb failed; overlay shows 'not measured'", t)
        null
    }

    companion object {
        private const val TAG = "vysion"

        /** Pure conversion (unit-tested): `Debug` reports kB; the overlay prints whole MB. */
        fun kbToMb(kb: Int): Int = kb / 1024

        /** As [kbToMb] for `Debug.getPss()`'s `Long`. */
        fun kbToMb(kb: Long): Int = (kb / 1024L).toInt()
    }
}
