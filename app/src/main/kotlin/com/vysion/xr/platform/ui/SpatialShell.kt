package com.vysion.xr.platform.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import androidx.xr.compose.platform.LocalSession
import androidx.xr.compose.platform.LocalSpatialCapabilities
import androidx.xr.compose.spatial.ExperimentalFollowingSubspaceApi
import androidx.xr.compose.spatial.FollowingSubspace
import androidx.xr.compose.spatial.Orbiter
import androidx.xr.compose.spatial.OrbiterPosition
import androidx.xr.compose.spatial.Subspace
import androidx.xr.compose.subspace.FollowBehavior
import androidx.xr.compose.subspace.FollowTarget
import androidx.xr.compose.subspace.SpatialBox
import androidx.xr.compose.subspace.SpatialBoxScope
import androidx.xr.compose.subspace.SpatialPanel
import androidx.xr.compose.subspace.SubspaceComposable
import androidx.xr.compose.subspace.TrackedDimensions
import androidx.xr.compose.subspace.draw.scale
import androidx.xr.compose.subspace.layout.SpatialRoundedCornerShape
import androidx.xr.compose.subspace.layout.SubspaceModifier
import androidx.xr.compose.subspace.layout.height
import androidx.xr.compose.subspace.layout.movable
import androidx.xr.compose.subspace.layout.offset
import androidx.xr.compose.subspace.layout.width
import androidx.xr.compose.subspace.layout.rotate
import androidx.xr.compose.unit.DpVolumeOffset
import androidx.xr.runtime.DeviceTrackingMode
import androidx.xr.runtime.Session
import androidx.xr.scenecore.SpatialCapability
import androidx.xr.scenecore.Space as SceneSpace
import androidx.xr.scenecore.scene
import com.vysion.xr.AppGraph
import com.vysion.xr.BuildConfig
import com.vysion.xr.R
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.Mode
import com.vysion.xr.platform.WorldLabel
import com.vysion.xr.platform.ReadingFrame
import com.vysion.xr.platform.ui.components.PillButton
import com.vysion.xr.platform.ui.components.PillStyle
import com.vysion.xr.platform.ui.components.glass
import com.vysion.xr.platform.ui.theme.Accent
import com.vysion.xr.platform.ui.theme.Ink
import com.vysion.xr.platform.ui.theme.LocalReduceMotion
import com.vysion.xr.platform.ui.theme.Radius
import com.vysion.xr.platform.ui.theme.Space
import com.vysion.xr.platform.ui.theme.SpatialType
import com.vysion.xr.platform.ui.theme.Target
import com.vysion.xr.platform.ui.theme.VysionIcons
import com.vysion.xr.platform.ui.theme.rememberReduceMotion
import com.vysion.xr.platform.ui.theme.vysionSpring
import com.vysion.xr.platform.ui.theme.vysionSpring
import kotlin.math.abs
import java.util.function.Consumer
import kotlin.math.tan
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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

    // Augmented reality: the room is always visible, from the first onboarding card on.
    KeepPassthroughOn(session)

    CompositionLocalProvider(LocalReduceMotion provides rememberReduceMotion()) {
        if (!onboarding.done) {
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
    val gesturePage by graph.gestureCommands.page.collectAsStateWithLifecycle()
    val preview = LocalDesignPreview.current
    var diagnosticsShown by rememberSaveable { mutableStateOf(preview == PreviewScreen.DIAGNOSTICS) }
    val obstacle = shell.obstacle

    // Headset 10 dimmed the room while the obstacle card showed. Not any more: SceneCore turns passthrough OFF for a
    // semi-transparent value when the app has no environment of its own, so the room went dark exactly when something
    // was close. The card's hazard styling carries the alert; the wearer keeps seeing the obstacle.

    val doorWord = stringResource(R.string.overlay_door)
    val lastSeenWord = stringResource(R.string.overlay_last_seen)
    var statusHeight by remember { mutableStateOf(STATUS_MIN_HEIGHT) }
    WorldLabelLayer(graph)
    // The Read page aims with the head: the crosshair marks what "read" will read.
    ReadingCrosshair(graph, session, visible = gesturePage == Mode.TEXT_READER && obstacle == null)
    // The HUD stays below the line of sight wherever the wearer looks, so the middle of the view stays the room.
    FollowingWearer(session, height = statusHeight) {
        // The status window stays centered in front of the wearer; the diagnostics window and the outline layer open
        // beside it without moving it (a SpatialRow centered the whole row, so opening diagnostics pushed the window
        // left). The rail and toolbar float just outside its edges, never over its text.
        SpatialBox {
            AutoHeightPanel(
                width = STATUS_WIDTH,
                min = STATUS_MIN_HEIGHT,
                max = STATUS_MAX_HEIGHT,
                // Tilted back so a window below eye level faces the eyes instead of the floor.
                modifier = SubspaceModifier.rotate(pitch = -HUD_TILT_DEG),
                onHeight = { statusHeight = it },
            ) {
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
                    StatusWindow(shell, SpatialType, Modifier.fillMaxWidth(), compact = true)
                }
                Orbiter(OrbiterPosition.CenterStart(OrbiterPosition.EdgeAlignment.Outside, DpVolumeOffset(-ORBITER_GAP, 0.dp, 0.dp))) {
                    ModeRail(
                        mode = shell.mode,
                        page = gesturePage,
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
 * Augmented: the main window is a heads-up display. It travels with the wearer (soft follow, turning and nodding, never
 * roll), [HUD_DISTANCE_M] away and with its top edge [HUD_TOP_BELOW_GAZE_DEG] below the line of sight whatever its
 * [height], so the middle of the view (and the reading crosshair) always shows the room. The first worn build
 * followed turning only and sat 0.5 m away: looking down at a sign brought the window over the crosshair. Head-locked following needs `DeviceTrackingMode.SPATIAL` (configured by the perception session);
 * without it the window stays where it opened, as before, instead of not appearing at all.
 */
@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
private fun FollowingWearer(session: Session?, height: Dp, content: @Composable @SubspaceComposable SpatialBoxScope.() -> Unit) {
    val tracked = rememberDeviceTracked(session)
    if (tracked && session != null) {
        val meters = rememberMetersToDp(session)
        val top = meters(HUD_DISTANCE_M * tan(Math.toRadians(HUD_TOP_BELOW_GAZE_DEG.toDouble())).toFloat())
        FollowingSubspace(
            target = FollowTarget.ArDevice(session),
            behavior = FollowBehavior.Soft(FOLLOW_DURATION_MS),
            modifier = SubspaceModifier.offset(y = -(top + height / 2), z = -(meters(HUD_DISTANCE_M) - meters.headLockDepth())),
            // Tilting the head down to read a table or a low sign keeps the HUD below the crosshair, never over it.
            dimensions = TrackedDimensions(
                isTranslationXTracked = true,
                isTranslationYTracked = true,
                isTranslationZTracked = true,
                isRotationXTracked = true,
                isRotationYTracked = true,
                isRotationZTracked = false,
            ),
            content = content,
        )
    } else {
        Subspace(content = content)
    }
}

/**
 * Meters in front of the wearer → dp inside a `FollowingSubspace(FollowTarget.ArDevice)`. That subspace is scaled by
 * the system's recommended scale (the key entity's) and already puts content [HEAD_LOCK_DEPTH_M] ahead, so a distance
 * D is reached with `offset(z = -(meters(D) - meters.headLockDepth()))`, and a size S at any distance with `meters(S)`.
 */
private class MetersToDp(private val dpPerMeter: Float, private val scale: Float) {
    operator fun invoke(m: Float): Dp = (m / scale * dpPerMeter).dp
    fun headLockDepth(): Dp = invoke(HEAD_LOCK_DEPTH_M * scale)
}

@Composable
private fun rememberMetersToDp(session: Session): MetersToDp {
    val density = LocalDensity.current.density
    return remember(session, density) {
        val scale = runCatching { session.scene.keyEntity?.getScale(relativeTo = SceneSpace.ACTIVITY) }.getOrNull()
            ?.takeIf { it > 0f } ?: 1f
        MetersToDp(session.scene.virtualPixelDensity.pixelsPerMeter / density, scale)
    }
}

/**
 * Whether head tracking is on (`DeviceTrackingMode.SPATIAL`), which head-locked content needs. The perception session
 * switches it on after the first frame, and `Session.config` is not snapshot state, so it is polled until on;
 * a composable that read it once would stay off for good.
 */
@Composable
private fun rememberDeviceTracked(session: Session?): Boolean {
    fun Session?.isTracked() = this != null && config.deviceTracking != DeviceTrackingMode.DISABLED
    val tracked by produceState(initialValue = session.isTracked(), session) {
        while (!value) {
            delay(TRACKING_POLL_MS)
            value = session.isTracked()
        }
    }
    return tracked
}

/**
 * Augmented Read: a crosshair locked to the head, in the middle of the view, framing the [ReadingFrame] the camera
 * reads, so the wearer points their head at the sign they want read. It sits at the depth of the surface under it
 * (R3 depth along the gaze, a few times a second) so the eyes do not have to refocus between the frame and the text;
 * without depth it keeps its last distance. Its size follows the distance, so it always covers the same 30° × 20°.
 *
 * `FollowingSubspace` with `FollowTarget.ArDevice` places content [HEAD_LOCK_DEPTH_M] in front of the head and scales
 * the whole subspace by the system's recommended scale (the key entity's scale), so both are divided out here.
 * `FollowBehavior.Tight` is not allowed with `ArDevice`; the minimum soft follow (100 ms) is the closest to locked.
 */
@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
private fun ReadingCrosshair(graph: AppGraph, session: Session?, visible: Boolean) {
    val tracked = rememberDeviceTracked(session)
    if (BuildConfig.DEBUG) {
        LaunchedEffect(visible, tracked) { android.util.Log.d("vysion.crosshair", "visible=$visible tracked=$tracked") }
    }
    if (!visible || session == null || !tracked) return
    val sampled by graph.gazeDistanceM.collectAsStateWithLifecycle(initialValue = null)
    var target by remember { mutableFloatStateOf(CROSSHAIR_DEFAULT_M) }
    LaunchedEffect(sampled) { sampled?.let { target = it.coerceIn(CROSSHAIR_MIN_M, CROSSHAIR_MAX_M) } }
    val reduceMotion = LocalReduceMotion.current
    val distance by animateFloatAsState(
        targetValue = target,
        animationSpec = if (reduceMotion) snap() else vysionSpring(response = 0.3f),
        label = "crosshair distance",
    )
    val meters = rememberMetersToDp(session)
    FollowingSubspace(
        target = FollowTarget.ArDevice(session),
        behavior = FollowBehavior.Soft(FollowBehavior.MIN_SOFT_DURATION_MS),
        modifier = SubspaceModifier.offset(z = -(meters(distance) - meters.headLockDepth())),
    ) {
        SpatialPanel(SubspaceModifier.width(meters(ReadingFrame.widthAt(distance))).height(meters(ReadingFrame.heightAt(distance)))) {
            CrosshairFrame()
        }
    }
}

/** Four corner brackets and a center mark; strokes are fractions of the frame, so they keep their angular weight. */
@Composable
private fun CrosshairFrame() {
    val label = stringResource(R.string.a11y_crosshair)
    Canvas(Modifier.fillMaxSize().semantics { contentDescription = label }) {
        val stroke = size.minDimension * CROSSHAIR_STROKE
        val arm = size.minDimension * CROSSHAIR_ARM
        val inset = stroke * 1.5f
        val l = inset
        val t = inset
        val r = size.width - inset
        val b = size.height - inset
        val corners = listOf(
            Offset(l, t) to Offset(1f, 1f),
            Offset(r, t) to Offset(-1f, 1f),
            Offset(l, b) to Offset(1f, -1f),
            Offset(r, b) to Offset(-1f, -1f),
        )
        val c = center
        val tick = arm * 0.45f
        // A dark halo under every stroke keeps the frame visible over a white wall and a dark doorway alike.
        for ((color, width) in listOf(CROSSHAIR_HALO to stroke * 2.2f, Accent.yellow to stroke)) {
            for ((corner, dir) in corners) {
                drawLine(color, corner, corner + Offset(arm * dir.x, 0f), width, StrokeCap.Round)
                drawLine(color, corner, corner + Offset(0f, arm * dir.y), width, StrokeCap.Round)
            }
            drawLine(color, c - Offset(tick, 0f), c + Offset(tick, 0f), width, StrokeCap.Round)
            drawLine(color, c - Offset(0f, tick), c + Offset(0f, tick), width, StrokeCap.Round)
        }
    }
}

/**
 * Augmented: what the controller pinned in the room (`ModeController.worldLabel`), drawn AT its anchor: a large door
 * marker where the door is, or the text just read beside the sign. Linked to the anchor in the scene graph
 * (`FollowTarget.Anchor` + `FollowBehavior.Tight`), so it stays put as the wearer moves.
 */
@OptIn(ExperimentalFollowingSubspaceApi::class)
@Composable
private fun WorldLabelLayer(graph: AppGraph) {
    val label by graph.modeController.worldLabel.collectAsStateWithLifecycle()
    val current = label ?: return
    val space = remember(current.anchor) { graph.anchorSpace(current.anchor) } ?: return
    // Constant apparent size: a card on a sign 2.5 m away was too small to read, and one on a laptop 0.6 m away
    // filled the view (worn captures, 2026-10-18), so it scales with distance both ways.
    val distanceFlow = remember(current.anchor) { graph.anchorDistanceM(current.anchor) }
    val distance by distanceFlow.collectAsStateWithLifecycle(initialValue = null)
    val reduceMotion = LocalReduceMotion.current
    val grow by animateFloatAsState(
        targetValue = ((distance ?: WORLD_LABEL_REF_M) / WORLD_LABEL_REF_M).coerceIn(WORLD_LABEL_MIN_SCALE, WORLD_LABEL_MAX_SCALE),
        animationSpec = if (reduceMotion) snap() else vysionSpring(response = 0.4f),
        label = "world label scale",
    )
    var cardHeight by remember(current.anchor) { mutableStateOf(READ_CARD_MIN_HEIGHT) }
    FollowingSubspace(target = FollowTarget.Anchor(space), behavior = FollowBehavior.Tight) {
        when (current.kind) {
            WorldLabel.Kind.DOOR -> SpatialPanel(
                SubspaceModifier.scale(grow).width(DOOR_LABEL_WIDTH).height(DOOR_LABEL_HEIGHT),
                shape = SpatialRoundedCornerShape(CornerSize(Radius.window)),
            ) { DoorMarker() }
            // Above what was read, never over it: the anchor is the crosshair's center, so the card's bottom edge goes
            // to the crosshair's top edge.
            WorldLabel.Kind.TEXT -> AutoHeightPanel(
                width = READ_CARD_WIDTH,
                min = READ_CARD_MIN_HEIGHT,
                max = 560.dp,
                modifier = SubspaceModifier.offset(y = (cardHeight / 2 + READ_CARD_LIFT) * grow).scale(grow),
                onHeight = { cardHeight = it },
            ) {
                ReadCard(current.text)
            }
        }
    }
}

@Composable
private fun DoorMarker() {
    val label = stringResource(R.string.world_door)
    Row(
        horizontalArrangement = Arrangement.spacedBy(Space.l, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxSize()
            .glass(RoundedCornerShape(Radius.window), edge = Accent.cyan)
            .semantics { contentDescription = label },
    ) {
        Icon(VysionIcons.Door, contentDescription = null, tint = Accent.cyan, modifier = Modifier.size(72.dp))
        Text(label, color = Ink.primary, style = SpatialType.display)
    }
}

@Composable
private fun ReadCard(text: String) {
    Text(
        text,
        color = Accent.yellow,
        style = SpatialType.answer,
        modifier = Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(Radius.window), edge = Accent.yellow)
            .padding(horizontal = Space.xl, vertical = Space.l),
    )
}

/**
 * A spatial panel whose height follows its content (the designs' window grows with the answer and shrinks
 * back for Ready). The content is measured unbounded and the panel resized within [min]..[max].
 */
@Composable
private fun AutoHeightPanel(
    width: Dp,
    min: Dp,
    max: Dp,
    modifier: SubspaceModifier = SubspaceModifier,
    onHeight: (Dp) -> Unit = {},
    content: @Composable () -> Unit,
) {
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
                    if (abs((measured - height).value) > 1f) {
                        height = measured
                        onHeight(measured)
                    }
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

/**
 * Augmented reality needs the room: full passthrough, enforced rather than requested once. SceneCore keeps it only as
 * the app's *preference* and the system can change what is shown (sleep and wake, Home and back, a system toggle), so
 * the preference is re-applied on every resume and whenever the visible opacity drops below full, and no app
 * environment is ever requested (with one, passthrough blends with it). Each drop is logged under `vysion` so a dark
 * room can be traced. If the system keeps refusing (a user setting), the listener fires once per change, not in a loop.
 */
@Composable
private fun KeepPassthroughOn(session: Session?) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(session, lifecycle) {
        if (session == null) return@DisposableEffect onDispose { }
        val environment = session.scene.spatialEnvironment
        val onChanged = Consumer<Float> { visible ->
            if (visible < FULL_PASSTHROUGH) {
                Log.w(PASSTHROUGH_TAG, "passthrough dropped to $visible; asking for full passthrough again")
                setPassthrough(session)
            }
        }
        val onResume = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) setPassthrough(session)
        }
        runCatching { environment.addPassthroughOpacityChangedListener(onChanged) }
        lifecycle.addObserver(onResume)
        setPassthrough(session)
        onDispose {
            runCatching { environment.removePassthroughOpacityChangedListener(onChanged) }
            lifecycle.removeObserver(onResume)
        }
    }
}

/** Full passthrough and no app environment, gated on the capability (A-2: passthrough works with our panels shown). */
private fun setPassthrough(session: Session) {
    val scene = session.scene
    if (!scene.spatialCapabilities.contains(SpatialCapability.PASSTHROUGH_CONTROL)) {
        Log.w(PASSTHROUGH_TAG, "no PASSTHROUGH_CONTROL capability; the system decides what is shown")
        return
    }
    runCatching {
        val environment = scene.spatialEnvironment
        if (environment.preferredSpatialEnvironment != null) environment.preferredSpatialEnvironment = null
        environment.preferredPassthroughOpacity = FULL_PASSTHROUGH
        Log.i(PASSTHROUGH_TAG, "passthrough requested: full (visible now ${environment.currentPassthroughOpacity})")
    }.onFailure { Log.w(PASSTHROUGH_TAG, "passthrough request failed: ${it.message}") }
}

private const val PASSTHROUGH_TAG = "vysion"

/** A heads-up display, not a monitor: about two thirds of Android XR's default panel, so the room stays the view. */
private val STATUS_WIDTH = 820.dp
/** Tall enough for the three-item rail orbiter, which is clipped to the window's height. */
private val STATUS_MIN_HEIGHT = 330.dp
private val STATUS_MAX_HEIGHT = 720.dp

/** Space between the status window and its rail / toolbar orbiters. */
private val ORBITER_GAP = 20.dp

/** Space between side-by-side windows. */
private val SIDE_GAP = 48.dp

/** Soft follow: the HUD catches up with the head in 0.4 s (0.6 s felt like it dragged behind on a quick turn). */
private const val FOLLOW_DURATION_MS = 400

/** The HUD's distance: farther than arm's length (0.5 m was too close to read comfortably), nearer than most signs. */
private const val HUD_DISTANCE_M = 1.25f

/** The HUD's top edge, below the line of sight: clear of the reading crosshair, which reaches 10° down. */
private const val HUD_TOP_BELOW_GAZE_DEG = 13f

/** Backward tilt of the HUD so it faces the eyes from below the line of sight (degrees, top edge away). */
private const val HUD_TILT_DEG = 16f

/** World labels keep at least the HUD's text size: past this distance they grow with it, up to [WORLD_LABEL_MAX_SCALE]. */
private const val WORLD_LABEL_REF_M = HUD_DISTANCE_M
private const val WORLD_LABEL_MIN_SCALE = 0.4f
private const val WORLD_LABEL_MAX_SCALE = 3f

/**
 * Lift of a read card above its anchor before scaling: at 1 dp ≈ 0.87 mm (measured: 1727.8 px/m at density 1.5) and a
 * scale of distance / [WORLD_LABEL_REF_M], 250 dp is the reading frame's top edge (10° up) at any distance.
 */
private val READ_CARD_LIFT = 250.dp
private val READ_CARD_MIN_HEIGHT = 160.dp

/** How often the layout checks whether head tracking has come on. */
private const val TRACKING_POLL_MS = 250L

/** `FollowTarget.ArDevice`'s built-in distance in front of the head (Compose for XR 1.0.0-beta01 `DEFAULT_OFFSET`). */
private const val HEAD_LOCK_DEPTH_M = 0.5f

/** The crosshair before any depth arrives, and the range it may move in (closer than arm's length is never text). */
private const val CROSSHAIR_DEFAULT_M = 1.5f
private const val CROSSHAIR_MIN_M = 0.7f
private const val CROSSHAIR_MAX_M = 4f
private const val CROSSHAIR_STROKE = 0.018f
private const val CROSSHAIR_ARM = 0.16f
private val CROSSHAIR_HALO = Color.Black.copy(alpha = 0.55f)

private val DOOR_LABEL_WIDTH = 520.dp
private val DOOR_LABEL_HEIGHT = 180.dp
private val READ_CARD_WIDTH = 760.dp
private val DIAGNOSTICS_WIDTH = 720.dp
private val OVERLAY_WIDTH = 1100.dp
private val OVERLAY_HEIGHT = 824.dp
/** Close to Android XR's default panel (~1024 x 720 dp): the card fills it instead of floating in a field of aurora. */
private val ONBOARDING_WIDTH = 1240.dp
private val ONBOARDING_HEIGHT = 860.dp
private const val FULL_PASSTHROUGH = 1.0f
