package com.vysion.xr.perception

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import com.vysion.xr.core.PlaceSource
import com.vysion.xr.core.WhereAmI
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * FR-N9 "where am I" on Android (ADR-0010 row "Where am I map"):
 *
 * 1. Location must be on in Settings ([WhereAmI.LocationOff]) and precise location granted ([WhereAmI.PermissionNeeded];
 *    the controller asks only when the command is first used). Approximate location is fuzzed to kilometers, too
 *    coarse to name a street, so only `ACCESS_FINE_LOCATION` counts.
 * 2. One fix from the fused provider (Play services: Wi-Fi, since the Galaxy XR has no GPS), else the network provider;
 *    a fix under [MAX_CACHED_AGE_MS] old is reused. None within [FIX_TIMEOUT_MS] is [WhereAmI.NoFix].
 * 3. The z14 OpenFreeMap tile around the fix, plus neighbors within [EDGE_MARGIN_M] ([WebMercator.tilesAround]),
 *    from memory or downloaded ([OpenFreeMapTiles]); without the fix's own tile, [WhereAmI.NoMap].
 * 4. [SurroundingsFinder] on the device.
 *
 * Privacy: the fix lives only in this call. Nothing about it is stored; the `vysion.latency` line has timings and
 * counts only, never coordinates, accuracy, tile numbers or names. Owner R3 (additive; written for FR-N9 2026-10-18).
 */
class MapPlaceSource(
    private val context: Context,
    userAgent: String,
    private val log: (String) -> Unit = { Log.i(LATENCY_TAG, it) },
) : PlaceSource {

    private val tiles = OpenFreeMapTiles(
        fetch = { key -> withContext(Dispatchers.IO) { OpenFreeMapTiles.httpGet(key, userAgent) } },
    )

    override suspend fun whereAmI(): WhereAmI {
        val lm = context.getSystemService(LocationManager::class.java) ?: return WhereAmI.NoFix
        if (!lm.isLocationEnabled) return WhereAmI.LocationOff
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return WhereAmI.PermissionNeeded
        }
        val t0 = System.nanoTime()
        val fix = currentFix(lm)
        val fixMs = msSince(t0)
        if (fix == null) {
            log("whereami fix=none after $fixMs ms")
            return WhereAmI.NoFix
        }
        val accuracy = if (fix.location.hasAccuracy()) fix.location.accuracy else Float.NaN
        return surroundingsAt(fix.location.latitude, fix.location.longitude, accuracy, "fix=$fixMs ms ${fix.how}")
    }

    /**
     * Steps 3 and 4 for a known position. Also the debug smoke test's entry (`--es model whereami`, a fixed test point,
     * never the wearer's position). [evidence] starts the latency line.
     */
    internal suspend fun surroundingsAt(lat: Double, lon: Double, accuracyM: Float, evidence: String): WhereAmI {
        val home = WebMercator.tileOf(lat, lon)
        val keys = WebMercator.tilesAround(lat, lon, EDGE_MARGIN_M)
        val got = tiles.get(keys)
        val counts = "tiles=${keys.size} memory=${got.fromMemory} downloaded=${got.downloaded} failed=${got.failed} " +
            "bytes=${got.bytes} download=${got.downloadMs} ms decode=${got.decodeMs} ms"
        if (got.tiles.none { it.key == home }) {
            log("whereami $evidence $counts -> no map")
            return WhereAmI.NoMap
        }
        val t0 = System.nanoTime()
        val surroundings = withContext(Dispatchers.Default) { SurroundingsFinder.find(got.tiles, LocalFrame(lat, lon), accuracyM) }
        log("whereami $evidence $counts find=${msSince(t0)} ms")
        return WhereAmI.Found(surroundings)
    }

    /** Drops the tiles in memory, so the debug smoke test can time a cold download. */
    internal suspend fun forgetTiles() = tiles.clear()

    private class Fix(val location: Location, val how: String)

    /** Fused (Play services) first, then network; a recent cached fix first. Permission is checked by the caller. */
    @SuppressLint("MissingPermission")
    private suspend fun currentFix(lm: LocationManager): Fix? {
        val provider = PROVIDERS.firstOrNull { lm.hasProvider(it) && lm.isProviderEnabled(it) } ?: return null
        lm.getLastKnownLocation(provider)
            ?.takeIf { ageMs(it) <= MAX_CACHED_AGE_MS && it.hasAccuracy() && it.accuracy <= MAX_CACHED_ACCURACY_M }
            ?.let { return Fix(it, "$provider cached") }
        val location = withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                val request = LocationRequest.Builder(0L)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setDurationMillis(FIX_TIMEOUT_MS)
                    .build()
                lm.getCurrentLocation(provider, request, signal, context.mainExecutor) { location ->
                    if (cont.isActive) cont.resume(location)
                }
            }
        }
        return location?.let { Fix(it, "$provider fresh") }
    }

    private fun ageMs(location: Location): Long =
        (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / NANOS_PER_MS

    private fun msSince(t0: Long): Long = (System.nanoTime() - t0) / NANOS_PER_MS

    companion object {
        /** Fused first: on the Galaxy XR both are Play services' (`dumpsys location`, 2026-10-18). */
        private val PROVIDERS = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /** A walking wearer covers ~80 m a minute; an older fix may name the last block. */
        const val MAX_CACHED_AGE_MS: Long = 30_000

        /** A cached fix vaguer than this is not reused; a fresh one is asked for. */
        const val MAX_CACHED_ACCURACY_M: Float = 100f

        const val FIX_TIMEOUT_MS: Long = 10_000

        /** Neighbor tiles are fetched when the fix is this close to an edge, so the next cross streets are on hand. */
        const val EDGE_MARGIN_M: Double = 300.0

        private const val LATENCY_TAG = "vysion.latency"
        private const val NANOS_PER_MS = 1_000_000L
    }
}
