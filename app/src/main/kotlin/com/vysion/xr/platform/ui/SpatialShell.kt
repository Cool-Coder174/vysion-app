package com.vysion.xr.platform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.unit.DpVolumeOffset
import androidx.xr.runtime.Session
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.scene
import com.vysion.xr.AppGraph
import com.vysion.xr.BuildConfig
import com.vysion.xr.R
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandSource
import com.vysion.xr.platform.ui.components.PillButton
import com.vysion.xr.platform.ui.components.PillStyle
import com.vysion.xr.platform.ui.components.glass
import com.vysion.xr.platform.ui.theme.Ink
import com.vysion.xr.platform.ui.theme.LocalReduceMotion
import com.vysion.xr.platform.ui.theme.Radius
import com.vysion.xr.platform.ui.theme.Space
import com.vysion.xr.platform.ui.theme.SpatialType
import com.vysion.xr.platform.ui.theme.Target
import com.vysion.xr.platform.ui.theme.rememberReduceMotion
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * The Galaxy XR layout (Michelle Bui's designs, Headset 1-13): passthrough Full Space with one glass status
 * window, the mode rail as an orbiter on its left edge, the contextual toolbar as an orbiter straddling its
 * bottom edge, the outline overlay beside it, and the diagnostics window on demand. First launch shows the
 * seven onboarding cards on the aurora instead (pages 2-8). Owner: R1.
 *
 * APIs (docs/stack.md → R1, re-checked with `javap` against Compose for XR 1.0.0-beta01): `Subspace`,
 * `SpatialRow`, `SpatialPanel(modifier, shape)`, `SpatialRoundedCornerShape(CornerSize)`,
 * `Orbiter(OrbiterPosition.CenterStart(EdgeAlignment.Outside))`, `OrbiterPosition.BottomCenter(EdgeAlignment.Center)`,
 * `SubspaceModifier.width/height/movable`, `LocalSession`, `LocalSpatialCapabilities`,
 * `spatialEnvironment.preferredPassthroughOpacity` (A-2 answered yes on the device, 2026-10-17).
 *
 * When spatial UI is not enabled the shell requests Full Space once (stack.md Q4) and shows [FlatShell].
 * [diagnostics] is the FR-G5 slot `MainActivity` fills with R5's overlay (ADR-0005).
 */
@Composable
fun SpatialShell(graph: AppGraph, diagnostics: @Composable () -> Unit, onboarding: OnboardingHost) {
    val capabilities = LocalSpatialCapabilities.current
    val session = LocalSession.current

    // One Session per activity: hand Compose's to R3 so perception never calls Session.create twice.
    LaunchedEffect(session) { graph.perception?.offerComposeSession(session) }
    // Q4 fallback: the manifest property should already put us in Full Space; ask once if it did not.
    LaunchedEffect(session, capabilities.isSpatialUiEnabled) {
        if (session != null && !capabilities.isSpatialUiEnabled) session.scene.requestFullSpace()
    }

    if (!capabilities.isSpatialUiEnabled) {
        FlatShell(graph, diagnostics, onboarding)
        return
    }

    CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) {
        if (!onboarding.done) {
            LaunchedEffect(session) { session?.let { setPassthrough(it, FULL_PASSTHROUGH) } }
            Subspace {
                SpatialPanel(
                    SubspaceModifier.width(ONBOARDING_WIDTH).height(ONBOARDING_HEIGHT).movable(),
                    shape = SpatialRoundedCornerShape(CornerSize(Radius.window)),
                ) {
                    OnboardingFlow(graph, onboarding, SpatialType, SpatialOnboarding)
                }
            }
        } else {
            MainLayout(graph, session, diagnostics)
        }
    }
}

