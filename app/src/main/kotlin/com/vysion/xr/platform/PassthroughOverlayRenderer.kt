package com.vysion.xr.platform

import com.vysion.xr.core.Detection
import com.vysion.xr.core.NormalizedBox
import com.vysion.xr.core.OverlayRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides [OverlayRenderer] (FR-N2 detection outlines, FR-N3 door highlight; SRS §8 R1
 * "outlines", due Sat 21:00). Owner: R1. Constructor `()` per `AppGraph`.
 *
 * Pure and fully working: it is a pair of `StateFlow`s that the Compose overlay
 * (`platform/ui/OverlayCanvas.kt`) collects and draws. Nothing here touches the XR runtime, so the
 * same class serves the headset, the S25 Ultra 2D layout (ADR-0006) and the unit tests.
 *
 * Callers: `ModeController` forwards `NavigationEngine.detections` to [showDetections] and the door
 * bearing to [highlightDoor]; `clear()` runs when Navigation stops. Boxes are normalized (0..1,
 * origin top-left), so the canvas scales them to whatever view it is sized to; this class never
 * knows a pixel size.
 *
 * Drawing in real passthrough (a `SpatialExternalSurface`) is UNVERIFIED in docs/stack.md → R1; the
 * canvas lives in a `SpatialPanel` until that is checked on the device (A-2).
 */
class PassthroughOverlayRenderer : OverlayRenderer {

    private val _detections = MutableStateFlow<List<Detection>>(emptyList())
    private val _door = MutableStateFlow<NormalizedBox?>(null)

    /** Latest detector hits; empty while Navigation is paused (ADR-0004) or stopped. */
    val detections: StateFlow<List<Detection>> = _detections

    /** The door highlight, `null` when no target is in view (FR-N3). */
    val door: StateFlow<NormalizedBox?> = _door

    // FR-N2: replace, never accumulate; stale boxes must not survive a paused detector.
    override fun showDetections(detections: List<Detection>) {
        _detections.value = detections
    }

    // FR-N3: a single highlight; `null` clears it (door out of view, FR-N4).
    override fun highlightDoor(box: NormalizedBox?) {
        _door.value = box
    }

    override fun clear() {
        _detections.value = emptyList()
        _door.value = null
    }
}
