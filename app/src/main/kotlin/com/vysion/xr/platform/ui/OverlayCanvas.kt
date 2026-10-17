package com.vysion.xr.platform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vysion.xr.core.NormalizedBox
import com.vysion.xr.platform.PassthroughOverlayRenderer

/**
 * Draws detector outlines (FR-N2) and the door highlight (FR-N3) from [PassthroughOverlayRenderer]
 * state onto a `Canvas` sized to the passthrough view it is placed over. Owner: R1 (SRS §8
 * "outlines", due Sat 21:00). Boxes are normalized, so the only geometry here is `box × size`.
 *
 * Colors are chosen for contrast against a live camera scene (yellow outline, cyan door) and the
 * label is drawn on a solid chip so it stays readable on any background. Nothing numeric is shown
 * except the detector label text; confidence is not printed (it is not a measured product number).
 *
 * In the spatial layout this canvas sits in its own transparent `SpatialPanel`; the real
 * passthrough-registered surface (`SpatialExternalSurface`) is UNVERIFIED in docs/stack.md → R1 and
 * is the Sat 21:00 device check (A-2).
 */
@Composable
fun OverlayCanvas(
    renderer: PassthroughOverlayRenderer,
    modifier: Modifier = Modifier,
) {
    val detections by renderer.detections.collectAsStateWithLifecycle()
    val door by renderer.door.collectAsStateWithLifecycle()
    val measurer = rememberTextMeasurer()

    Canvas(modifier = modifier) {
        val stroke = Stroke(width = OUTLINE_STROKE_DP.dp.toPx())
        val doorStroke = Stroke(width = DOOR_STROKE_DP.dp.toPx())

        detections.forEach { detection ->
            val rect = detection.box.toCanvas(size)
            drawRect(color = OUTLINE_COLOR, topLeft = rect.first, size = rect.second, style = stroke)
            val label = measurer.measure(detection.label, LABEL_STYLE)
            val chipPadding = LABEL_PADDING_DP.dp.toPx()
            val chipTop = (rect.first.y - label.size.height - 2 * chipPadding).coerceAtLeast(0f)
            drawRect(
                color = OUTLINE_COLOR,
                topLeft = Offset(rect.first.x, chipTop),
                size = Size(label.size.width + 2 * chipPadding, label.size.height + 2 * chipPadding),
            )
            drawText(label, topLeft = Offset(rect.first.x + chipPadding, chipTop + chipPadding))
        }

        door?.let { box ->
            val rect = box.toCanvas(size)
            drawRect(color = DOOR_COLOR, topLeft = rect.first, size = rect.second, style = doorStroke)
        }
    }
}

/** Normalized box → (top-left, size) in canvas pixels. */
private fun NormalizedBox.toCanvas(canvas: Size): Pair<Offset, Size> =
    Offset(left * canvas.width, top * canvas.height) to Size(width * canvas.width, height * canvas.height)

private val OUTLINE_COLOR = Color(0xFFFFD60A) // yellow, 15.6:1 on black, visible on passthrough
private val DOOR_COLOR = Color(0xFF00E5FF) // cyan, distinct from the outlines
private val LABEL_STYLE = TextStyle(color = Color.Black, fontSize = 24.sp, fontWeight = FontWeight.Bold)
private const val OUTLINE_STROKE_DP = 4
private const val DOOR_STROKE_DP = 8
private const val LABEL_PADDING_DP = 4
