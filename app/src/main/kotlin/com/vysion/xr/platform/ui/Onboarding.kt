package com.vysion.xr.platform.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vysion.xr.AppGraph
import com.vysion.xr.R
import com.vysion.xr.core.Command
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.OnboardingPage
import com.vysion.xr.core.OnboardingStep
import com.vysion.xr.platform.ui.components.GlyphDisc
import com.vysion.xr.platform.ui.components.Orb
import com.vysion.xr.platform.ui.components.PageDots
import com.vysion.xr.platform.ui.components.PillButton
import com.vysion.xr.platform.ui.components.PillStyle
import com.vysion.xr.platform.ui.components.ReadsAloudChip
import com.vysion.xr.platform.ui.components.Tile
import com.vysion.xr.platform.ui.components.Waveform
import com.vysion.xr.platform.ui.components.glass
import com.vysion.xr.platform.ui.theme.Accent
import com.vysion.xr.platform.ui.theme.Aurora
import com.vysion.xr.platform.ui.theme.Glass
import com.vysion.xr.platform.ui.theme.Ink
import com.vysion.xr.platform.ui.theme.LocalReduceMotion
import com.vysion.xr.platform.ui.theme.Radius
import com.vysion.xr.platform.ui.theme.Space
import com.vysion.xr.platform.ui.theme.VysionIcons
import com.vysion.xr.platform.ui.theme.VysionType
import com.vysion.xr.platform.ui.theme.vysionSpring
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * What the shells need from `MainActivity` to run first-launch onboarding (designs pages 2-8).
 * [requestPermissions] shows Android's camera and microphone dialogs and calls back when they close;
 * [finish] saves the flag, starts the app, and runs the mode card the wearer picked, if any.
 */
class OnboardingHost(
    val done: Boolean,
    val requestPermissions: (onClosed: () -> Unit) -> Unit,
    val finish: (Command?) -> Unit,
)

/** Sizes that differ between the headset card and the phone. */
class OnboardingMetrics(
    val cardWidth: Dp,
    val button: Dp,
    val icon: Dp,
    val chip: Dp,
    val dot: Dp,
    val pad: Dp,
    /** Gap between the card and the screen edge. */
    val margin: Dp,
    /** Sound tiles per row (three on the headset, two on a phone). */
    val soundColumns: Int,
)

val SpatialOnboarding = OnboardingMetrics(
    cardWidth = 1040.dp, button = 64.dp, icon = 88.dp, chip = 40.dp, dot = 8.dp, pad = 56.dp, margin = 40.dp, soundColumns = 3,
)
val PhoneOnboarding = OnboardingMetrics(
    cardWidth = 560.dp, button = 52.dp, icon = 56.dp, chip = 30.dp, dot = 6.dp, pad = 24.dp, margin = 16.dp, soundColumns = 2,
)

/**
 * The seven onboarding cards on the aurora ground ("before the camera turns on"). Each page's words come
 * from R4's phrasebook (`voice/Cues.kt`) and are read aloud on entry; leaving a page stops the voice.
 */
