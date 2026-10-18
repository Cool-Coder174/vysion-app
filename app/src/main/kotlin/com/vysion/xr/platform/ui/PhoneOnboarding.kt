package com.vysion.xr.platform.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vysion.xr.AppGraph
import com.vysion.xr.R
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.Mode
import com.vysion.xr.core.OnboardingItem
import com.vysion.xr.core.PhoneOnboardingPage
import com.vysion.xr.core.PhoneOnboardingStep
import com.vysion.xr.platform.Permissions
import com.vysion.xr.platform.ui.components.GlyphDisc
import com.vysion.xr.platform.ui.components.Orb
import com.vysion.xr.platform.ui.components.PageDots
import com.vysion.xr.platform.ui.components.PillButton
import com.vysion.xr.platform.ui.components.PillStyle
import com.vysion.xr.platform.ui.components.phoneGlass
import com.vysion.xr.platform.ui.theme.Accent
import com.vysion.xr.platform.ui.theme.Glass
import com.vysion.xr.platform.ui.theme.Ink
import com.vysion.xr.platform.ui.theme.LocalReduceMotion
import com.vysion.xr.platform.ui.theme.PhoneType
import com.vysion.xr.platform.ui.theme.Space
import com.vysion.xr.platform.ui.theme.Target
import com.vysion.xr.platform.ui.theme.VysionIcons
import com.vysion.xr.platform.ui.theme.rememberReduceMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * First launch on a phone, "Phone v6 · End-to-end" section 1 (Michelle Bui's designs): about sixty seconds, all spoken,
 * six pages on the aurora ground. Welcome, then the camera-and-microphone ask, then three lessons that each wait for
 * the gesture they teach (tap, swipe, hold-and-stop) and move on by themselves once it lands, then the six sounds and
 * Start. Every page's words come from R4's phrasebook (`voice/Cues.kt`, `PhoneOnboardingCopy`) and are read aloud on
 * entry; the screen shows the same words (INV-4). Completion is the one ADR-0009 flag, saved by [OnboardingHost.finish].
 *
 * The same gesture surface as the app ([gestureFirst]) runs under the pages, so the lessons teach the real thing. With
 * TalkBack on the gesture layer steps aside, every page shows a Continue button, and the lesson pages do not block.
 * "Not now" on the permission page skips Android's dialogs; `MainActivity` asks again when the app starts.
 */
@Composable
fun PhoneOnboardingFlow(graph: AppGraph, host: OnboardingHost, modifier: Modifier = Modifier) {
    CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) {
        PhoneOnboardingPages(graph, host, modifier)
    }
}

