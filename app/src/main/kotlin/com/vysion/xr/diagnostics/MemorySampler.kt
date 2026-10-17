package com.vysion.xr.diagnostics

import android.app.ActivityManager
import android.app.Application
import android.os.Debug
import android.os.Process
import android.util.Log

/**
 * Resident memory of this process in MB for the overlay line "App memory" (FR-G5) and the
 * NFR Memory row "App resident memory with VLM loaded ≤ 4 GB".
 *
 * - Provides: the `appMemoryMb` field of [com.vysion.xr.core.DiagnosticsSnapshot]; sampled at 1 Hz by
 *   [DiagnosticsCollector] (Contracts.kt: "Total PSS from ActivityManager.getProcessMemoryInfo, sampled
 *   every second"). Also used by the debug `BenchmarkReceiver` soak samples (`pss_mb`).
 * - Owner: R5. SRS §8: "Diagnostics overlay fed by ... ActivityManager memory", due Sat 18:00.
 * - API: `ActivityManager.getProcessMemoryInfo(int[]): Debug.MemoryInfo[]` (kB) → `MemoryInfo.totalPss`,
 *   docs/stack.md §3 → R5 (VERIFIED, DV-PS + DV-AC).
 * - Assumption: A-15 / A-11 (the `.pte` is `mmap`ed from a real file path), which is exactly why PSS is
 *   the right number, see below.
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
 * Caveat recorded in docs/stack.md → R5: since Android Q `getProcessMemoryInfo` is rate-limited; a call
 * faster than the limit returns the previous sample. A repeated sample is still a measured value, never
 * an invented one; if the overlay number visibly freezes on the headset, the one-line swap is
 * `Debug.getMemoryInfo(Debug.MemoryInfo())` (same `totalPss`, reads /proc/self/smaps directly, not rate
 * limited), see docs/modules/r5-integration.md "Contract issues".
 *
 * Never throws: any failure returns `null`, which renders as "not measured" (INV-7).
 */
class MemorySampler(app: Application) {

    // May be null on the JVM (unit tests with returnDefaultValues) → sampleMb() returns null → "not measured".
    private val activityManager: ActivityManager? = app.getSystemService(ActivityManager::class.java)
    private val ownPid: IntArray = intArrayOf(Process.myPid())

    /** Total PSS of this process in MB, or `null` if it could not be measured. Safe to call from any thread. */
    fun sampleMb(): Int? {
        val am = activityManager ?: return null
        return try {
            // Since Q only the caller's own uid is reported, which is all we ask for (docs/stack.md → R5).
            val infos: Array<Debug.MemoryInfo>? = am.getProcessMemoryInfo(ownPid)
            val info = infos?.firstOrNull() ?: return null
            kbToMb(info.totalPss)
        } catch (t: Throwable) {
            Log.w(TAG, "MemorySampler.sampleMb failed; overlay shows 'not measured'", t)
            null
        }
    }

    companion object {
        private const val TAG = "vysion"

        /** Pure conversion (unit-tested): `Debug.MemoryInfo` reports kB; the overlay prints whole MB. */
        fun kbToMb(kb: Int): Int = kb / 1024
    }
}
