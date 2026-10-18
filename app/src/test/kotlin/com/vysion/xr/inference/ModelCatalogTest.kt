package com.vysion.xr.inference

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The pure half of [ModelCatalog]: lockfile parsing, resolution order with temp dirs, and the S-03/S-11
 * verified copy. The `Application`-backed half (assets, `getExternalFilesDir`) runs only on a device.
 */
class ModelCatalogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val lock = """
        {
          "_comment": "test copy",
          "schema": 1,
          "models": [
            {"name": "vlm", "dest": "vlm.pte", "url": "TBD", "sha256": "TBD", "bytes": 0,
             "backend": "XNNPACK", "quantization": "4-bit weights", "input_width": 0, "input_height": 0,
             "export": "ml/export/export_vlm.sh",
             "source_model": "Gemma 4 edge multimodal (~2B); fallback SmolVLM-500M keeps this same dest (RK-2)"},
            {"name": "vlm-tokenizer", "dest": "vlm-tokenizer.json", "url": "TBD", "sha256": "TBD", "bytes": 0,
             "backend": "-", "quantization": "-", "input_width": 0, "input_height": 0,
             "export": "ml/export/export_vlm.sh", "source_model": "same as vlm"},
            {"name": "detector", "dest": "detector.pte",
             "url": "https://example.invalid/detector.pte",
             "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", "bytes": 12345678,
             "backend": "XNNPACK", "quantization": "int8", "input_width": 640, "input_height": 640,
             "export": "ml/export/export_detector.sh", "source_model": "YOLO26-n (COCO)"},
            {"name": "broken-row-without-dest"}
          ]
        }
    """.trimIndent()

    @Test
    fun `parse reads every row and defaults the TBD fields`() {
        val entries = ModelCatalog.parse(lock)
        assertEquals(listOf("vlm", "vlm-tokenizer", "detector"), entries.map { it.name })
        val vlm = entries[0]
        assertEquals("vlm.pte", vlm.dest)
        assertFalse(vlm.hasSha256)
        assertFalse(vlm.hasBytes)
        assertEquals("XNNPACK", vlm.backend)
        assertEquals("4-bit weights", vlm.quantization)
        assertEquals("Gemma 4 edge multimodal", vlm.displayName)
        val det = entries[2]
        assertTrue(det.hasSha256)
        assertTrue(det.hasBytes)
        assertEquals(12_345_678L, det.bytes)
        assertEquals(640, det.inputWidth)
        assertEquals(640, det.inputHeight)
        assertEquals("YOLO26-n", det.displayName)
        assertEquals("ml/export/export_detector.sh", det.export)
    }

    @Test
    fun `parse accepts the real lockfile in the repo`() {
        val repoLock = File("../ml/models.lock")
        if (!repoLock.isFile) return // running outside the repo layout
        val entries = ModelCatalog.parse(repoLock.readText())
        assertEquals(setOf("vlm", "vlm-tokenizer", "detector", "ocr", "ocr-charset"), entries.map { it.name }.toSet())
        assertTrue(entries.first { it.name == "detector" }.dest.endsWith(".pte"))
        val vlm = entries.first { it.name == "vlm" }
        assertTrue("{prompt}" in vlm.chatTemplate)
        assertEquals(512, vlm.inputWidth)
    }

    @Test
    fun `parse reads detector classes and the VLM chat format and normalization`() {
        val rows = ModelCatalog.parse(
            """{"models": [
              {"name": "detector", "dest": "detector.pte", "classes": ["door", "stairs", "person"]},
              {"name": "vlm", "dest": "vlm.pte", "chat_before_image": "<|im_start|>User:",
               "chat_template": "{prompt}<end_of_utterance>\nAssistant:", "image_mean": [0.5, 0.5, 0.5], "image_std": [0.5, 0.5, 0.5]}
            ]}""",
        )
        assertEquals(listOf("door", "stairs", "person"), rows[0].classes)
        val oiv7 = ModelCatalog.parse("""{"models": [{"name": "detector", "dest": "d.pte", "output_layout": "columns",
            "classes": {"164": "door", "489": "stairs", "x": "bad"}}]}""").single()
        assertEquals(mapOf(164 to "door", 489 to "stairs"), oiv7.classMap)
        assertEquals("columns", oiv7.outputLayout)
        assertTrue(oiv7.classes.isEmpty())
        assertTrue(rows[0].chatTemplate.isEmpty())
        assertEquals("<|im_start|>User:", rows[1].chatBeforeImage)
        assertEquals("{prompt}<end_of_utterance>\nAssistant:", rows[1].chatTemplate)
        assertEquals(listOf(0.5f, 0.5f, 0.5f), rows[1].imageMean)
        assertEquals(listOf(0.5f, 0.5f, 0.5f), rows[1].imageStd)
        val lfm = ModelCatalog.parse("""{"models": [{"name": "vlm", "dest": "vlm.pte", "image_format": "float_0_255"}]}""").single()
        assertEquals("float_0_255", lfm.imageFormat)
        assertTrue(rows[1].classes.isEmpty())
    }

    @Test
    fun `parse tolerates an empty models list and rejects garbage`() {
        assertTrue(ModelCatalog.parse("""{"schema": 1}""").isEmpty())
        assertTrue(ModelCatalog.parse("""{"models": []}""").isEmpty())
        try {
            ModelCatalog.parse("""{"models": [}""")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
        try {
            ModelCatalog.parse("[1, 2]")
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            // ok
        }
    }

    @Test
    fun `lock json reader handles escapes, numbers, booleans and nesting`() {
        val v = LockJson.parse(
            """{"s": "a\"b\\c\né", "n": -12, "d": 1.5e2, "t": true, "f": false, "z": null, "a": [1, {"k": []}]}""",
        ) as Map<*, *>
        assertEquals("a\"b\\c\né", v["s"])
        assertEquals(-12L, v["n"])
        assertEquals(150.0, v["d"])
        assertEquals(true, v["t"])
        assertEquals(false, v["f"])
        assertNull(v["z"])
        val a = v["a"] as List<*>
        assertEquals(1L, a[0])
        assertEquals(emptyList<Any?>(), (a[1] as Map<*, *>)["k"])
    }

    @Test
    fun `displayNameOf cuts at the first semicolon or parenthesis`() {
        assertEquals("YOLO26-n", ModelCatalog.displayNameOf("YOLO26-n (COCO)", "detector"))
        assertEquals("SmolVLM-500M", ModelCatalog.displayNameOf("SmolVLM-500M; fallback", "vlm"))
        assertEquals("vlm", ModelCatalog.displayNameOf("", "vlm"))
        assertEquals("vlm", ModelCatalog.displayNameOf("   (x)", "vlm"))
    }

    @Test
    fun `resolution prefers the external dir, then internal, and never a part file`() {
        val external = tmp.newFolder("external", "models")
        val internal = tmp.newFolder("files", "models")
        val dirs = listOf(external, internal)

        assertNull(ModelCatalog.findExisting(dirs, "detector.pte"))

        File(internal, "detector.pte").writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(File(internal, "detector.pte"), ModelCatalog.findExisting(dirs, "detector.pte"))

        File(external, "detector.pte").writeBytes(byteArrayOf(9))
        assertEquals(File(external, "detector.pte"), ModelCatalog.findExisting(dirs, "detector.pte"))

        File(external, "vlm.pte.part").writeBytes(byteArrayOf(1))
        assertNull(ModelCatalog.findExisting(dirs, "vlm.pte"))

        File(internal, "empty.pte").writeBytes(ByteArray(0))
        assertNull(ModelCatalog.findExisting(dirs, "empty.pte"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `findExisting refuses to look up a part file`() {
        ModelCatalog.findExisting(listOf(tmp.root), "vlm.pte.part")
    }

    @Test
    fun `copyVerified writes via part file, verifies bytes and sha256, then renames`() {
        val dir = File(tmp.root, "files/models") // does not exist yet: mkdirs path
        val payload = ByteArray(3 * 1024 * 1024 + 17) { (it * 31).toByte() }
        val sha = sha256(payload)
        val out =
            ModelCatalog.copyVerified(ByteArrayInputStream(payload), dir, "detector.pte", payload.size.toLong(), sha)
        assertEquals(File(dir, "detector.pte"), out)
        assertTrue(out.isFile)
        assertEquals(payload.size.toLong(), out.length())
        assertFalse(File(dir, "detector.pte.part").exists())
        assertEquals(sha, ModelCatalog.sha256Hex(out))
        assertTrue(out.readBytes().contentEquals(payload))
    }

    @Test
    fun `copyVerified skips checks the lockfile does not have yet`() {
        val dir = tmp.newFolder("models")
        val out = ModelCatalog.copyVerified(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dir, "vlm.pte", null, null)
        assertEquals(3L, out.length())
    }

    @Test
    fun `copyVerified rejects a wrong byte count and leaves no part file behind`() {
        val dir = tmp.newFolder("models")
        try {
            ModelCatalog.copyVerified(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dir, "vlm.pte", 4L, null)
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("3 bytes"))
        }
        assertFalse(File(dir, "vlm.pte").exists())
        assertFalse(File(dir, "vlm.pte.part").exists())
    }

    @Test
    fun `copyVerified rejects a wrong sha256 and keeps a previous good file`() {
        val dir = tmp.newFolder("models")
        File(dir, "vlm.pte").writeBytes(byteArrayOf(7, 7, 7))
        try {
            ModelCatalog.copyVerified(ByteArrayInputStream(byteArrayOf(1, 2, 3)), dir, "vlm.pte", 3L, "00".repeat(32))
            fail("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("sha256"))
        }
        assertTrue(File(dir, "vlm.pte").readBytes().contentEquals(byteArrayOf(7, 7, 7)))
        assertFalse(File(dir, "vlm.pte.part").exists())
    }

    @Test
    fun `copyVerified replaces an existing destination atomically`() {
        val dir = tmp.newFolder("models")
        File(dir, "vlm.pte").writeBytes(byteArrayOf(7, 7, 7))
        val out = ModelCatalog.copyVerified(ByteArrayInputStream(byteArrayOf(1, 2)), dir, "vlm.pte", 2L, null)
        assertTrue(out.readBytes().contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun `sha256Hex matches MessageDigest`() {
        val f = tmp.newFile("x.bin")
        val bytes = ByteArray(70_000) { it.toByte() }
        f.writeBytes(bytes)
        assertEquals(sha256(bytes), ModelCatalog.sha256Hex(f))
    }

    @Test
    fun `a sideload with the wrong size or hash is rejected, a TBD row accepts anything`() {
        val pinned = ModelCatalog.Entry(name = "vlm", dest = "vlm.pte", sha256 = "ab".repeat(32), bytes = 3L)
        assertNull(ModelCatalog.sizeProblem(pinned, 3L))
        assertTrue(ModelCatalog.sizeProblem(pinned, 2L)!!.contains("unfinished adb push"))
        assertNull(ModelCatalog.sha256Problem(pinned, "AB".repeat(32)))
        assertTrue(ModelCatalog.sha256Problem(pinned, "00".repeat(32))!!.contains("does not match"))

        val tbd = ModelCatalog.Entry(name = "vlm", dest = "vlm.pte")
        assertNull(ModelCatalog.sizeProblem(tbd, 1L))
        assertNull(ModelCatalog.sha256Problem(tbd, "00".repeat(32)))
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