@Composable
fun OnboardingFlow(graph: AppGraph, host: OnboardingHost, type: VysionType, metrics: OnboardingMetrics, modifier: Modifier = Modifier) {
    val steps = OnboardingStep.entries
    val startAt = LocalOnboardingStart.current
    var index by rememberSaveable { mutableStateOf(startAt.coerceIn(0, OnboardingStep.entries.lastIndex)) }
    var forward by remember { mutableStateOf(true) }
    val step = steps[index]
    val page = graph.phrasebook.onboarding(step)
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current

    fun go(to: Int) {
        forward = to > index
        index = to.coerceIn(0, steps.lastIndex)
    }

    // Read each page aloud on entry; leave quietly.
    LaunchedEffect(step) { if (page.spoken.isNotBlank()) graph.speaker.say(page.spoken, interrupt = true) }
    DisposableEffect(Unit) { onDispose { graph.speaker.stop() } }

    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize().auroraBackground().padding(horizontal = metrics.margin)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.l)) {
            AnimatedContent(
                targetState = step,
                transitionSpec = { pageTransition(forward, reduceMotion) },
                label = "onboarding",
            ) { shown ->
                Card(graph, graph.phrasebook.onboarding(shown), steps.indexOf(shown), steps.size, type, metrics, onPick = host.finish)
            }
            ButtonTray(metrics) {
                if (index > 0 && step != OnboardingStep.ALL_SET) {
                    PillButton(stringResource(R.string.action_back), { go(index - 1) }, PillStyle.SECONDARY, type.label, metrics.button)
                }
                when (step) {
                    OnboardingStep.WELCOME ->
                        PillButton(stringResource(R.string.action_get_started), { go(index + 1) }, PillStyle.PRIMARY, type.label, metrics.button)
                    OnboardingStep.PRIVACY ->
                        PillButton(
                            stringResource(R.string.action_allow_camera_mic),
                            { host.requestPermissions { scope.launch { go(index + 1) } } },
                            PillStyle.PRIMARY, type.label, metrics.button,
                        )
                    OnboardingStep.SAFETY ->
                        PillButton(stringResource(R.string.action_i_understand), { go(index + 1) }, PillStyle.PRIMARY, type.label, metrics.button)
                    OnboardingStep.ALL_SET ->
                        PillButton(stringResource(R.string.action_start), { host.finish(null) }, PillStyle.TALK, type.label, metrics.button)
                    else ->
                        PillButton(stringResource(R.string.action_continue), { go(index + 1) }, PillStyle.PRIMARY, type.label, metrics.button)
                }
            }
        }
    }
}

internal fun pageTransition(forward: Boolean, reduceMotion: Boolean): ContentTransform =
    if (reduceMotion) {
        fadeIn(vysionSpring(response = 0.25f)) togetherWith fadeOut(vysionSpring(response = 0.2f))
    } else {
        val sign = if (forward) 1 else -1
        (slideInHorizontally(vysionSpring(response = 0.4f)) { sign * it / 6 } + fadeIn(vysionSpring(response = 0.35f))) togetherWith
            (slideOutHorizontally(vysionSpring(response = 0.4f)) { -sign * it / 6 } + fadeOut(vysionSpring(response = 0.2f)))
    }

@Composable
private fun Card(
    graph: AppGraph,
    page: OnboardingPage,
    index: Int,
    count: Int,
    type: VysionType,
    metrics: OnboardingMetrics,
    onPick: (Command) -> Unit,
) {
    val wide = page.step == OnboardingStep.SOUNDS || page.step == OnboardingStep.ALL_SET
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l),
        modifier = Modifier
            .widthIn(max = if (wide) metrics.cardWidth * 1.18f else metrics.cardWidth)
            .then(if (wide) Modifier.fillMaxWidth() else Modifier)
            .glass(RoundedCornerShape(Radius.window), top = Glass.indigoTop, bottom = Glass.indigoBottom)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = metrics.pad, vertical = metrics.pad * 0.85f),
    ) {
        ReadsAloudChip(stringResource(R.string.onboarding_reads_aloud, index + 1, count), type.chip, metrics.chip)
        when (page.step) {
            OnboardingStep.WELCOME -> Orb(size = metrics.icon * 1.25f)
            OnboardingStep.TALK -> Row(horizontalArrangement = Arrangement.spacedBy(metrics.icon * 0.6f), verticalAlignment = Alignment.CenterVertically) {
                Icon(VysionIcons.PinchHand, contentDescription = null, tint = Ink.primary, modifier = Modifier.size(metrics.icon))
                Waveform(color = Accent.blue, barWidth = metrics.icon * 0.07f, height = metrics.icon * 0.7f, bars = 9)
            }
            OnboardingStep.STOP -> Row(horizontalArrangement = Arrangement.spacedBy(metrics.icon * 0.6f), verticalAlignment = Alignment.CenterVertically) {
                Icon(VysionIcons.HangLoose, contentDescription = null, tint = Ink.primary, modifier = Modifier.size(metrics.icon)) // hang loose = stop (HandGestureRecognizer)
                Column {
                    Text(stringResource(R.string.onboarding_or_say), color = Ink.secondary, style = type.footnote)
                    Text(stringResource(R.string.onboarding_stop_word), color = Accent.orange, style = type.windowTitle)
                }
            }
            OnboardingStep.SAFETY -> GlyphDisc(VysionIcons.Alert, Accent.yellow, Ink.onLight, metrics.icon * 0.8f)
            else -> Unit
        }
        Text(page.title, color = Ink.primary, style = type.display, textAlign = TextAlign.Center)
        page.body?.let { Text(it, color = Ink.secondary, style = type.body, textAlign = TextAlign.Center) }
        when (page.step) {
            OnboardingStep.PRIVACY -> PrivacyRows(page, type, metrics)
            OnboardingStep.TALK -> TalkPractice(graph, page.hint, type, metrics)
            OnboardingStep.STOP -> StopPractice(graph, page.hint, type, metrics)
            OnboardingStep.SOUNDS -> SoundTiles(graph, page, type, metrics)
            OnboardingStep.SAFETY -> page.hint?.let { CalloutPill(it, Accent.yellow, type, metrics) }
            OnboardingStep.ALL_SET -> ModeCards(page, type, metrics, onPick)
            OnboardingStep.WELCOME -> Unit
        }
        page.footnote?.let { Text(it, color = Ink.tertiary, style = type.footnote, textAlign = TextAlign.Center) }
        PageDots(count = count, index = index, dot = metrics.dot)
    }
}

