package com.vysion.xr.perception

import com.vysion.xr.core.HeadPose
import com.vysion.xr.core.HeadPoseSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [HeadPoseSource] for devices without an XR runtime (the S25 Ultra, the laptop emulator,
 * `-PuseFakes=true`; ADR-0006). Fully working: `pose` is always `null`, which R1 treats as "no pose,
 * skip the hold-still check" (FR-T5) and which renders as "not measured" (INV-7). Constructor matches
 * `AppGraph`: `()`. Owner: R3.
 */
class NoOpHeadPoseSource : HeadPoseSource {
    override val pose: StateFlow<HeadPose?> = MutableStateFlow<HeadPose?>(null).asStateFlow()
}
