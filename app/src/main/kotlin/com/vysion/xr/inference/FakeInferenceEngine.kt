package com.vysion.xr.inference

import android.graphics.Bitmap
import com.vysion.xr.core.Detection
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.InferenceStats
import com.vysion.xr.core.ModelInfo
import com.vysion.xr.core.NormalizedBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fully working [InferenceEngine] with no models, for the laptop emulator, the S25 Ultra without `.pte` files
 * (`-PuseFakes=true`, `AppGraph`), R1's `ModeController` unit tests and R5's overlay.
 *
 * - Contract provided: [InferenceEngine]. Constructor `()` per `AppGraph.kt` (both parameters default).
 * - FR ids: FR-T1 / FR-D1 / FR-N3 / FR-N6 (canned answers per `PromptLibrary` prompt), FR-N2 (two canned
 *   boxes), FR-G5 (stats update on every call), FR-G4 / INV-5 ([hangNextDescribe] for the 15 s watchdog test,
 *   cancellable), NFR reliability ([failNextDescribe] → [InferenceException]).
 * - SRS §8 R2 due: with the engine, Sat 16:00 (the fake lands first so nobody waits).
 * - Owner: R2 ML / ExecuTorch.
 * - API: none; `delay()` only. The `Bitmap` is never read (so a JVM test may pass any instance).
 * - Assumption: none.
 *
 * Mirrors ADR-0004 observably: one [Mutex], `detectorLoaded` flips to `false` while a describe runs and back
 * after, latencies are measured with `System.nanoTime()` around the delay (so the overlay shows real, if tiny,
 * numbers, never typed-in ones).
 */
