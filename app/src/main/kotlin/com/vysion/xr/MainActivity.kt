package com.vysion.xr

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandSource
import com.vysion.xr.platform.ui.LocalDesignPreview
import com.vysion.xr.platform.ui.LocalOnboardingStart
import com.vysion.xr.platform.ui.OnboardingHost
import com.vysion.xr.platform.ui.PreviewScreen
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import com.vysion.xr.diagnostics.DiagnosticsOverlay
import com.vysion.xr.platform.Permissions
import com.vysion.xr.platform.ui.FlatShell
import com.vysion.xr.platform.ui.SpatialShell
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The single activity (FR-G1: launch into passthrough Full Space with one floating control panel
 * and a spoken welcome; SRS §8 R1 "App shell … permissions", Sat 11:00). Owner: R1.
 *
 * - Reads the one [AppGraph] from [VysionApplication] (ADR-0008) and shows [SpatialShell] on the
 *   headset or [FlatShell] on the S25 Ultra ([com.vysion.xr.platform.DeviceProfile.isXr], ADR-0006, A-12).
 * - Full Space itself comes from the manifest `PROPERTY_XR_ACTIVITY_START_MODE =
 *   XR_ACTIVITY_START_MODE_FULL_SPACE_MANAGED` (docs/stack.md §2.7, VERIFIED); the shell applies
 *   the A-2 / Q4 fallbacks (`preferredPassthroughOpacity`, `requestFullSpace`).
 * - Requests `CAMERA`, `RECORD_AUDIO` and the four XR permissions through
 *   [Permissions] with a spoken explanation (`Cues.permissionExplanation`, R4, reached through the
 *   controller's phrasebook; no string lives here, INV-4). On grant: [AppGraph.start] and, on the
 *   headset, `AppGraph.startPerception(this)` (Session.create needs the resumed Activity, stack.md D7).
 *   `onStop`: [AppGraph.stop] (reversible: taking the headset off and on restarts the modules).
 *   `onDestroy` while finishing: [AppGraph.release] (recognizer, TTS, models).
 * - Logs the two NFR evidence lines: `vysion.latency` (command → action) and `vysion.reliability`
 *   (failures the controller recovered from). No frame content is ever logged (ADR-0003).
 * */
class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph
    private var graphStarted = false
    private var permissionsRequested = false

    /** First-launch onboarding (designs pages 2-8) is showing until this turns true (ADR-0009 flag). */
    private var onboardingDone by mutableStateOf(true)

    /** Set while the onboarding privacy card's permission dialogs are open; called when they close. */
    private var onboardingPermissionsClosed: (() -> Unit)? = null

    /** Registered before STARTED, as the contract requires (docs/stack.md → R1 "Runtime permission request"). */
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        Permissions.register(this) { grants -> onPermissionResult(grants) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as VysionApplication).graph
        // A-12 evidence line: which layout and which modules this device got.
        Log.i(TAG, "DeviceProfile: ${graph.deviceProfile.displayName}; useFakes=${BuildConfig.USE_FAKES}")

        // Debug builds only: open a designs screen or onboarding page with synthetic content (DesignPreview.kt).
        val preview = if (BuildConfig.DEBUG) PreviewScreen.parse(intent.getStringExtra(EXTRA_PREVIEW)) else null
        val onboardingPage = if (BuildConfig.DEBUG) intent.getIntExtra(EXTRA_ONBOARDING_PAGE, 0) else 0
        onboardingDone = if (onboardingPage > 0) false else graph.onboarding.done || preview != null

        setContent {
            // FR-G5: R5's overlay enters `platform` as a slot, so platform never imports diagnostics (ADR-0005).
            val diagnostics: @Composable () -> Unit = { DiagnosticsOverlay(source = graph.diagnostics) }
            val host = OnboardingHost(
                done = onboardingDone,
                requestPermissions = ::requestPermissionsForOnboarding,
                finish = ::finishOnboarding,
            )
            CompositionLocalProvider(
                LocalDesignPreview provides preview,
                LocalOnboardingStart provides (onboardingPage - 1).coerceAtLeast(0),
            ) {
                // ADR-0006: Full Space on the headset, the flat layout on the S25 Ultra.
                if (graph.deviceProfile.isXr) SpatialShell(graph, diagnostics, host) else FlatShell(graph, diagnostics, host)
            }
        }

        // NFR latency: command recognized → action started (≤ 1.5 s), monotonic (H-21), tag vysion.latency.
        lifecycleScope.launch {
            graph.modeController.commandLatencyNanos.filterNotNull().collect { nanos ->
                Log.i(LATENCY_TAG, "command->action ${nanos / NANOS_PER_MS} ms")
            }
        }
        // NFR reliability: every failure the controller spoke and recovered from, tag vysion.reliability.
        lifecycleScope.launch {
            graph.modeController.lastError.filterNotNull().collect { error ->
                Log.w(RELIABILITY_TAG, "recovered from ${error::class.java.simpleName}: ${error.message}")
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (!onboardingDone) return // onboarding asks for permissions on its privacy card, then starts the app
        val missing = Permissions.missing(this, graph.deviceProfile.isXr)
        when {
            Permissions.canStart(this) && missing.isEmpty() -> startGraph()
            // XR extras can be denied without blocking; perception is retried in onPermissionResult.
            Permissions.canStart(this) -> startGraph().also { requestOnce(missing) }
            else -> requestOnce(missing)
        }
    }

    override fun onStop() {
        if (graphStarted) {
            graph.stop()
            graphStarted = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) graph.release() // once: recognizer, TTS and models; a config change keeps them
        super.onDestroy()
    }

    /** FR-G1: speak why before the system dialogs, then ask once per activity lifetime (no denial loop). */
    private fun requestOnce(missing: List<String>) {
        if (permissionsRequested || missing.isEmpty()) return
        permissionsRequested = true
        // Cues.permissionExplanation (R4, voice/Cues.kt) via ModePhrasebook; see docs/modules/r1-xr-platform.md.
        graph.modeController.speakPermissionExplanation()
        permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionResult(grants: Map<String, Boolean>) {
        // plan.md Security "Detection": permission denials go to logcat under vysion.security.
        grants.filterValues { !it }.keys.forEach { Log.w(SECURITY_TAG, "permission denied: $it") }
        onboardingPermissionsClosed?.let { closed ->
            // Onboarding continues either way; a refusal is spoken again when the app starts.
            onboardingPermissionsClosed = null
            closed()
            return
        }
        if (Permissions.canStart(grants, this)) {
            startGraph()
        } else {
            // Cues.permissionDenied (R4): camera or microphone refused; the panel stays, nothing can run.
            graph.modeController.speakPermissionDenied()
        }
    }

    /** The privacy card's "Allow camera and mic": Android's dialogs, then [onClosed] (designs page 3). */
    private fun requestPermissionsForOnboarding(onClosed: () -> Unit) {
        val missing = Permissions.missing(this, graph.deviceProfile.isXr)
        if (missing.isEmpty()) {
            onClosed()
            return
        }
        permissionsRequested = true
        onboardingPermissionsClosed = onClosed
        permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * The last card's Start (or a mode card): save the flag (ADR-0009), start the app without repeating the
     * welcome the last card already spoke, and run the picked mode.
     */
    private fun finishOnboarding(command: Command?) {
        graph.onboarding.done = true
        onboardingDone = true
        if (Permissions.canStart(this)) {
            startGraph(speakWelcome = false)
            command?.let { graph.commandBus.inject(it, CommandSource.PANEL) }
        } else {
            requestOnce(Permissions.missing(this, graph.deviceProfile.isXr))
        }
    }

    /**
     * Starts the graph once per onStart/onStop cycle, and (re)starts perception on every call: when the
     * core pair was already granted, the graph starts before the XR permission dialog returns, so the
     * first `session.start` finds no XR grant (UNAVAILABLE) and the call from [onPermissionResult] is the
     * retry. `XrPerceptionSession.start` returns at once while RUNNING or STARTING.
     */
    private fun startGraph(speakWelcome: Boolean = true) {
        if (!graphStarted) {
            graphStarted = true
            graph.start(speakWelcome) // → ModeController.start(): welcome (FR-G1), frames, warm-up
        }
        // R3: the ARCore for Jetpack XR session needs this Activity (stack.md D7); a no-op on the S25 Ultra.
        // AppGraph orders it after the previous onStop's perception teardown (quick headset off/on).
        if (graph.perception != null) lifecycleScope.launch { graph.startPerception(this@MainActivity) }
    }

    private companion object {
        const val TAG = "vysion"
        const val LATENCY_TAG = "vysion.latency"
        const val RELIABILITY_TAG = "vysion.reliability"
        const val SECURITY_TAG = "vysion.security"
        const val NANOS_PER_MS = 1_000_000L
        const val EXTRA_PREVIEW = "vysion.preview"
        const val EXTRA_ONBOARDING_PAGE = "vysion.onboarding"
    }
}
