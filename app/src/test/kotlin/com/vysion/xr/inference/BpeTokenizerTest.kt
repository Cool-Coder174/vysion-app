package com.vysion.xr.inference

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BpeTokenizerTest {

    /** A tiny tokenizer: merges build "ab" then "abc"; one added token. */
    private val tiny = BpeTokenizer(
        vocab = mapOf("a" to 0, "b" to 1, "c" to 2, "ab" to 3, "abc" to 4, "Ġ" to 5, "Ġa" to 6, "Ã" to 7, "±" to 8, "Ã±" to 9),
        merges = listOf("a" to "b", "ab" to "c", "Ġ" to "a", "Ã" to "±"),
        added = mapOf("<|end|>" to 10),
        pattern = " ?\\p{L}+|\\s+",
    )

    @Test
    fun `merges apply by rank and added tokens split the text`() {
        assertEquals(listOf(4, 10, 5, 3), tiny.encode("abc<|end|> ab")) // "a b" outranks "Ġ a"
        assertEquals(listOf(9), tiny.encode("ñ")) // UTF-8 C3 B1 → "Ã±"
    }

    @Test
    fun `decode drops added tokens and joins a character split over two tokens`() {
        assertEquals("abc ab", tiny.decode(listOf(4, 10, 5, 3)))
        val stream = tiny.StreamDecoder()
        assertEquals("", stream.next(7)) // first byte of ñ alone is not a character yet
        assertEquals("ñ", stream.next(8))
    }

    @Test
    fun `utf8 prefix stops before an unfinished character`() {
        val bytes = "añ".toByteArray()
        assertEquals(1, BpeTokenizer.completeUtf8Prefix(bytes.copyOf(2)))
        assertEquals(3, BpeTokenizer.completeUtf8Prefix(bytes))
    }

    /** Byte-exact against Hugging Face `tokenizers` on the real LFM2.5-VL tokenizer, when it is present locally. */
    @Test
    fun `matches Hugging Face on the LFM2_5-VL tokenizer`() {
        val file = File("../ml/out/vlm-tokenizer.json")
        if (!file.isFile) return // the model files are sideloaded, not committed (ADR-0007)
        val tok = BpeTokenizer.fromJson(file.readText())
        val expected = listOf(
            "<|startoftext|><|im_start|>user\n<|image_start|>" to listOf(1, 6, 6423, 708, 498),
            "<|image_end|>Describe what is in front of me in one short sentence.<|im_end|>\n<|im_start|>assistant\n" to listOf(499, 11980, 9918, 1620, 856, 797, 4749, 803, 1123, 797, 1235, 3290, 13184, 523, 7, 708, 6, 64015, 708),
            "¿Dónde está la salida? Exit 24 — ñandú." to listOf(17995, 545, 58838, 7453, 944, 51170, 540, 2315, 785, 730, 1826, 2180, 1032, 619, 905, 2192, 523),
            "  multiple   spaces\n\nand newlines 12345 it's" to listOf(730, 5321, 767, 12914, 509, 905, 1361, 10890, 730, 10293, 2637, 936, 1090),
        )
        expected.forEach { (text, ids) -> assertEquals(text, ids, tok.encode(text)) }
        val decoded = listOf(
            "<|startoftext|><|im_start|>user\n<|image_start|>" to "user\n",
            "<|image_end|>Describe what is in front of me in one short sentence.<|im_end|>\n<|im_start|>assistant\n" to "Describe what is in front of me in one short sentence.\nassistant\n",
            "¿Dónde está la salida? Exit 24 — ñandú." to "¿Dónde está la salida? Exit 24 — ñandú.",
            "  multiple   spaces\n\nand newlines 12345 it's" to "  multiple   spaces\n\nand newlines 12345 it's",
        )
        decoded.forEach { (text, out) -> assertEquals(out, tok.decode(tok.encode(text))) }
    }
}
