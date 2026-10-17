package com.vysion.xr.perception

import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.Detection
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.NavigationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [NavigationEngine] for devices without an XR runtime (S25 Ultra, emulator, `-PuseFakes=true`;
 * ADR-0006 "Navigation on the phone is reported as unavailable with a spoken notice", the notice being
 * R4's `Cues.navigationUnavailable` spoken by R1). Fully working: `isRunning` stays `false`, `cues` and
 * `detections` are empty flows, every call is a no-op. Constructor matches `AppGraph`: `()`. Owner: R3.
 */
class NoOpNavigationEngine : NavigationEngine {
    override val cues: Flow<NavigationCue> = emptyFlow()
    override val detections: Flow<List<Detection>> = emptyFlow()
    override val isRunning: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()

    override suspend fun start() = Unit
    override suspend fun pause() = Unit
    override suspend fun resume() = Unit
    override suspend fun stop() = Unit
    override suspend fun guideTo(anchor: AnchorId) = Unit
    override suspend fun clearTarget() = Unit
}
