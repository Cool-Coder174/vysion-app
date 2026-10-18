package com.vysion.xr.diagnostics

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.vysion.xr.AppGraph
import com.vysion.xr.VysionApplication
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.File
import java.time.Instant
import kotlin.math.roundToLong

/**
 * Debug-only measurement protocol (SRS §7: "20 runs per model on the headset in airplane mode; report
 * median and p90 latency, peak memory, and model file size"), driven by `scripts/benchmark.sh`.
 *
 * - Declared in `app/src/debug/AndroidManifest.xml` (exported, guarded by `android.permission.DUMP`, which the
 *   shell uid holds and third-party apps cannot get; action `com.vysion.xr.BENCH`); absent from release builds
 *   (plan.md ## Security "Debug BenchmarkReceiver broadcast ... extras are ints/enums").
 * - Owner: R5 (plan.md tasks 2.4, 4.5 with R2, 8.1). SRS §8 R2/R5 "Benchmark script ... results into the
 *   README table", due Sun 10:00. Feeds docs/benchmarks.md.
 * - Contract with the script (its header comment, verbatim fields):
 *   broadcast `am broadcast -a com.vysion.xr.BENCH --es model <vlm|detector|soak> --ei runs <n> --ei seconds <s>`;
 *   result `files/bench/<model>-latest.json` with `model, runs, latencies_ms, median_ms, p90_ms, load_ms,
 *   file_bytes, backend, quantization, max_new_tokens, device, airplane_mode, started_at, finished_at`
 *   and, for soak, `samples[{minute, fps, pss_mb, battery, thermal}]`.
 * - APIs: `InferenceEngine` (core), `FrameSource.captureStill` (core), [MemorySampler] (docs/stack.md → R5),
 *   `Settings.Global.AIRPLANE_MODE_ON`, `BatteryManager.getIntProperty(BATTERY_PROPERTY_CAPACITY)` and
 *   `PowerManager.currentThermalStatus` (API 29). The last three are stable platform APIs that are not
 *   listed in docs/stack.md; verify once on the headset (docs/modules/r5-integration.md).
 * - Assumptions: A-13 / A-15 (a real `.pte` loads) for non-null VLM numbers; with `-PuseFakes=true` the
 *   fakes answer and the JSON carries their (honest) zeros and nulls.
 *
 * Honesty rules (NFR): every latency is `System.nanoTime()` around one engine call (H-21); each VLM run
 * gets a fresh `Bitmap` instance so a prefill cache keyed on the instance (Contracts.kt `describe` KDoc,
 * FR-D2) cannot turn runs 2..n into decode-only numbers; median/p90 are computed here from the list that
 * is written, and the engine's own `medianVlmMs` is logged beside it for cross-checking; a run that fails
 * stops the protocol and the completed runs are written with `runs` = their count, so a half benchmark
 * can never be pasted as a full one. Peak PSS is measured from outside by the script (`dumpsys meminfo`).
 *
 * Privacy (ADR-0003): the only file written is the JSON under `filesDir/bench/`; no frame or pixel data
 * ever reaches it or the log.
 *
 * Threading: `onReceive` runs on the main thread. [BroadcastReceiver.goAsync] keeps the broadcast open
 * while the preflight runs on the graph scope (`Dispatchers.Default`), then the broadcast is finished
 * BEFORE the runs start: a 20-run VLM protocol is minutes long, far past the broadcast window, and the
 * process stays alive because the script launched `MainActivity` first.
 */
class BenchmarkReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val request = BenchmarkRequest.parse(
            model = intent.getStringExtra(EXTRA_MODEL),
            runs = intent.getIntExtra(EXTRA_RUNS, DEFAULT_RUNS),
            seconds = intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SOAK_SECONDS),
        )
        if (request == null) {
            Log.e(TAG, "rejected: model=${intent.getStringExtra(EXTRA_MODEL)} (expected vlm|detector|soak)")
            return
        }
        val app = context.applicationContext as? VysionApplication
        if (app == null) {
            Log.e(TAG, "rejected: application is not VysionApplication")
            return
        }
        val graph: AppGraph = app.graph
        val pending = goAsync()
        graph.scope.launch {
            var finished = false
            try {
                val outDir = File(app.filesDir, BENCH_DIR)
                val runner = BenchmarkRunner(app, graph, outDir)
                Log.i(TAG, "accepted: $request → ${File(outDir, request.model.fileName)}")
                pending.finish() // see class KDoc: the runs outlive the broadcast window
                finished = true
                runner.run(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "benchmark ${request.model} aborted: $t", t)
            } finally {
                if (!finished) pending.finish()
            }
        }
    }

    companion object {
        const val TAG: String = "vysion.bench"
        const val ACTION: String = "com.vysion.xr.BENCH"
        const val EXTRA_MODEL: String = "model"
        const val EXTRA_RUNS: String = "runs"
        const val EXTRA_SECONDS: String = "seconds"
        const val DEFAULT_RUNS: Int = 20
        const val MAX_RUNS: Int = 1_000
        const val DEFAULT_SOAK_SECONDS: Int = 600
        const val BENCH_DIR: String = "bench"
    }
}

