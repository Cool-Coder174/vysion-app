package com.vysion.xr.platform.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vysion.xr.core.NormalizedBox
import com.vysion.xr.platform.PassthroughOverlayRenderer
import com.vysion.xr.platform.ui.theme.Accent
import com.vysion.xr.platform.ui.theme.Ink

/**
 * How the outlines look for the current task (designs: Headset 4-11). Color carries the task: yellow while
 * reading or describing, orange for obstacles in Navigation, cyan for the door, green when arrived. A dashed
 * line means uncertain: the door's last-seen spot, or text too small to read.
 */
data class OverlayStyle(
    val detectionColor: Color = Accent.yellow,
    val doorColor: Color = Accent.cyan,
    val doorDashed: Boolean = false,
    val doorLabel: String? = null,
    val labelSize: TextUnit = 24.sp,
) {
    companion object {
        /** The outline style each headset screen asks for; [door] and [lastSeen] are the chip words (strings.xml). */
        fun forScreen(screen: HeadsetScreen, door: String, lastSeen: String, base: OverlayStyle = OverlayStyle()): OverlayStyle =
            when (screen) {
                is HeadsetScreen.Guiding ->
                    base.copy(detectionColor = Accent.orange, doorColor = Accent.cyan, doorDashed = !screen.inView, doorLabel = door)
                is HeadsetScreen.Arrived -> base.copy(detectionColor = Accent.orange, doorColor = Accent.green, doorLabel = door)
                is HeadsetScreen.Lost ->
                    base.copy(detectionColor = Accent.orange, doorColor = Accent.cyan, doorDashed = true, doorLabel = lastSeen)
                HeadsetScreen.LookingForDoor -> base.copy(detectionColor = Accent.orange, doorLabel = door)
                else -> base.copy(doorLabel = door)
            }
    }
}

/**
 * Draws detector outlines (FR-N2) and the door highlight (FR-N3) from [PassthroughOverlayRenderer] state onto a
 * `Canvas` sized to the passthrough view it is placed over. Owner: R1. Boxes are normalized, so the only
 * geometry here is `box × size`. Each label sits on a solid pill of the outline's color so it reads on any
 * scene. Nothing numeric is drawn (detector confidence is not a measured product number).
 *
 * In the spatial layout this canvas sits in its own transparent `SpatialPanel`; registering it to the real
 * passthrough (`SpatialExternalSurface`) is UNVERIFIED in docs/stack.md → R1.
 */
@Composable
fun OverlayCanvas(
    renderer: PassthroughOverlayRenderer,
    modifier: Modifier = Modifier,
    style: OverlayStyle = OverlayStyle(),
) {
    val detections by renderer.detections.collectAsStateWithLifecycle()
    val door by renderer.door.collectAsStateWithLifecycle()
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Ink.onLight, fontSize = style.labelSize, fontWeight = FontWeight.SemiBold)

    Canvas(modifier = modifier) {
        detections.forEach { detection ->
            outline(detection.box, style.detectionColor, dashed = false, width = OUTLINE_DP)
            drawLabel(measurer, detection.label, detection.box, style.detectionColor, labelStyle)
        }
        door?.let { box ->
            outline(box, style.doorColor, dashed = style.doorDashed, width = DOOR_DP)
            style.doorLabel?.let { drawLabel(measurer, it, box, style.doorColor, labelStyle) }
        }
    }
}

private fun DrawScope.outline(box: NormalizedBox, color: Color, dashed: Boolean, width: Float) {
    val (topLeft, size) = box.toCanvas(this.size)
    val stroke = width.dp.toPx()
    val dash = if (dashed) PathEffect.dashPathEffect(floatArrayOf(stroke * 3f, stroke * 2.2f)) else null
    drawRoundRect(
        color = color,
        topLeft = topLeft,
        size = size,
        cornerRadius = CornerRadius(CORNER_DP.dp.toPx()),
        style = Stroke(width = stroke, pathEffect = dash),
    )
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, box: NormalizedBox, color: Color, style: TextStyle) {
    val (topLeft, _) = box.toCanvas(size)
    val measured = measurer.measure(text, style)
    val padX = 12.dp.toPx()
    val padY = 5.dp.toPx()
    val chip = Size(measured.size.width + 2 * padX, measured.size.height + 2 * padY)
    val top = (topLeft.y - chip.height - 8.dp.toPx()).coerceAtLeast(0f)
    drawRoundRect(color = color, topLeft = Offset(topLeft.x, top), size = chip, cornerRadius = CornerRadius(chip.height / 2f))
    drawText(measured, topLeft = Offset(topLeft.x + padX, top + padY))
}

/** Normalized box → (top-left, size) in canvas pixels. */
private fun NormalizedBox.toCanvas(canvas: Size): Pair<Offset, Size> =
    Offset(left * canvas.width, top * canvas.height) to Size(width * canvas.width, height * canvas.height)

private const val OUTLINE_DP = 4f
private const val DOOR_DP = 6f
private const val CORNER_DP = 18f