@Composable
private fun MainLayout(graph: AppGraph, session: Session?, diagnostics: @Composable () -> Unit) {
    val shell = rememberShellState(graph)
    val preview = LocalDesignPreview.current
    var diagnosticsShown by rememberSaveable { mutableStateOf(preview == PreviewScreen.DIAGNOSTICS) }
    val obstacle = shell.obstacle

    // Headset 10, "the room dims": lower passthrough while the obstacle card shows, restore after (A-2).
    LaunchedEffect(session, obstacle != null) {
        session?.let { setPassthrough(it, if (obstacle != null) DIMMED_PASSTHROUGH else FULL_PASSTHROUGH) }
    }

    val doorWord = stringResource(R.string.overlay_door)
    val lastSeenWord = stringResource(R.string.overlay_last_seen)
    Subspace {
        // The status window stays centered in front of the wearer; the diagnostics window and the outline layer open
        // beside it without moving it (a SpatialRow centered the whole row, so opening diagnostics pushed the window
        // left). The rail and toolbar float just outside its edges, never over its text.
        SpatialBox {
            AutoHeightPanel(width = STATUS_WIDTH, min = 300.dp, max = 980.dp) {
                if (obstacle != null) {
                    ObstacleCard(
                        alert = obstacle,
                        onOk = shell::dismissHazard,
                        onRepeat = { graph.commandBus.inject(Command.Repeat, CommandSource.PANEL) },
                        type = SpatialType,
                        buttonHeight = Target.spatial,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    StatusWindow(shell, SpatialType, Modifier.fillMaxWidth())
                }
                Orbiter(OrbiterPosition.CenterStart(OrbiterPosition.EdgeAlignment.Outside, DpVolumeOffset(-ORBITER_GAP, 0.dp, 0.dp))) {
                    ModeRail(
                        mode = shell.mode,
                        commands = graph.commandBus,
                        itemSize = 56.dp,
                        labelStyle = SpatialType.railLabel,
                    )
                }
                // Above the window's top-right corner, clear of the answer text.
                Orbiter(
                    OrbiterPosition.TopEnd(
                        OrbiterPosition.EdgeAlignment.Inside,
                        OrbiterPosition.EdgeAlignment.Outside,
                        DpVolumeOffset(12.dp, 12.dp, 0.dp),
                    ),
                ) {
                    DiagnosticsToggle(
                        more = RailMore(diagnosticsShown) { diagnosticsShown = !diagnosticsShown },
                        height = 56.dp,
                        labelStyle = SpatialType.railLabel,
                    )
                }
                if (obstacle == null) {
                    Orbiter(OrbiterPosition.BottomCenter(OrbiterPosition.EdgeAlignment.Outside, DpVolumeOffset(0.dp, -ORBITER_GAP, 0.dp))) {
                        StatusToolbar(shell, graph.commandBus, SpatialType, Target.spatial)
                    }
                }
            }
            // FR-G5 (designs: Headset 13, the second window): R5's numbers, plus the R3 test tool in debug builds.
            if (diagnosticsShown) {
                AutoHeightPanel(
                    width = DIAGNOSTICS_WIDTH,
                    min = 240.dp,
                    max = 900.dp,
                    modifier = SubspaceModifier.offset(x = (STATUS_WIDTH + DIAGNOSTICS_WIDTH) / 2 + SIDE_GAP),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        diagnostics()
                        if (BuildConfig.DEBUG) MarkerTestTool(graph)
                    }
                }
                // FR-N2 / FR-N3 outlines. A flat panel cannot line up with the objects it outlines, so it is a
                // developer view that opens with diagnostics, never part of the wearer's main view.
                SpatialPanel(
                    SubspaceModifier.width(OVERLAY_WIDTH).height(OVERLAY_HEIGHT)
                        .offset(x = -((STATUS_WIDTH + OVERLAY_WIDTH) / 2 + SIDE_GAP)),
                ) {
                    Box(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
                        shell.overlay?.let {
                            OverlayCanvas(
                                renderer = it,
                                modifier = Modifier.fillMaxSize(),
                                style = OverlayStyle.forScreen(shell.screen, doorWord, lastSeenWord),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A spatial panel whose height follows its content (the designs' window grows with the answer and shrinks
 * back for Ready). The content is measured unbounded and the panel resized within [min]..[max].
 */
@Composable
private fun AutoHeightPanel(width: Dp, min: Dp, max: Dp, modifier: SubspaceModifier = SubspaceModifier, content: @Composable () -> Unit) {
    var height by remember { mutableStateOf(min) }
    val density = LocalDensity.current
    SpatialPanel(
        modifier.width(width).height(height).movable(),
        shape = SpatialRoundedCornerShape(CornerSize(Radius.window)),
    ) {
        Box(
            contentAlignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .onSizeChanged { size ->
                    val measured = with(density) { size.height.toDp() }.coerceIn(min, max)
                    if (abs((measured - height).value) > 1f) height = measured
                },
        ) { content() }
    }
}

/**
 * TEST ONLY, debug builds (R3, Alexia): place an anchor 2 m ahead and start guidance to it, to check anchors
 * and cues without the VLM. Lives in the diagnostics window so the demo view stays clean; remove before the
 * demo build if it is not needed.
 */
@Composable
private fun MarkerTestTool(graph: AppGraph) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Place marker ahead") }
    val liveFlow = remember { graph.perception?.debugLive ?: kotlinx.coroutines.flow.MutableStateFlow(null) }
    val live by liveFlow.collectAsState()
    val pingFlow = remember { graph.perception?.debugPing ?: kotlinx.coroutines.flow.MutableStateFlow("ping: no XR") }
    val ping by pingFlow.collectAsState()
    Column(
        verticalArrangement = Arrangement.spacedBy(Space.s),
        modifier = Modifier.glass(RoundedCornerShape(Radius.tile)).padding(Space.m),
    ) {
        Text("Test only (debug build)", color = Ink.tertiary, style = SpatialType.footnote)
        PillButton(
            label = live ?: status,
            onClick = {
                scope.launch {
                    status = graph.perception?.debugPlaceAnchorAhead() ?: "no XR session"
                    graph.perception?.debugLastAnchor?.let { id ->
                        if (!graph.navigationEngine.isRunning.value) graph.navigationEngine.start()
                        graph.navigationEngine.guideTo(id)
                    }
                }
            },
            style = PillStyle.SECONDARY,
            textStyle = SpatialType.label,
            height = Target.spatial,
        )
        Text(ping, color = Ink.tertiary, style = SpatialType.footnote)
    }
}

/** Passthrough preference, gated on the capability (A-2: full passthrough was on with our panels shown). */
private fun setPassthrough(session: Session, opacity: Float) {
    val scene = session.scene
    if (scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)) {
        scene.spatialEnvironment.preferredPassthroughOpacity = opacity
    }
}

private val STATUS_WIDTH = 1040.dp

/** Space between the status window and its rail / toolbar orbiters. */
private val ORBITER_GAP = 20.dp

/** Space between side-by-side windows. */
private val SIDE_GAP = 48.dp
private val DIAGNOSTICS_WIDTH = 720.dp
private val OVERLAY_WIDTH = 1100.dp
private val OVERLAY_HEIGHT = 824.dp
/** Close to Android XR's default panel (~1024 x 720 dp): the card fills it instead of floating in a field of aurora. */
private val ONBOARDING_WIDTH = 1240.dp
private val ONBOARDING_HEIGHT = 860.dp
private const val FULL_PASSTHROUGH = 1.0f
private const val DIMMED_PASSTHROUGH = 0.55f
