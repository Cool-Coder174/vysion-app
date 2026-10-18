package com.vysion.xr.voice

import android.os.SystemClock
import android.util.JsonReader
import android.util.Log
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The files Whisper needs, resolved by `AppGraph` through `ModelCatalog` (`ml/models.lock` rows `asr`,
 * `asr-preprocessor`, `asr-tokenizer`, `vad`), so this package never imports `inference` (ADR-0005).
 * [vad] is optional: without it a clip ends only on release, Done or the cap.
 */
data class AsrModelFiles(val model: File, val preprocessor: File, val tokenizer: File, val vad: File?)

/**
 * Whisper-tiny on the headset through plain ExecuTorch `Module`s (ADR-0011; `docs/stack.md` §3-R4 "Whisper"). Owner
 * R4, FR-G2. The clip goes from memory to the log-mel preprocessor as a tensor, then to the model's `encoder` and
 * `text_decoder` methods, with the prompt set here ([WhisperDecoding]): English or Spanish only, transcribe, no
 * timestamps, and no empty answer. Nothing is written to storage and no audio or words are logged.
 *
 * Why not the AAR's `AsrModule`: it reads a WAV *file* and lets Whisper choose the whole prompt; on the headset that
 * returned nothing for most one-word commands ("stop", "describe") and heard Spanish as other languages
 * (`docs/benchmarks.md` "Voice commands").
 *
 * One thread ("vysion-asr") loads the models, runs every transcription and closes them, so no `Module` is used from
 * two threads. It runs outside the inference `Mutex` (ADR-0011: a voice "stop" must not queue behind a Describe).
 *
 * State: [State.LOADING] until the models load, then [State.READY]; [State.FAILED] after a failed load or
 * [MAX_FAILURES] failed transcriptions in a row, and from then on [WhisperRecognizerPort] uses the system
 * recognizer.
 */
class WhisperTranscriber(private val resolve: () -> AsrModelFiles) : Closeable {

    enum class State { LOADING, READY, FAILED }

    @Volatile
    var state: State = State.LOADING
        private set

