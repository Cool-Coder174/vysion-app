package com.vysion.xr.platform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vysion.xr.R
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState

/**
 * The floating control panel (FR-G1 "one floating control panel"; FR-G2 mic button; FR-G3 active
 * mode shown; FR-G5 diagnostics toggle; FR-G4 stop). Owner: R1, due Sat 18:00. Design rule 2:
 * "voice first, gestures second, panel buttons third", so every button injects the same
 * [Command] the voice path would (`CommandBus.inject`, source PANEL) and the controller treats them
 * identically.
 *
 * Accessibility (NFR; SRS §3 rule 4): every control carries `Modifier.semantics { contentDescription
 * = stringResource(R.string.a11y_…) }` from `res/values/strings.xml`, targets are ≥ 56 dp
 * (docs/stack.md XR visual-design numbers) and the active mode is also exposed through
 * `selected` / `stateDescription`. Visible captions reuse the same resources: this file adds no
 * strings (they belong to `strings.xml`, which R4 maintains for TalkBack; see the module doc).
 *
 * The mic: press-and-hold calls `startListening()` on press and `stopListening()` on release
 * (push-to-talk); a TalkBack double-tap toggles instead, because a hold is not reachable there.
 * Pinch-and-hold (P1, Sun 10:00) will call the same two functions from hand tracking (R3's session).
 */
@Composable
fun ControlPanel(
    commands: CommandBus,
    listening: ListeningState,
    mode: Mode?,
    state: ModeState,
    diagnosticsShown: Boolean,
    onToggleDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER))
            .padding(PANEL_PADDING),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ROW_SPACING),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(BUTTON_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicButton(commands = commands, listening = listening)
            ModeButton(
                label = stringResource(R.string.a11y_mode_read),
                selected = mode == Mode.TEXT_READER,
                onClick = { commands.inject(Command.Read, CommandSource.PANEL) }, // FR-T1
            )
            ModeButton(
                label = stringResource(R.string.a11y_mode_describe),
                selected = mode == Mode.AUDIO_DESCRIPTION,
                onClick = { commands.inject(Command.Describe, CommandSource.PANEL) }, // FR-D1
            )
            ModeButton(
                label = stringResource(R.string.a11y_mode_navigate),
                selected = mode == Mode.NAVIGATION,
                onClick = { commands.inject(Command.GuideMe, CommandSource.PANEL) }, // FR-N1..N3
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(BUTTON_SPACING),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelButton(
                label = stringResource(R.string.a11y_stop_button),
                onClick = { commands.inject(Command.Stop, CommandSource.PANEL) }, // FR-G4: same lane as "stop"
                container = STOP_COLOR,
            )
            PanelButton(
                label = stringResource(R.string.a11y_diagnostics_toggle),
                onClick = onToggleDiagnostics, // FR-G5
                container = if (diagnosticsShown) SELECTED_COLOR else BUTTON_COLOR,
            )
            // FR-G3: the state is informational; the enum name is not a spoken cue and not a number.
            Text(
                text = state.name,
                color = TEXT_COLOR,
                fontSize = STATE_TEXT_SIZE,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = BUTTON_SPACING),
            )
        }
    }
}

/** FR-G2: hold to talk. `startListening()` on press, `stopListening()` on release; double-tap toggles for TalkBack. */
@Composable
private fun MicButton(commands: CommandBus, listening: ListeningState) {
    val label = stringResource(R.string.a11y_mic_button)
    val caption = label.substringBefore('.') // "Microphone": the visible word comes from the same resource
    val isListening = listening == ListeningState.LISTENING
    val color = when (listening) {
        ListeningState.LISTENING -> SELECTED_COLOR
        ListeningState.UNAVAILABLE -> UNAVAILABLE_COLOR // RK-4: voice unavailable, buttons stay the P0 path
        else -> BUTTON_COLOR
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .sizeIn(minWidth = MIC_SIZE, minHeight = MIC_SIZE)
            .background(color, CircleShape)
            .border(BORDER_WIDTH, TEXT_COLOR, CircleShape)
            .pointerInput(commands) {
                detectTapGestures(
                    onPress = {
                        commands.startListening()
                        try {
                            tryAwaitRelease()
                        } finally {
                            commands.stopListening()
                        }
                    },
                )
            }
            .semantics {
                contentDescription = label
                role = Role.Button
                stateDescription = listening.name
                onClick {
                    if (isListening) commands.stopListening() else commands.startListening()
                    true
                }
            },
    ) {
        Text(text = caption, color = TEXT_COLOR, fontSize = BUTTON_TEXT_SIZE, fontWeight = FontWeight.Bold)
    }
}

/** A mode button; `selected` is both visual (border) and semantic (FR-G3 "active mode shown on the panel"). */
@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) SELECTED_COLOR else BUTTON_COLOR,
            contentColor = TEXT_COLOR,
        ),
        modifier = Modifier
            .sizeIn(minWidth = TARGET_SIZE, minHeight = TARGET_SIZE)
            .border(BORDER_WIDTH, if (selected) TEXT_COLOR else Color.Transparent, ButtonDefaults.shape)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
    ) {
        Text(text = label, fontSize = BUTTON_TEXT_SIZE, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PanelButton(label: String, onClick: () -> Unit, container: Color) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = TEXT_COLOR),
        modifier = Modifier
            .sizeIn(minWidth = TARGET_SIZE, minHeight = TARGET_SIZE)
            .semantics { contentDescription = label },
    ) {
        Text(text = label, fontSize = BUTTON_TEXT_SIZE, fontWeight = FontWeight.Bold)
    }
}

// docs/stack.md → R4: XR targets ≥ 56 dp, icons ≥ 48 dp; 7:1 contrast on the dark panel.
private val TARGET_SIZE = 64.dp
private val MIC_SIZE = 96.dp
private val BUTTON_SPACING = 16.dp
private val ROW_SPACING = 16.dp
private val PANEL_PADDING = 16.dp
private val PANEL_CORNER = 24.dp
private val BORDER_WIDTH = 3.dp
private val BUTTON_TEXT_SIZE = 20.sp
private val STATE_TEXT_SIZE = 20.sp
private val PANEL_BACKGROUND = Color(0xE6000000) // near-opaque black over passthrough
private val BUTTON_COLOR = Color(0xFF1F2937) // dark slate, white text 13.6:1
private val SELECTED_COLOR = Color(0xFF1D4ED8) // blue, white text 7.9:1
private val STOP_COLOR = Color(0xFFB91C1C) // red, white text 7.5:1
private val UNAVAILABLE_COLOR = Color(0xFF4B5563)
private val TEXT_COLOR = Color.White
