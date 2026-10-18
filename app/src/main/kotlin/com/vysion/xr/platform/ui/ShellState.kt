package com.vysion.xr.platform.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vysion.xr.AppGraph
import com.vysion.xr.core.Command
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.SpeechProgress
import com.vysion.xr.platform.PassthroughOverlayRenderer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop

/**
 * Everything both shells read from the graph, plus the few event times [HeadsetScreens] needs (last command,
 * last error, last navigation cue, last hazard). Times are `SystemClock.elapsedRealtime()`: monotonic (H-21).
 */
class ShellState internal constructor(
    val state: ModeState,
    val mode: Mode?,
    val transcript: String?,
    val listening: ListeningState,
    val progress: SpeechProgress?,
    /** `null` only if `AppGraph` is ever wired with another `OverlayRenderer`; then nothing is drawn. */
    val overlay: PassthroughOverlayRenderer?,
    val input: ScreenInput,
    private val onDismissHazard: () -> Unit,
    /** ADR-0010: the wearer's cloud Describe switch (`ModeController.cloudDescribe`), shown on the Describe toolbar. */
    val cloudDescribe: Boolean = false,
) {
    val screen: HeadsetScreen = HeadsetScreens.reduce(input)
    val obstacle: ObstacleAlert? = HeadsetScreens.obstacle(input)

    /** The obstacle card's OK. */
    fun dismissHazard() = onDismissHazard()

    /** The phrase being spoken right now, as a range of [text], or `null` when this text is not being spoken. */
    fun spokenRange(text: String): IntRange? {
        val p = progress ?: return null
        if (p.done || p.text != text) return null
        return HeadsetScreens.spokenSentence(text, p.charStart, p.charEnd)
    }
}

@Composable
fun rememberShellState(graph: AppGraph): ShellState {
    // Debug builds only: a synthetic designs screen requested by the launch intent (DesignPreview.kt).
    LocalDesignPreview.current?.let { return rememberPreviewShellState(graph, it) }

    val state by graph.modeController.state.collectAsStateWithLifecycle()
    val mode by graph.modeController.mode.collectAsStateWithLifecycle()
    val transcript by graph.modeController.transcript.collectAsStateWithLifecycle()
    val transcriptKind by graph.modeController.transcriptKind.collectAsStateWithLifecycle()
    val lookingForDoor by graph.modeController.lookingForDoor.collectAsStateWithLifecycle()
    val listening by graph.commandBus.state.collectAsStateWithLifecycle()
    val progress by graph.speaker.progress.collectAsStateWithLifecycle(initialValue = null)
    val cloudDescribe by graph.modeController.cloudDescribe.collectAsStateWithLifecycle()

    var lastCommand by remember { mutableStateOf<Command?>(null) }
    var lastCommandAt by remember { mutableStateOf<Long?>(null) }
    var lastErrorAt by remember { mutableStateOf<Long?>(null) }
    var navCue by remember { mutableStateOf<NavigationCue?>(null) }
    var navCueAt by remember { mutableStateOf<Long?>(null) }
    var lastDoorClock by remember { mutableStateOf<Int?>(null) }
    var hazard by remember { mutableStateOf<NavigationCue?>(null) }
    var hazardAt by remember { mutableStateOf<Long?>(null) }
    var hazardDismissedAt by remember { mutableStateOf<Long?>(null) }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LaunchedEffect(graph) {
        graph.commandBus.commands.collect { event ->
            lastCommand = event.command
            lastCommandAt = SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(graph) {
        // Only failures that happen while this screen is up: the value already held is from an earlier task.
        graph.modeController.lastError.drop(1).collect { error ->
            if (error != null) lastErrorAt = SystemClock.elapsedRealtime()
        }
    }
    LaunchedEffect(graph) {
        graph.navigationEngine.cues.collect { cue ->
            val t = SystemClock.elapsedRealtime()
            when (cue) {
                is NavigationCue.Proximity, is NavigationCue.WallAhead, is NavigationCue.FloorEnds -> {
                    hazard = cue
                    hazardAt = t
                }
                is NavigationCue.DoorBearing -> {
                    navCue = cue
                    navCueAt = t
                    lastDoorClock = cue.clock
                }
                else -> {
                    navCue = cue
                    navCueAt = t
                }
            }
        }
    }
    // Freshness rules (a stale bearing, an expired hazard card) need the clock only while one is pending.
    val ticking = navCue is NavigationCue.DoorBearing || hazard != null
    LaunchedEffect(ticking) {
        while (ticking) {
            now = SystemClock.elapsedRealtime()
            delay(TICK_MS)
        }
    }

    val input = ScreenInput(
        state = state,
        mode = mode,
        transcript = transcript,
        listening = listening,
        transcriptKind = transcriptKind,
        lookingForDoor = lookingForDoor,
        lastCommand = lastCommand,
        lastCommandAtMs = lastCommandAt,
        lastErrorAtMs = lastErrorAt,
        navCue = navCue,
        navCueAtMs = navCueAt,
        lastDoorClock = lastDoorClock,
        hazard = hazard,
        hazardAtMs = hazardAt,
        hazardDismissedAtMs = hazardDismissedAt,
        noTextSentinel = graph.prompts.noTextSentinel,
        nowMs = maxOf(now, SystemClock.elapsedRealtime()),
    )
    return ShellState(
        state = state,
        mode = mode,
        transcript = transcript,
        listening = listening,
        progress = progress,
        overlay = graph.overlay as? PassthroughOverlayRenderer,
        input = input,
        onDismissHazard = {
            hazardDismissedAt = SystemClock.elapsedRealtime()
            graph.speaker.stop()
        },
        cloudDescribe = cloudDescribe,
    )
}

/** A [ShellState] built from [DesignPreview] instead of the live graph (debug builds only). */
@Composable
private fun rememberPreviewShellState(graph: AppGraph, preview: PreviewScreen): ShellState {
    val input = DesignPreview.input(preview)
    val overlay = graph.overlay as? PassthroughOverlayRenderer
    LaunchedEffect(preview) {
        graph.overlay.showDetections(DesignPreview.detections(preview))
        graph.overlay.highlightDoor(DesignPreview.door(preview))
    }
    return ShellState(
        state = input.state,
        mode = input.mode,
        transcript = input.transcript,
        listening = input.listening,
        progress = DesignPreview.progress(preview),
        overlay = overlay,
        input = input,
        onDismissHazard = {},
    )
}

private const val TICK_MS = 500L