@Composable
private fun PhoneOnboardingPages(graph: AppGraph, host: OnboardingHost, modifier: Modifier) {
    val steps = PhoneOnboardingStep.entries
    val startAt = LocalOnboardingStart.current
    var index by rememberSaveable { mutableIntStateOf(startAt.coerceIn(0, steps.lastIndex)) }
    var forward by remember { mutableStateOf(true) }
    val step = steps[index]
    val page = graph.phrasebook.phoneOnboarding(step)
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val talkBack = rememberTouchExploration()
    val haptics = rememberPhoneHaptics(enabled = true)
    val context = LocalContext.current
    val micGranted = Permissions.isGranted(context, Permissions.RECORD_AUDIO) // re-read on every recomposition after the dialogs

    // Lesson state, reset on every page change.
    var lessonDone by remember(step) { mutableStateOf(false) }
    var swipeMode by remember(step) { mutableStateOf(Mode.TEXT_READER) }
    var swipes by remember(step) { mutableIntStateOf(0) }
    var held by remember(step) { mutableStateOf(false) }
    var talking by remember(step) { mutableStateOf(false) }

    fun go(to: Int) {
        forward = to > index
        index = to.coerceIn(0, steps.lastIndex)
    }
    fun next() = go(index + 1)
    fun completeLesson() {
        if (lessonDone) return
        lessonDone = true
        haptics.done() // "Success pattern: two quick taps"
        graph.speaker.earcon(Earcon.DONE)
    }

    // Read each page aloud on entry; leave quietly.
    LaunchedEffect(step) { if (page.spoken.isNotBlank()) graph.speaker.say(page.spoken, interrupt = true) }
    DisposableEffect(Unit) { onDispose { graph.speaker.stop() } }
    // A lesson moves on by itself a moment after its gesture lands (designs: "Each step waits for you to try the gesture once").
    LaunchedEffect(lessonDone) {
        if (lessonDone) {
            delay(LESSON_DONE_PAUSE_MS)
            next()
        }
    }

    Box(modifier = modifier.fillMaxSize().auroraBackground()) {
        // The real gesture surface, under the page: the welcome's "tap anywhere" and the three lessons.
        if (!talkBack) {
            Box(
                Modifier
                    .fillMaxSize()
                    .gestureFirst(
                        onTap = {
                            when (step) {
                                PhoneOnboardingStep.WELCOME -> {
                                    haptics.tap()
                                    next()
                                }
                                PhoneOnboardingStep.LEARN_TAP -> {
                                    haptics.tap()
                                    completeLesson()
                                }
                                else -> Unit
                            }
                        },
                        onHoldStart = {
                            if (step == PhoneOnboardingStep.LEARN_TALK_STOP && !held) {
                                haptics.hold()
                                // After "Not now" the mic permission is missing: a real session would fail with
                                // ERROR_INSUFFICIENT_PERMISSIONS and leave the bus UNAVAILABLE for the whole process, so the
                                // lesson only plays the listening sound then. The app asks for the permission when it starts.
                                if (micGranted) graph.commandBus.startListening() else graph.speaker.earcon(Earcon.LISTENING)
                            }
                        },
                        onHoldEnd = {
                            if (step == PhoneOnboardingStep.LEARN_TALK_STOP && !held) {
                                haptics.send()
                                if (micGranted) graph.commandBus.stopListening()
                                held = true
                                talking = true
                                // Then Vysion talks until the two-finger double-tap stops it (the phrasebook's practice line).
                                scope.launch {
                                    graph.speaker.say(graph.phrasebook.phoneStopPractice, interrupt = true)
                                    talking = false
                                }
                            }
                        },
                        onHoldCancel = {
                            if (step == PhoneOnboardingStep.LEARN_TALK_STOP) {
                                haptics.reject()
                                if (micGranted) graph.commandBus.cancelListening()
                            }
                        },
                        onSwipe = { dx ->
                            if (step == PhoneOnboardingStep.LEARN_SWIPE) {
                                swipeMode = PhoneLogic.shifted(swipeMode, if (dx < 0) 1 else -1)
                                swipes++
                                haptics.tick()
                                // "I say each one as you land on it": the same announcement the app makes (FR-G3).
                                scope.launch { graph.speaker.say(graph.phrasebook.modeAnnounced(swipeMode), interrupt = true) }
                                if (swipes >= SWIPES_TO_LEARN) completeLesson()
                            }
                        },
                        onSwipeUp = {},
                        onSwipeDown = {},
                        onTwoFingerDoubleTap = {
                            if (step == PhoneOnboardingStep.LEARN_TALK_STOP && held) {
                                graph.speaker.stop()
                                talking = false
                                haptics.stop() // the double tick on stop
                                completeLesson()
                            }
                        },
                    ),
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Space.l, vertical = Space.m),
        ) {
            val dotsLabel = stringResource(R.string.a11y_page_dots, index + 1, steps.size)
            PageDots(count = steps.size, index = index, dot = 8.dp, modifier = Modifier.semantics { contentDescription = dotsLabel })
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = step,
                transitionSpec = { pageTransition(forward, reduceMotion) },
                label = "phoneOnboarding",
            ) { shown ->
                PageBody(
                    page = graph.phrasebook.phoneOnboarding(shown),
                    swipeMode = swipeMode,
                    held = held,
                    talking = talking,
                    lessonDone = lessonDone && shown == step,
                    onSound = { earcon -> graph.speaker.earcon(earcon) },
                )
            }
            Spacer(Modifier.weight(1f))
            Tray(
                step = step,
                page = page,
                talkBack = talkBack,
                lessonDone = lessonDone,
                onNext = ::next,
                onAllow = { host.requestPermissions { scope.launch { next() } } },
                onStart = { host.finish(null) },
            )
        }
    }
}

/** How long a finished lesson stays on screen before the next page, so the success is felt and heard. */
private const val LESSON_DONE_PAUSE_MS = 2_500L

/** Two swipes teach the switcher: the learner hears two modes land. */
private const val SWIPES_TO_LEARN = 2