    /** Silero VAD, loaded after Whisper; `null` when its file is missing or failed to load. Used by the mic thread. */
    @Volatile
    internal var vad: SileroVad? = null
        private set

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "vysion-asr") }
    private var model: Module? = null
    private var preprocessor: Module? = null
    private var tokenizer: WhisperTokenizer? = null
    private var failures = 0

    init {
        executor.execute(::load)
    }

    /**
     * Transcribes [samples] 16 kHz mono samples of [pcm] on the ASR thread (after the load, if it is still running);
     * [languageTag] is the app's language ("en-US" / "es-MX"), which Whisper's language choice leans toward
     * ([WhisperDecoding.LANGUAGE_BIAS]). [done] gets the cleaned text ("" when Whisper heard no words), or `null` when
     * the model is unavailable or the call failed. [done] runs on the ASR thread.
     */
    fun transcribe(pcm: ShortArray, samples: Int, languageTag: String, done: (String?) -> Unit) {
        val spanish = languageTag.lowercase().startsWith("es")
        val submitted = runCatching { executor.execute { done(transcribeNow(pcm, samples, spanish)) } }
        if (submitted.isFailure) done(null) // closed
    }

    private fun transcribeNow(pcm: ShortArray, samples: Int, preferSpanish: Boolean): String? {
        val m = model ?: return null
        val pre = preprocessor ?: return null
        val tok = tokenizer ?: return null
        return try {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val audio = FloatArray(samples) { pcm[it] / PCM_SCALE }
            val features = pre.execute(METHOD_FORWARD, EValue.from(Tensor.fromBlob(audio, longArrayOf(samples.toLong()))))[0]
            val t1 = SystemClock.elapsedRealtimeNanos()
            val encoded = m.execute(METHOD_ENCODER, features)[0]
            val t2 = SystemClock.elapsedRealtimeNanos()
            val token = LongArray(1)
            val position = LongArray(1)
            val result = WhisperDecoding.greedy(MAX_TEXT_TOKENS, preferSpanish) { id, pos ->
                token[0] = id
                position[0] = pos
                m.execute(
                    METHOD_DECODER,
                    EValue.from(Tensor.fromBlob(token, TOKEN_SHAPE)),
                    encoded,
                    EValue.from(Tensor.fromBlob(position, POSITION_SHAPE)),
                )[0].toTensor().dataAsFloatArray
            }
            val t3 = SystemClock.elapsedRealtimeNanos()
            failures = 0
            Log.i(
                LATENCY_TAG,
                "asr transcribe_ms=${(t3 - t0) / 1_000_000} preprocess_ms=${(t1 - t0) / 1_000_000} " +
                    "encoder_ms=${(t2 - t1) / 1_000_000} decode_ms=${(t3 - t2) / 1_000_000} steps=${result.steps} " +
                    "lang=${result.language} es_minus_en=${"%.2f".format(result.spanishMargin)} " +
                    "audio_ms=${samples * 1000L / SAMPLE_RATE_HZ}",
            ) // durations, step count and language only, never the words
            WhisperText.clean(tok.decode(result.text))
        } catch (t: Throwable) {
            failures++
            Log.w(TAG, "asr transcription failed ($failures in a row): ${t.javaClass.simpleName} ${t.message}")
            if (failures >= MAX_FAILURES) {
                state = State.FAILED
                closeModels()
                Log.w(TAG, "asr: Whisper off for this run; the system recognizer takes over")
            }
            null
        }
    }

    private fun load() {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val files = try {
            resolve()
        } catch (t: Throwable) {
            state = State.FAILED
            Log.w(TAG, "asr: Whisper files unavailable (${t.message}); using the system recognizer")
            return
        }
        try {
            model = Module.load(files.model.absolutePath, Module.LOAD_MODE_MMAP, THREADS).also {
                it.loadMethod(METHOD_ENCODER)
                it.loadMethod(METHOD_DECODER)
            }
            preprocessor = Module.load(files.preprocessor.absolutePath, Module.LOAD_MODE_MMAP, THREADS)
                .also { it.loadMethod(METHOD_FORWARD) }
            tokenizer = WhisperTokenizer.load(files.tokenizer)
            state = State.READY
            Log.i(LATENCY_TAG, "asr load_ms=${(SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000} (Whisper ready)")
        } catch (t: Throwable) {
            state = State.FAILED
            closeModels()
            Log.w(TAG, "asr: Whisper failed to load (${t.javaClass.simpleName} ${t.message}); using the system recognizer")
            return
        }
        files.vad?.let { file ->
            vad = runCatching { SileroVad(file) }
                .onFailure { Log.w(TAG, "asr: VAD failed to load (${it.message}); clips end on release, Done or the cap") }
                .getOrNull()
        }
        if (files.vad == null) Log.i(TAG, "asr: no VAD file; clips end on release, Done or the cap")
    }

    private fun closeModels() {
        runCatching { model?.close() }
        model = null
        runCatching { preprocessor?.close() }
        preprocessor = null
        tokenizer = null
        runCatching { vad?.close() }
        vad = null
    }

    /** Once, from `WhisperRecognizerPort.destroy`; a transcription already running finishes first. */
    override fun close() {
        state = State.FAILED
        runCatching { executor.execute(::closeModels) }
        executor.shutdown()
    }

    companion object {
        const val SAMPLE_RATE_HZ: Int = 16_000

        /**
         * Words, end-of-text excluded, after the four prompt tokens. A 7.5 s clip holds about 20 spoken words (~28
         * tokens); each decoder step costs ~130 ms on the Galaxy XR (fp32), so this bounds a runaway decode at ~4 s.
         */
        const val MAX_TEXT_TOKENS: Int = 28

        /** Failed transcriptions in a row before the run switches to the system recognizer. */
        const val MAX_FAILURES: Int = 2

        /**
         * `Module.load` resizes ExecuTorch's one global XNNPACK thread pool to its thread count (cores / 2 = 3 when
         * none is given), under the VLM and the detector and while they may be running. 4 is what they load with
         * (`VlmRunners.DEFAULT_THREADS`, `DETECTOR_THREADS`), so these loads leave the pool as it is.
         */
        internal const val THREADS = 4

        /** int16 to [-1, 1], as ExecuTorch's `wav_loader.h` does before the preprocessor. */
        private const val PCM_SCALE = 32_767f
        private const val METHOD_FORWARD = "forward"
        private const val METHOD_ENCODER = "encoder"
        private const val METHOD_DECODER = "text_decoder"
        private val TOKEN_SHAPE = longArrayOf(1, 1)
        private val POSITION_SHAPE = longArrayOf(1)
        private const val TAG = "vysion"
        private const val LATENCY_TAG = "vysion.latency"
    }
}

