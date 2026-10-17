package com.vysion.xr.platform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vysion.xr.AppGraph
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.SpeechProgress
import com.vysion.xr.platform.PassthroughOverlayRenderer

/**
 * The flat 2D layout for the S25 Ultra and the laptop emulator (ADR-0006, SRS §8 R5 "S25 Ultra build
 * of the same app with a flat 2D UI", Sat 20:00; A-12). Owner: R1. Same controls, transcript and
 * diagnostics as [SpatialShell], one Compose screen:
 *
 * ```
 * ┌──────────────────────────┐
 * │ overlay area (4:3)       │  outlines from PassthroughOverlayRenderer (FR-N2) over a camera preview
 * ├──────────────────────────┤
 * │ transcript (48 sp, 21:1) │  FR-T2
 * ├──────────────────────────┤
 * │ control panel            │  FR-G1/G2/G3/G4/G5
 * └──────────────────────────┘
 * ```
 * The camera preview behind the overlay is a `PreviewView` (camera-view, pinned) bound by
 * `CameraFrameSource` once A-1 is answered; until then the area is black (`TODO(FR-N2)` below).
 * Navigation is reported unavailable here by the controller (`Cues.navigationUnavailable`, R4).
 *
 * [diagnostics] is the FR-G5 overlay slot: `MainActivity` (root package) passes R5's
 * `DiagnosticsOverlay(graph.diagnostics)`, so this package never imports `com.vysion.xr.diagnostics`
 * (ADR-0005). It is shown while the diagnostics toggle is on.
 */
@Composable
fun FlatShell(graph: AppGraph, diagnostics: @Composable () -> Unit) {
    val shell = rememberShellState(graph)
    var diagnosticsShown by rememberSaveable { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(OVERLAY_ASPECT)
                    .background(Color.Black),
            ) {
                // TODO(FR-N2): AndroidView { PreviewView(it) } bound through CameraFrameSource on the phone
                // (camera-view 1.6.2).
                shell.overlay?.let { OverlayCanvas(renderer = it, modifier = Modifier.fillMaxSize()) }
            }
            TranscriptPanel(
                transcript = shell.transcript,
                progress = shell.progress,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            ControlPanel(
                commands = graph.commandBus,
                listening = shell.listening,
                mode = shell.mode,
                state = shell.state,
                diagnosticsShown = diagnosticsShown,
                onToggleDiagnostics = { diagnosticsShown = !diagnosticsShown },
                modifier = Modifier.fillMaxWidth().padding(PANEL_MARGIN),
            )
        }
        if (diagnosticsShown) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(PANEL_MARGIN)) {
                diagnostics() // FR-G5 (R5's DiagnosticsOverlay, passed in by MainActivity)
            }
        }
    }
}

/** Everything both shells read from the graph, collected once per frame of recomposition. */
internal class ShellState(
    val state: ModeState,
    val mode: Mode?,
    val transcript: String?,
    val listening: ListeningState,
    val progress: SpeechProgress?,
    /** `null` only if `AppGraph` is ever wired with another `OverlayRenderer`; then nothing is drawn. */
    val overlay: PassthroughOverlayRenderer?,
)

@Composable
internal fun rememberShellState(graph: AppGraph): ShellState {
    val state by graph.modeController.state.collectAsStateWithLifecycle()
    val mode by graph.modeController.mode.collectAsStateWithLifecycle()
    val transcript by graph.modeController.transcript.collectAsStateWithLifecycle()
    val listening by graph.commandBus.state.collectAsStateWithLifecycle()
    val progress by graph.speaker.progress.collectAsStateWithLifecycle(initialValue = null)
    return ShellState(
        state = state,
        mode = mode,
        transcript = transcript,
        listening = listening,
        progress = progress,
        overlay = graph.overlay as? PassthroughOverlayRenderer,
    )
}

/** SRS §6: the analysis stream is 640x480. */
private const val OVERLAY_ASPECT = 4f / 3f
private val PANEL_MARGIN = 12.dp
