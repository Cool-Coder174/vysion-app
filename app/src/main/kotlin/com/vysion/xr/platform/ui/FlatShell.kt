package com.vysion.xr.platform.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vysion.xr.AppGraph
import com.vysion.xr.R
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import com.vysion.xr.platform.ui.components.PillButton
import com.vysion.xr.platform.ui.components.PillStyle
import com.vysion.xr.platform.ui.components.ThinkingLines
import com.vysion.xr.platform.ui.components.Waveform
import com.vysion.xr.platform.ui.components.glass
import com.vysion.xr.platform.ui.theme.Accent
import com.vysion.xr.platform.ui.theme.Glass
import com.vysion.xr.platform.ui.theme.Ink
import com.vysion.xr.platform.ui.theme.LocalReduceMotion
import com.vysion.xr.platform.ui.theme.PhoneType
import com.vysion.xr.platform.ui.theme.Radius
import com.vysion.xr.platform.ui.theme.Space
import com.vysion.xr.platform.ui.theme.Target
import com.vysion.xr.platform.ui.theme.VysionIcons
import com.vysion.xr.platform.ui.theme.rememberReduceMotion
import com.vysion.xr.platform.ui.theme.vysionSpring
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The S25 Ultra app, "Phone app v5 · Gesture-first" (Michelle Bui's designs, page 23): the camera fills the
 * screen and the whole screen is the button; modes have real labels; answers appear as one bold color block;
 * every gesture also has a TalkBack-friendly control. Same graph and same commands as the headset (ADR-0006).
 *
 * Gestures (designs' gesture guide): tap anywhere runs the current mode; press and hold talks (release sends);
 * swipe left or right changes mode; swipe up repeats; two-finger double-tap stops talking. With TalkBack on the
 * gesture layer steps aside and the shutter, mode tabs and an "Again" button carry the same actions.
 *
 * The camera preview behind the overlay is a `PreviewView` bound by `CameraFrameSource` once A-1 is answered;
 * until then the area is a dark ground (`TODO(FR-N2)` below). Navigation on the phone is reported unavailable by
 * the controller (`Cues.navigationUnavailable`). [diagnostics] is R5's overlay slot (ADR-0005).
 */
@Composable
fun FlatShell(graph: AppGraph, diagnostics: @Composable () -> Unit, onboarding: OnboardingHost) {
    CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) {
        if (!onboarding.done) {
            OnboardingFlow(graph, onboarding, PhoneType, PhoneOnboarding, Modifier.statusBarsPadding().navigationBarsPadding())
        } else {
            PhoneApp(graph, diagnostics)
        }
    }
}

@Composable
private fun PhoneApp(graph: AppGraph, diagnostics: @Composable () -> Unit) {
    val shell = rememberShellState(graph)
    val talkBack = rememberTouchExploration()
    var selected by rememberSaveable { mutableStateOf(Mode.TEXT_READER) }
    var showTips by rememberSaveable { mutableStateOf(true) }
    val preview = LocalDesignPreview.current
    var diagnosticsShown by rememberSaveable { mutableStateOf(preview == PreviewScreen.DIAGNOSTICS) }
    var modeFlash by remember { mutableStateOf(0) }
    val commands = graph.commandBus

    // The controller's mode follows voice too; the tabs follow it.
    LaunchedEffect(shell.mode) { shell.mode?.let { selected = it } }
    LaunchedEffect(shell.input.lastCommandAtMs) { if (shell.input.lastCommandAtMs != null) showTips = false }

    fun run() = commands.inject(selected.command(), CommandSource.PANEL)
    fun shift(by: Int) {
        val modes = Mode.entries
        selected = modes[(modes.indexOf(selected) + by + modes.size) % modes.size]
        modeFlash++
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // TODO(FR-N2): AndroidView { PreviewView(it) } bound through CameraFrameSource on the phone (camera-view 1.6.2).
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0xFF16161A), Color(0xFF0B0B0D)))),
        )
        shell.overlay?.let {
            OverlayCanvas(
                renderer = it,
                modifier = Modifier.fillMaxSize(),
                style = OverlayStyle.forScreen(shell.screen, stringResource(R.string.overlay_door), stringResource(R.string.overlay_last_seen))
                    .copy(labelSize = PhoneType.chip.fontSize),
            )
        }

        // The whole screen is the button (skipped under TalkBack, where the controls below carry the actions).
        if (!talkBack) {
            Box(
                Modifier
                    .fillMaxSize()
                    .gestureFirst(
                        onTap = ::run,
                        onHoldStart = commands::startListening,
                        onHoldEnd = commands::stopListening,
                        onSwipe = { dx -> shift(if (dx < 0) 1 else -1) },
                        onSwipeUp = { commands.inject(Command.Repeat, CommandSource.PANEL) },
                        onTwoFingerDoubleTap = { commands.inject(Command.Stop, CommandSource.PANEL) },
                    ),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) {
                    androidx.compose.animation.AnimatedVisibility(
                        visible = showTips && shell.screen == HeadsetScreen.Ready,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        TipsCard()
                    }
                }
                RoundIconButton(
                    label = stringResource(R.string.a11y_diagnostics_toggle),
                    selected = diagnosticsShown,
                    onClick = { diagnosticsShown = !diagnosticsShown },
                ) { tint -> Icon(VysionIcons.More, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
            }
            if (diagnosticsShown) diagnostics() // R5's panel is its own glass window; no second frame around it
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ModeFlash(selected, modeFlash)
            }
            AnswerBlock(shell, talkBack)
            ModeTabs(selected = selected, onSelect = { selected = it; modeFlash++ })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
                if (talkBack) {
                    // Designs page 23, "Same screen with TalkBack on": swipe up becomes a labeled Again button.
                    PillButton(
                        label = stringResource(R.string.action_again),
                        onClick = { commands.inject(Command.Repeat, CommandSource.PANEL) },
                        style = PillStyle.SECONDARY,
                        textStyle = PhoneType.label,
                        height = Target.phone,
                        icon = VysionIcons.Repeat,
                    )
                    Box(Modifier.size(Space.l))
                }
                Shutter(shell = shell, selected = selected, commands = commands, onRun = ::run)
            }
        }
    }
}

