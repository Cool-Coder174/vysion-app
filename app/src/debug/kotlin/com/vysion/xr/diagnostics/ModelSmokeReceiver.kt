package com.vysion.xr.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import com.vysion.xr.VysionApplication
import com.vysion.xr.core.InferenceEngine
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

    private companion object {
        const val TAG = "vysion.smoke"
        const val ACTION = "com.vysion.xr.SMOKE"
        const val EXTRA_MODEL = "model"
        const val EXTRA_IMAGE = "image"
        const val EXTRA_PROMPT = "prompt"
        const val DIR = "smoke"
        const val DEFAULT_PROMPT = "Describe what is in front of me in one short sentence."
        val MODELS = setOf("ocr", "vlm", "detector")
        val NAME = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
