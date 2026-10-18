package com.vysion.xr.platform.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vysion.xr.platform.AndroidHaptics
import com.vysion.xr.platform.Haptic
import com.vysion.xr.platform.Haptics
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/*
 * What the phone app and its onboarding share (designs "Phone v6 · End-to-end"): the whole-screen gesture surface, the
 * haptic vocabulary ("You feel" in the designs' legend, patterns in platform/Haptics.kt, choices in PhoneFeedback.kt)
 * and the TalkBack check that makes the gesture layer step aside.
 */

/**
 * The designs' phone gestures on one surface. A gesture is classified when the last finger lifts: two fingers twice
 * within the double-tap window is Stop; a long, mostly horizontal move is a mode swipe; a long upward move is Repeat
 * (or more detail), a long downward move less detail; a press held past the long-press timeout without moving is talk,
 * started while still held, and sent on release unless the finger slid away first ("slide away before letting go to
 * cancel", designs V1); a short still touch is a tap.
 */
internal fun Modifier.gestureFirst(
    onTap: () -> Unit,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    onHoldCancel: () -> Unit,
    onSwipe: (dx: Float) -> Unit,
    onSwipeUp: () -> Unit,
    onSwipeDown: () -> Unit,
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
                holding -> if (moved > swipeDistance) onHoldCancel() else onHoldEnd()
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
                dy > swipeDistance && abs(dy) > abs(dx) * 1.5f -> onSwipeDown()
                moved < viewConfiguration.touchSlop -> onTap()
            }
        }
    }
}

/** "You feel": the [Haptic] vocabulary behind a Settings switch; silent when the user turned haptics off. */
internal class PhoneHaptics(private val haptics: Haptics, private val enabled: State<Boolean>) {
    fun play(kind: Haptic) {
        if (enabled.value) haptics.play(kind)
    }

    /** A light tap when you touch. */
    fun tap() = play(Haptic.TOUCH)

    /** A selection tick: a mode change, like a picker wheel. */
    fun tick() = play(Haptic.SELECTION)

    /** A medium thump when listening starts. */
    fun hold() = play(Haptic.LISTEN_START)

    /** A light tap at send. */
    fun send() = play(Haptic.LISTEN_SEND)

    /** The success buzz when an answer starts. */
    fun success() = play(Haptic.ANSWER)

    /** The success pattern, two quick taps: arrived, a lesson landed, all set. */
    fun done() = play(Haptic.SUCCESS)

    /** Two soft taps: no text, an error, the door lost, a cancelled hold. */
    fun reject() = play(Haptic.SOFT_DOUBLE)

    /** The double tick on stop. */
    fun stop() = play(Haptic.STOP)

    /** Three strong pulses for an obstacle. */
    fun warning() = play(Haptic.WARNING)
}

@Composable
internal fun rememberPhoneHaptics(enabled: Boolean): PhoneHaptics {
    val context = LocalContext.current
    val on = rememberUpdatedState(enabled)
    return remember(context) { PhoneHaptics(AndroidHaptics(context), on) }
}

/**
 * What the app does on its own, felt in the hand: a buzz when an answer starts, two soft taps on no text or a failure,
 * three strong pulses for an obstacle, and a faint pulse with each thinking tick. The choices live in [PhoneFeedback]
 * (unit-tested); this only watches the screen and plays them.
 */
@Composable
internal fun PhoneFeedbackEffect(shell: ShellState, haptics: PhoneHaptics) {
    val screen = shell.screen
    var previous by remember { mutableStateOf(screen) }
    LaunchedEffect(screen) {
        PhoneFeedback.onScreenChange(previous, screen)?.let(haptics::play)
        previous = screen
    }
    val obstacleShown = shell.obstacle != null
    var obstacleWasShown by remember { mutableStateOf(false) }
    LaunchedEffect(obstacleShown) {
        PhoneFeedback.onObstacle(obstacleWasShown, obstacleShown)?.let(haptics::play)
        obstacleWasShown = obstacleShown
    }
    val pulsing = PhoneFeedback.pulses(screen)
    LaunchedEffect(pulsing) {
        while (pulsing) {
            delay(PhoneFeedback.PULSE_EVERY_MS)
            haptics.play(Haptic.PULSE)
        }
    }
}

/** Whether TalkBack (touch exploration) is on; the gesture layer steps aside when it is. */
@Composable
internal fun rememberTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var enabled by remember { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}
