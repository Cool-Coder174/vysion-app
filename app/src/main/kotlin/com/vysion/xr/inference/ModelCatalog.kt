package com.vysion.xr.inference

import android.app.Application
import android.util.Log
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.ModelInfo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections

/**
 * Where the model files are and what the lockfile says about them (ADR-0007; `ml/models.lock` is the source of
 * truth, `scripts/fetch_models.sh` copies it to `assets/models/models.lock`).
 *
 * - Contract provided: `ModelInfo` for `InferenceEngine.vlmInfo / detectorInfo` (FR-G5 overlay: name,
 *   quantization, backend, file size, input dims), and absolute file paths for `Module.load` / `LlmModule`.
 *   Constructor `(Application)` per `AppGraph.kt`.
 * - FR ids: FR-G5 (every overlay field read from the file or the lockfile, never typed in), FR-T1/FR-N2 (the
 *   engine cannot load without a path).
 * - SRS §8 R2 due: "load a sample .pte on the headset to confirm the runtime works. Sat 11:00".
 * - Owner: R2 ML / ExecuTorch.
 * - API: `docs/stack.md` §3-R2 "Paths" (VERIFIED): `Module.load` and every `LlmModule` constructor call
 *   `ExecuTorchRuntime.validateFilePath`, so assets and content URIs are not loadable; multi-GB files are
 *   `adb push`ed to `/sdcard/Android/data/com.vysion.xr/files/models/` (readable through
 *   `getExternalFilesDir("models")` without any permission) and small ones are copied from assets to `filesDir`.
 * - Assumptions: A-11 (copy to `filesDir` on first launch, model files only, never frames), A-15 / Q20 (a
 *   multi-GB `.pte` in the external files dir passes `validateFilePath` and loads with `LOAD_MODE_MMAP`),
 *   RK-11 (lockfile rows may still say `TBD`).
 *
 * Resolution order for an artifact `dest` (plan.md Q1): `getExternalFilesDir("models")/dest` (sideloaded, dev
 * builds; keeps the multi-GB models out of the dev APK for the 15-minute merge cadence) → `filesDir/models/dest`
 * (copied from `assets/models/dest` on first launch, release builds). The copy follows plan.md S-03 / S-11:
 * stream with `InputStream.copyTo` into `dest.part`, verify the byte count and sha256 against the lockfile when
 * they are not `TBD`, then rename onto `dest`. A `.part` is never loaded; a torn copy therefore never becomes
 * `dest`. The copy is the only write this app ever makes to storage (ADR-0003; `scripts/check_privacy.sh` honors the
 * `privacy-allow` marker on that one line).
 *
 * Integrity (plan.md `## Security`: "sha256 must match before Module.load", fail closed): a sideloaded file whose
 * size differs from a non-`TBD` lockfile `bytes` (a truncated or still-running `adb push`) or whose sha256 differs
 * from a non-`TBD` lockfile `sha256` is NOT returned; the mismatch is logged under `vysion.security` and resolution
 * falls through to the bundled copy (or fails with "model not found"). The multi-GB hash runs once per process per
 * `(path, length, lastModified)` ([verifiedSideloads]); [resolve] runs from `warmUp`, off the main thread.
 *
 * Lockfile: `assets/models/models.lock` (copied by `scripts/fetch_models.sh`, gitignored), falling back to
 * `getExternalFilesDir("models")/models.lock` (pushed by `scripts/push_models.sh`), so a dev APK built from a
 * fresh clone still resolves sideloaded models once `push_models.sh` ran.
 *
 * Everything that does not need `Application` lives in the companion and is JVM-tested: lockfile parsing
 * ([parse]; a 60-line JSON reader instead of `org.json`, which is only a stub on the unit-test classpath),
 * path resolution ([findExisting]) and the verified copy ([copyVerified]).
 */
class ModelCatalog(private val app: Application) {

