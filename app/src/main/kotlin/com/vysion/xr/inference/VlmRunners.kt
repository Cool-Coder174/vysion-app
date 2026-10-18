package com.vysion.xr.inference

import android.graphics.Bitmap
import android.util.Log
import java.io.File
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig

/**
 * One VLM behind `ExecuTorchInferenceEngine`, always called under the engine's mutex (INV-1) except [stop], which
 * is lock-free and safe from any thread (FR-G4). [prefill] starts a fresh context with one frame; [generate] answers
 * one prompt on that context and BLOCKS until the answer ends, [stop] is called, or `maxNewTokens` is reached.
 */
internal interface VlmRunner {
    fun prefill(bitmap: Bitmap)
    fun generate(prompt: String, maxNewTokens: Int, onToken: (String) -> Unit): String
    fun stop()
    fun close()
}

/** Pixels of [bitmap] fitted to the encoder's input, in the layout the lockfile row names (INV-3: local only). */
private fun encoderPixels(bitmap: Bitmap, entry: ModelCatalog.Entry?): Triple<Any, Int, Int> {
    val fitted = VlmFrames.fit(bitmap, entry?.inputWidth ?: 0, entry?.inputHeight ?: 0)
    val w = fitted.width
    val h = fitted.height
    val argb = IntArray(w * h)
    fitted.getPixels(argb, 0, w, 0, 0, w, h)
    if (fitted !== bitmap) fitted.recycle() // ours, not the caller's (INV-9)
    val mean = entry?.imageMean.orEmpty()
    val std = entry?.imageStd.orEmpty()
    val pixels: Any = when {
        entry?.imageFormat.equals(ExecuTorchInferenceEngine.IMAGE_FORMAT_FLOAT_0_255, ignoreCase = true) -> VlmPixels.toFloatChw(argb)
        mean.size == 3 && std.size == 3 -> VlmPixels.toNormalizedChw(argb, mean, std)
        else -> VlmPixels.toUint8Chw(argb)
    }
    return Triple(pixels, w, h)
}

/**
 * The stock ExecuTorch multimodal runner (`LlmModule`, `MODEL_TYPE_MULTIMODAL`): for exports built for it (Gemma 3
 * through optimum-executorch, LLaVA). Greedy (`temperature 0`); 1.4 does not forward a new-token limit, so chunks are
 * counted with [TokenCap] and the lock-free `stop()` ends the answer.
 */
internal class LlmModuleRunner(pte: File, tokenizer: File, private val entry: ModelCatalog.Entry?) : VlmRunner {

    private val module = LlmModule(
        LlmModuleConfig.create()
            .modulePath(pte.absolutePath)
            .tokenizerPath(tokenizer.absolutePath)
            .modelType(LlmModuleConfig.MODEL_TYPE_MULTIMODAL)
            .temperature(0f)
            .loadMode(LlmModuleConfig.LOAD_MODE_MMAP)
            .build(),
    ).also { it.load() }

    override fun prefill(bitmap: Bitmap) {
        module.resetContext()
        entry?.chatBeforeImage?.takeIf { it.isNotEmpty() }?.let { module.prefillPrompt(it) }
        val (pixels, w, h) = encoderPixels(bitmap, entry)
        when (pixels) {
            is FloatArray -> module.prefillImages(pixels, w, h, 3)
            is IntArray -> module.prefillImages(pixels, w, h, 3)
        }
    }

    override fun generate(prompt: String, maxNewTokens: Int, onToken: (String) -> Unit): String {
        val text = entry?.chatTemplate?.takeIf { "{prompt}" in it }?.replace("{prompt}", prompt) ?: prompt
        val answer = StringBuilder()
        val cap = TokenCap(maxNewTokens)
        var failure: Throwable? = null
        module.generate(text, ExecuTorchInferenceEngine.SEQ_LEN_REQUEST, object : LlmCallback {
            override fun onResult(result: String) {
                if (cap.reached || failure != null) return
                try {
                    answer.append(result)
                    onToken(result)
                } catch (t: Throwable) { // never throw into the native runner
                    failure = t
                    module.stop()
                    return
                }
                if (cap.add()) module.stop() // lock-free: the one LlmModule call allowed inside a callback
            }

            override fun onStats(stats: String) {
                Log.i(ExecuTorchInferenceEngine.TAG_LATENCY, "vlm stats=$stats")
            }

            override fun onError(errorCode: Int, message: String) {
                failure = com.vysion.xr.core.InferenceException("runner error $errorCode: $message")
            }
        }, false)
        failure?.let { throw it }
        return answer.toString().trim()
    }

    override fun stop() = module.stop()

    override fun close() = module.close()
}

/**
 * A VLM driven method by method through the plain `Module` API: `token_embedding` (ids → embeddings),
 * `vision_encoder` (image → embeddings), `text_decoder` (embeddings + cache positions → last-position logits), greedy.
 * For exports the stock runner cannot drive: Software Mansion's LFM2.5-VL `.pte` loads, and each of its methods runs
 * on the headset, but `LlmModule` segfaults in its prefill (`docs/stack.md` Corrections, 2026-10-17). The chat
 * template is encoded here ([BpeTokenizer], byte-exact with Hugging Face), the image embeddings are spliced between
 * `chat_before_image` and `chat_template`, and the KV cache position is tracked here, so a follow-up on the same frame
 * (FR-D2) continues the context.
 */
