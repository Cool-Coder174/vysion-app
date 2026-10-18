package com.vysion.xr.platform.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vysion.xr.core.ListeningState
import com.vysion.xr.platform.ui.theme.Accent
import com.vysion.xr.platform.ui.theme.Glass
import com.vysion.xr.platform.ui.theme.Ink
import com.vysion.xr.platform.ui.theme.LocalReduceMotion
import com.vysion.xr.platform.ui.theme.VysionIcons
import com.vysion.xr.platform.ui.theme.vysionSpring
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The shared atoms of Michelle Bui's designs: the glass surface, Vysion's orb, pill buttons, the hold-to-talk
 * button, the reads-aloud chip, page dots, the listening waveform, the thinking lines and the guidance dial.
 * Every interactive atom carries a TalkBack label and a ≥ 48 dp target (≥ 56 dp in XR, chosen by the caller).
 */

/**
 * The glass material: a vertical graphite (or indigo) fill, a soft top sheen, and a hairline edge that
 * defines the shape instead of a drop shadow. Chrome only; never behind content that is itself glass.
 */
fun Modifier.glass(
    shape: Shape,
    top: Color = Glass.graphiteTop,
    bottom: Color = Glass.graphiteBottom,
    edge: Color = Glass.edge,
): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(top, bottom)), shape)
    .background(Brush.verticalGradient(0f to Glass.sheen, 0.45f to Color.Transparent), shape)
    .border(1.5.dp, Brush.verticalGradient(listOf(edge, edge.copy(alpha = edge.alpha * 0.35f))), shape)

/**
 * The phone's glass (designs "Phone v6"): a translucent white or near-black fill over the live camera with a
 * one-pixel hairline, instead of the headset's graphite gradient. Chrome only, like [glass].
 */
fun Modifier.phoneGlass(
    shape: Shape,
    fill: Color = Glass.control,
    edge: Color = Glass.controlEdge,
): Modifier = this
    .clip(shape)
    .background(fill, shape)
    .border(1.dp, edge, shape)

/**
 * Vysion's presence: a small luminous blue sphere. It breathes while [active] (listening, thinking) and is
 * still otherwise; with Remove animations on it never moves.
 */
@Composable
fun Orb(size: Dp, active: Boolean = false, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val pulse = if (active && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "orb")
        val value by transition.animateFloat(
            initialValue = 0.96f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing), RepeatMode.Reverse),
            label = "orbPulse",
        )
        value
    } else {
        1f
    }
    Canvas(modifier = modifier.size(size).scale(pulse)) {
        val r = this.size.minDimension / 2f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Accent.blue.copy(alpha = 0.32f), Color.Transparent),
                center = center,
                radius = r,
            ),
            radius = r,
        )
        val core = r * 0.82f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFFF4F9FF), Color(0xFF9CCBFF), Color(0xFF4C8DFF), Color(0xFF2E5FE0)),
                center = Offset(center.x - core * 0.28f, center.y - core * 0.34f),
                radius = core * 1.35f,
            ),
            radius = core,
        )
    }
}

/** The status mark beside a window title: the orb by default, or a colored disc with a glyph. */
enum class Badge { ORB, CHECK, QUESTION, WARNING, ERROR, TEXT }

@Composable
fun StatusBadge(badge: Badge, size: Dp, active: Boolean = false) {
    when (badge) {
        Badge.ORB -> Orb(size = size, active = active)
        Badge.CHECK -> GlyphDisc(VysionIcons.Check, Accent.green, Color.White, size)
        Badge.QUESTION -> GlyphDisc(VysionIcons.Question, Accent.yellow, Ink.onLight, size)
        Badge.WARNING -> GlyphDisc(VysionIcons.Alert, Accent.orange, Ink.onLight, size)
        Badge.ERROR -> GlyphDisc(VysionIcons.Alert, Accent.red, Color.White, size)
        Badge.TEXT -> Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(size).background(Accent.yellow, CircleShape),
        ) { Text("Aa", color = Ink.onLight, style = TextStyle(fontSize = (size.value * 0.38f).spLike())) }
    }
}

/** A filled disc carrying one glyph (privacy rows, sound tiles, status badges). */
@Composable
fun GlyphDisc(icon: ImageVector, fill: Color, glyph: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size).background(fill, CircleShape)) {
        Icon(icon, contentDescription = null, tint = glyph, modifier = Modifier.size(size * 0.6f))
    }
}

