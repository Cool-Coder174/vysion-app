package com.vysion.xr.perception

import com.vysion.xr.core.PlaceSource
import com.vysion.xr.core.WhereAmI

/**
 * FR-N9 fake [PlaceSource]: answers [result] without location or network, for consumer tests (ADR-0005) and the
 * `-PuseFakes=true` graph. [calls] counts the lookups.
 */
class FakePlaceSource(@Volatile var result: WhereAmI = WhereAmI.NoFix) : PlaceSource {
    @Volatile
    var calls: Int = 0
        private set

    override suspend fun whereAmI(): WhereAmI {
        calls++
        return result
    }
}
