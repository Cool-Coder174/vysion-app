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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.pytorch.executorch.Module
import org.pytorch.executorch.extension.llm.LlmModule

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
 *   1.3.1, so the contract's `maxNewTokens` becomes [seqLenBudget].
 * - Assumptions: A-3 (the Maven AAR has `LlmModule` + XNNPACK), A-13 / Q9 (the chosen VLM `.pte` loads with
 *   `MODEL_TYPE_MULTIMODAL` and answers on the headset; the SRS Gemma 4 export does NOT, `docs/stack.md` D5),
 *   A-15 / Q20 (multi-GB `.pte` in the external files dir loads with mmap), Q7 (detector loads on the 1.3.1 AAR).
 *
 * Skeleton status: the mutex, the unload/reload order, the timing, the stats and the catalog resolution are real;
 * every call into the ExecuTorch runtime is a stub (a logged `TODO` line for `Unit` stubs, a throwing one for
 * value stubs) that is never reached while no model is loaded, so [describe] and [detect] throw
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
    private var vlm: LlmModule? = null
    private var detector: Module? = null
    private var vlmFile: File? = null
    private var tokenizerFile: File? = null
    private var detectorFile: File? = null
    private var detectorEntry: ModelCatalog.Entry? = null

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

    /** Tokens already in the context (image soft tokens + prior turns); base of the `seqLen` budget. */
    private var prefilledTokens: Int = 0

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
            loadVlmLocked()
            loadDetectorLocked()
            Log.i(
                TAG_LATENCY,
                "warmUp ms=${(System.nanoTime() - t0) / NANOS_PER_MS} " +
                    "vlmLoaded=${vlm != null} detectorLoaded=${detector != null}",
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
     * re-emitted at the end. The channel is unbounded (in practice bounded by `seqLen` ≤ [MAX_CONTEXT_LEN]), so a
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
                "detector not loaded (${detectorFile?.name ?: ModelCatalog.DETECTOR}); see the FR-N2 TODO in logcat",
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
                val detections = YoloPostProcessor.decode(
                    data = data,
                    shape = shape,
                    layout = YoloPostProcessor.OutputLayout.XYXY_CONF_CLS_ROWS,
                    lb = lb,
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
            prefilled = null
            prefilledTokens = 0
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

    /** Real preconditions and cache logic; the runtime calls are in [prefillLocked] / [generateLocked]. */
    private suspend fun runVlmLocked(
        bitmap: Bitmap,
        prompt: String,
        maxNewTokens: Int,
        onToken: (String) -> Unit,
    ): String {
        val runner = vlm ?: throw InferenceException(
            "VLM not loaded (${vlmFile?.name ?: ModelCatalog.VLM}); warmUp() left it unloaded, " +
                "see the FR-T1 TODO in logcat",
        )
        if (bitmap.isRecycled) throw InferenceException("describe(): bitmap already recycled (INV-9)")
        require(maxNewTokens > 0) { "maxNewTokens must be positive: $maxNewTokens" }
        val sameFrame = prefilled?.get() === bitmap
        if (!sameFrame) {
            // New frame: fresh context, then image tokens into the KV cache (RK-2 "prefill once").
            prefilledTokens = prefillLocked(runner, bitmap)
            prefilled = WeakReference(bitmap)
        }
        val seqLen = seqLenBudget(prefilledTokens, maxNewTokens, MAX_CONTEXT_LEN)
        val text = generateLocked(runner, prompt, seqLen, onToken)
        // The answer's tokens now sit in the KV cache too (FR-D2 follow-ups continue the same context).
        prefilledTokens = seqLen
        return text
    }

    /**
     * Stub (device): reset the context and prefill one frame; returns the number of tokens now in the context.
     * Real body (`docs/stack.md` §3-R2, VERIFIED):
     * ```
     * runner.resetContext()
     * val w = bitmap.width; val h = bitmap.height
     * val argb = IntArray(w * h); bitmap.getPixels(argb, 0, w, 0, 0, w, h)
     * val chw = IntArray(3 * w * h)          // uint8 0..255, CHW, RGB (prefillImages(int[]) truncates to uint8)
     * for (i in 0 until w * h) {
     *     val p = argb[i]
     *     chw[i] = (p shr 16) and 0xFF; chw[w * h + i] = (p shr 8) and 0xFF; chw[2 * w * h + i] = p and 0xFF
     * }
     * runner.prefillImages(chw, w, h, 3)
     * return IMAGE_SOFT_TOKENS   // or prompt_tokens from the first onStats JSON
     *                            // (TTFT = first_token_ms - inference_start_ms)
     * ```
     * The frame must already be the VLM's input size (`FrameSource.captureStill()` center-crops to it). Expected
     * size for the default option C model: 896×896 → 256 soft tokens (`// stack.md: UNVERIFIED`).
     * Which overload (`// stack.md: UNVERIFIED`, docs/stack.md "Corrections" §3-R2 row): uint8
     * `prefillImages(IntArray)` above if the exported `vision_encoder` normalizes internally; otherwise
     * `prefillImages(FloatArray, w, h, 3)` with
     * `((v / 255f) - 0.5f) / 0.5f` per channel. Decide on the first device run: `onStats` `prompt_tokens` equals
     * [IMAGE_SOFT_TOKENS] (+ prompt) and a printed sign is read correctly; record the result in stack.md.
     */
    private fun prefillLocked(runner: LlmModule, bitmap: Bitmap): Int {
        TODO("FR-T1: ${runner.javaClass.simpleName}.resetContext() + prefillImages(IntArray uint8 CHW, " +
            "${bitmap.width}, ${bitmap.height}, 3) per docs/stack.md §3-R2; return the prefilled token count")
    }

    /**
     * Stub (device): generate [seqLen] − prefilled new tokens for [prompt], streaming every `onResult` chunk to
     * [onToken]; returns the concatenated answer. Real body (`docs/stack.md` §3-R2, VERIFIED):
     * ```
     * suspendCancellableCoroutine { cont ->
     *     cont.invokeOnCancellation { runner.stop() }          // FR-G4: lock-free, any thread → generate() returns
     *     val sb = StringBuilder()
     *     var failure: InferenceException? = null
     *     try {
     *         runner.generate(prompt, seqLen, object : LlmCallback {   // BLOCKS; never on Main; no LlmModule call here
     *             override fun onResult(result: String) { sb.append(result); onToken(result) }
     *             override fun onStats(statsJson: String) { Log.i(TAG_LATENCY, "vlm stats=$statsJson") }
     *             override fun onError(errorCode: Int, message: String) {
     *                 failure = InferenceException("runner error $errorCode: $message")
     *             }
     *         }, /* echo = */ false)
     *         failure?.let { cont.resumeWithException(it) } ?: cont.resume(sb.toString().trim())
     *     } catch (t: Throwable) { cont.resumeWithException(InferenceException("generate failed: ${t.message}", t)) }
     * }
     * ```
     * If the chosen VLM needs a chat template, wrap [prompt] here, not in `PromptLibrary` (ADR-0001 keeps the
     * prompt text model-independent). `// stack.md: UNVERIFIED` whether `LlmModule`'s multimodal runner applies
     * Gemma 3's template itself; the A/B to run on the first device answer is in ml/README.md §3 "Prompt format".
     */
    private suspend fun generateLocked(
        runner: LlmModule,
        prompt: String,
        seqLen: Int,
        onToken: (String) -> Unit,
    ): String {
        TODO("FR-T1: ${runner.javaClass.simpleName}.generate(prompt(${prompt.length} chars), seqLen=$seqLen, " +
            "LlmCallback → onToken, echo=false) per docs/stack.md §3-R2 " +
            "(LlmModule.generate(String, int, LlmCallback, boolean))")
    }

    // ── Detector path ────────────────────────────────────────────────────────────────────────────────

    /**
     * Stub (device): one forward pass. Returns the output data and its shape for [YoloPostProcessor.decode].
     * Real body (`docs/stack.md` §3-R2, VERIFIED):
     * ```
     * val input = Tensor.fromBlob(chw, longArrayOf(1, 3, inputH.toLong(), inputW.toLong()))
     * val out = module.forward(EValue.from(input))[0].toTensor()     // [1, N, 6] post-NMS, letterboxed pixels
     * return out.getDataAsFloatArray() to out.shape()
     * ```
     */
    private fun forwardDetector(
        module: Module,
        chw: FloatArray,
        inputW: Int,
        inputH: Int,
    ): Pair<FloatArray, LongArray> {
        TODO("FR-N2: ${module.javaClass.simpleName}.forward(EValue.from(Tensor.fromBlob(chw[${chw.size}], " +
            "longArrayOf(1, 3, $inputH, $inputW))))[0].toTensor() → (getDataAsFloatArray(), shape()) " +
            "per docs/stack.md §3-R2")
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
        // FR-G5: quantization/name from the lockfile (export metadata, ADR-0002), size from the file. LlmModule
        // exposes no metadata API; for the detector the real load replaces `backend` with
        // module.getMethodMetadata("forward").getBackends().joinToString() ("XnnpackBackend").
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
     * Stub (device). Idempotent: returns at once while a VLM is loaded, so a repeated [warmUp] never builds a
     * second `LlmModule` beside a live one (keep the guard when pasting the body). Real body (`docs/stack.md`
     * §3-R2, VERIFIED at v1.3.1), after the guard:
     * ```
     * vlm = LlmModule(LlmModuleConfig.create()
     *     .modulePath(pte.absolutePath).tokenizerPath(tok.absolutePath)
     *     .modelType(LlmModuleConfig.MODEL_TYPE_MULTIMODAL).temperature(0f)   // greedy for read-verbatim
     *     .loadMode(LlmModuleConfig.LOAD_MODE_MMAP).build()).also { it.load() } // eager; else first generate() pays it
     * _stats.update { it.copy(vlmLoaded = true) }
     * _vlmInfo.value = vlmInfoPending                                          // FR-G5: only once it loaded
     * ```
     * A-13/Q9: only an `LlmModule`-compatible export (`token_embedding` + 2-input `text_decoder`, option C) loads;
     * the SRS Gemma 4 export fails in `MultimodalPrefiller::load()` with `Error::InvalidArgument` (18) (D5).
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
        Log.w(
            TAG,
            "TODO(FR-T1) ExecuTorchInferenceEngine.loadVlmLocked: vlm = LlmModule(LlmModuleConfig.create()" +
                ".modulePath(${pte.name}).tokenizerPath(${tok.name}).modelType(MODEL_TYPE_MULTIMODAL).temperature(0f)" +
                ".loadMode(LOAD_MODE_MMAP).build()).also { it.load() }; then stats.vlmLoaded = true (A-13, Q9)",
        )
    }

    /**
     * Stub (device). Idempotent: returns at once while the detector is loaded (keep the guard when pasting the
     * body). Real body (`docs/stack.md` §3-R2, VERIFIED at v1.3.1), after the guard:
     * ```
     * val t0 = System.nanoTime()
     * detector = Module.load(file.absolutePath, Module.LOAD_MODE_MMAP, DETECTOR_THREADS)
     * val backend = detector.getMethodMetadata("forward").getBackends().joinToString()   // "XnnpackBackend"
     * _detectorInfo.value = detectorInfoPending?.copy(backend = backend)                  // FR-G5: only once it loaded
     * _stats.update { it.copy(detectorLoaded = true) }
     * Log.i(TAG_LATENCY, "detector load ms=${(System.nanoTime() - t0) / NANOS_PER_MS} backend=$backend") // INV-2
     * ```
     */
    private fun loadDetectorLocked() {
        if (detector != null) return // idempotent
        val file = detectorFile
        if (file == null) {
            Log.w(TAG, "detector not loaded: detector.pte missing (sideload or fetch_models.sh)")
            return
        }
        Log.w(
            TAG,
            "TODO(FR-N2) ExecuTorchInferenceEngine.loadDetectorLocked: detector = Module.load(${file.name}, " +
                "LOAD_MODE_MMAP, $DETECTOR_THREADS); backend = getMethodMetadata(\"forward\").getBackends(); " +
                "stats.detectorLoaded = true (Q7)",
        )
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

        /** `Module.load(path, loadMode, numThreads)`; 4 big cores on the XR2+ Gen 2 (measure, then tune). */
        const val DETECTOR_THREADS: Int = 4

        /** Export-time `max_seq_len`; the runner clamps `seqLen` to it (`docs/stack.md` §3-R2). Set from the export. */
        const val MAX_CONTEXT_LEN: Int = 1024

        /** Image soft tokens for the default option C model (SigLIP 896×896 → 256). stack.md: UNVERIFIED */
        const val IMAGE_SOFT_TOKENS: Int = 256

        /**
         * `docs/stack.md` §3-R2: `seqLen` is an ABSOLUTE position budget, `max_new = min(seqLen, max_context_len)
         * − pos`, and `maxNewTokens` is not forwarded in 1.3.1. So the contract's `maxNewTokens` becomes
         * `prefilledTokens + maxNewTokens`, clamped to [maxContextLen] and always strictly above the prefilled
         * count (otherwise the runner errors with nothing to generate).
         */
        fun seqLenBudget(prefilledTokens: Int, maxNewTokens: Int, maxContextLen: Int): Int {
            require(prefilledTokens >= 0) { "prefilledTokens must be ≥ 0: $prefilledTokens" }
            require(maxNewTokens > 0) { "maxNewTokens must be > 0: $maxNewTokens" }
            require(maxContextLen > 0) { "maxContextLen must be > 0: $maxContextLen" }
            val wanted = prefilledTokens.toLong() + maxNewTokens
            val clamped = minOf(wanted, maxContextLen.toLong()).toInt()
            return maxOf(clamped, minOf(prefilledTokens + 1, maxContextLen))
        }
    }
}