/** The command each mode tab runs. */
private fun Mode.command(): Command = when (this) {
    Mode.TEXT_READER -> Command.Read
    Mode.AUDIO_DESCRIPTION -> Command.Describe
    Mode.NAVIGATION -> Command.FindDoor
}

private fun Mode.accent(): Color = if (this == Mode.NAVIGATION) Accent.cyan else Accent.yellow

@Composable
private fun Mode.label(): String = stringResource(
    when (this) {
        Mode.TEXT_READER -> R.string.mode_read
        Mode.AUDIO_DESCRIPTION -> R.string.mode_describe
        Mode.NAVIGATION -> R.string.mode_navigate
    },
)

@Composable
private fun ModeIcon(mode: Mode, tint: Color) {
    when (mode) {
        Mode.TEXT_READER -> Text("Aa", color = tint, style = PhoneType.label)
        Mode.AUDIO_DESCRIPTION -> Icon(VysionIcons.Eye, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        Mode.NAVIGATION -> Icon(VysionIcons.Navigate, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** First-time tips: "The whole screen is the button." Hidden after the first command. */
@Composable
private fun TipsCard() {
    val tips = listOf(
        R.string.phone_tip_tap to R.string.phone_tip_tap_detail,
        R.string.phone_tip_hold to R.string.phone_tip_hold_detail,
        R.string.phone_tip_swipe to R.string.phone_tip_swipe_detail,
        R.string.phone_tip_stop to R.string.phone_tip_stop_detail,
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier.widthIn(max = 320.dp).glass(RoundedCornerShape(Radius.tile)).padding(Space.m),
    ) {
        Text(stringResource(R.string.phone_tips_title), color = Ink.primary, style = PhoneType.windowTitle)
        tips.forEach { (title, detail) ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Accent.yellow, CircleShape))
                Column {
                    Text(stringResource(title), color = Ink.primary, style = PhoneType.label)
                    Text(stringResource(detail), color = Ink.secondary, style = PhoneType.footnote)
                }
            }
        }
    }
}

/** After a swipe, the new mode's name shows in the middle of the screen for a moment (designs: "Swipe → Describe"). */
@Composable
private fun ModeFlash(mode: Mode, flash: Int) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(flash) {
        if (flash == 0) return@LaunchedEffect
        visible = true
        delay(1_200)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = fadeIn(vysionSpring()) + scaleIn(vysionSpring(), initialScale = 0.92f), exit = fadeOut(vysionSpring())) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s),
                modifier = Modifier.background(mode.accent(), CircleShape).padding(horizontal = Space.l, vertical = Space.s),
            ) {
                ModeIcon(mode, Ink.onLight)
                Text(mode.label(), color = Ink.onLight, style = PhoneType.prompt)
            }
            val hint = when (mode) {
                Mode.TEXT_READER -> R.string.phone_hint_read
                Mode.AUDIO_DESCRIPTION -> R.string.phone_hint_describe
                Mode.NAVIGATION -> R.string.phone_hint_navigate
            }
            Text(
                stringResource(hint),
                color = Ink.primary,
                style = PhoneType.footnote,
                modifier = Modifier.background(Glass.tray, CircleShape).padding(horizontal = Space.m, vertical = Space.xs),
            )
        }
    }
}