/** The three protocols the script can request; `fileName` is what `benchmark.sh` polls for. */
internal enum class BenchmarkModel(val wire: String) {
    VLM("vlm"),
    DETECTOR("detector"),
    SOAK("soak"),
    ;

    val fileName: String get() = "$wire-latest.json"

    companion object {
        fun fromWire(value: String?): BenchmarkModel? = entries.firstOrNull { it.wire == value }
    }
}

internal data class BenchmarkRequest(val model: BenchmarkModel, val runs: Int, val seconds: Int) {
    companion object {
        /** Pure (unit-tested): enum + clamped ints only; anything else is rejected with `null`. */
        fun parse(model: String?, runs: Int, seconds: Int): BenchmarkRequest? {
            val m = BenchmarkModel.fromWire(model) ?: return null
            return BenchmarkRequest(
                model = m,
                runs = runs.coerceIn(1, BenchmarkReceiver.MAX_RUNS),
                seconds = seconds.coerceAtLeast(0),
            )
        }
    }
}

/** Runs one protocol against the live [AppGraph] and writes the JSON the script expects. */
internal class BenchmarkRunner(
    private val app: Application,
    private val graph: AppGraph,
    private val outDir: File,
) {
    private val engine: InferenceEngine get() = graph.inferenceEngine

    /** SRS §7 protocol prompt: READ, from R2's PromptLibrary through `AppGraph.prompts` (no cross-package import). */
    private val readPrompt: String get() = graph.prompts.read
    private val memory = MemorySampler(app)

    suspend fun run(request: BenchmarkRequest) {
        val startedAt = isoNow()
        val body: Map<String, Any?> = when (request.model) {
            BenchmarkModel.VLM -> vlm(request.runs)
            BenchmarkModel.DETECTOR -> detector(request.runs)
            BenchmarkModel.SOAK -> soak(request.seconds)
        }
        val result = LinkedHashMap<String, Any?>(body)
        result["device"] = Build.MODEL
        result["airplane_mode"] = airplaneModeOn()
        result["started_at"] = startedAt
        result["finished_at"] = isoNow()
        write(request.model, result)
    }

    // ── VLM: READ prompt, 60 max tokens, one fresh Bitmap instance per run ────────────────────────────

    private suspend fun vlm(runs: Int): Map<String, Any?> {
        val loadMs = if (engine.stats.value.vlmLoaded) null else timedMs { engine.warmUp() }
        val info = engine.vlmInfo.value
        val base = still(preferredWidth = info?.inputWidth ?: 0, preferredHeight = info?.inputHeight ?: 0)
        val maxNewTokens = InferenceEngine.DEFAULT_MAX_NEW_TOKENS // RK-2 mitigation: cap at 60 (SRS §7 protocol)
        val timed = try {
            timedRuns(runs, perRunTimeoutMs = VLM_RUN_TIMEOUT_MS) {
                // A new instance per run defeats an instance-keyed prefill cache (Contracts.kt describe KDoc).
                val frame = base.copy(Bitmap.Config.ARGB_8888, false)
                try {
                    engine.describe(frame, readPrompt, maxNewTokens)
                } finally {
                    frame.recycle() // INV-9: one owner recycles
                }
            }
        } finally {
            base.recycle()
        }
        val engineMedian = engine.stats.value.medianVlmMs
        Log.i(TAG, "vlm: own median=${BenchmarkStats.median(timed.latenciesMs)} ms, engine median=$engineMedian ms")
        return summary(BenchmarkModel.VLM, timed, loadMs, engine.vlmInfo.value, maxNewTokens)
    }

    // ── Detector: one analysis-sized frame per run ────────────────────────────────────────────────────

    private suspend fun detector(runs: Int): Map<String, Any?> {
        val stats = graph.frameSource.stats.value
        val info = engine.detectorInfo.value
        val base = still(
            preferredWidth = info?.inputWidth ?: stats?.width ?: 0,
            preferredHeight = info?.inputHeight ?: stats?.height ?: 0,
        )
        try {
            // load_ms for the detector = first detect() when it was not loaded yet (load + one inference),
            // because the engine loads the detector lazily (ADR-0004). Documented in docs/benchmarks.md notes.
            val loadMs = if (engine.stats.value.detectorLoaded) null else timedMs { engine.detect(base) }
            val timed = timedRuns(runs, perRunTimeoutMs = DETECT_RUN_TIMEOUT_MS) { engine.detect(base) }
            return summary(BenchmarkModel.DETECTOR, timed, loadMs, engine.detectorInfo.value, maxNewTokens = null)
        } finally {
            base.recycle()
        }
    }

    // ── Soak: Navigation loop through the real command path, one sample every 5 s ────────────────────

    private suspend fun soak(seconds: Int): Map<String, Any?> {
        // NFR Thermal: "10 min of continuous Navigation without detector dropping below 3 fps".
        // GuideMe through the CommandBus so the ModeController owns the loop exactly as for a user (FR-N1/N2).
        graph.commandBus.inject(Command.GuideMe, CommandSource.TEST)
        val samples = ArrayList<Map<String, Any?>>()
        val t0 = System.nanoTime()
        val endNanos = t0 + seconds * NANOS_PER_SECOND
        try {
            while (true) {
                val elapsedMs = (System.nanoTime() - t0) / NANOS_PER_MS
                val sample = sample(elapsedMs)
                samples += sample
                Log.i(TAG, "soak: $sample")
                if (System.nanoTime() >= endNanos) break
                delay(SOAK_SAMPLE_PERIOD_MS)
            }
        } finally {
            // FR-G4: Stop is always honored. Whether Navigation itself ends on Stop is ModeController policy (R1).
            graph.commandBus.inject(Command.Stop, CommandSource.TEST)
        }
        return linkedMapOf("model" to BenchmarkModel.SOAK.wire, "samples" to samples)
    }

    private fun sample(elapsedMs: Long): Map<String, Any?> = linkedMapOf(
        "minute" to BenchmarkStats.minutes(elapsedMs),
        "fps" to DiagnosticsCollector.detectorFpsOf(engine.stats.value, System.nanoTime()), // 0 if detect stalled
        "pss_mb" to memory.sampleMb(),
        "battery" to batteryPercent(),
        "thermal" to thermalStatus(),
    )

    // ── Shared pieces ─────────────────────────────────────────────────────────────────────────────────

    private class TimedRuns(val latenciesMs: List<Long>, val failure: Throwable?)

    private suspend fun timedRuns(runs: Int, perRunTimeoutMs: Long, body: suspend () -> Unit): TimedRuns {
        val latencies = ArrayList<Long>(runs)
        for (i in 1..runs) {
            try {
                val ms = timedMs { withTimeout(perRunTimeoutMs) { body() } }
                latencies += ms
                Log.i(TAG, "run $i/$runs: $ms ms")
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "run $i/$runs: timed out after $perRunTimeoutMs ms; stopping, ${latencies.size} runs kept")
                return TimedRuns(latencies, e)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "run $i/$runs failed: $t; stopping, ${latencies.size} runs kept", t)
                return TimedRuns(latencies, t)
            }
        }
        return TimedRuns(latencies, null)
    }

    /** Monotonic wall time of one call in whole ms (H-21). */
    private suspend inline fun timedMs(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / NANOS_PER_MS
    }

    private fun summary(
        model: BenchmarkModel,
        timed: TimedRuns,
        loadMs: Long?,
        info: ModelInfo?,
        maxNewTokens: Int?,
    ): Map<String, Any?> = linkedMapOf(
        "model" to model.wire,
        "runs" to timed.latenciesMs.size,
        "latencies_ms" to timed.latenciesMs,
        "median_ms" to BenchmarkStats.median(timed.latenciesMs),
        "p90_ms" to BenchmarkStats.p90(timed.latenciesMs),
        "load_ms" to loadMs,
        "file_bytes" to info?.fileSizeBytes?.takeIf { it > 0 },
        "backend" to info?.backend?.takeIf { it.isNotBlank() },
        "quantization" to info?.quantization?.takeIf { it.isNotBlank() },
        "max_new_tokens" to maxNewTokens,
    )

    /**
     * The input frame: the real pipeline's still when it works (headset, or `FakeFrameSource` with fakes),
     * else a synthetic flat bitmap at the model's input size. Which one was used is logged, never written.
     */
    private suspend fun still(preferredWidth: Int, preferredHeight: Int): Bitmap {
        val captured = runCatching { graph.frameSource.captureStill() }.getOrNull()
        if (captured != null) {
            Log.i(TAG, "input: FrameSource.captureStill ${captured.width}x${captured.height}")
            return captured
        }
        val w = if (preferredWidth > 0) preferredWidth else FALLBACK_WIDTH
        val h = if (preferredHeight > 0) preferredHeight else FALLBACK_HEIGHT
        Log.w(TAG, "input: captureStill unavailable; synthetic ${w}x${h} frame (latency is still real, content is not)")
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
    }

    private fun write(model: BenchmarkModel, result: Map<String, Any?>) {
        outDir.mkdirs()
        val target = File(outDir, model.fileName)
        val part = File(outDir, "${model.fileName}.part")
        part.writeText(BenchmarkJson.render(result) + "\n") // privacy-allow: benchmark JSON, no frame data
        if (!part.renameTo(target)) {
            target.writeText(BenchmarkJson.render(result) + "\n") // privacy-allow: benchmark JSON, no frame data
            part.delete()
        }
        Log.i(
            TAG,
            "wrote ${target.path}: runs=${result["runs"]} median_ms=${result["median_ms"]} p90_ms=${result["p90_ms"]}",
        )
    }

    private fun airplaneModeOn(): Boolean = runCatching {
        Settings.Global.getInt(app.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
    }.getOrDefault(false)

    private fun batteryPercent(): Int? = runCatching {
        val bm = app.getSystemService(BatteryManager::class.java) ?: return null
        bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it != Int.MIN_VALUE }
    }.getOrNull()

    private fun thermalStatus(): String? = runCatching {
        val pm = app.getSystemService(PowerManager::class.java) ?: return null
        BenchmarkStats.thermalName(pm.currentThermalStatus) // API 29; Galaxy XR is API 34 (D9)
    }.getOrNull()

    /** The only wall-clock use: `started_at` / `finished_at` labels for the row (never a latency). */
    private fun isoNow(): String = Instant.ofEpochMilli(System.currentTimeMillis()).toString()

    companion object {
        private const val TAG = BenchmarkReceiver.TAG
        private const val NANOS_PER_MS = 1_000_000L
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /**
         * Per-run cap: 4× the product watchdog (Contracts.kt VLM_TIMEOUT_MS) so a bad miss is still measured,
         * not hidden.
         */
        const val VLM_RUN_TIMEOUT_MS: Long = 4 * InferenceEngine.VLM_TIMEOUT_MS
        const val DETECT_RUN_TIMEOUT_MS: Long = 10_000L

        /** nfr.md Thermal: "records fps every 5 s". */
        const val SOAK_SAMPLE_PERIOD_MS: Long = 5_000L

        /** SRS §6 analysis stream size, used only when neither the model nor the camera reports a size. */
        private const val FALLBACK_WIDTH = 640
        private const val FALLBACK_HEIGHT = 480
    }
}