/**
 * Whisper's greedy decoding with the prompt fixed here instead of left to the model. Pure, JVM-tested.
 *
 * Step 0 feeds `<|startoftranscript|>`; its logits choose the language between English and Spanish only (the two
 * the app speaks; left free, Whisper heard "lee esto" as other languages), leaning [LANGUAGE_BIAS] toward the app's
 * language. Then `<|transcribe|>` and
 * `<|notimestamps|>` are fed, and text tokens are taken greedily until `<|endoftext|>`. The first text token may not
 * be end-of-text or a bare space (Whisper's own "suppress blank" rule): without it Whisper ended at once on most
 * one-word commands. Special and timestamp tokens are never taken.
 */
object WhisperDecoding {
    const val END_OF_TEXT: Long = 50_257
    const val START_OF_TRANSCRIPT: Long = 50_258
    const val ENGLISH: Long = 50_259
    const val SPANISH: Long = 50_262
    const val TRANSCRIBE: Long = 50_359
    const val NO_TIMESTAMPS: Long = 50_363

    /** GPT-2's "Ġ" alone: a space with no word. */
    const val BLANK: Long = 220

    /**
     * Logits added to the app's language before the choice. Chosen from the 87 headset clips (`docs/benchmarks.md`
     * "Voice commands"): the raw Spanish-minus-English margin was -1.8 or lower on every English clip and -3.7 to
     * +5.1 on Spanish ones; 1 gave every right answer that always taking the app's language did (Spanish 26 → 28 of
     * 42) and still hears English said with the app in Spanish.
     */
    const val LANGUAGE_BIAS: Float = 1f

    /**
     * [text] excludes the prompt and end-of-text; [steps] counts decoder calls; [language] is "en" or "es";
     * [spanishMargin] is the raw Spanish logit minus the English one, before any bias.
     */
    data class Result(val language: String, val text: List<Long>, val steps: Int, val spanishMargin: Float)

    /**
     * @param preferSpanish the app speaks Spanish now, so the choice leans [bias] toward Spanish (else toward English).
     * @param step one decoder call: the token at a position in, the logits over the whole vocabulary out.
     */
    fun greedy(
        maxTextTokens: Int,
        preferSpanish: Boolean = false,
        bias: Float = LANGUAGE_BIAS,
        step: (token: Long, position: Long) -> FloatArray,
    ): Result {
        val first = step(START_OF_TRANSCRIPT, 0)
        val margin = first[SPANISH.toInt()] - first[ENGLISH.toInt()]
        val language = if (margin + (if (preferSpanish) bias else -bias) > 0f) SPANISH else ENGLISH
        step(language, 1)
        step(TRANSCRIBE, 2)
        var logits = step(NO_TIMESTAMPS, 3)
        var steps = 4
        val text = ArrayList<Long>()
        while (text.size < maxTextTokens) {
            val next = argmax(logits, firstToken = text.isEmpty())
            if (next == END_OF_TEXT) break
            text += next
            if (text.size == maxTextTokens) break // no step whose logits nobody reads
            logits = step(next, (3 + text.size).toLong())
            steps++
        }
        return Result(if (language == SPANISH) "es" else "en", text, steps, margin)
    }

    /** The best ordinary token, or end-of-text; at the first text position neither end-of-text nor a bare space. */
    internal fun argmax(logits: FloatArray, firstToken: Boolean): Long {
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        val last = END_OF_TEXT.toInt()
        for (i in 0..last) {
            if (firstToken && (i == last || i == BLANK.toInt())) continue
            if (logits[i] > bestScore) {
                bestScore = logits[i]
                best = i
            }
        }
        return best.toLong()
    }
}

/**
 * Token ids to text for Whisper's byte-level BPE (GPT-2's): each vocabulary entry is a string of stand-in characters,
 * one per byte (GPT-2 `bytes_to_unicode`); joining the entries and mapping the characters back gives UTF-8 bytes.
 * Only ordinary tokens are decoded; [WhisperDecoding] never hands over special ones.
 */
class WhisperTokenizer(private val vocab: Array<String?>) {