class FakeInferenceEngine(
    var describeDelayMs: Long = 50,
    var detectDelayMs: Long = 20,
) : InferenceEngine {

    /** Next [describe] throws `InferenceException("fake failure")`, then resets. */
    var failNextDescribe: Boolean = false

    /** Next [describe] suspends forever (cancellable), then resets. For the 15 s watchdog test (INV-5). */
    var hangNextDescribe: Boolean = false

    /** What [readText] returns: `null` (the default) plays "no OCR model", so Read goes through [describe]. */
    var cannedText: List<String>? = null

    /** Calls to [readText], for the OCR-first tests. */
    var readTextCalls: Int = 0

    /**
     * When set, every [describeStreaming] plays a VLM decoding tokens: [describeDelayMs] of prefill, then these chunks
     * [streamChunkDelayMs] apart, all inside the mutex like the real engine, instead of the canned answer's sentences.
     */
    var streamChunks: List<String>? = null

    /** Virtual time between two [streamChunks]. */
    var streamChunkDelayMs: Long = STREAM_CHUNK_DELAY_MS

    /** Chunks of [streamChunks] emitted so far, and streams cancelled before their last chunk (Stop, FR-G4). */
    var streamedChunks: Int = 0
        private set
    var streamsCancelled: Int = 0
        private set

    /** Keyed by prompt text. Defaults cover every `PromptLibrary` prompt; others get [DEFAULT_FOLLOW_UP_ANSWER]. */
    val cannedAnswers: MutableMap<String, String> = linkedMapOf(
        PromptLibrary.READ to "EXIT. Push bar to open. Alarm will sound.",
        PromptLibrary.DESCRIBE to "A hallway with a closed door straight ahead. A chair on the left. " +
            "A trash can on the right.",
        PromptLibrary.DOOR to "center",
        PromptLibrary.ARRIVAL to "The door is closed and the handle is on the right.",
        PromptLibrary.DESCRIBE_BRIEF to "A hallway with a closed door ahead.",
        PromptLibrary.DESCRIBE_DETAILED to "A hallway with a closed door straight ahead. A chair on the left. " +
            "A trash can on the right. The floor is clear. There is an exit sign above the door.",
        PromptLibrary.TRANSLATE_ES to "SALIDA. Empuje la barra para abrir. Sonará una alarma.",
    )

    /** Two boxes: a person left of center and a chair on the right (FR-N2). */
    var cannedDetections: List<Detection> = listOf(
        Detection(
            label = "person",
            confidence = 0.91f,
            box = NormalizedBox(left = 0.30f, top = 0.20f, right = 0.45f, bottom = 0.90f),
        ),
        Detection(
            label = "chair",
            confidence = 0.78f,
            box = NormalizedBox(left = 0.65f, top = 0.55f, right = 0.85f, bottom = 0.95f),
        ),
    )

    private val mutex = Mutex() // ADR-0004: one mutex, same shape as the real engine
    private val vlmLatency = LatencyStats()
    private val detectorFps = FpsWindow()

    private val _vlmInfo = MutableStateFlow<ModelInfo?>(null)
    private val _detectorInfo = MutableStateFlow<ModelInfo?>(null)
    private val _stats = MutableStateFlow(InferenceStats())

    override val vlmInfo: StateFlow<ModelInfo?> = _vlmInfo.asStateFlow()
    override val detectorInfo: StateFlow<ModelInfo?> = _detectorInfo.asStateFlow()
    override val stats: StateFlow<InferenceStats> = _stats.asStateFlow()

    /** Number of [describe] calls that reached generation (excludes failures and hangs). */
    var describeCalls: Int = 0
        private set

    /** Number of [detect] calls. */
    var detectCalls: Int = 0
        private set

    /** The last prompt passed to [describe] / [describeStreaming]; lets a test assert which prompt R1 chose. */
    var lastPrompt: String? = null
        private set

    override suspend fun warmUp() {
        mutex.withLock {
            _vlmInfo.value = FAKE_VLM
            _detectorInfo.value = FAKE_DETECTOR
            _stats.update { it.copy(vlmLoaded = true, detectorLoaded = true) }
        }
    }

    override suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int): String =
        mutex.withLock {
            lastPrompt = prompt
            if (failNextDescribe) {
                failNextDescribe = false
                throw InferenceException("fake failure")
            }
            if (hangNextDescribe) {
                hangNextDescribe = false
                awaitCancellation() // suspends until the caller's watchdog cancels (INV-5)
            }
            // INV-2 mirrored: the detector is not resident, so its 5 s window restarts at 0 (never a stale fps).
            detectorFps.reset()
            _stats.update { it.copy(detectorLoaded = false, detectorFps = 0f) }
            val t0 = System.nanoTime()
            try {
                delay(describeDelayMs)
                val answer = answerFor(prompt, maxNewTokens)
                val ms = (System.nanoTime() - t0) / NANOS_PER_MS
                vlmLatency.record(ms)
                describeCalls++
                val snap = vlmLatency.snapshot()
                _stats.update {
                    it.copy(lastVlmMs = snap.last, medianVlmMs = snap.median, p90VlmMs = snap.p90, vlmRuns = snap.count)
                }
                answer
            } finally {
                _stats.update { it.copy(detectorLoaded = _detectorInfo.value != null) }
            }
        }

    /**
     * Emits the canned answer sentence by sentence so R4 can exercise early TTS start (FR-T1), or [streamChunks]
     * one by one when set.
     */
    override fun describeStreaming(bitmap: Bitmap, prompt: String, maxNewTokens: Int): Flow<String> = flow {
        val chunks = streamChunks
        if (chunks != null) {
            mutex.withLock {
                lastPrompt = prompt
                delay(describeDelayMs)
                try {
                    chunks.forEachIndexed { i, chunk ->
                        if (i > 0) delay(streamChunkDelayMs)
                        emit(chunk)
                        streamedChunks++
                    }
                } catch (e: CancellationException) {
                    streamsCancelled++
                    throw e
                }
                describeCalls++
            }
            return@flow
        }
        val text = describe(bitmap, prompt, maxNewTokens)
        val sentences = SENTENCE_SPLIT.split(text).filter { it.isNotBlank() }
        if (sentences.isEmpty()) {
            emit(text)
            return@flow
        }
        sentences.forEachIndexed { i, s ->
            if (i > 0) delay(STREAM_CHUNK_DELAY_MS)
            emit(if (i < sentences.lastIndex) "$s " else s)
        }
    }

    override suspend fun detect(bitmap: Bitmap): List<Detection> = mutex.withLock {
        val t0 = System.nanoTime()
        delay(detectDelayMs)
        val now = System.nanoTime()
        val ms = (now - t0) / NANOS_PER_MS
        val fps = detectorFps.record(now)
        detectCalls++
        _stats.update { it.copy(lastDetectMs = ms, detectorFps = fps, lastDetectAtNanos = now) }
        cannedDetections
    }

    override suspend fun readText(bitmap: Bitmap): List<String>? = mutex.withLock {
        readTextCalls++
        cannedText
    }

    override suspend fun release() {
        mutex.withLock {
            _vlmInfo.value = null
            _detectorInfo.value = null
            detectorFps.reset()
            _stats.update { it.copy(vlmLoaded = false, detectorLoaded = false, detectorFps = 0f) }
        }
    }

    private fun answerFor(prompt: String, maxNewTokens: Int): String {
        // FR-G6: a prompt localized by PromptLibrary.forLocale keeps its canned answer.
        val full = cannedAnswers[prompt] ?: cannedAnswers[prompt.removeSuffix(PromptLibrary.SPANISH_SUFFIX)]
            ?: DEFAULT_FOLLOW_UP_ANSWER
        // Honor the token cap roughly (one word ≈ one token) so a 60-token cap visibly truncates long answers.
        val words = full.split(' ')
        return if (words.size <= maxNewTokens) full else words.take(maxNewTokens).joinToString(" ")
    }

    companion object {
        val FAKE_VLM: ModelInfo = ModelInfo(
            name = "fake-vlm",
            quantization = "none",
            backend = "FAKE",
            fileSizeBytes = 0L,
            inputWidth = 896,
            inputHeight = 896,
        )
        val FAKE_DETECTOR: ModelInfo = ModelInfo(
            name = "fake-detector",
            quantization = "none",
            backend = "FAKE",
            fileSizeBytes = 0L,
            inputWidth = 640,
            inputHeight = 640,
        )

        /** Answer for a prompt not in [cannedAnswers] (e.g. `PromptLibrary.followUp(...)`, FR-D2). */
        const val DEFAULT_FOLLOW_UP_ANSWER: String = "There is a cup and a notebook on the table."

        private const val NANOS_PER_MS: Long = 1_000_000L
        private const val STREAM_CHUNK_DELAY_MS: Long = 10L
        private val SENTENCE_SPLIT: Regex = Regex("(?<=[.!?])\\s+")
    }
}