/** One bold block for the current answer or state, colored by meaning (yellow read/describe, cyan navigation). */
@Composable
private fun AnswerBlock(shell: ShellState, talkBack: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    AnimatedContent(
        targetState = shell.screen,
        contentKey = { it::class },
        transitionSpec = {
            if (reduceMotion) fadeIn() togetherWith fadeOut() else (fadeIn(vysionSpring()) + scaleIn(vysionSpring(), initialScale = 0.96f)) togetherWith fadeOut(vysionSpring(response = 0.2f))
        },
        label = "answer",
    ) { screen ->
        when (screen) {
            is HeadsetScreen.Answer -> {
                // Under TalkBack the swipe hint does not apply (the Again button does the job).
                val label = when {
                    screen.mode == Mode.TEXT_READER && talkBack -> R.string.status_reading
                    screen.mode == Mode.TEXT_READER -> R.string.phone_label_reading
                    talkBack -> R.string.status_describing
                    else -> R.string.phone_label_describing
                }
                ColorBlock(Accent.yellow, stringResource(label).uppercase()) {
                    // A long answer scrolls inside the block so the camera and the controls stay on screen.
                    Text(
                        screen.text,
                        color = Ink.onLight,
                        style = PhoneType.answer,
                        modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                    )
                }
            }
            is HeadsetScreen.Place -> ColorBlock(Accent.cyan, stringResource(R.string.status_where_am_i).uppercase()) {
                Text(
                    screen.text,
                    color = Ink.onLight,
                    style = PhoneType.answer,
                    modifier = Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                )
                // FR-N9: the map's credit wherever its data is shown.
                if (screen.mapCredit) Text(stringResource(R.string.map_attribution), color = Ink.onLight, style = PhoneType.chip)
            }
            is HeadsetScreen.Guiding -> ColorBlock(Accent.cyan, stringResource(R.string.phone_label_door, clockText(screen.clock)).uppercase()) {
                Text(metersText(screen.distanceM), color = Ink.onLight, style = PhoneType.distance)
            }
            HeadsetScreen.Listening -> ColorBlock(Color.White, stringResource(R.string.phone_label_listening).uppercase()) {
                Waveform(color = Accent.blue, barWidth = 6.dp, height = 44.dp, bars = 11)
            }
            is HeadsetScreen.Thinking, HeadsetScreen.LookingForDoor -> DarkBlock(stringResource(if (screen is HeadsetScreen.Thinking) R.string.status_thinking else R.string.status_looking)) {
                ThinkingLines(lineHeight = 14.dp)
            }
            HeadsetScreen.NoText -> DarkBlock(stringResource(R.string.status_no_text)) {
                Text(stringResource(R.string.status_no_text_body), color = Ink.primary, style = PhoneType.prompt)
            }
            is HeadsetScreen.Arrived -> ColorBlock(Accent.green, stringResource(R.string.status_arrived).uppercase()) {
                Text(stringResource(R.string.status_arrived_body), color = Ink.onLight, style = PhoneType.answer)
            }
            is HeadsetScreen.Lost -> DarkBlock(stringResource(R.string.status_lost)) {
                Text(stringResource(R.string.status_lost_body_unknown), color = Ink.primary, style = PhoneType.prompt)
            }
            HeadsetScreen.Error -> DarkBlock(stringResource(R.string.status_error)) {
                Text(stringResource(R.string.status_error_body), color = Ink.primary, style = PhoneType.prompt)
            }
            HeadsetScreen.Ready -> Box(Modifier.fillMaxWidth()) {}
        }
    }
    shell.obstacle?.let { alert ->
        ColorBlock(Accent.orange, stringResource(R.string.status_obstacle).uppercase()) {
            Text("${metersText(alert.distanceM)} · ${stringResource(R.string.status_obstacle_ahead)}", color = Ink.onLight, style = PhoneType.answer)
        }
    }
}

@Composable
private fun ColorBlock(color: Color, label: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(Radius.tile)).padding(Space.m),
    ) {
        Text(label, color = Ink.onLight.copy(alpha = 0.72f), style = PhoneType.chip)
        content()
    }
}

@Composable
private fun DarkBlock(title: String, content: @Composable () -> Unit) {
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier.fillMaxWidth().glass(RoundedCornerShape(Radius.tile)).padding(Space.m),
    ) {
        Text(title, color = Ink.secondary, style = PhoneType.windowTitle)
        content()
    }
}