    /** One row of `ml/models.lock`. `url`/`sha256` may be [TBD] and `bytes` 0 until R2 publishes the artifact. */
    data class Entry(
        val name: String,
        val dest: String,
        val url: String = TBD,
        val sha256: String = TBD,
        val bytes: Long = 0L,
        val backend: String = "",
        val quantization: String = "",
        val inputWidth: Int = 0,
        val inputHeight: Int = 0,
        val export: String = "",
        val sourceModel: String = "",
        /**
         * Detector class names in id order (optional `classes` array). Empty means COCO80: a model exported with
         * other classes (Open Images, or open-vocabulary prompts such as "door") names them here, so its labels are
         * right without a code change.
         */
        val classes: List<String> = emptyList(),
        /**
         * The object form of `classes` (`{"164": "door", "489": "stairs"}`): only these class ids are decoded and
         * every other is dropped. For large label sets (Open Images, 601) where Navigation needs a dozen.
         */
        val classMap: Map<Int, String> = emptyMap(),
        /** Detector output layout: `"rows"` (default; `[1, N, 6]` post-NMS) or `"columns"` (`[1, 4 + nc, N]`, NMS here). */
        val outputLayout: String = "",
        /**
         * VLM chat format, from the model card (optional). [chatBeforeImage] is prefilled before the image;
         * [chatTemplate] wraps the prompt after it and must contain `{prompt}`. Both empty: the raw prompt, which
         * suits exports whose runner applies the template itself.
         */
        val chatBeforeImage: String = "",
        val chatTemplate: String = "",
        /**
         * VLM image normalization (optional, per channel RGB). Empty: the image goes in as uint8 0..255
         * (`prefillImages(int[])`, for encoders that normalize inside the graph); else `(v / 255 - mean) / std` as
         * float (`prefillImages(float[])`).
         */
        val imageMean: List<Float> = emptyList(),
        val imageStd: List<Float> = emptyList(),
        /**
         * `"float_0_255"`: raw pixel values as float (`prefillImages(float[])`), for encoders that rescale and
         * normalize inside the graph but take float input (LFM2.5-VL). Otherwise empty and [imageMean] decides.
         */
        val imageFormat: String = "",
    ) {
        val hasSha256: Boolean get() = sha256.isNotBlank() && !sha256.equals(TBD, ignoreCase = true)
        val hasBytes: Boolean get() = bytes > 0L

        /** Overlay name: the `source_model` text up to its first `;` or `(`, else the row name. */
        val displayName: String get() = displayNameOf(sourceModel, name)
    }

    /** Parsed once from `assets/models/models.lock`, else the sideloaded copy; empty (with a warning) if neither. */
    val entries: List<Entry> by lazy { loadEntries() }

    fun entry(name: String): Entry? = entries.firstOrNull { it.name == name }

