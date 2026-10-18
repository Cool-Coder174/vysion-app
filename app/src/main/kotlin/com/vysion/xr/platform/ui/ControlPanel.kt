package com.vysion.xr.platform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
 *
 * Layout: two [FlowRow]s (mic + modes; stop + diagnostics + state). In the spatial orbiter each is one
 * line, as before. [compact] is the phone-width layout [FlatShell] asks for: two equal cells per line,
 * 18 sp captions that wrap at word boundaries and 56 dp targets. Checked on a 448 dp Pixel 8 Pro AVD at
 * font scale 1.0, 1.3 and 2.0: every control on screen, no caption broken inside a word.
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
    compact: Boolean = false,
) {
    val metrics = if (compact) COMPACT else SPATIAL
    Column(
        modifier = modifier
            .background(PANEL_BACKGROUND, RoundedCornerShape(PANEL_CORNER))
            .padding(metrics.padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(metrics.spacing),
    ) {
        PanelRow(metrics) { cell ->
            MicButton(commands = commands, listening = listening, metrics = metrics, modifier = cell)
            ModeButton(
                label = stringResource(R.string.a11y_mode_read),
                selected = mode == Mode.TEXT_READER,
                onClick = { commands.inject(Command.Read, CommandSource.PANEL) }, // FR-T1
                metrics = metrics,
                modifier = cell,
            )
            ModeButton(
                label = stringResource(R.string.a11y_mode_describe),
                selected = mode == Mode.AUDIO_DESCRIPTION,
                onClick = { commands.inject(Command.Describe, CommandSource.PANEL) }, // FR-D1
                metrics = metrics,
                modifier = cell,
            )
            ModeButton(
                label = stringResource(R.string.a11y_mode_navigate),
                selected = mode == Mode.NAVIGATION,
                onClick = { commands.inject(Command.GuideMe, CommandSource.PANEL) }, // FR-N1..N3
                metrics = metrics,
                modifier = cell,
            )
        }
        PanelRow(metrics) { cell ->
            PanelButton(
                label = stringResource(R.string.a11y_stop_button),
                onClick = { commands.inject(Command.Stop, CommandSource.PANEL) }, // FR-G4: same lane as "stop"
                container = STOP_COLOR,
                metrics = metrics,
                modifier = cell,
            )
            PanelButton(
                label = stringResource(R.string.a11y_diagnostics_toggle),
                onClick = onToggleDiagnostics, // FR-G5
                container = if (diagnosticsShown) SELECTED_COLOR else BUTTON_COLOR,
                metrics = metrics,
                modifier = cell,
            )
            // FR-G3: the state is informational; the enum name is not a spoken cue and not a number.
            Text(
                text = state.name,
                color = TEXT_COLOR,
                fontSize = STATE_TEXT_SIZE,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = if (compact) cell else Modifier.padding(start = metrics.spacing),
            )
        }
    }
}

/**
 * One group of controls. Unlimited items per line in the spatial layout (one line, like a `Row`); in the
 * compact layout [PanelMetrics.itemsPerLine] cells of equal width (`weight(1f)`, passed to [content] as
 * `cell`), so a long caption gets half the panel to wrap in instead of whatever a full row leaves over.
 */
@Composable
private fun PanelRow(metrics: PanelMetrics, content: @Composable (cell: Modifier) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(metrics.spacing, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing),
        itemVerticalAlignment = Alignment.CenterVertically,
        maxItemsInEachRow = metrics.itemsPerLine,
    ) {
        content(if (metrics.itemsPerLine == Int.MAX_VALUE) Modifier else Modifier.weight(1f))
    }
}

/** FR-G2: hold to talk. `startListening()` on press, `stopListening()` on release; double-tap toggles for TalkBack. */
@Composable
private fun MicButton(
    commands: CommandBus,
    listening: ListeningState,
    metrics: PanelMetrics,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier
            .sizeIn(minWidth = metrics.mic, minHeight = metrics.mic)
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
            }
            // Keeps the caption inside the round ends: a caption wider than the circle stretches it to a pill.
            .padding(horizontal = MIC_CAPTION_INSET),
    ) {
        Text(
            text = caption,
            color = TEXT_COLOR,
            fontSize = metrics.text,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/** A mode button; `selected` is both visual (border) and semantic (FR-G3 "active mode shown on the panel"). */
@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    metrics: PanelMetrics,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) SELECTED_COLOR else BUTTON_COLOR,
            contentColor = TEXT_COLOR,
        ),
        contentPadding = metrics.buttonPadding,
        modifier = modifier
            .sizeIn(minWidth = metrics.target, minHeight = metrics.target)
            .border(BORDER_WIDTH, if (selected) TEXT_COLOR else Color.Transparent, ButtonDefaults.shape)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
    ) {
        Text(text = label, fontSize = metrics.text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PanelButton(
    label: String,
    onClick: () -> Unit,
    container: Color,
    metrics: PanelMetrics,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = TEXT_COLOR),
        contentPadding = metrics.buttonPadding,
        modifier = modifier
            .sizeIn(minWidth = metrics.target, minHeight = metrics.target)
            .semantics { contentDescription = label },
    ) {
        Text(text = label, fontSize = metrics.text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** The sizes that differ between the spatial panel and the phone-width (compact) one. */
private class PanelMetrics(
    val target: Dp,
    val mic: Dp,
    val spacing: Dp,
    val padding: Dp,
    val text: TextUnit,
    val buttonPadding: PaddingValues,
    val itemsPerLine: Int,
)

// docs/stack.md → R4: XR targets ≥ 56 dp, icons ≥ 48 dp; 7:1 contrast on the dark panel.
private val SPATIAL = PanelMetrics(
    target = 64.dp,
    mic = 96.dp,
    spacing = 16.dp,
    padding = 16.dp,
    text = 20.sp,
    buttonPadding = ButtonDefaults.ContentPadding,
    itemsPerLine = Int.MAX_VALUE,
)

// Phone held in the hand (FlatShell at < 600 dp): still ≥ 48 dp targets (NFR accessibility); the user's
// font scale enlarges the 18 sp captions, and they wrap by words inside their cell.
private val COMPACT = PanelMetrics(
    target = 56.dp,
    mic = 72.dp,
    spacing = 8.dp,
    padding = 12.dp,
    text = 18.sp,
    buttonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    itemsPerLine = 2,
)

private val MIC_CAPTION_INSET = 16.dp
private val PANEL_CORNER = 24.dp
private val BORDER_WIDTH = 3.dp
private val STATE_TEXT_SIZE = 20.sp
private val PANEL_BACKGROUND = Color(0xE6000000) // near-opaque black over passthrough
private val BUTTON_COLOR = Color(0xFF1F2937) // dark slate, white text 13.6:1
private val SELECTED_COLOR = Color(0xFF1D4ED8) // blue, white text 7.9:1
private val STOP_COLOR = Color(0xFFB91C1C) // red, white text 7.5:1
private val UNAVAILABLE_COLOR = Color(0xFF4B5563)
private val TEXT_COLOR = Color.White