/** The three promises on page 2, each with its own meaning color. */
@Composable
private fun PrivacyRows(page: OnboardingPage, type: VysionType, metrics: OnboardingMetrics) {
    val marks = listOf(VysionIcons.NoNetwork to Accent.green, VysionIcons.Camera to Accent.blue, VysionIcons.Stop to Accent.red)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.fillMaxWidth()) {
        page.items.forEachIndexed { i, item ->
            val (icon, color) = marks.getOrElse(i) { marks.last() }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x1FFFFFFF), RoundedCornerShape(Radius.tile))
                    .padding(horizontal = Space.l, vertical = Space.m),
            ) {
                GlyphDisc(icon, color, Color.White, metrics.chip)
                Text(item.title, color = Ink.primary, style = type.label)
            }
        }
    }
}

/** Page 3: "Try it now: say 'read this'". Pressing it listens; hearing Read turns it green. */
@Composable
private fun TalkPractice(graph: AppGraph, hint: String?, type: VysionType, metrics: OnboardingMetrics) {
    hint ?: return
    var heard by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        graph.commandBus.commands.filter { it.command == Command.Read }.collect {
            heard = true
            graph.speaker.earcon(Earcon.DONE)
        }
    }
    if (heard) {
        CalloutPill(stringResource(R.string.onboarding_heard), Accent.green, type, metrics, icon = VysionIcons.Check)
    } else {
        PillButton(hint, graph.commandBus::startListening, PillStyle.ACCENT, type.label, metrics.button, accent = Accent.blue)
    }
}

/** Page 4: "Try it: I'll start talking, you stop me". While Vysion talks the pill is a red Stop. */
@Composable
private fun StopPractice(graph: AppGraph, hint: String?, type: VysionType, metrics: OnboardingMetrics) {
    hint ?: return
    val scope = rememberCoroutineScope()
    var talking by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    // The controller is not running during onboarding, so this page answers "stop" itself.
    LaunchedEffect(Unit) {
        graph.commandBus.commands.filter { it.command == Command.Stop }.collect {
            graph.speaker.stop()
        }
    }
    when {
        talking -> PillButton(
            stringResource(R.string.action_stop),
            {
                graph.speaker.stop()
            },
            PillStyle.STOP, type.label, metrics.button, icon = VysionIcons.Stop,
        )
        stopped -> CalloutPill(stringResource(R.string.onboarding_heard), Accent.green, type, metrics, icon = VysionIcons.Check)
        else -> PillButton(
            hint,
            {
                talking = true
                scope.launch {
                    graph.speaker.say(graph.phrasebook.stopPractice, interrupt = true) // returns when done or stopped
                    talking = false
                    stopped = true
                    graph.speaker.earcon(Earcon.DONE)
                }
            },
            PillStyle.ACCENT, type.label, metrics.button, accent = Accent.orange,
        )
    }
}

