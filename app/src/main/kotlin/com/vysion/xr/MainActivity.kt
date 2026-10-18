package com.vysion.xr

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    /** Registered before STARTED, as the contract requires (docs/stack.md → R1 "Runtime permission request"). */
    private val permissionLauncher: ActivityResultLauncher<Array<String>> =
        Permissions.register(this) { grants -> onPermissionResult(grants) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = (application as VysionApplication).graph
        // A-12 evidence line: which layout and which modules this device got.
        Log.i(TAG, "DeviceProfile: ${graph.deviceProfile.displayName}; useFakes=${BuildConfig.USE_FAKES}")

        setContent {
            // FR-G5: R5's overlay enters `platform` as a slot, so platform never imports diagnostics (ADR-0005).
            val diagnostics: @Composable () -> Unit = { DiagnosticsOverlay(source = graph.diagnostics) }
            // ADR-0006: Full Space on the headset, the flat layout on the S25 Ultra.
            if (graph.deviceProfile.isXr) SpatialShell(graph, diagnostics) else FlatShell(graph, diagnostics)
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
        if (Permissions.canStart(grants, this)) {
            startGraph()
        } else {
            // Cues.permissionDenied (R4): camera or microphone refused; the panel stays, nothing can run.
            graph.modeController.speakPermissionDenied()
        }
    }

    /**
     * Starts the graph once per onStart/onStop cycle, and (re)starts perception on every call: when the
     * core pair was already granted, the graph starts before the XR permission dialog returns, so the
     * first `session.start` finds no XR grant (UNAVAILABLE) and the call from [onPermissionResult] is the
     * retry. `XrPerceptionSession.start` returns at once while RUNNING or STARTING.
     */
    private fun startGraph() {
        if (!graphStarted) {
            graphStarted = true
            graph.start() // → ModeController.start(): welcome (FR-G1), frames, warm-up
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
    }
}
