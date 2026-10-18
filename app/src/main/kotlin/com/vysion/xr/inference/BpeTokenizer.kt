package com.vysion.xr.inference

import java.io.ByteArrayOutputStream

/**
 * Byte-level BPE (GPT-2 family) over a Hugging Face `tokenizer.json`, for the VLM runner that drives the model's
 * methods itself ([ModuleVlmRunner]). Covers what LFM2.5-VL's tokenizer uses: added tokens matched first (longest
 * first), the `Split` regex pre-tokenizer, the byte-to-unicode map, merges by rank. No normalizer, no post-processor:
 * the chat template in the lockfile spells out every special token, `<|startoftext|>` included. Pure Kotlin and
 * JVM-tested against Hugging Face `tokenizers` output (`BpeTokenizerTest`).
 */
internal class BpeTokenizer(
    private val vocab: Map<String, Int>,
    merges: List<Pair<String, String>>,
    /** Added tokens (`<|im_start|>` …) by content; matched verbatim before pre-tokenization. */
    private val added: Map<String, Int>,
    pattern: String,
) {
    private val ranks: Map<Pair<String, String>, Int> = merges.withIndex().associate { (i, pair) -> pair to i }
    private val idToToken: Array<String?> = arrayOfNulls<String>(maxOf(vocab.values.maxOrNull() ?: 0, added.values.maxOrNull() ?: 0) + 1)
        .also { arr ->
            vocab.forEach { (t, id) -> arr[id] = t }
            added.forEach { (t, id) -> arr[id] = t }
        }
    private val addedIds: Set<Int> = added.values.toSet()
    private val addedByLength: List<String> = added.keys.sortedByDescending { it.length }
    private val splitter = Regex(pattern)
    private val cache = HashMap<String, List<Int>>()

    val vocabSize: Int get() = idToToken.size

    fun id(token: String): Int? = added[token] ?: vocab[token]

    /** Token ids for [text]; added tokens inside it map to their ids. */
    fun encode(text: String): List<Int> {
        val out = ArrayList<Int>()
        var i = 0
        var segmentStart = 0
        while (i < text.length) {
            val special = if (text[i] == '<') addedByLength.firstOrNull { text.startsWith(it, i) } else null
            if (special != null) {
                encodeOrdinary(text.substring(segmentStart, i), out)
                out += added.getValue(special)
                i += special.length
                segmentStart = i
            } else {
                i++
            }
        }
        encodeOrdinary(text.substring(segmentStart), out)
        return out
    }

    /** Text for [ids]; added (special) tokens are dropped, bytes are decoded as UTF-8. */
    fun decode(ids: List<Int>): String {
        val decoder = StreamDecoder()
        val sb = StringBuilder()
        ids.forEach { sb.append(decoder.next(it)) }
        return sb.append(decoder.flush()).toString()
    }

    /** Incremental decoding for streaming: a multi-byte character split over two tokens comes out whole. */
    inner class StreamDecoder {
        private val pending = ByteArrayOutputStream()

        fun next(id: Int): String {
            if (id in addedIds) return ""
            val token = idToToken.getOrNull(id) ?: return ""
            for (ch in token) {
                val b = UNICODE_TO_BYTE[ch] ?: continue
                pending.write(b)
            }
            return drainComplete()
        }

        fun flush(): String {
            val s = pending.toByteArray().toString(Charsets.UTF_8)
            pending.reset()
            return s
        }

        private fun drainComplete(): String {
            val bytes = pending.toByteArray()
            val end = completeUtf8Prefix(bytes)
            if (end == 0) return ""
            pending.reset()
            pending.write(bytes, end, bytes.size - end)
            return String(bytes, 0, end, Charsets.UTF_8)
        }
    }

    private fun encodeOrdinary(text: String, out: MutableList<Int>) {
        if (text.isEmpty()) return
        for (match in splitter.findAll(text)) {
            val piece = match.value
            out += cache.getOrPut(piece) { bpe(piece) }
        }
    }

    private fun bpe(piece: String): List<Int> {
        val mapped = piece.toByteArray(Charsets.UTF_8).joinToString("") { BYTE_TO_UNICODE[it.toInt() and 0xFF].toString() }
        vocab[mapped]?.let { return listOf(it) }
        var word = mapped.map { it.toString() }
        while (word.size > 1) {
            var best = -1
            var bestRank = Int.MAX_VALUE
            for (k in 0 until word.size - 1) {
                val r = ranks[word[k] to word[k + 1]] ?: continue
                if (r < bestRank) {
                    bestRank = r
                    best = k
                }
            }
            if (best < 0) break
            val a = word[best]
            val b = word[best + 1]
            val merged = ArrayList<String>(word.size)
            var k = 0
            while (k < word.size) {
                if (k < word.size - 1 && word[k] == a && word[k + 1] == b) {
                    merged += a + b
                    k += 2
                } else {
                    merged += word[k]
                    k++
                }
            }
            word = merged
        }
        return word.map { vocab[it] ?: throw IllegalStateException("BPE produced a token outside the vocab: $it") }
    }

    companion object {
        /** GPT-2's reversible byte ↔ printable-unicode map. */
        val BYTE_TO_UNICODE: CharArray = CharArray(256).also { map ->
            val printable = (('!'.code..'~'.code) + ('¡'.code..'¬'.code) + ('®'.code..'ÿ'.code)).toSet()
            var n = 0
            for (b in 0 until 256) {
                map[b] = if (b in printable) b.toChar() else (256 + n++).toChar()
            }
        }
        private val UNICODE_TO_BYTE: Map<Char, Int> = BYTE_TO_UNICODE.withIndex().associate { (b, c) -> c to b }

        /** Length of the longest prefix of [bytes] that ends on a UTF-8 character boundary. */
        fun completeUtf8Prefix(bytes: ByteArray): Int {
            var i = bytes.size
            var back = 0
            while (i > 0 && back < 4) {
                val b = bytes[i - 1].toInt() and 0xFF
                if (b and 0xC0 != 0x80) { // a lead byte (or ASCII)
                    val need = when {
                        b < 0x80 -> 1
                        b shr 5 == 0x6 -> 2
                        b shr 4 == 0xE -> 3
                        b shr 3 == 0x1E -> 4
                        else -> 1
                    }
                    return if (back + 1 >= need) bytes.size else i - 1
                }
                i--
                back++
            }
            return bytes.size
        }

        /** Parse a Hugging Face `tokenizer.json` (BPE model, `Split` pre-tokenizer with a regex). */
        fun fromJson(json: String): BpeTokenizer {
            val root = LockJson.parse(json) as Map<*, *>
            val model = root["model"] as Map<*, *>
            require(model["type"] == "BPE") { "not a BPE tokenizer: ${model["type"]}" }
            val vocab = (model["vocab"] as Map<*, *>).entries.associate { (k, v) -> k as String to (v as Number).toInt() }
            val merges = (model["merges"] as List<*>).map { m ->
                when (m) {
                    is List<*> -> m[0] as String to m[1] as String
                    is String -> m.substringBefore(' ') to m.substringAfter(' ')
                    else -> throw IllegalArgumentException("bad merge $m")
                }
            }
            val added = (root["added_tokens"] as? List<*>).orEmpty().associate { t ->
                val obj = t as Map<*, *>
                obj["content"] as String to (obj["id"] as Number).toInt()
            }
            return BpeTokenizer(vocab, merges, added, splitPattern(root["pre_tokenizer"]) ?: DEFAULT_PATTERN)
        }

        private fun splitPattern(node: Any?): String? {
            val obj = node as? Map<*, *> ?: return null
            if (obj["type"] == "Split") return (obj["pattern"] as? Map<*, *>)?.get("Regex") as? String
            return (obj["pretokenizers"] as? List<*>)?.firstNotNullOfOrNull { splitPattern(it) }
        }

        /** GPT-2's pattern, for tokenizers whose ByteLevel pre-tokenizer carries the regex itself. */
        private const val DEFAULT_PATTERN = "'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+"
    }
}
