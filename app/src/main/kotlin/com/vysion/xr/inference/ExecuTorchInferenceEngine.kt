package com.vysion.xr.inference

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import com.vysion.xr.core.Detection
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.InferenceStats
import com.vysion.xr.core.ModelInfo
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The one [InferenceEngine] (ADR-0004): both models, one [Mutex], detector unloaded while the VLM generates.
 *
 * - Contract provided: [InferenceEngine] (`docs/contracts.md` gate 4, Sat 16:00: "InferenceEngine with
 *   describe(bitmap, prompt) and detect(bitmap) running on the headset"). Constructor
 *   `(Application, ModelCatalog, CoroutineScope)` per `AppGraph.kt`. Consumed by R1 `ModeController`,
 *   R3 `NavigationEngine`, R5 `DiagnosticsCollector`.
 * - FR ids: FR-T1, FR-D1, FR-N3, FR-N6 ([describe]); FR-D2, FR-T4 (prefill cache for the same `Bitmap`);
 *   FR-N2 ([detect] ≥ 5 fps, `detectorFps`); FR-G5 (timing hooks, backend, model info); FR-G4 (`LlmModule.stop()`
 *   on cancellation so "stop" is not blocked by a running generate).
 * - SRS §8 R2 due: "`InferenceEngine`: `describe(bitmap, prompt): String`, `detect(bitmap): List<Detection>`,
 *   load/unload lifecycle, timing hooks for the overlay. Sat 16:00".
 * - Owner: R2 ML / ExecuTorch.
 * - API (`docs/stack.md` §3-R2, verified at tag v1.3.1): VLM through
 *   `LlmModule(LlmModuleConfig.create().modulePath(..).tokenizerPath(..).modelType(MODEL_TYPE_MULTIMODAL)
 *   .temperature(0f).loadMode(LOAD_MODE_MMAP).build())`, `load()`, `resetContext()`, `prefillImages(IntArray
 *   uint8 CHW, w, h, 3)`, `generate(prompt, seqLen, LlmCallback, echo = false)`, `stop()`, `close()`; detector
 *   through `Module.load(path, LOAD_MODE_MMAP, numThreads)`, `forward(EValue.from(Tensor.fromBlob(chw,
 *   longArrayOf(1, 3, h, w))))[0].toTensor()`, `getMethodMetadata("forward").getBackends()`, `close()`.
 *   `seqLen` is an absolute position budget (prefilled tokens + new tokens); `maxNewTokens` is not forwarded in
 *   1.3.1, so each [VlmRunner] caps the answer itself and stops with a lock-free `stop()` (VlmRunners.kt).
 * - Assumptions: A-3 (the Maven AAR has `LlmModule` + XNNPACK), A-13 / Q9 (the chosen VLM `.pte` loads with
 *   `MODEL_TYPE_MULTIMODAL` and answers on the headset; the SRS Gemma 4 export does NOT, `docs/stack.md` D5),
 *   A-15 / Q20 (multi-GB `.pte` in the external files dir loads with mmap), Q7 (detector loads on the 1.3.1 AAR).
 *
 * Status: every runtime call is real (ExecuTorch 1.3.1 AAR, signatures checked with `javap`). Model-specific facts
 * come from the lockfile row, not from code: the detector's `classes`, the VLM's `chat_before_image` /
 * `chat_template` and `image_mean` / `image_std`. With no model files, [describe] and [detect] throw
 * [InferenceException] ("not loaded") and the controller speaks `Cues.inferenceFailed` instead of crashing.
 *
 * Invariants: INV-1 (one model executes at a time: every runtime call is under [mutex]); INV-2 (detector not
 * resident while the VLM generates: [unloadDetectorLocked] before, lazy reload in the next [detect], both under
 * the same mutex);
 * INV-3 (frames stay in memory: `getPixels` into an array that is dropped after the call; no log of pixels);
 * INV-8 (nothing in here calls another module; data out only); INV-9 (the `Bitmap` is the caller's; it is only
 * read, never recycled, and the FR-D2 cache holds a [WeakReference]). Clocks: `System.nanoTime()` only (H-21).
 */
class ExecuTorchInferenceEngine(
    private val app: Application,
    private val catalog: ModelCatalog,
    // Kept: the constructor is part of R2's contract in AppGraph. Nothing is launched on it any more (the detector
    // reloads lazily inside detect()), so no work can outlive release() or reload the detector after it.
    @Suppress("unused") private val scope: CoroutineScope,
) : InferenceEngine {

    // ADR-0004: ONE mutex around all inference; INV-1/INV-2/INV-8.
    private val mutex = Mutex()

    private val _vlmInfo = MutableStateFlow<ModelInfo?>(null)
    private val _detectorInfo = MutableStateFlow<ModelInfo?>(null)
    private val _stats = MutableStateFlow(InferenceStats())

    override val vlmInfo: StateFlow<ModelInfo?> = _vlmInfo.asStateFlow()
    override val detectorInfo: StateFlow<ModelInfo?> = _detectorInfo.asStateFlow()
    override val stats: StateFlow<InferenceStats> = _stats.asStateFlow()

    // Everything below is guarded by [mutex].
    private var vlm: VlmRunner? = null
    private var detector: Module? = null
    private var vlmFile: File? = null
    private var tokenizerFile: File? = null
    private var detectorFile: File? = null
    private var detectorEntry: ModelCatalog.Entry? = null
    private var vlmEntry: ModelCatalog.Entry? = null
    private var ocr: Module? = null
    private var ocrFile: File? = null
    private var ocrCharsetFile: File? = null

    /** OCR characters in recognizer order, without the CTC blank (`ocr-charset.json`, a JSON array of strings). */
    private var ocrCharset: List<String>? = null

    /**
     * FR-G5: metadata of the resolved files ([resolveLocked]), published on [vlmInfo] / [detectorInfo] only once
     * the matching model has actually loaded (Contracts.kt: "`null` until the model is loaded").
     */
    private var vlmInfoPending: ModelInfo? = null
    private var detectorInfoPending: ModelInfo? = null

    /** `System.nanoTime()` of the last `detect ms=… fps=…` log line; the line is written at most once per second. */
    private var lastDetectLogNanos: Long? = null

    /** FR-D2 / FR-T4: the frame whose image tokens are in the KV cache; identity only, never dereferenced. */
    private var prefilled: WeakReference<Bitmap>? = null

    private val vlmLatency = LatencyStats(LatencyStats.DEFAULT_CAPACITY)
    private val detectorFps = FpsWindow(FpsWindow.DEFAULT_WINDOW_NANOS)

    /**
     * Resolve files via the catalog (real), load both models (stubs), publish [ModelInfo] for what loaded. Never
     * throws. Idempotent: `ModeController.start()` calls it on every `onStart` and `AppGraph.stop()` keeps the
     * models, so a loaded VLM returns at once (no reload, no second `LlmModule` beside the first; RK-3).
     */
    override suspend fun warmUp(): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (vlm != null) return@withLock // already loaded; the detector reloads lazily in detect()
            val t0 = System.nanoTime()
            resolveLocked()
            loadOcrLocked() // small and fast: Read works before the multi-GB VLM finishes loading
            loadVlmLocked()
            loadDetectorLocked()
            Log.i(
                TAG_LATENCY,
                "warmUp ms=${(System.nanoTime() - t0) / NANOS_PER_MS} " +
                    "vlmLoaded=${vlm != null} detectorLoaded=${detector != null} ocrLoaded=${ocr != null}",
            )
        }
    }

    /**
     * FR-T1 / FR-D1 / FR-N3 / FR-N6. Inside the mutex: unload the detector (INV-2), prefill the image unless this
     * exact [bitmap] is already in the KV cache (FR-D2), generate with a `seqLen` of prefilled + [maxNewTokens],
     * record the latency. The detector is NOT reloaded eagerly: the next [detect] reloads it lazily
     * ([ensureDetectorLoadedLocked]); Navigation is paused for the whole one-shot, so the first detect after
     * `resume()` pays the load (ADR-0004 "reloaded after"), and the two door-vote calls do not churn it.
     * Watchdog: [InferenceEngine.VLM_TIMEOUT_MS]; on timeout or cancellation `LlmModule.stop()` ends the blocking
     * generate (FR-G4) and the caller gets an [InferenceException] (timeout) or the cancellation.
     */
    override suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int): String =
        runVlmGuarded(bitmap, prompt, maxNewTokens) { /* whole answer returned below */ }

    /**
     * Streaming override: the runner's `LlmCallback.onResult` delivers tokens as they decode
     * (`docs/stack.md` §3-R2, verified), so the Speaker can start before generation ends (FR-T1 ≤ 6 s to the
     * first spoken word). Same mutex, same unload/reload, same timing as [describe]; the final string is not
     * re-emitted at the end. The channel is unbounded (in practice bounded by [TokenCap] at `maxNewTokens`), so a
     * slow Speaker never makes `trySend` drop a token and speak a partial sentence as if complete (SRS §3 rule 5).
     */
    override fun describeStreaming(bitmap: Bitmap, prompt: String, maxNewTokens: Int): Flow<String> = channelFlow {
        runVlmGuarded(bitmap, prompt, maxNewTokens) { token ->
            if (trySend(token).isFailure) throw InferenceException("token stream overflow")
        }
    }.buffer(Channel.UNLIMITED)

    /**
     * FR-N2. Inside the mutex: make sure the detector is resident (lazy reload after a VLM call), read the frame's
     * pixels into memory (INV-3), letterbox + normalize ([YoloPostProcessor.letterboxToChw]), forward (stub),
     * decode + NMS + un-letterbox ([YoloPostProcessor.decode]), then update `lastDetectMs` and the 5 s `detectorFps`.
     * A failing detector is marked unloaded and Navigation continues on depth only (`docs/nfr.md` fault model).
     */
    override suspend fun detect(bitmap: Bitmap): List<Detection> = withContext(Dispatchers.Default) {
        mutex.withLock {
            ensureDetectorLoadedLocked()
            val module = detector ?: throw InferenceException(
                "detector not loaded (${detectorFile?.name ?: ModelCatalog.DETECTOR}); sideload detector.pte (ml/README.md)",
            )
            if (bitmap.isRecycled) throw InferenceException("detect(): bitmap already recycled (INV-9)")
            val t0 = System.nanoTime()
            try {
                val w = bitmap.width
                val h = bitmap.height
                val inputW = detectorEntry?.inputWidth?.takeIf { it > 0 } ?: DEFAULT_DETECTOR_INPUT
                val inputH = detectorEntry?.inputHeight?.takeIf { it > 0 } ?: DEFAULT_DETECTOR_INPUT
                val argb = IntArray(w * h)
                bitmap.getPixels(argb, 0, w, 0, 0, w, h) // INV-3: pixels live in this array only, never logged/stored
                val lb = YoloPostProcessor.Letterbox.compute(w, h, inputW, inputH)
                val chw = YoloPostProcessor.letterboxToChw(argb, w, h, lb)
                val (data, shape) = forwardDetector(module, chw, inputW, inputH)
                val entry = detectorEntry
                val detections = YoloPostProcessor.decode(
                    data = data,
                    shape = shape,
                    layout = if (entry?.outputLayout.equals("columns", ignoreCase = true)) {
                        YoloPostProcessor.OutputLayout.CXCYWH_SCORES_COLUMNS
                    } else {
                        YoloPostProcessor.OutputLayout.XYXY_CONF_CLS_ROWS
                    },
                    lb = lb,
                    classNames = entry?.classes?.takeIf { it.isNotEmpty() } ?: YoloPostProcessor.COCO_CLASSES,
                    onlyClasses = entry?.classMap?.takeIf { it.isNotEmpty() },
                )
                val now = System.nanoTime()
                val ms = (now - t0) / NANOS_PER_MS
                val fps = detectorFps.record(now)
                _stats.update { it.copy(lastDetectMs = ms, detectorFps = fps, lastDetectAtNanos = now) }
                val lastLog = lastDetectLogNanos
                if (lastLog == null || now - lastLog >= NANOS_PER_SECOND) { // FR-N2 evidence line, at most 1 Hz
                    lastDetectLogNanos = now
                    Log.i(TAG_LATENCY, "detect ms=$ms fps=${"%.1f".format(Locale.ROOT, fps)}")
                }
                detections
            } catch (e: CancellationException) {
                throw e
            } catch (e: InferenceException) {
                unloadDetectorLocked()
                throw e
            } catch (t: Throwable) {
                unloadDetectorLocked()
                throw InferenceException("detector failed: ${t.message}", t)
            }
        }
    }

    /**
     * FR-T1 fast path: PP-OCRv6 `detect` on the whole frame, then `recognize` on each text box (a very wide line in
     * pieces), greedy CTC, lines in reading order. `null` when no OCR model is loaded (the controller reads with the
     * VLM). At most [OCR_MAX_BOXES] boxes, the most confident first, so a busy scene cannot stall the reader. Pixels
     * stay in local arrays (INV-3); the [bitmap] is only read (INV-9).
     */
    override suspend fun readText(bitmap: Bitmap): List<String>? = withContext(Dispatchers.Default) {
        mutex.withLock {
            val module = ocr ?: return@withLock null
            val charset = ocrCharset ?: return@withLock null
            if (bitmap.isRecycled) throw InferenceException("readText(): bitmap already recycled (INV-9)")
            val t0 = System.nanoTime()
            try {
                val w = bitmap.width
                val h = bitmap.height
                val argb = IntArray(w * h)
                bitmap.getPixels(argb, 0, w, 0, 0, w, h)
                val recMaxWidth = Ocr.REC_MAX_WIDTH
                val det = OcrPipeline.detInput(argb, w, h, Ocr.DET_MIN_SIDE, Ocr.DET_MAX_SIDE, Ocr.DET_MULTIPLE)
                val probs = module.execute(
                    "detect",
                    EValue.from(Tensor.fromBlob(det.chw, longArrayOf(1, 3, det.height.toLong(), det.width.toLong()))),
                ).first().toTensor().dataAsFloatArray
                val boxes = OcrPipeline.boxes(probs, det.width, det.height, det.scaleX, det.scaleY, w, h)
                    .filter { it.width >= MIN_TEXT_SIDE && it.height >= MIN_TEXT_SIDE }
                    .take(OCR_MAX_BOXES)
                val read = boxes.mapNotNull { box ->
                    val text = StringBuilder()
                    var weighted = 0f
                    for (piece in OcrPipeline.split(box, Ocr.REC_HEIGHT, recMaxWidth)) {
                        val rec = OcrPipeline.recInput(argb, w, h, piece, Ocr.REC_HEIGHT, recMaxWidth, Ocr.REC_STRIDE)
                        val out = module.execute(
                            "recognize",
                            EValue.from(Tensor.fromBlob(rec.chw, longArrayOf(1, 3, rec.height.toLong(), rec.width.toLong()))),
                        ).first().toTensor()
                        val shape = out.shape()
                        val (chunk, conf) = OcrPipeline.ctcGreedy(
                            out.dataAsFloatArray, shape[1].toInt(), shape[2].toInt(), charset,
                        )
                        text.append(chunk)
                        weighted += conf * chunk.length
                    }
                    val line = text.toString().trim()
                    val confidence = if (text.isEmpty()) 0f else weighted / text.length
                    if (line.isNotEmpty() && confidence >= Ocr.MIN_LINE_CONFIDENCE) box to line else null
                }
                val lines = OcrPipeline.readingOrder(read) { it.first }.map { row -> row.joinToString(" ") { it.second } }
                Log.i(
                    TAG_LATENCY,
                    "ocr ms=${(System.nanoTime() - t0) / NANOS_PER_MS} input=${det.width}x${det.height} " +
                        "boxes=${boxes.size} lines=${lines.size}",
                )
                lines
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                throw InferenceException("OCR failed: ${t.message}", t)
            }
        }
    }

    /**
     * Unload everything. Called once from `AppGraph.release()` (`onDestroy` when finishing); `AppGraph.stop()`
     * (`onStop`) keeps the models. Never throws.
     */
    override suspend fun release(): Unit = withContext(Dispatchers.Default) {
        mutex.withLock {
            val runner = vlm
            vlm = null
            if (runner != null) {
                // close() throws if generate() is in flight; impossible here, generate runs under this mutex.
                runCatching { runner.close() }.onFailure { Log.w(TAG, "vlm close failed: ${it.message}") }
            }
            unloadDetectorLocked()
            ocr?.let { module -> runCatching { module.close() } }
            ocr = null
            prefilled = null
            detectorFps.reset()
            _stats.update { it.copy(vlmLoaded = false, detectorLoaded = false, detectorFps = 0f) }
            _vlmInfo.value = null
            _detectorInfo.value = null
        }
    }

    // ── VLM path ────────────────────────────────────────────────────────────────────────────────────

    private suspend fun runVlmGuarded(
        bitmap: Bitmap,
        prompt: String,
        maxNewTokens: Int,
        onToken: (String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        mutex.withLock { // INV-1
            unloadDetectorLocked() // INV-2: never both resident while the VLM runs
            val t0 = System.nanoTime()
            try {
                val text = withTimeout(InferenceEngine.VLM_TIMEOUT_MS) { // INV-5 watchdog, NFR reliability
                    runVlmLocked(bitmap, prompt, maxNewTokens, onToken)
                }
                val ms = (System.nanoTime() - t0) / NANOS_PER_MS
                vlmLatency.record(ms)
                val snap = vlmLatency.snapshot()
                _stats.update {
                    it.copy(lastVlmMs = snap.last, medianVlmMs = snap.median, p90VlmMs = snap.p90, vlmRuns = snap.count)
                }
                Log.i(
                    TAG_LATENCY,
                    "vlm ms=$ms median=${snap.median} p90=${snap.p90} runs=${snap.count} maxNewTokens=$maxNewTokens",
                )
                text
            } catch (e: TimeoutCancellationException) {
                vlm?.stop() // FR-G4: lock-free, safe from any thread; makes generate() return
                prefilled = null
                throw InferenceException("VLM timed out after ${InferenceEngine.VLM_TIMEOUT_MS} ms", e)
            } catch (e: CancellationException) {
                vlm?.stop() // "stop" from the controller: the Job is cancelled outside the mutex (INV-6)
                prefilled = null
                throw e
            } catch (e: InferenceException) {
                throw e
            } catch (t: Throwable) {
                prefilled = null
                throw InferenceException("VLM failed: ${t.message}", t)
            }
        }
    }

    /** Preconditions and the FR-D2 prefill cache; the model calls are in the [VlmRunner]. */
    private suspend fun runVlmLocked(
        bitmap: Bitmap,
        prompt: String,
        maxNewTokens: Int,
        onToken: (String) -> Unit,
    ): String {
        val runner = vlm ?: throw InferenceException(
            "VLM not loaded (${vlmFile?.name ?: ModelCatalog.VLM}); sideload vlm.pte + vlm-tokenizer.json (ml/README.md)",
        )
        if (bitmap.isRecycled) throw InferenceException("describe(): bitmap already recycled (INV-9)")
        require(maxNewTokens > 0) { "maxNewTokens must be positive: $maxNewTokens" }
        if (prefilled?.get() !== bitmap) {
            // New frame: fresh context, then its image tokens into the KV cache (RK-2 "prefill once").
            runner.prefill(bitmap)
            prefilled = WeakReference(bitmap)
        }
        // The answer's tokens stay in the KV cache, so an FR-D2 follow-up on the same frame continues this context.
        return generateLocked(runner, prompt, maxNewTokens, onToken)
    }

    /**
     * Generate the answer to [prompt] on the prefilled context, streaming each chunk to [onToken]. The runner BLOCKS
     * this thread; cancellation (Stop, the watchdog) calls its lock-free `stop()` (FR-G4) so it returns promptly.
     */
    private suspend fun generateLocked(
        runner: VlmRunner,
        prompt: String,
        maxNewTokens: Int,
        onToken: (String) -> Unit,
    ): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runner.stop() }
        try {
            cont.resume(runner.generate(prompt, maxNewTokens, onToken))
        } catch (e: InferenceException) {
            cont.resumeWithException(e)
        } catch (t: Throwable) {
            cont.resumeWithException(InferenceException("generate failed: ${t.message}", t))
        }
    }

    // ── Detector path ────────────────────────────────────────────────────────────────────────────────

    /** One forward pass: float NCHW in, `[1, N, 6]` post-NMS rows in letterboxed-input pixels out. */
    private fun forwardDetector(
        module: Module,
        chw: FloatArray,
        inputW: Int,
        inputH: Int,
    ): Pair<FloatArray, LongArray> {
        val input = Tensor.fromBlob(chw, longArrayOf(1, 3, inputH.toLong(), inputW.toLong()))
        val out = module.forward(EValue.from(input)).firstOrNull()?.toTensor()
            ?: throw InferenceException("detector returned no output")
        return out.dataAsFloatArray to out.shape()
    }

    // ── Lifecycle (under the mutex) ──────────────────────────────────────────────────────────────────

    /**
     * Real: find the three artifacts through the catalog (which checks them against the lockfile and fails closed,
     * plan.md Security) and prepare their [ModelInfo]. Nothing is published here: [loadVlmLocked] /
     * [loadDetectorLocked] publish the info only after the model loaded, so the overlay never names a model that
     * is not resident (FR-G5, NFR Honesty). Failures are logged only.
     */
    private fun resolveLocked() {
        vlmFile = resolveOrNull(ModelCatalog.VLM)
        tokenizerFile = resolveOrNull(ModelCatalog.VLM_TOKENIZER)
        detectorFile = resolveOrNull(ModelCatalog.DETECTOR)
        detectorEntry = catalog.entry(ModelCatalog.DETECTOR)
        vlmEntry = catalog.entry(ModelCatalog.VLM)
        ocrFile = resolveOrNull(ModelCatalog.OCR)
        ocrCharsetFile = resolveOrNull(ModelCatalog.OCR_CHARSET)
        // FR-G5: quantization/name from the lockfile (export metadata, ADR-0002), size from the file. LlmModule
        // exposes no metadata API; for the detector the real load replaces `backend` with
        // module.getMethodMetadata("forward").backends.joinToString() ("XnnpackBackend").
        vlmInfoPending = vlmFile?.let { infoOrNull(ModelCatalog.VLM, it) }
        detectorInfoPending = detectorFile?.let { infoOrNull(ModelCatalog.DETECTOR, it) }
    }

    private fun infoOrNull(name: String, file: File): ModelInfo? =
        runCatching { catalog.modelInfo(name, file) }.getOrNull()

    private fun resolveOrNull(name: String): File? = try {
        catalog.resolve(name).also { Log.i(TAG, "$name → ${it.absolutePath} (${it.length()} bytes)") }
    } catch (e: InferenceException) {
        // Integrity failures (size / sha256 against the lockfile) are logged by ModelCatalog under vysion.security.
        Log.w(TAG, "$name unavailable: ${e.message}")
        null
    }

    /**
     * Load the VLM eagerly (else the first `generate()` pays it). Idempotent: returns at once while a VLM is loaded,
     * so a repeated [warmUp] never builds a second `LlmModule` beside a live one (RK-3). Greedy (`temperature 0`) for
     * read-verbatim; mmap so a multi-GB `.pte` is paged, not copied (A-15). A load failure is logged and leaves the
     * VLM unloaded: the controller then speaks `inferenceFailed` (A-13 / Q9: only an `LlmModule`-compatible export
     * loads, `docs/stack.md` D5).
     */
    private fun loadVlmLocked() {
        if (vlm != null) return // idempotent (Contracts.kt warmUp)
        val pte = vlmFile
        val tok = tokenizerFile
        if (pte == null || tok == null) {
            val missing = if (pte == null) "vlm.pte missing" else "tokenizer missing"
            Log.w(TAG, "vlm not loaded: $missing (sideload or fetch_models.sh)")
            return
        }
        val t0 = System.nanoTime()
        val entry = vlmEntry
        val module = try {
            if (entry?.runner.equals(RUNNER_MODULE, ignoreCase = true)) {
                ModuleVlmRunner(pte, tok, entry)
            } else {
                LlmModuleRunner(pte, tok, entry)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "vlm load failed (A-13): ${t.message}")
            return
        }
        vlm = module
        _stats.update { it.copy(vlmLoaded = true) }
        _vlmInfo.value = vlmInfoPending // FR-G5: only once it loaded
        Log.i(TAG_LATENCY, "vlm load ms=${(System.nanoTime() - t0) / NANOS_PER_MS} file=${pte.name} runner=${module.javaClass.simpleName}")
    }

    /**
     * Load the OCR program (one `.pte`, methods `detect` and `recognize`) and its charset. Idempotent. A missing or
     * broken file is logged and Read falls back to the VLM.
     */
    private fun loadOcrLocked() {
        if (ocr != null) return
        val file = ocrFile ?: return
        val charsetFile = ocrCharsetFile ?: run {
            Log.w(TAG, "ocr not loaded: ocr-charset.json missing")
            return
        }
        val t0 = System.nanoTime()
        val charset = runCatching {
            (LockJson.parse(charsetFile.readText()) as List<*>).map { it as String }
        }.getOrElse {
            Log.w(TAG, "ocr not loaded: bad charset (${it.message})")
            return
        }
        val module = try {
            Module.load(file.absolutePath, Module.LOAD_MODE_MMAP, DETECTOR_THREADS)
        } catch (t: Throwable) {
            Log.w(TAG, "ocr load failed: ${t.message}")
            return
        }
        val methods = runCatching { module.getMethods().toList() }.getOrDefault(emptyList())
        if (!methods.containsAll(listOf("detect", "recognize"))) {
            Log.w(TAG, "ocr not loaded: ${file.name} has methods $methods, need detect + recognize")
            runCatching { module.close() }
            return
        }
        ocr = module
        ocrCharset = charset
        Log.i(TAG_LATENCY, "ocr load ms=${(System.nanoTime() - t0) / NANOS_PER_MS} charset=${charset.size}")
    }

    /**
     * Load the detector with mmap on [DETECTOR_THREADS] threads and publish its backend (FR-G5). Idempotent. A load
     * failure is logged and Navigation runs on depth and planes only (`docs/nfr.md` fault model).
     */
    private fun loadDetectorLocked() {
        if (detector != null) return // idempotent
        val file = detectorFile
        if (file == null) {
            Log.w(TAG, "detector not loaded: detector.pte missing (sideload or fetch_models.sh)")
            return
        }
        val t0 = System.nanoTime()
        val module = try {
            Module.load(file.absolutePath, Module.LOAD_MODE_MMAP, DETECTOR_THREADS)
        } catch (t: Throwable) {
            Log.w(TAG, "detector load failed (Q7): ${t.message}")
            return
        }
        detector = module
        val backend = runCatching { module.getMethodMetadata("forward").backends.joinToString() }.getOrDefault("")
        _detectorInfo.value = detectorInfoPending?.let { if (backend.isNotEmpty()) it.copy(backend = backend) else it }
        _stats.update { it.copy(detectorLoaded = true) }
        Log.i(TAG_LATENCY, "detector load ms=${(System.nanoTime() - t0) / NANOS_PER_MS} backend=$backend") // INV-2
    }

    /**
     * Real: `Module.close()` frees the mmap and the XNNPACK workspace (INV-2). Idempotent. The 5 s fps window is
     * reset so the overlay and the soak JSON read 0 while the detector is not resident, never a stale value.
     */
    private fun unloadDetectorLocked() {
        val module = detector ?: return
        detector = null
        runCatching { module.close() }.onFailure { Log.w(TAG, "detector close failed: ${it.message}") }
        detectorFps.reset()
        _stats.update { it.copy(detectorLoaded = false, detectorFps = 0f) }
        Log.i(TAG_LATENCY, "detector unloaded (INV-2)")
    }

    /** Real: lazy reload before a detect() call if a VLM call unloaded the detector and the reload has not run yet. */
    private fun ensureDetectorLoadedLocked() {
        if (detector == null && detectorFile != null) loadDetectorLocked()
    }


    companion object {
        const val TAG: String = "vysion"
        const val TAG_LATENCY: String = "vysion.latency"
        private const val NANOS_PER_MS: Long = 1_000_000L
        private const val NANOS_PER_SECOND: Long = 1_000_000_000L

        /** Fallback detector input when the lockfile row has no dims; the verified yolo26 export is 640×640. */
        const val DEFAULT_DETECTOR_INPUT: Int = 640

        /** Text boxes read per frame at most (each costs one `recognize`); the most confident are kept. */
        const val OCR_MAX_BOXES: Int = 40

        /** Text boxes smaller than this in source pixels are noise, not glyphs. */
        const val MIN_TEXT_SIDE: Int = 3

        /** `Module.load(path, loadMode, numThreads)`; 4 big cores on the XR2+ Gen 2 (measure, then tune). */
        const val DETECTOR_THREADS: Int = 4

        /**
         * `seqLen` asked of `generate()`: an absolute position budget the runner clamps to the export's
         * `max_seq_len` (`max_new = min(seqLen, max_context_len) − pos`, `docs/stack.md` §3-R2), so this means
         * "whatever context is left"; [TokenCap] ends the answer at `maxNewTokens`.
         */
        const val SEQ_LEN_REQUEST: Int = 1 shl 15

        /** Lockfile `runner` value for exports the stock `LlmModule` runner cannot drive ([ModuleVlmRunner]). */
        const val RUNNER_MODULE: String = "module"

        /** Lockfile `image_format` for encoders that take raw pixel values as float. */
        const val IMAGE_FORMAT_FLOAT_0_255: String = "float_0_255"
    }
}