/** Visual weight of a pill, in the designs' vocabulary. */
enum class PillStyle { PRIMARY, SECONDARY, STOP, TALK, ACCENT }

/**
 * A pill button. [label] is also its TalkBack name. Pressed: a quick scale-down on a critically damped
 * spring; hovered by gaze or pointer: one shade lighter. No ripple: the pill's own state is the feedback.
 */
@Composable
fun PillButton(
    label: String,
    onClick: () -> Unit,
    style: PillStyle,
    textStyle: TextStyle,
    height: Dp,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    accent: Color = Accent.blue,
    selected: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, vysionSpring(response = 0.25f), label = "pill")
    val (container, content) = pillColors(style, accent, hovered || selected)
    val border = if (style == PillStyle.ACCENT) accent else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(height * 0.16f),
        modifier = modifier
            .scale(scale)
            .heightIn(min = height)
            .clip(CircleShape)
            .background(container, CircleShape)
            .border(2.dp, border, CircleShape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = height * 0.42f, vertical = height * 0.18f),
    ) {
        leading?.invoke()
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(height * 0.4f))
        Text(label, color = content, style = textStyle)
    }
}

private fun pillColors(style: PillStyle, accent: Color, lifted: Boolean): Pair<Color, Color> = when (style) {
    PillStyle.PRIMARY -> (if (lifted) Color(0xFFE9E9EE) else Glass.white) to Ink.onLight
    PillStyle.SECONDARY -> (if (lifted) Glass.pillPressed else Glass.pill) to Ink.primary
    PillStyle.STOP -> Accent.red to Color.White
    PillStyle.TALK -> Accent.blue to Color.White
    PillStyle.ACCENT -> accent.copy(alpha = if (lifted) 0.28f else 0.16f) to Ink.primary
}

/**
 * Talk: a tap toggle, not press-and-hold. On Galaxy XR a pinch held for 2 s opens the system's options, so holding
 * to talk fought the OS whenever someone spoke for longer than that (research, 2026-10-17). Tap to start; the
 * recognizer sends by itself when you pause, and a second tap ([onDone]) sends at once. Hover feedback comes from the
 * system's gaze highlight plus a lighter fill here.
 */
@Composable
fun TalkButton(
    label: String,
    doneLabel: String,
    listening: ListeningState,
    onStart: () -> Unit,
    onDone: () -> Unit,
    textStyle: TextStyle,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val isListening = listening == ListeningState.LISTENING
    val unavailable = listening == ListeningState.UNAVAILABLE
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val container = when {
        unavailable -> Glass.pill
        hovered -> Accent.blue.copy(alpha = 0.85f)
        else -> Accent.blue
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(height * 0.16f),
        modifier = modifier
            .heightIn(min = height)
            .clip(CircleShape)
            .background(container, CircleShape)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = !unavailable,
                role = Role.Button,
                onClick = { if (isListening) onDone() else onStart() },
            )
            .semantics { stateDescription = listening.name.lowercase() }
            .padding(horizontal = height * 0.42f, vertical = height * 0.18f),
    ) {
        if (isListening) {
            Waveform(color = Color.White, barWidth = height * 0.07f, height = height * 0.42f, bars = 5)
        } else {
            Icon(VysionIcons.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(height * 0.4f))
        }
        Text(if (isListening) doneLabel else label, color = Color.White, style = textStyle)
    }
}

/** "Vysion reads this aloud · 1 of 7": the small chip that opens every onboarding card. */
@Composable
fun ReadsAloudChip(text: String, textStyle: TextStyle, height: Dp) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(height * 0.3f),
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0x33FFFFFF), CircleShape)
            .padding(horizontal = height * 0.45f, vertical = height * 0.2f),
    ) {
        Orb(size = height * 0.62f)
        Text(text, color = Ink.secondary, style = textStyle)
    }
}

/** Progress through onboarding: the current page is a lengthened pill; the move springs. */
@Composable
fun PageDots(count: Int, index: Int, dot: Dp, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(dot * 0.9f), verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == index) dot * 3.6f else dot, vysionSpring(), label = "dot$i")
            Box(
                Modifier
                    .width(width)
                    .height(dot)
                    .background(if (i == index) Color.White else Color(0x59FFFFFF), CircleShape),
            )
        }
    }
}

