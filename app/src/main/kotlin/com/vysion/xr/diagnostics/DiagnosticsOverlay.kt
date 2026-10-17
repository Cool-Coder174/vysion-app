package com.vysion.xr.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vysion.xr.core.DiagnosticsSnapshot
import com.vysion.xr.core.DiagnosticsSource
import com.vysion.xr.core.ModelInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * The judge-facing diagnostics panel (FR-G5, SRS §11 "Diagnostics panel fields (all measured at runtime)").
 *
 * - Consumes: [DiagnosticsSource] (R5's [DiagnosticsCollector]) and nothing else (docs/contracts.md).
 *   R1 places it in the control panel behind the diagnostics toggle (`a11y_diagnostics_toggle`) on both the
 *   spatial and the 2D layout (ADR-0006); this composable is layout-agnostic.
 * - Owner: R5. SRS §8 due Sat 18:00; gate 6.
 * - API: plain Compose (`androidx.compose.material3.Text`, semantics), docs/stack.md §1 Compose BOM 2026.09.00.
 * - Assumption: Q14 (TalkBack announces controls inside a `SpatialPanel`) decides whether the panel's
 *   `contentDescription` is heard on the headset; the description is built regardless.
 *
 * Exactly the six SRS §11 fields, in SRS order: model and quantization · backend · last VLM latency and the
 * session median · detector fps over the 5 s window · app memory in MB · "Network permission: <value>".
 * Every `null` renders as "not measured" (NFR Honesty, INV-7); there is no sample number anywhere in this
 * file, the preview included. Monospace, ≥ 24 sp, white on black (21:1, above the 7:1 SRS floor), and one
 * `contentDescription` on the panel that reads every row so the visual has its audio twin (SRS §3 rule 1).
 *
 * Labels here are display text, not spoken cues: nothing in this file goes through `Speaker.say`
 * (spoken strings live in `voice/Cues.kt`, R4).
 */
@Composable
fun DiagnosticsOverlay(source: DiagnosticsSource, modifier: Modifier = Modifier) {
    val snapshot by source.snapshot.collectAsState()
    val rows = DiagnosticsText.rows(snapshot)
    val description = DiagnosticsText.spoken(rows)
    Column(
        modifier = modifier
            .background(PanelBackground)
            .padding(PanelPadding)
            .semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        Text(
            text = DiagnosticsText.TITLE,
            color = PanelForeground,
            fontFamily = FontFamily.Monospace,
            fontSize = PanelFontSize,
            fontWeight = FontWeight.Bold,
        )
        rows.forEach { (label, value) ->
            Text(
                text = DiagnosticsText.line(label, value),
                color = PanelForeground,
                fontFamily = FontFamily.Monospace,
                fontSize = PanelFontSize,
            )
        }
    }
}

// SRS §3 rule 4 asks 7:1 for transcript text; white on black is 21:1. Sizes are layout, not measurements.
private val PanelBackground = Color.Black
private val PanelForeground = Color.White
private val PanelFontSize = 24.sp
private val PanelPadding = 16.dp

/**
 * Pure text rules for the overlay, unit-tested on the JVM (no Compose, no Android classes).
 * One place decides how a `null` renders so the honesty rule cannot drift between rows.
 */
internal object DiagnosticsText {
    const val TITLE: String = "Diagnostics (measured on device)"

    /** INV-7: the one and only rendering of a `null` field. */
    const val NOT_MEASURED: String = "not measured"

    const val LABEL_MODEL: String = "Model"
    const val LABEL_BACKEND: String = "Backend"
    const val LABEL_VLM_LATENCY: String = "VLM ms (last / median)"
    const val LABEL_DETECTOR_FPS: String = "Detector fps (5 s)"
    const val LABEL_MEMORY: String = "App memory"
    const val LABEL_NETWORK: String = "Network permission"

    /** INV-2 residency words inside the Model row. */
    const val RESIDENT: String = "resident"
    const val UNLOADED: String = "unloaded"

    /** The six SRS §11 rows, in SRS order, as (label, value) with every `null` already turned into [NOT_MEASURED]. */
    fun rows(s: DiagnosticsSnapshot): List<Pair<String, String>> = listOf(
        LABEL_MODEL to model(s.vlm, s.detector, s.detectorLoaded),
        LABEL_BACKEND to (s.backend ?: NOT_MEASURED),
        LABEL_VLM_LATENCY to latency(s.lastVlmMs, s.medianVlmMs),
        LABEL_DETECTOR_FPS to fps(s.detectorFps),
        LABEL_MEMORY to memory(s.appMemoryMb),
        LABEL_NETWORK to (s.networkPermission ?: NOT_MEASURED),
    )

    fun line(label: String, value: String): String = "$label: $value"

    /** The panel's contentDescription: the title and every row, so TalkBack reads what a sighted judge sees. */
    fun spoken(rows: List<Pair<String, String>>): String =
        (listOf(TITLE) + rows.map { (label, value) -> line(label, value) }).joinToString(". ") + "."

    /**
     * "<vlm name> (<quantization>)", then "detector <name> (<quantization>[, resident|unloaded])" when known; both
     * from ModelInfo. The residency is the INV-2 monitor (plan.md: "detectorLoaded visible on the overlay"): it
     * reads "unloaded" while the VLM runs (ADR-0004). It lives in the Model row so the panel keeps six SRS rows.
     */
    fun model(vlm: ModelInfo?, detector: ModelInfo?, detectorLoaded: Boolean? = null): String {
        val residency = when (detectorLoaded) {
            true -> ", $RESIDENT"
            false -> ", $UNLOADED"
            null -> ""
        }
        val parts = listOfNotNull(
            vlm?.let { "${it.name} (${it.quantization})" },
            detector?.let { "detector ${it.name} (${it.quantization}$residency)" },
        )
        return if (parts.isEmpty()) NOT_MEASURED else parts.joinToString(" · ")
    }

    /** "<last> ms / <median> ms"; a missing half says so instead of printing 0. */
    fun latency(lastMs: Long?, medianMs: Long?): String {
        if (lastMs == null && medianMs == null) return NOT_MEASURED
        val last = lastMs?.let { "$it ms" } ?: NOT_MEASURED
        val median = medianMs?.let { "$it ms" } ?: NOT_MEASURED
        return "$last / $median"
    }

    fun fps(value: Float?): String =
        if (value == null) NOT_MEASURED else String.format(Locale.ROOT, "%.1f fps", value)

    fun memory(mb: Int?): String = if (mb == null) NOT_MEASURED else "$mb MB"
}

/** Design-time only. Every field is `null` on purpose: the preview must never show a number that was not measured. */
@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun DiagnosticsOverlayPreview() {
    DiagnosticsOverlay(
        source = object : DiagnosticsSource {
            override val snapshot: StateFlow<DiagnosticsSnapshot> = MutableStateFlow(DiagnosticsSnapshot())
        },
    )
}