internal class ModuleVlmRunner(
    pte: File,
    tokenizerFile: File,
    private val entry: ModelCatalog.Entry?,
    threads: Int = DEFAULT_THREADS,
) : VlmRunner {

    private val module: Module = Module.load(pte.absolutePath, Module.LOAD_MODE_MMAP, threads)
    private val tokenizer: BpeTokenizer = BpeTokenizer.fromJson(tokenizerFile.readText())
    private val eos: Set<Int> = listOfNotNull(tokenizer.id("<|im_end|>"), tokenizer.id("<|endoftext|>")).toSet()

    /** Next KV cache position; the context is full at [MAX_CONTEXT]. */
    private var position = 0L

    @Volatile private var stopped = false

    override fun prefill(bitmap: Bitmap) {
        val t0 = System.nanoTime()
        position = 0L
        val before = tokenizer.encode(entry?.chatBeforeImage.orEmpty())
        val (pixels, w, h) = encoderPixels(bitmap, entry)
        val image = module.execute(
            "vision_encoder",
            when (pixels) {
                is FloatArray -> EValue.from(Tensor.fromBlob(pixels, longArrayOf(1, 3, h.toLong(), w.toLong())))
                else -> EValue.from(Tensor.fromBlob((pixels as IntArray).map { it.toByte() }.toByteArray(), longArrayOf(1, 3, h.toLong(), w.toLong())))
            },
        )[0].toTensor()
        val encoded = System.nanoTime()
        val hidden = image.shape()[2].toInt()
        val prefix = if (before.isEmpty()) FloatArray(0) else embed(before)
        val imageEmbeds = image.dataAsFloatArray
        decode(prefix + imageEmbeds, (prefix.size + imageEmbeds.size) / hidden, hidden)
        val end = System.nanoTime()
        Log.i(
            ExecuTorchInferenceEngine.TAG_LATENCY,
            "vlm prefill ms=${(end - t0) / 1_000_000} (vision_encoder ${(encoded - t0) / 1_000_000}, " +
                "decoder ${(end - encoded) / 1_000_000}) tokens=$position",
        )
    }

    override fun generate(prompt: String, maxNewTokens: Int, onToken: (String) -> Unit): String {
        stopped = false
        val t0 = System.nanoTime()
        var firstTokenNanos = 0L
        val text = entry?.chatTemplate?.takeIf { "{prompt}" in it }?.replace("{prompt}", prompt) ?: prompt
        val ids = tokenizer.encode(text)
        val promptEmbeds = embed(ids)
        val hidden = promptEmbeds.size / ids.size
        var logits = decode(promptEmbeds, ids.size, hidden)
        val stream = tokenizer.StreamDecoder()
        val answer = StringBuilder()
        var produced = 0
        while (!stopped && produced < maxNewTokens && position < MAX_CONTEXT) {
            val next = argmax(logits)
            if (next in eos) break
            if (produced == 0) firstTokenNanos = System.nanoTime()
            val piece = stream.next(next)
            if (piece.isNotEmpty()) {
                answer.append(piece)
                onToken(piece)
            }
            produced++
            if (stopped || produced >= maxNewTokens) break
            logits = decode(embed(listOf(next)), 1, hidden)
        }
        stream.flush().takeIf { it.isNotEmpty() }?.let {
            answer.append(it)
            onToken(it)
        }
        if (produced > 0) {
            val end = System.nanoTime()
            val decodeMs = (end - firstTokenNanos) / 1_000_000
            Log.i(
                ExecuTorchInferenceEngine.TAG_LATENCY,
                "vlm prompt+first token ms=${(firstTokenNanos - t0) / 1_000_000} decode tokens=$produced " +
                    "tok/s=${"%.1f".format(if (decodeMs > 0) (produced - 1) * 1000f / decodeMs else 0f)}",
            )
        }
        return answer.toString().trim()
    }

    override fun stop() {
        stopped = true // checked between decode steps (each a few tens of ms), so Stop lands within the budget
    }

    override fun close() = module.close()

    private fun embed(ids: List<Int>): FloatArray {
        val input = Tensor.fromBlob(LongArray(ids.size) { ids[it].toLong() }, longArrayOf(1, ids.size.toLong()))
        return module.execute("token_embedding", EValue.from(input))[0].toTensor().dataAsFloatArray
    }

    /** Runs [count] embeddings through the decoder at the next cache positions; returns the last position's logits. */
    private fun decode(embeds: FloatArray, count: Int, hidden: Int): FloatArray {
        require(count > 0 && embeds.size == count * hidden) { "embeds ${embeds.size} != $count x $hidden" }
        val positions = LongArray(count) { position + it }
        val out = module.execute(
            "text_decoder",
            EValue.from(Tensor.fromBlob(embeds, longArrayOf(1, count.toLong(), hidden.toLong()))),
            EValue.from(Tensor.fromBlob(positions, longArrayOf(count.toLong()))),
        )[0].toTensor()
        position += count
        return out.dataAsFloatArray
    }

    private fun argmax(logits: FloatArray): Int {
        var best = 0
        for (i in 1 until logits.size) if (logits[i] > logits[best]) best = i
        return best
    }

    companion object {
        /** The export's `get_max_context_len` (2048 for LFM2.5-VL-450M). */
        private const val MAX_CONTEXT = 2048L

        /** XNNPACK threads. Measured on the Galaxy XR: 4, 6 and 8 give the same prefill (vision 4.5 s), 4 the best decode (36 tok/s). */
        const val DEFAULT_THREADS: Int = 4
    }
}
