package com.vysion.xr.diagnostics

import com.vysion.xr.core.DiagnosticsSnapshot
import com.vysion.xr.core.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overlay's text rules (FR-G5): exactly six SRS §11 rows, and every `null` reads "not measured" (INV-7). */
class DiagnosticsOverlayTextTest {

    private val vlm = ModelInfo("gemma-vlm", "4-bit", "XNNPACK", 1_234L, 896, 896)
    private val det = ModelInfo("yolo26n", "int8", "XNNPACK", 5_678L, 640, 640)

    @Test
    fun `an empty snapshot renders six rows that all say not measured`() {
        val rows = DiagnosticsText.rows(DiagnosticsSnapshot())
        assertEquals(6, rows.size)
        assertEquals(
            listOf(
                DiagnosticsText.LABEL_MODEL,
                DiagnosticsText.LABEL_BACKEND,
                DiagnosticsText.LABEL_VLM_LATENCY,
                DiagnosticsText.LABEL_DETECTOR_FPS,
                DiagnosticsText.LABEL_MEMORY,
                DiagnosticsText.LABEL_NETWORK,
            ),
            rows.map { it.first },
        )
        assertTrue(rows.all { it.second == DiagnosticsText.NOT_MEASURED })
        // No digit anywhere in an unmeasured panel: nothing can look like a number.
        assertTrue(rows.none { row -> row.second.any { it.isDigit() } })
    }

    @Test
    fun `measured values are rendered with units`() {
        val s = DiagnosticsSnapshot(
            vlm = vlm,
            detector = det,
            backend = "XNNPACK",
            lastVlmMs = 4_321L,
            medianVlmMs = 4_000L,
            detectorFps = 5.24f,
            appMemoryMb = 2_048,
            networkPermission = ManifestAudit.NONE,
            measuredAtMs = 1L,
        )
        val rows = DiagnosticsText.rows(s).toMap()
        assertEquals("gemma-vlm (4-bit) · detector yolo26n (int8)", rows[DiagnosticsText.LABEL_MODEL])
        assertEquals("XNNPACK", rows[DiagnosticsText.LABEL_BACKEND])
        assertEquals("4321 ms / 4000 ms", rows[DiagnosticsText.LABEL_VLM_LATENCY])
        assertEquals("5.2 fps", rows[DiagnosticsText.LABEL_DETECTOR_FPS])
        assertEquals("2048 MB", rows[DiagnosticsText.LABEL_MEMORY])
        assertEquals("none", rows[DiagnosticsText.LABEL_NETWORK])
    }

    @Test
    fun `half-measured latency names the missing half instead of printing zero`() {
        assertEquals("4321 ms / not measured", DiagnosticsText.latency(4_321L, null))
        assertEquals("not measured / 4000 ms", DiagnosticsText.latency(null, 4_000L))
        assertEquals(DiagnosticsText.NOT_MEASURED, DiagnosticsText.latency(null, null))
    }

    @Test
    fun `model row works with either model alone`() {
        assertEquals("gemma-vlm (4-bit)", DiagnosticsText.model(vlm, null))
        assertEquals("detector yolo26n (int8)", DiagnosticsText.model(null, det))
        assertEquals(DiagnosticsText.NOT_MEASURED, DiagnosticsText.model(null, null))
    }

    @Test
    fun `fps uses one decimal and a dot regardless of locale`() {
        assertEquals("0.0 fps", DiagnosticsText.fps(0f))
        assertEquals("12.3 fps", DiagnosticsText.fps(12.34f))
        assertEquals("5.3 fps", DiagnosticsText.fps(5.25f)) // String.format rounds HALF_UP
    }

    @Test
    fun `the spoken description reads the title and every row`() {
        val spoken = DiagnosticsText.spoken(DiagnosticsText.rows(DiagnosticsSnapshot(networkPermission = "none")))
        assertTrue(spoken.startsWith(DiagnosticsText.TITLE + ". "))
        assertTrue(spoken.contains("Network permission: none"))
        assertTrue(spoken.contains("Backend: not measured"))
        assertTrue(spoken.endsWith("."))
    }
}