@Composable
private fun PageBody(page: PhoneOnboardingPage, swipeMode: Mode, held: Boolean, talking: Boolean, lessonDone: Boolean, onSound: (Earcon) -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.l),
        modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
    ) {
        lessonNumber(page.step)?.let { n ->
            Text(stringResource(R.string.onboarding_lesson, n).uppercase(), color = Ink.tertiary, style = PhoneType.chip)
        }
        when (page.step) {
            PhoneOnboardingStep.WELCOME -> Orb(size = 96.dp)
            PhoneOnboardingStep.PERMISSIONS -> Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                GlyphDisc(VysionIcons.Camera, Glass.pill, Ink.primary, 56.dp)
                GlyphDisc(VysionIcons.Mic, Glass.pill, Ink.primary, 56.dp)
            }
            PhoneOnboardingStep.LEARN_TAP -> TapRings(done = lessonDone)
            PhoneOnboardingStep.LEARN_SWIPE -> SwipeRow(swipeMode)
            PhoneOnboardingStep.LEARN_TALK_STOP -> TalkStopRow(held = held, talking = talking)
            PhoneOnboardingStep.ALL_SET -> GlyphDisc(VysionIcons.Check, Accent.green, Color.White, 76.dp)
        }
        Text(page.title, color = Ink.primary, style = PhoneType.display, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        page.body?.let { Text(it, color = Ink.secondary, style = PhoneType.body, textAlign = TextAlign.Center) }
        when (page.step) {
            PhoneOnboardingStep.LEARN_TAP -> if (lessonDone) DoneCallout(stringResource(R.string.onboarding_tap_done))
            PhoneOnboardingStep.LEARN_SWIPE -> if (lessonDone) DoneCallout(stringResource(R.string.onboarding_swipe_done))
            PhoneOnboardingStep.LEARN_TALK_STOP -> when {
                lessonDone -> DoneCallout(stringResource(R.string.onboarding_stopped))
                held -> Text(stringResource(R.string.onboarding_now_stop), color = Accent.yellow, style = PhoneType.label, textAlign = TextAlign.Center)
                else -> Unit
            }
            PhoneOnboardingStep.ALL_SET -> SoundChips(page.items, onSound)
            else -> Unit
        }
    }
}

private fun lessonNumber(step: PhoneOnboardingStep): Int? = when (step) {
    PhoneOnboardingStep.LEARN_TAP -> 1
    PhoneOnboardingStep.LEARN_SWIPE -> 2
    PhoneOnboardingStep.LEARN_TALK_STOP -> 3
    else -> null
}

/** Lesson 1: three yellow rings around a dot, breathing until the tap lands, then still and green (designs O3). */
@Composable
private fun TapRings(done: Boolean) {
    val reduceMotion = LocalReduceMotion.current
    val pulse = if (reduceMotion || done) {
        1f
    } else {
        val transition = rememberInfiniteTransition(label = "tapRings")
        val value by transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = LinearEasing), RepeatMode.Reverse),
            label = "tapPulse",
        )
        value
    }
    val color = if (done) Accent.green else Accent.yellow
    Canvas(Modifier.size(230.dp)) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val stroke = 2.dp.toPx()
        drawCircle(color.copy(alpha = 0.35f), radius = 115.dp.toPx() * pulse - stroke, center = c, style = Stroke(stroke))
        drawCircle(color.copy(alpha = 0.6f), radius = 80.dp.toPx() * pulse, center = c, style = Stroke(stroke))
        drawCircle(color, radius = 36.dp.toPx(), center = c)
    }
}

/** Lesson 2: the three modes as pills between two arrows; the one just landed on is filled yellow (designs O4). */
@Composable
private fun SwipeRow(selected: Mode) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Mode.entries.forEach { mode ->
                val isSelected = mode == selected
                Text(
                    stringResource(modeLabel(mode)),
                    color = if (isSelected) Ink.onLight else Ink.primary,
                    style = PhoneType.label,
                    modifier = Modifier
                        .background(if (isSelected) Accent.yellow else Glass.pill, CircleShape)
                        .padding(horizontal = Space.m, vertical = Space.xs),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
            Icon(VysionIcons.Next, contentDescription = null, tint = Ink.secondary, modifier = Modifier.size(28.dp).rotate(180f))
            Box(Modifier.size(14.dp).background(Accent.yellow, CircleShape))
            Icon(VysionIcons.Next, contentDescription = null, tint = Ink.secondary, modifier = Modifier.size(28.dp))
        }
    }
}

private fun modeLabel(mode: Mode): Int = when (mode) {
    Mode.TEXT_READER -> R.string.mode_read
    Mode.AUDIO_DESCRIPTION -> R.string.mode_describe
    Mode.NAVIGATION -> R.string.mode_navigate
}