    /**
     * Absolute path of the artifact named [name], copying it from assets on first use.
     * @throws InferenceException when the lockfile has no such row or the file is nowhere (the README's
     *   troubleshooting row "App says model not found").
     */
    fun resolve(name: String): File {
        val entry = entry(name)
            ?: throw InferenceException("no row named '$name' in assets/$LOCK_ASSET; run scripts/fetch_models.sh")
        val external = externalDir()
        val internal = internalDir()

        var sideloadRejected: String? = null
        external?.let { dir ->
            findExisting(listOf(dir), entry.dest)?.let { file ->
                val problem = sideloadProblem(entry, file)
                if (problem == null) return file
                // Fail closed (plan.md Security): never hand a torn or foreign file to the native .pte parser.
                Log.w(SECURITY_TAG, "model integrity: $problem; not loaded")
                sideloadRejected = problem
            }
        }
        findExisting(listOf(internal), entry.dest)?.let { file ->
            if (!entry.hasBytes || file.length() == entry.bytes) return file
            // Lockfile changed under a previous copy (model swap, RK-2): a stale file must not be loaded.
            Log.w(TAG, "${entry.dest}: copied size ${file.length()} differs from lockfile ${entry.bytes}; re-copying")
            file.delete()
        }

        val source: InputStream = try {
            app.assets.open("$ASSET_DIR/${entry.dest}")
        } catch (e: IOException) {
            val rejected = sideloadRejected?.let { " (sideloaded copy rejected: $it)" } ?: ""
            throw InferenceException(
                "model not found: ${entry.dest} is neither in ${external?.absolutePath ?: "<no external files dir>"} " +
                    "(adb push <file> /sdcard/Android/data/${app.packageName}/files/$EXTERNAL_SUBDIR/) nor in assets " +
                    "(scripts/fetch_models.sh); build it with ${entry.export.ifBlank { "ml/export/*.sh" }}$rejected",
                e,
            )
        }
        Log.i(TAG, "copying ${entry.dest} from assets to ${internal.absolutePath} (A-11, first launch)")
        return try {
            copyVerified(
                source = source,
                dir = internal,
                dest = entry.dest,
                expectedBytes = entry.bytes.takeIf { entry.hasBytes },
                expectedSha256 = entry.sha256.takeIf { entry.hasSha256 },
            )
        } catch (e: IOException) {
            Log.w(SECURITY_TAG, "model integrity: ${entry.dest} copy rejected: ${e.message}") // size/sha256 or I/O
            throw InferenceException("model copy failed: ${entry.dest}", e) // plan.md S-01/S-02
        }
    }

    /** `(absolutePath|length|lastModified)` of sideloads whose sha256 already matched in this process. */
    private val verifiedSideloads: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** `null` when [file] matches [entry]'s non-`TBD` size and sha256, else why not. Hashes once per file version. */
    private fun sideloadProblem(entry: Entry, file: File): String? {
        sizeProblem(entry, file.length())?.let { return it }
        if (!entry.hasSha256) return null
        val key = "${file.absolutePath}|${file.length()}|${file.lastModified()}"
        if (key in verifiedSideloads) return null
        val actual = try {
            sha256Hex(file)
        } catch (e: IOException) {
            return "${entry.dest}: cannot hash sideloaded file (${e.message})"
        }
        sha256Problem(entry, actual)?.let { return it }
        verifiedSideloads += key
        return null
    }

    /** [ModelInfo] for the overlay (FR-G5): lockfile facts plus the real `File.length()` of the resolved file. */
    fun modelInfo(name: String): ModelInfo = modelInfo(name, resolve(name))

    /** As [modelInfo], for a [file] the caller already resolved (no second resolution or hash). */
    fun modelInfo(name: String, file: File): ModelInfo {
        val entry = entry(name) ?: throw InferenceException("no row named '$name' in assets/$LOCK_ASSET")
        return ModelInfo(
            name = entry.displayName,
            quantization = entry.quantization,
            backend = entry.backend,
            fileSizeBytes = file.length(),
            inputWidth = entry.inputWidth,
            inputHeight = entry.inputHeight,
        )
    }

    /**
     * Full sha256 re-check of the resolved file against the lockfile (plan.md "Integrity of model files":
     * `ModelCatalog.verify()`). `true` when the row has no sha256 yet (nothing to compare), or it matches.
     * Always re-hashes, so call it from a benchmark or a debug command; [resolve] already checks a sideload once
     * per process and [copyVerified] checks a bundled copy once before its rename.
     */
    fun verify(name: String): Boolean {
        val entry = entry(name) ?: return false
        if (!entry.hasSha256) return true
        val file = findExisting(candidateDirs(), entry.dest) ?: return false
        return sha256Hex(file).equals(entry.sha256, ignoreCase = true)
    }

    private fun externalDir(): File? = app.getExternalFilesDir(EXTERNAL_SUBDIR)

    private fun internalDir(): File = File(app.filesDir, INTERNAL_SUBDIR)

    private fun candidateDirs(): List<File> = listOfNotNull(externalDir(), internalDir())

    private fun loadEntries(): List<Entry> {
        val text = try {
            app.assets.open(LOCK_ASSET).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            val pushed = externalDir()?.let { File(it, LOCK_FILE) }?.takeIf { it.isFile }
            if (pushed == null) {
                Log.w(
                    TAG,
                    "assets/$LOCK_ASSET missing (${e.message}) and no sideloaded $LOCK_FILE; run " +
                        "scripts/fetch_models.sh then rebuild, or scripts/push_models.sh. No models will resolve.",
                )
                return emptyList()
            }
            Log.i(TAG, "assets/$LOCK_ASSET missing; using the sideloaded ${pushed.absolutePath} (push_models.sh)")
            try {
                pushed.readText()
            } catch (io: IOException) {
                Log.w(TAG, "${pushed.absolutePath} unreadable: ${io.message}")
                return emptyList()
            }
        }
        return try {
            parse(text)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "$LOCK_FILE unreadable: ${e.message}")
            emptyList()
        }
    }

    companion object {
        const val TAG: String = "vysion"

        /** plan.md Security "Detection": integrity failures go to logcat under this tag. */
        const val SECURITY_TAG: String = "vysion.security"

        /** File name of the lockfile, in assets and in the sideload directory. */
        const val LOCK_FILE: String = "models.lock"

        /** Asset path of the lockfile copy `scripts/fetch_models.sh` writes. */
        const val LOCK_ASSET: String = "models/models.lock"

        /** Asset directory holding the bundled `.pte` files (release builds). */
        const val ASSET_DIR: String = "models"

        /** `getExternalFilesDir(EXTERNAL_SUBDIR)` = `/sdcard/Android/data/com.vysion.xr/files/models/`. */
        const val EXTERNAL_SUBDIR: String = "models"

        /** `filesDir/models/`, the copy target (A-11). */
        const val INTERNAL_SUBDIR: String = "models"

        const val TBD: String = "TBD"
        const val PART_SUFFIX: String = ".part"

        /** Row names in `ml/models.lock`. */
        const val VLM: String = "vlm"
        const val VLM_TOKENIZER: String = "vlm-tokenizer"
        const val DETECTOR: String = "detector"
        const val OCR: String = "ocr"
        const val OCR_CHARSET: String = "ocr-charset"

        /**
         * Parse the lockfile text. Rows without `name` and `dest` are skipped; other fields default.
         * @throws IllegalArgumentException on malformed JSON.
         */
        fun parse(json: String): List<Entry> {
            val root = LockJson.parse(json) as? Map<*, *>
                ?: throw IllegalArgumentException("lockfile root is not an object")
            val models = root["models"] as? List<*> ?: return emptyList()
            return models.mapNotNull { row ->
                val obj = row as? Map<*, *> ?: return@mapNotNull null
                val name = obj.str("name") ?: return@mapNotNull null
                val dest = obj.str("dest") ?: return@mapNotNull null
                Entry(
                    name = name,
                    dest = dest,
                    url = obj.str("url") ?: TBD,
                    sha256 = obj.str("sha256") ?: TBD,
                    bytes = obj.num("bytes"),
                    backend = obj.str("backend") ?: "",
                    quantization = obj.str("quantization") ?: "",
                    inputWidth = obj.num("input_width").toInt(),
                    inputHeight = obj.num("input_height").toInt(),
                    export = obj.str("export") ?: "",
                    sourceModel = obj.str("source_model") ?: "",
                    classes = (obj["classes"] as? List<*>).orEmpty().filterIsInstance<String>(),
                    classMap = (obj["classes"] as? Map<*, *>).orEmpty().mapNotNull { (k, v) ->
                        val id = (k as? String)?.toIntOrNull() ?: return@mapNotNull null
                        val label = (v as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        id to label
                    }.toMap(),
                    outputLayout = obj.str("output_layout") ?: "",
                    chatBeforeImage = obj["chat_before_image"] as? String ?: "",
                    chatTemplate = obj["chat_template"] as? String ?: "",
                    imageMean = (obj["image_mean"] as? List<*>).orEmpty().filterIsInstance<Number>().map { it.toFloat() },
                    imageStd = (obj["image_std"] as? List<*>).orEmpty().filterIsInstance<Number>().map { it.toFloat() },
                    imageFormat = obj.str("image_format") ?: "",
                )
            }
        }

        /**
         * First directory in [dirs] that holds a regular, non-empty file named [dest]. A `dest.part` never
         * counts: it is an unfinished copy (plan.md S-11).
         */
        fun findExisting(dirs: List<File>, dest: String): File? {
            require(!dest.endsWith(PART_SUFFIX)) { "never resolve a .part file: $dest" }
            return dirs.asSequence()
                .map { File(it, dest) }
                .firstOrNull { it.isFile && it.length() > 0L }
        }

        /**
         * Stream [source] into `dir/dest.part`, verify, then rename onto `dir/dest` (S-03 short reads are handled
         * by `copyTo`; S-11 no in-place overwrite). On any mismatch the `.part` is deleted and an [IOException]
         * is thrown, so the caller can speak an error and a later launch retries.
         *
         * Pure `java.io`: unit-tested with temp directories.
         */
        fun copyVerified(
            source: InputStream,
            dir: File,
            dest: String,
            expectedBytes: Long?,
            expectedSha256: String?,
        ): File {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.absolutePath}")
            val part = File(dir, dest + PART_SUFFIX)
            val target = File(dir, dest)
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            try {
                source.use { input ->
                    FileOutputStream(part).use { output -> // privacy-allow: model files only (A-11), never frames
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            total += n
                        }
                        copied = total
                    }
                }
                if (expectedBytes != null && copied != expectedBytes) {
                    throw IOException("$dest: copied $copied bytes, lockfile says $expectedBytes")
                }
                if (expectedSha256 != null) {
                    val actual = digest.digest().toHex()
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        throw IOException("$dest: sha256 $actual does not match lockfile $expectedSha256")
                    }
                }
                if (target.exists() && !target.delete()) throw IOException("cannot replace ${target.absolutePath}")
                if (!part.renameTo(target)) throw IOException("cannot rename ${part.name} onto ${target.name}")
                return target
            } catch (e: IOException) {
                part.delete()
                throw e
            }
        }

        /** Fail-closed size rule for a sideload: `null` when the lockfile has no size yet or it matches. */
        fun sizeProblem(entry: Entry, actualBytes: Long): String? =
            if (entry.hasBytes && actualBytes != entry.bytes) {
                "${entry.dest}: size $actualBytes differs from lockfile ${entry.bytes} (unfinished adb push?)"
            } else {
                null
            }

        /** Fail-closed hash rule: `null` when the lockfile has no sha256 yet or it matches (case-insensitive). */
        fun sha256Problem(entry: Entry, actualSha256: String): String? =
            if (entry.hasSha256 && !actualSha256.equals(entry.sha256, ignoreCase = true)) {
                "${entry.dest}: sha256 $actualSha256 does not match lockfile ${entry.sha256}"
            } else {
                null
            }

        /** Lower-case hex sha256 of [file], streamed. */
        fun sha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(COPY_BUFFER_BYTES)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().toHex()
        }

