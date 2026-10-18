package com.vysion.xr.inference

import android.app.Application
import android.graphics.Bitmap
import java.io.File

/** DEBUG ONLY: a throwaway [ModuleVlmRunner] with an explicit thread count, for tuning `DEFAULT_THREADS` on the device. */
internal object SmokeVlm {
    fun describe(app: Application, bitmap: Bitmap, prompt: String, threads: Int): String {
        val dir = File(app.getExternalFilesDir(null), "models")
        val entry = ModelCatalog(app).entry(ModelCatalog.VLM)
        val runner = ModuleVlmRunner(File(dir, "vlm.pte"), File(dir, "vlm-tokenizer.json"), entry, threads)
        return try {
            runner.prefill(bitmap)
            runner.generate(prompt, 30) {}
        } finally {
            runner.close()
        }
    }
}
