package com.vysion.xr.diagnostics

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

/** [WavClip] reads the debug voice clips `scripts/asr_clips.sh` makes (python `wave`, macOS `say`). */
class WavClipTest {

    private fun wav(samples: ShortArray, extraChunk: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) = repeat(4) { out.write(v ushr (8 * it)) }
        fun le16(v: Int) = repeat(2) { out.write(v ushr (8 * it)) }
        val data = samples.size * 2
        ascii("RIFF"); le32(36 + data + (extraChunk?.size ?: 0)); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1); le32(16_000); le32(32_000); le16(2); le16(16)
        extraChunk?.let { out.write(it) }
        ascii("data"); le32(data)
        samples.forEach { le16(it.toInt()) }
        return out.toByteArray()
    }

    @Test
    fun `16-bit mono samples come back with their sign`() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234)
        assertArrayEquals(samples, WavClip.decode(wav(samples)))
    }

    @Test
    fun `a data chunk of 32 KB or more is read whole (sizes are unsigned)`() {
        val samples = ShortArray(20_000) { (it * 37).toShort() } // 40 000 bytes: size bytes 0x40 0x9C
        assertArrayEquals(samples, WavClip.decode(wav(samples)))
    }

    @Test
    fun `chunks other than fmt and data are skipped, odd sizes padded`() {
        val list = "LIST".toByteArray(Charsets.US_ASCII) + byteArrayOf(3, 0, 0, 0, 1, 2, 3, 0)
        assertArrayEquals(shortArrayOf(7, -7, 300), WavClip.decode(wav(shortArrayOf(7, -7, 300), list)))
    }
}