/** Lesson 3: the blue hold disc and the red two-finger double-tap (designs O5); each lights up as it is done. */
@Composable
private fun TalkStopRow(held: Boolean, talking: Boolean) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.xl)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(84.dp).border(2.dp, Accent.blue.copy(alpha = if (held) 0.3f else 0.9f), CircleShape)) {
                GlyphDisc(VysionIcons.Mic, Accent.blue, Color.White, 52.dp)
            }
            Text(stringResource(R.string.onboarding_hold_label), color = Ink.secondary, style = PhoneType.footnote)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                val alpha = if (talking || held) 1f else 0.6f
                Box(Modifier.offset(x = (-12).dp).size(40.dp).background(Accent.red.copy(alpha = alpha), CircleShape))
                Box(Modifier.offset(x = 12.dp).size(40.dp).background(Accent.red.copy(alpha = alpha), CircleShape))
                Text("×2", color = Ink.primary, style = PhoneType.label, modifier = Modifier.align(Alignment.TopEnd))
            }
            Text(stringResource(R.string.onboarding_two_finger_label), color = Ink.secondary, style = PhoneType.footnote)
        }
    }
}

/** The last page's six sounds as chips with their meaning color; tapping one plays it (designs O6). */
@Composable
private fun SoundChips(items: List<OnboardingItem>, onSound: (Earcon) -> Unit) {
    val sounds = listOf(
        Earcon.LISTENING to Accent.blue, Earcon.THINKING to Accent.purple, Earcon.DONE to Accent.green,
        Earcon.PING to Accent.cyan, Earcon.PROXIMITY to Accent.orange, Earcon.ERROR to Accent.red,
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        items.take(sounds.size).chunked(3).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                chunk.forEachIndexed { col, item ->
                    val (earcon, color) = sounds[row * 3 + col]
                    val label = stringResource(R.string.a11y_play_sound, item.title)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                        modifier = Modifier
                            .heightIn(min = Target.phone)
                            .phoneGlass(CircleShape, fill = Glass.control, edge = Color.Transparent)
                            .clickable(role = Role.Button, onClick = { onSound(earcon) })
                            .semantics { contentDescription = label }
                            .padding(start = Space.s, end = 14.dp, top = 10.dp, bottom = 10.dp),
                    ) {
                        Box(Modifier.size(12.dp).background(color, CircleShape))
                        Text(item.title, color = Ink.primary, style = PhoneType.label)
                    }
                }
            }
        }
    }
}

/** A lesson that landed: a green call-out with a check. */
@Composable
private fun DoneCallout(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier
            .background(Accent.green.copy(alpha = 0.16f), CircleShape)
            .border(2.dp, Accent.green, CircleShape)
            .padding(horizontal = Space.l, vertical = Space.xs),
    ) {
        Icon(VysionIcons.Check, contentDescription = null, tint = Accent.green, modifier = Modifier.size(18.dp))
        Text(text, color = Ink.primary, style = PhoneType.label)
    }
}

/** The buttons under each page. Lessons offer Skip until they land (Continue under TalkBack, where no gesture can land). */
@Composable
private fun Tray(
    step: PhoneOnboardingStep,
    page: PhoneOnboardingPage,
    talkBack: Boolean,
    lessonDone: Boolean,
    onNext: () -> Unit,
    onAllow: () -> Unit,
    onStart: () -> Unit,
) {
    val button = 52.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.heightIn(min = button * 2 + Space.s)) {
        when (step) {
            PhoneOnboardingStep.WELCOME -> {
                val hint = page.hint ?: stringResource(R.string.action_continue)
                Text(
                    hint,
                    color = Ink.primary,
                    style = PhoneType.label,
                    modifier = Modifier
                        .heightIn(min = Target.phone)
                        .phoneGlass(CircleShape)
                        .clickable(role = Role.Button, onClick = onNext)
                        .padding(horizontal = 18.dp, vertical = 11.dp),
                )
            }
            PhoneOnboardingStep.PERMISSIONS -> {
                PillButton(stringResource(R.string.action_allow_camera_mic), onAllow, PillStyle.PRIMARY, PhoneType.label, button, modifier = Modifier.fillMaxWidth())
                PillButton(stringResource(R.string.action_not_now), onNext, PillStyle.SECONDARY, PhoneType.label, button, modifier = Modifier.fillMaxWidth())
            }
            PhoneOnboardingStep.LEARN_TAP, PhoneOnboardingStep.LEARN_SWIPE, PhoneOnboardingStep.LEARN_TALK_STOP -> {
                if (talkBack) {
                    PillButton(stringResource(R.string.action_continue), onNext, PillStyle.PRIMARY, PhoneType.label, button)
                } else if (!lessonDone) {
                    PillButton(stringResource(R.string.action_skip), onNext, PillStyle.SECONDARY, PhoneType.label, button)
                }
            }
            PhoneOnboardingStep.ALL_SET -> PillButton(stringResource(R.string.action_start), onStart, PillStyle.PRIMARY, PhoneType.label, button, modifier = Modifier.widthIn(min = 200.dp))
        }
    }
}
