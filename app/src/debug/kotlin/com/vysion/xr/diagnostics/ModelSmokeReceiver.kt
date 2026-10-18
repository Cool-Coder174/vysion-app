package com.vysion.xr.diagnostics

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import com.vysion.xr.VysionApplication
import com.vysion.xr.core.InferenceEngine
import kotlinx.coroutines.flow.toList
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
 * `--es model describe [--ez cloud false]` runs Describe exactly as the controller does (`describeScene` with the
 * DESCRIBE prompt, cloud requested by default): the cloud when online and a key is built in, else the headset
 * (ADR-0010). `vysion.latency: describe source=…` says which answered. `--es model cloud` sends the same request to
 * the cloud only (no headset hedge) and logs its time to the first chunk, to tell a slow network from a slow model.
 *
 * `--es model detector --ei runs 20` times [InferenceEngine.detect] on the image: one untimed warm-up call, then `runs`
 * calls, logged as `detector runs=… median=… p90=… min=… max=… ms` (the whole `detect()`: pixels, letterbox, forward,
 * decode, NMS). The row comes from the build (`-Pdetector=detector-int8`); `vysion.latency: detector load … row=` says
 * which file loaded.
 *
 * `--es model whereami [--ez cold true]` runs FR-N9 at a fixed test point (the Civic Center tile in the unit tests,
 * never the wearer's location, which needs a worn headset and Location on): the tiles (cold = dropped from memory
 * first), the on-device finder, the cloud wording and the template, with their times. No image is needed.
 * `--es model ask-whereami` asks the running app "where am I" as if spoken (the worn check, and the offline check
 * after airplane mode is turned on: the process and its tiles in memory stay, unlike a relaunch).
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
        if (model == "ask-whereami") {
            app.graph.commandBus.inject(com.vysion.xr.core.Command.WhereAmI, com.vysion.xr.core.CommandSource.TEST)
            Log.i(TAG, "ask-whereami injected")
            return
        }
        if (model == "whereami") {
            val pending = goAsync()
            app.graph.scope.launch {
                try {
                    smokeWhereAmI(app, cold = intent.getBooleanExtra(EXTRA_COLD, true))
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "whereami failed: $t", t)
                } finally {
                    pending.finish()
                }
            }
            return
        }
        val name = intent.getStringExtra(EXTRA_IMAGE)?.takeIf { NAME.matches(it) }
        val dir = app.getExternalFilesDir(null)?.let { File(it, DIR) }
        if (model !in MODELS || name == null || dir == null) {
            Log.e(TAG, "rejected: model=$model image=${intent.getStringExtra(EXTRA_IMAGE)} (expected ocr|vlm|detector|describe, a plain file name)")
            return
        }
        val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: DEFAULT_PROMPT
        val threads = intent.getIntExtra("threads", 0)
        if (model == "tts") { // how long one short sentence takes to come back from Speaker.say (callback health)
            val pending = goAsync()
            app.graph.scope.launch {
                val t0 = System.nanoTime()
                app.graph.speaker.say(app.graph.phrasebook.holdStill, interrupt = true) // a real cue, so the gate stays green
                Log.i(TAG, "tts say returned after ${(System.nanoTime() - t0) / 1_000_000} ms")
                pending.finish()
            }
            return
        }
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
                    "detector" -> {
                        val runs = intent.getIntExtra(EXTRA_RUNS, 1).coerceIn(1, MAX_RUNS)
                        var found = engine.detect(bitmap) // warm-up: XNNPACK's first-run allocations
                        if (runs > 1) {
                            val ms = List(runs) {
                                val r0 = System.nanoTime()
                                found = engine.detect(bitmap)
                                (System.nanoTime() - r0) / 1_000_000
                            }.sorted()
                            Log.i(
                                TAG,
                                "detector runs=$runs median=${BenchmarkStats.median(ms)} p90=${BenchmarkStats.p90(ms)} " +
                                    "min=${ms.first()} max=${ms.last()} ms",
                            )
                        }
                        found.joinToString { "${it.label} ${"%.2f".format(it.confidence)}" }
                    }
                    "cloud" -> {
                        val cloud = com.vysion.xr.cloudDescriber(app) ?: throw IllegalStateException("no GEMINI_API_KEY built in")
                        val jpeg = com.vysion.xr.inference.CloudJpeg.encode(bitmap)
                        val c0 = System.nanoTime()
                        var firstMs: Long? = null
                        val text = StringBuilder()
                        cloud.stream(jpeg, intent.getStringExtra(EXTRA_PROMPT) ?: graph.prompts.describe).collect {
                            if (firstMs == null) firstMs = (System.nanoTime() - c0) / 1_000_000
                            text.append(it)
                        }
                        "jpeg=${jpeg.size} B first-chunk ms=$firstMs | $text"
                    }
                    "describe" -> engine.describeScene(
                        bitmap, intent.getStringExtra(EXTRA_PROMPT) ?: graph.prompts.describe,
                        cloud = intent.getBooleanExtra(EXTRA_CLOUD, true),
                    ).toList().joinToString("")
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

    /** FR-N9 at [TEST_LAT], [TEST_LON] (a public square, not anyone's location): lookup, cloud wording, template. */
    private suspend fun smokeWhereAmI(app: VysionApplication, cold: Boolean) {
        val graph = app.graph
        val source = graph.places as? com.vysion.xr.perception.MapPlaceSource
            ?: throw IllegalStateException("places is not MapPlaceSource (useFakes?)")
        if (cold) source.forgetTiles()
        val t0 = System.nanoTime()
        val result = source.surroundingsAt(TEST_LAT, TEST_LON, TEST_ACCURACY_M, "smoke cold=$cold")
        val lookupMs = (System.nanoTime() - t0) / 1_000_000
        if (result !is com.vysion.xr.core.WhereAmI.Found) {
            Log.i(TAG, "whereami lookup ms=$lookupMs -> $result")
            return
        }
        val prompt = graph.prompts.forLocale(graph.prompts.whereAmI(result.surroundings).orEmpty(), graph.speaker.locale.value)
        val c0 = System.nanoTime()
        var firstMs: Long? = null
        val cloud = StringBuilder()
        val error = runCatching {
            graph.inferenceEngine.cloudWording(prompt).collect {
                if (firstMs == null && it.isNotBlank()) firstMs = (System.nanoTime() - c0) / 1_000_000
                cloud.append(it)
            }
        }.exceptionOrNull()
        val wordingMs = (System.nanoTime() - c0) / 1_000_000
        Log.i(TAG, "whereami lookup ms=$lookupMs wording first-chunk ms=$firstMs total ms=$wordingMs error=${error?.message}")
        Log.i(TAG, "whereami cloud: $cloud")
        Log.i(TAG, "whereami template: ${graph.phrasebook.whereAmI(result)}")
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
        const val EXTRA_CLOUD = "cloud"
        const val EXTRA_COLD = "cold"
        const val EXTRA_RUNS = "runs"
        const val MAX_RUNS = 200

        /** Larkin Street by the Main Library, San Francisco: the unit tests' Civic Center tile (FR-N9 smoke). */
        const val TEST_LAT = 37.779136
        const val TEST_LON = -122.416605
        const val TEST_ACCURACY_M = 30f
        const val DIR = "smoke"
        const val DEFAULT_PROMPT = "Describe what is in front of me in one short sentence."
        val MODELS = setOf("ocr", "vlm", "detector", "vlm-probe", "tts", "describe", "cloud", "whereami", "ask-whereami")
        val NAME = Regex("[A-Za-z0-9._-]{1,64}")
    }
}