/** Page 5: six sounds, one meaning each; pressing a tile plays it. */
@Composable
private fun SoundTiles(graph: AppGraph, page: OnboardingPage, type: VysionType, metrics: OnboardingMetrics) {
    val sounds = listOf(
        Earcon.LISTENING to Accent.blue, Earcon.THINKING to Accent.purple, Earcon.DONE to Accent.green,
        Earcon.PING to Accent.cyan, Earcon.PROXIMITY to Accent.orange, Earcon.ERROR to Accent.red,
    )
    val columns = metrics.soundColumns
    Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.fillMaxWidth()) {
        page.items.chunked(columns).forEachIndexed { row, items ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.fillMaxWidth()) {
                items.forEachIndexed { col, item ->
                    val (earcon, color) = sounds[row * columns + col]
                    Tile(
                        label = stringResource(R.string.a11y_play_sound, item.title),
                        selected = false,
                        onClick = { graph.speaker.earcon(earcon) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.s),
                            modifier = Modifier.padding(Space.m),
                        ) {
                            GlyphDisc(VysionIcons.Play, color, if (color == Accent.yellow || color == Accent.orange) Ink.onLight else Color.White, metrics.chip)
                            Column {
                                Text(item.title, color = Ink.primary, style = type.label)
                                item.detail?.let { Text(it, color = Ink.secondary, style = type.footnote) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Page 7: the three modes with their spoken example; picking one finishes onboarding and runs it. Side by side
 * on the headset as in the designs; stacked full-width on a phone, where three columns would break the words.
 */
@Composable
private fun ModeCards(page: OnboardingPage, type: VysionType, metrics: OnboardingMetrics, onPick: (Command) -> Unit) {
    val modes = listOf(Command.Read to Accent.yellow, Command.Describe to Accent.yellow, Command.FindDoor to Accent.cyan)
    val sideBySide = metrics.soundColumns >= 3
    val card: @Composable (Int, Modifier) -> Unit = { i, modifier ->
        val item = page.items[i]
        val (command, color) = modes[i]
        Tile(label = "${item.title}. ${item.detail.orEmpty()}", selected = false, onClick = { onPick(command) }, modifier = modifier) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.padding(if (sideBySide) Space.l else Space.m)) {
                Box(Modifier.width(metrics.chip).height(5.dp).background(color, CircleShape))
                Spacer(Modifier.height(Space.xs))
                Text(item.title, color = Ink.primary, style = type.windowTitle)
                item.detail?.let { Text(it, color = color, style = type.label) }
            }
        }
    }
    if (sideBySide) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.fillMaxWidth()) {
            page.items.indices.forEach { i -> card(i, Modifier.weight(1f)) }
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.fillMaxWidth()) {
            page.items.indices.forEach { i -> card(i, Modifier.fillMaxWidth()) }
        }
    }
}

/** A non-interactive call-out pill (the cane reminder, a practice that succeeded). */
@Composable
private fun CalloutPill(text: String, color: Color, type: VysionType, metrics: OnboardingMetrics, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), CircleShape)
            .border(2.dp, color, CircleShape)
            .padding(horizontal = metrics.button * 0.42f, vertical = metrics.button * 0.2f),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(metrics.button * 0.36f))
        Text(text, color = Ink.primary, style = type.label)
    }
}

/** The darker glass tray that carries the page's buttons under the card. */
@Composable
private fun ButtonTray(metrics: OnboardingMetrics, content: @Composable () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Glass.tray, CircleShape)
            .border(1.5.dp, Glass.edge, CircleShape)
            .padding(metrics.button * 0.14f),
    ) { content() }
}

/** The aurora: soft blue, violet and teal light over near-black, as in the designs' onboarding pages. */
fun Modifier.auroraBackground(): Modifier = this
    .background(Aurora.ground)
    .drawBehind {
        fun blob(color: Color, cx: Float, cy: Float, r: Float) {
            val center = Offset(size.width * cx, size.height * cy)
            val radius = size.minDimension * r
            drawCircle(
                brush = Brush.radialGradient(listOf(color.copy(alpha = 0.85f), Color.Transparent), center = center, radius = radius),
                radius = radius,
                center = center,
            )
        }
        blob(Aurora.blue, 0.24f, 0.36f, 0.62f)
        blob(Aurora.violet, 0.78f, 0.3f, 0.6f)
        blob(Aurora.teal, 0.55f, 0.92f, 0.5f)
        blob(Aurora.ember, 0.98f, 0.98f, 0.45f)
    }
