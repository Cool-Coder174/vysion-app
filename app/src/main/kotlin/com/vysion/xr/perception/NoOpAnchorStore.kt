package com.vysion.xr.perception

import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.RelativeTarget
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * [AnchorStore] for devices without an XR runtime (S25 Ultra, emulator, `-PuseFakes=true`; ADR-0006).
 * Fully working: `placeFromBearing` returns `null` (the contract's "no usable depth"), so "find the
 * door" ends in `Cues.doorNotFound` (R4) instead of a crash; `track` completes immediately (the engine
 * reports `TargetLost`). Constructor matches `AppGraph`: `()`. Owner: R3.
 */
class NoOpAnchorStore : AnchorStore {
    override suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId? = null
    override fun track(id: AnchorId): Flow<RelativeTarget> = emptyFlow()
    override suspend fun remove(id: AnchorId) = Unit
    override suspend fun clear() = Unit
}
