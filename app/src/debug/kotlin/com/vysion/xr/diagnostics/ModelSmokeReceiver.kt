package com.vysion.xr.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import com.vysion.xr.VysionApplication
import com.vysion.xr.core.InferenceEngine
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * DEBUG BUILDS ONLY. Runs one model once on a test image, so a sideloaded `.pte` can be checked on the headset with
 * nobody wearing it (the camera needs a worn headset; this does not):
 *
 * ```
 * adb push sign.png /sdcard/Android/data/com.vysion.xr/files/smoke/sign.png
 * adb shell am broadcast -a com.vysion.xr.SMOKE -n com.vysion.xr/.diagnostics.ModelSmokeReceiver \
 *     --es model ocr|vlm|detector --es image sign.png [--es prompt "Describe this."]
 * adb logcat -s vysion.smoke vysion.latency vysion
 * ```
 *
 * The image is a test fixture pushed by the developer, never a camera frame (ADR-0003); the receiver writes nothing
 * and is guarded by `android.permission.DUMP` like [BenchmarkReceiver]. Only file names under `files/smoke/` are
 * accepted, so the broadcast cannot point the app at another path.
 */
class ModelSmokeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val app = context.applicationContext as? VysionApplication ?: return
        val model = intent.getStringExtra(EXTRA_MODEL)?.lowercase()
        val name = intent.getStringExtra(EXTRA_IMAGE)?.takeIf { NAME.matches(it) }
        val dir = app.getExternalFilesDir(null)?.let { File(it, DIR) }
        if (model !in MODELS || name == null || dir == null) {
            Log.e(TAG, "rejected: model=$model image=${intent.getStringExtra(EXTRA_IMAGE)} (expected ocr|vlm|detector, a plain file name)")
            return
        }
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: DEFAULT_PROMPT
        val threads = intent.getIntExtra("threads", 0)
        if (model == "vlm-probe") {
            val pending = goAsync()
            Thread { runCatching { probeVlm(File(app.getExternalFilesDir(null), "models/vlm.pte"), intent.getIntExtra("pad", 0)) }
                .onFailure { Log.e(TAG, "vlm-probe failed: $it", it) }; pending.finish() }.start()
            return
        }
        val graph = app.graph
        val pending = goAsync()
        graph.scope.launch {
            try {
                val bitmap = BitmapFactory.decodeFile(File(dir, name).absolutePath)
                    ?: throw IllegalArgumentException("cannot decode $name in ${dir.absolutePath}")
                val engine: InferenceEngine = graph.inferenceEngine
                engine.warmUp()
                pending.finish() // a VLM answer can outlast the broadcast window
                val t0 = System.nanoTime()
                val result: String = when (model) {
                    "ocr" -> engine.readText(bitmap)?.joinToString(" | ") ?: "<no OCR model loaded>"
                    "detector" -> engine.detect(bitmap).joinToString { "${it.label} ${"%.2f".format(it.confidence)}" }
                    "vlm" -> if (threads > 0) {
                        com.vysion.xr.inference.SmokeVlm.describe(app, bitmap, prompt, threads)
                    } else {
                        engine.describe(bitmap, prompt)
                    }
                    else -> engine.describe(bitmap, prompt)
                }
                val ms = (System.nanoTime() - t0) / 1_000_000
                val w = bitmap.width // metadata only (scripts/check_privacy.sh §4)
                val h = bitmap.height
                Log.i(TAG, "$model ${w}x$h ms=$ms -> $result")
                bitmap.recycle()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "$model on $name failed: $t", t)
                runCatching { pending.finish() }
            }
        }
    }

    /**
     * Calls the VLM's three methods one at a time with the plain `Module` API and logs before each, so a native crash
     * names the method: `token_embedding` with [n] ids (padded to [pad] when pad > 0), `vision_encoder` on a gray
     * 512 x 512 image, `text_decoder` on the embeddings with cache positions 0..n-1.
     */
    private fun probeVlm(file: File, pad: Int) {
        val module = Module.load(file.absolutePath, Module.LOAD_MODE_MMAP)
        val ids = longArrayOf(1, 6, 6423, 708)
        val len = if (pad > 0) pad else ids.size
        val padded = LongArray(len).also { ids.copyInto(it) }
        Log.i(TAG, "probe: token_embedding [1, $len]")
        val emb = module.execute("token_embedding", EValue.from(Tensor.fromBlob(padded, longArrayOf(1, len.toLong()))))[0].toTensor()
        val hidden = emb.shape()[2].toInt()
        Log.i(TAG, "probe: embedding ${emb.shape().toList()}")
        Log.i(TAG, "probe: vision_encoder [1, 3, 512, 512]")
        val img = module.execute("vision_encoder", EValue.from(Tensor.fromBlob(FloatArray(3 * 512 * 512) { 128f }, longArrayOf(1, 3, 512, 512))))[0].toTensor()
        Log.i(TAG, "probe: image ${img.shape().toList()}")
        val n = ids.size
        val embeds = emb.dataAsFloatArray.copyOf(n * hidden)
        Log.i(TAG, "probe: text_decoder [1, $n, $hidden] + positions [$n]")
        val logits = module.execute(
            "text_decoder",
            EValue.from(Tensor.fromBlob(embeds, longArrayOf(1, n.toLong(), hidden.toLong()))),
            EValue.from(Tensor.fromBlob(LongArray(n) { it.toLong() }, longArrayOf(n.toLong()))),
        )[0].toTensor()
        val l = logits.dataAsFloatArray
        Log.i(TAG, "probe: logits ${logits.shape().toList()} argmax=${l.indices.maxByOrNull { l[it] }}")
        module.close()
    }

    private companion object {
        const val TAG = "vysion.smoke"
        const val ACTION = "com.vysion.xr.SMOKE"
        const val EXTRA_MODEL = "model"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_PROMPT = "prompt"
        const val DIR = "smoke"
        const val DEFAULT_PROMPT = "Describe what is in front of me in one short sentence."
        val MODELS = setOf("ocr", "vlm", "detector", "vlm-probe")
        val NAME = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