/**
 * Pure statistics for the JSON (unit-tested). Definitions are stated so a row in docs/benchmarks.md is
 * reproducible.
 */
internal object BenchmarkStats {
    /** Standard median: middle value, or the mean of the two middle values for an even count. `null` when empty. */
    fun median(values: List<Long>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toDouble() else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** Nearest-rank p90: the ceil(0.9·n)-th smallest value (n = 20 → the 18th). `null` when empty. */
    fun p90(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val rank = (9 * sorted.size + 9) / 10 // = ceil(9n/10) in integer arithmetic
        return sorted[rank - 1]
    }

    /** Elapsed minutes with two decimals for the soak `minute` column (5 s samples → 0.08, 0.17, …). */
    fun minutes(elapsedMs: Long): Double = (elapsedMs / 60_000.0 * 100.0).roundToLong() / 100.0

    /** `PowerManager.THERMAL_STATUS_*` (API 29) by value; literals so this object stays JVM-pure. */
    fun thermalName(status: Int): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown($status)"
    }
}

/**
 * Minimal JSON writer (unit-tested): maps keep insertion order, strings are escaped, non-finite doubles
 * become `null`. Deliberately not `org.json`: that class is a stub on the JVM test classpath.
 */
internal object BenchmarkJson {
    fun render(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value)
            is Int, is Long, is Short, is Byte -> sb.append(value)
            is Float -> appendDouble(sb, value.toDouble())
            is Double -> appendDouble(sb, value)
            is String -> appendString(sb, value)
            is Map<*, *> -> appendObject(sb, value)
            is Iterable<*> -> appendArray(sb, value)
            is Array<*> -> appendArray(sb, value.asIterable())
            else -> appendString(sb, value.toString())
        }
    }

    private fun appendDouble(sb: StringBuilder, d: Double) {
        if (d.isNaN() || d.isInfinite()) sb.append("null") else sb.append(d)
    }

    private fun appendObject(sb: StringBuilder, map: Map<*, *>) {
        sb.append('{')
        var first = true
        for ((k, v) in map) {
            if (!first) sb.append(',')
            first = false
            appendString(sb, k.toString())
            sb.append(':')
            append(sb, v)
        }
        sb.append('}')
    }

    private fun appendArray(sb: StringBuilder, items: Iterable<*>) {
        sb.append('[')
        var first = true
        for (item in items) {
            if (!first) sb.append(',')
            first = false
            append(sb, item)
        }
        sb.append(']')
    }

    private fun appendString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
