package com.vysion.xr.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure parts of the debug benchmark protocol (SRS §7): request parsing, median/p90 definitions, the soak
 * `minute` column, thermal names and the JSON the script parses. `BenchmarkReceiver` lives in the debug
 * source set, so this test compiles for `testDebugUnitTest` (the CI and AGENTS.md command), not for the
 * release unit-test variant.
 */
class BenchmarkReceiverTest {

    @Test
    fun `request accepts only the three models and clamps the ints`() {
        assertNull(BenchmarkRequest.parse(null, 20, 0))
        assertNull(BenchmarkRequest.parse("VLM", 20, 0))
        assertNull(BenchmarkRequest.parse("llama", 20, 0))
        assertEquals(BenchmarkRequest(BenchmarkModel.VLM, 20, 0), BenchmarkRequest.parse("vlm", 20, 0))
        assertEquals(BenchmarkRequest(BenchmarkModel.DETECTOR, 1, 0), BenchmarkRequest.parse("detector", -5, -1))
        assertEquals(BenchmarkRequest(BenchmarkModel.SOAK, 1_000, 600), BenchmarkRequest.parse("soak", 99_999, 600))
        assertEquals("vlm-latest.json", BenchmarkModel.VLM.fileName)
        assertEquals("soak-latest.json", BenchmarkModel.SOAK.fileName)
    }

    @Test
    fun `median is the middle value or the mean of the two middles`() {
        assertNull(BenchmarkStats.median(emptyList()))
        assertEquals(7.0, BenchmarkStats.median(listOf(7L)))
        assertEquals(5.0, BenchmarkStats.median(listOf(9L, 1L, 5L)))
        assertEquals(3.5, BenchmarkStats.median(listOf(4L, 1L, 3L, 6L)))
    }

    @Test
    fun `p90 is nearest rank, the 18th of 20 sorted values`() {
        assertNull(BenchmarkStats.p90(emptyList()))
        assertEquals(7L, BenchmarkStats.p90(listOf(7L)))
        val twenty = (1L..20L).shuffled()
        assertEquals(18L, BenchmarkStats.p90(twenty))
        assertEquals(9L, BenchmarkStats.p90((1L..10L).toList()))
        assertEquals(3L, BenchmarkStats.p90(listOf(3L, 1L, 2L)))
    }

    @Test
    fun `soak minute column has two decimals`() {
        assertEquals(0.0, BenchmarkStats.minutes(0L), 0.0)
        assertEquals(0.08, BenchmarkStats.minutes(5_000L), 0.0)
        assertEquals(0.5, BenchmarkStats.minutes(30_000L), 0.0)
        assertEquals(10.0, BenchmarkStats.minutes(600_000L), 0.0)
    }

    @Test
    fun `thermal status values map to PowerManager names`() {
        assertEquals("none", BenchmarkStats.thermalName(0))
        assertEquals("severe", BenchmarkStats.thermalName(3))
        assertEquals("shutdown", BenchmarkStats.thermalName(6))
        assertEquals("unknown(42)", BenchmarkStats.thermalName(42))
    }

    @Test
    fun `json carries exactly the script's fields with nulls for the unmeasured`() {
        val result = linkedMapOf<String, Any?>(
            "model" to "vlm",
            "runs" to 2,
            "latencies_ms" to listOf(4_100L, 4_300L),
            "median_ms" to 4_200.0,
            "p90_ms" to 4_300L,
            "load_ms" to null,
            "file_bytes" to null,
            "backend" to "XNNPACK",
            "quantization" to "4-bit",
            "max_new_tokens" to 60,
            "device" to "SM-I610",
            "airplane_mode" to true,
            "started_at" to "2026-10-18T17:00:00Z",
            "finished_at" to "2026-10-18T17:00:09Z",
        )
        assertEquals(
            "{\"model\":\"vlm\",\"runs\":2,\"latencies_ms\":[4100,4300],\"median_ms\":4200.0,\"p90_ms\":4300," +
                "\"load_ms\":null,\"file_bytes\":null,\"backend\":\"XNNPACK\",\"quantization\":\"4-bit\"," +
                "\"max_new_tokens\":60,\"device\":\"SM-I610\",\"airplane_mode\":true," +
                "\"started_at\":\"2026-10-18T17:00:00Z\",\"finished_at\":\"2026-10-18T17:00:09Z\"}",
            BenchmarkJson.render(result),
        )
    }

    @Test
    fun `json soak samples are a list of objects and strings are escaped`() {
        val soak = linkedMapOf<String, Any?>(
            "model" to "soak",
            "samples" to listOf(
                linkedMapOf("minute" to 0.0, "fps" to 5.5f, "pss_mb" to 2_048, "battery" to 97, "thermal" to "none"),
                linkedMapOf(
                    "minute" to 0.08,
                    "fps" to null,
                    "pss_mb" to null,
                    "battery" to null,
                    "thermal" to "say \"hi\"\n",
                ),
            ),
        )
        assertEquals(
            "{\"model\":\"soak\",\"samples\":[" +
                "{\"minute\":0.0,\"fps\":5.5,\"pss_mb\":2048,\"battery\":97,\"thermal\":\"none\"}," +
                "{\"minute\":0.08,\"fps\":null,\"pss_mb\":null,\"battery\":null,\"thermal\":\"say \\\"hi\\\"\\n\"}]}",
            BenchmarkJson.render(soak),
        )
        assertEquals("null", BenchmarkJson.render(Double.NaN))
        assertEquals("\"\\u0001\"", BenchmarkJson.render("\u0001"))
    }
}