/**
 * The listening waveform. There is no microphone level on the CommandBus contract yet, so the bars move on
 * a gentle loop that only says "listening"; R4 can drive them from `onRmsChanged` later. Still with Remove
 * animations on.
 */
@Composable
fun Waveform(color: Color, barWidth: Dp, height: Dp, bars: Int = 11, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val phase = if (reduceMotion) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "wave")
        val p by transition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(durationMillis = 1400, easing = LinearEasing)),
            label = "wavePhase",
        )
        p
    }
    Canvas(modifier = modifier.width(barWidth * (bars * 2 - 1)).height(height)) {
        val w = barWidth.toPx()
        for (i in 0 until bars) {
            val envelope = 0.45f + 0.55f * sin(PI.toFloat() * (i + 0.5f) / bars)
            val wave = 0.55f + 0.45f * sin(phase + i * 0.8f)
            val h = size.height * (0.22f + 0.78f * envelope * wave)
            val x = i * 2 * w
            drawRoundRect(
                color = color.copy(alpha = if (i > bars * 0.7f) 0.55f else 1f),
                topLeft = Offset(x, (size.height - h) / 2f),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
        }
    }
}

/** Thinking: three soft lines with a slow light sweep (the designs' loading state), still with Remove animations. */
@Composable
fun ThinkingLines(lineHeight: Dp, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val sweep = if (reduceMotion) {
        -1f
    } else {
        val transition = rememberInfiniteTransition(label = "thinking")
        val s by transition.animateFloat(
            initialValue = -0.4f,
            targetValue = 1.4f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1500, easing = LinearEasing)),
            label = "sweep",
        )
        s
    }
    Canvas(modifier = modifier.fillMaxWidth().height(lineHeight * 5)) {
        val h = lineHeight.toPx()
        listOf(1f, 0.86f, 0.58f).forEachIndexed { i, fraction ->
            val y = i * h * 1.9f
            val width = size.width * fraction
            val base = Color(0x33FFFFFF)
            drawRoundRect(color = base, topLeft = Offset(0f, y), size = Size(width, h), cornerRadius = CornerRadius(h / 2, h / 2))
            if (sweep >= -0.4f) {
                val cx = size.width * sweep
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, Color(0x3DFFFFFF), Color.Transparent),
                        startX = cx - size.width * 0.25f,
                        endX = cx + size.width * 0.25f,
                    ),
                    topLeft = Offset(0f, y),
                    size = Size(width, h),
                    cornerRadius = CornerRadius(h / 2, h / 2),
                )
            }
        }
    }
}

/**
 * The guidance dial (designs: Headset 7): a ring with one hand pointing at the door's clock hour, 12 at the
 * top. The hand moves on a spring as the wearer turns; with Remove animations it jumps.
 */
@Composable
fun ClockDial(clock: Int, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val target = ((clock % 12) * 30f)
    val angle by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) tween(0) else vysionSpring(response = 0.5f),
        label = "dial",
    )
    Canvas(modifier = modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val ring = r * 0.08f
        drawCircle(color = Color(0x40FFFFFF), radius = r - ring / 2, style = Stroke(width = ring))
        drawCircle(color = Color(0x26000000), radius = r - ring)
        val radians = Math.toRadians((angle - 90f).toDouble())
        val tip = Offset(center.x + cos(radians).toFloat() * r * 0.62f, center.y + sin(radians).toFloat() * r * 0.62f)
        val tail = Offset(center.x + cos(radians).toFloat() * r * 0.18f, center.y + sin(radians).toFloat() * r * 0.18f)
        drawLine(color = color, start = tail, end = tip, strokeWidth = r * 0.12f, cap = StrokeCap.Round)
    }
}

/** A selectable tile (sound tiles, mode cards): glassier fill, lifts on gaze. */
@Composable
fun Tile(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (hovered || selected) Color(0x33FFFFFF) else Color(0x1FFFFFFF), shape)
            .hoverable(interaction)
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) { content() }
}

/** Font size in sp from a float: lets badges size their letters from the badge size. */
private fun Float.spLike() = androidx.compose.ui.unit.TextUnit(this, androidx.compose.ui.unit.TextUnitType.Sp)