    fun decode(ids: List<Long>): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            val piece = vocab.getOrNull(id.toInt()) ?: continue
            for (ch in piece) {
                val b = BYTE_OF.getOrElse(ch.code) { -1 }
                if (b >= 0) bytes.write(b)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    companion object {
        /** GPT-2 `bytes_to_unicode`, inverted: stand-in character code to byte, -1 where none. */
        private val BYTE_OF: IntArray = IntArray(512) { -1 }.also { table ->
            val printable = ((0x21..0x7E) + (0xA1..0xAC) + (0xAE..0xFF)).toSet()
            printable.forEach { table[it] = it }
            var n = 0
            for (b in 0..255) {
                if (b !in printable) {
                    table[256 + n] = b
                    n++
                }
            }
        }

        /** Reads `model.vocab` (`{"token": id}`) from a Hugging Face `tokenizer.json`; ~2.5 MB, read once. */
        fun load(file: File): WhisperTokenizer {
            val entries = ArrayList<Pair<String, Int>>(51_000)
            JsonReader(file.bufferedReader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "model") {
                        r.skipValue()
                        continue
                    }
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() != "vocab") {
                            r.skipValue()
                            continue
                        }
                        r.beginObject()
                        while (r.hasNext()) entries += r.nextName() to r.nextInt()
                        r.endObject()
                    }
                    r.endObject()
                }
                r.endObject()
            }
            require(entries.isNotEmpty()) { "no model.vocab in ${file.name}" }
            val vocab = arrayOfNulls<String>(entries.maxOf { it.second } + 1)
            entries.forEach { (token, id) -> vocab[id] = token }
            return WhisperTokenizer(vocab)
        }
    }
}

/**
 * Silero VAD (16 kHz) as a plain ExecuTorch `Module` (ExecuTorch `examples/models/silero_vad`): method `forward(x
 * [1, 576], state [2, 1, 128]) -> (probability [1, 1], state)`, where `x` is the last 64 samples of the previous
 * window followed by one 512-sample window (32 ms). One mic session at a time feeds it; [close] can come from the
 * ASR thread, so both are synchronized and a closed VAD answers 0 (silence) instead of touching freed memory.
 */
internal class SileroVad(file: File) : Closeable {
    private val module: Module =
        Module.load(file.absolutePath, Module.LOAD_MODE_MMAP, WhisperTranscriber.THREADS).also { it.loadMethod(METHOD) }
    private val input = FloatArray(CONTEXT + WINDOW)
    private var state = FloatArray(STATE_SIZE)
    private var closed = false

    /** Clears the context and the LSTM state; once per clip. */
    @Synchronized
    fun reset() {
        input.fill(0f)
        state = FloatArray(STATE_SIZE)
    }

    /** Speech probability of the [WINDOW] samples of [pcm] starting at [offset]. */
    @Synchronized
    fun probability(pcm: ShortArray, offset: Int): Float {
        if (closed) return 0f
        System.arraycopy(input, WINDOW, input, 0, CONTEXT) // the previous window's tail is this one's context
        for (i in 0 until WINDOW) input[CONTEXT + i] = pcm[offset + i] / 32_768f
        val out = module.execute(
            METHOD,
            EValue.from(Tensor.fromBlob(input, INPUT_SHAPE)),
            EValue.from(Tensor.fromBlob(state, STATE_SHAPE)),
        )
        state = out[1].toTensor().dataAsFloatArray
        return out[0].toTensor().dataAsFloatArray[0]
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        module.close()
    }

    companion object {
        const val WINDOW: Int = 512
        const val CONTEXT: Int = 64
        private const val METHOD = "forward"
        private const val STATE_SIZE = 2 * 128
        private val INPUT_SHAPE = longArrayOf(1, (CONTEXT + WINDOW).toLong())
        private val STATE_SHAPE = longArrayOf(2, 1, 128)
    }
}

/** Whisper's text to the words [CommandGrammar.parse] reads. */
object WhisperText {
    /** Any special token that slips through: `<|en|>`, `<|endoftext|>`. */
    private val SPECIAL = Regex("<\\|[^|>]*\\|>")

    /** Sound annotations Whisper writes for non-speech: `[BLANK_AUDIO]`, `(music)`, `*beep*`. */
    private val ANNOTATION = Regex("\\[[^]]*]|\\([^)]*\\)|\\*[^*]*\\*")

    private val SPACES = Regex("\\s+")

    fun clean(raw: String): String =
        raw.replace(SPECIAL, " ").replace(ANNOTATION, " ").replace(SPACES, " ").trim()
}