        /** `"Gemma 3 4B (4-bit); option C"` → `"Gemma 3 4B"`; blank → [fallback]. */
        fun displayNameOf(sourceModel: String, fallback: String): String {
            val cut = sourceModel.indexOfAny(charArrayOf(';', '('))
            val head = (if (cut >= 0) sourceModel.substring(0, cut) else sourceModel).trim()
            return head.ifBlank { fallback }
        }

        private const val COPY_BUFFER_BYTES: Int = 1 shl 20

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun Map<*, *>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotBlank() }

        private fun Map<*, *>.num(key: String): Long = when (val v = this[key]) {
            is Number -> v.toLong()
            is String -> v.toLongOrNull() ?: 0L
            else -> 0L
        }
    }
}

/**
 * Minimal JSON reader for the lockfile (objects → `Map<String, Any?>`, arrays → `List<Any?>`, strings, numbers
 * as `Long`/`Double`, booleans, null). `org.json` is a stub in the unit-test `android.jar`, and no JSON library
 * is pinned in `gradle/libs.versions.toml` (AGENTS.md §5: no new dependency without a `docs/stack.md` line), so
 * this keeps [ModelCatalog.parse] pure and JVM-testable. Not a general-purpose parser: enough for the lockfile.
 */
internal object LockJson {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.pos == text.length) { "trailing characters at ${p.pos}" }
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end of JSON" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) number() else throw IllegalArgumentException("bad JSON at $pos")
            }
        }

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (peek() == '}') {
                pos++
                return out
            }
            while (true) {
                skipWs()
                require(peek() == '"') { "expected key at $pos" }
                val key = str()
                skipWs()
                require(peek() == ':') { "expected ':' at $pos" }
                pos++
                out[key] = value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (peek() == ']') {
                pos++
                return out
            }
            while (true) {
                out += value()
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "unterminated escape" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad \\u escape" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e' at ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun number(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val token = s.substring(start, pos)
            return token.toLongOrNull() ?: token.toDoubleOrNull()
                ?: throw IllegalArgumentException("bad number '$token'")
        }

        private fun literal(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else '\u0000'
    }
}