/** Read · Describe · Navigate as labeled pills; the current one filled with its meaning color. */
@Composable
private fun ModeTabs(selected: Mode, onSelect: (Mode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs, Alignment.CenterHorizontally), modifier = Modifier.fillMaxWidth()) {
        Mode.entries.forEach { mode ->
            val isSelected = mode == selected
            val tint = if (isSelected) Ink.onLight else Ink.primary
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .background(if (isSelected) mode.accent() else Glass.tray, CircleShape)
                    .clickable(role = Role.Tab, onClick = { onSelect(mode) })
                    .semantics { this.selected = isSelected }
                    .padding(horizontal = Space.m, vertical = Space.s),
            ) {
                ModeIcon(mode, tint)
                Text(mode.label(), color = tint, style = PhoneType.label)
            }
        }
    }
}

/**
 * The big shutter: tap runs the current mode, press and hold talks. While Vysion is busy or speaking it turns
 * into a red Stop. Its TalkBack label says what a double-tap does now.
 */
@Composable
private fun Shutter(shell: ShellState, selected: Mode, commands: CommandBus, onRun: () -> Unit) {
    val busy = shell.state != ModeState.IDLE
    val listening = shell.listening == ListeningState.LISTENING
    val runLabel = stringResource(R.string.a11y_shutter_run, selected.label())
    val stopLabel = stringResource(R.string.a11y_shutter_stop)
    val inner = when {
        listening -> Accent.blue
        busy -> Accent.red
        else -> Color.White
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(84.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .background(inner, CircleShape)
            .pointerInput(busy) {
                detectTapGestures(
                    onTap = { if (busy) commands.inject(Command.Stop, CommandSource.PANEL) else onRun() },
                    onLongPress = { commands.startListening() },
                    onPress = {
                        tryAwaitRelease()
                        if (commands.state.value == ListeningState.LISTENING) commands.stopListening()
                    },
                )
            }
            .semantics {
                contentDescription = if (busy) stopLabel else runLabel
                role = Role.Button
                onClick {
                    if (busy) commands.inject(Command.Stop, CommandSource.PANEL) else onRun()
                    true
                }
                onLongClick {
                    if (listening) commands.stopListening() else commands.startListening()
                    true
                }
            },
    ) {
        when {
            listening -> Icon(VysionIcons.Mic, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
            busy -> Icon(VysionIcons.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun RoundIconButton(label: String, selected: Boolean, onClick: () -> Unit, glyph: @Composable (Color) -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Target.phone)
            .background(if (selected) Color.White else Glass.tray, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
    ) { glyph(if (selected) Ink.onLight else Ink.primary) }
}

/** Whether TalkBack (touch exploration) is on; the gesture layer steps aside when it is. */
@Composable
private fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var enabled by remember { mutableStateOf(manager.isTouchExplorationEnabled) }
    androidx.compose.runtime.DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/**
 * The designs' phone gestures on one surface. A gesture is classified when the last finger lifts: two fingers
 * twice within the double-tap window is Stop; a long, mostly horizontal move is a mode swipe; a long upward move
 * is Repeat; a press held past the long-press timeout (without moving) is talk, started while still held; a
 * short still touch is a tap.
 */
private fun Modifier.gestureFirst(
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onSwipe: (dx: Float) -> Unit,
    onSwipeUp: () -> Unit,
    onTwoFingerDoubleTap: () -> Unit,
): Modifier = this.pointerInput(Unit) {
    var lastTwoFingerTapAt = 0L
    val swipeDistance = 72.dp.toPx()
    coroutineScope {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val start = down.position
            var maxPointers = 1
            var moved = 0f
            var dx = 0f
            var dy = 0f
            var holding = false
            val hold = launch {
                delay(viewConfiguration.longPressTimeoutMillis)
                if (maxPointers == 1 && moved < viewConfiguration.touchSlop) {
                    holding = true
                    onHoldStart()
                }
            }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                maxPointers = maxOf(maxPointers, event.changes.count { it.pressed })
                event.changes.firstOrNull { it.id == down.id }?.let { change ->
                    dx = change.position.x - start.x
                    dy = change.position.y - start.y
                    moved = maxOf(moved, abs(dx) + abs(dy))
                }
                if (event.changes.none { it.pressed }) break
            }
            hold.cancel()
            val now = down.uptimeMillis // monotonic event time (H-21)
            when {
                holding -> onHoldEnd()
                maxPointers >= 2 && moved < swipeDistance -> {
                    if (now - lastTwoFingerTapAt < viewConfiguration.doubleTapTimeoutMillis * 2) {
                        lastTwoFingerTapAt = 0L
                        onTwoFingerDoubleTap()
                    } else {
                        lastTwoFingerTapAt = now
                    }
                }
                abs(dx) > swipeDistance && abs(dx) > abs(dy) * 1.5f -> onSwipe(dx)
                -dy > swipeDistance && abs(dy) > abs(dx) * 1.5f -> onSwipeUp()
                moved < viewConfiguration.touchSlop -> onTap()
            }
        }
    }
}
