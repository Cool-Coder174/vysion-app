package com.vysion.xr.perception

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** One slippy-map tile (z/x/y, x east from the antimeridian, y south from the top), as OpenFreeMap names them. */
internal data class TileKey(val z: Int, val x: Int, val y: Int)

/**
 * Web Mercator (EPSG:3857) arithmetic for FR-N9: which tiles hold a location, and tile pixels as local meters. Mercator
 * is linear in tile pixels, so one scale per query turns a pixel into meters east and north of the wearer: exact at
 * the wearer and within 0.1 % over the ±2 km a z14 tile spans. Pure; unit-tested.
 */
internal object WebMercator {
    /** 2π × 6 378 137 m, the sphere Web Mercator uses. */
    const val EARTH_CIRCUMFERENCE_M: Double = 40_075_016.685_578_5

    /** OpenMapTiles' highest zoom: every named street and place is in it. */
    const val ZOOM: Int = 14

    /** The world as 0..1 in x (east) and y (south) for [latDeg], [lonDeg]. */
    fun worldX(lonDeg: Double): Double = (lonDeg + 180.0) / 360.0

    fun worldY(latDeg: Double): Double {
        val lat = Math.toRadians(latDeg.coerceIn(-MAX_LAT, MAX_LAT))
        return (1.0 - ln(tan(lat) + 1.0 / cos(lat)) / PI) / 2.0
    }

    fun latOf(worldY: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * worldY))))

    fun tileOf(latDeg: Double, lonDeg: Double, z: Int = ZOOM): TileKey {
        val n = 1 shl z
        val x = floor(worldX(lonDeg) * n).toInt().mod(n)
        val y = floor(worldY(latDeg) * n).toInt().coerceIn(0, n - 1)
        return TileKey(z, x, y)
    }

    /**
     * The tile holding the location plus each neighbor whose edge is closer than [marginM]: at most a 2×2 block, so
     * every street within [marginM] is on hand and no more than four requests run at once (OpenFreeMap's limit).
     */
    fun tilesAround(latDeg: Double, lonDeg: Double, marginM: Double, z: Int = ZOOM): List<TileKey> {
        val n = 1 shl z
        val fx = worldX(lonDeg) * n
        val fy = worldY(latDeg) * n
        val home = tileOf(latDeg, lonDeg, z)
        val tileM = EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(latDeg)) / n
        val inX = (fx - floor(fx)) * tileM
        val inY = (fy - floor(fy)) * tileM
        val dx = when {
            inX < marginM -> -1
            tileM - inX < marginM -> 1
            else -> 0
        }
        val dy = when {
            inY < marginM -> -1
            tileM - inY < marginM -> 1
            else -> 0
        }
        val xs = listOf(home.x, home.x + dx).distinct().map { it.mod(n) }
        val ys = listOf(home.y, home.y + dy).distinct().filter { it in 0 until n }
        return ys.flatMap { y -> xs.map { x -> TileKey(z, x, y) } }.distinct()
    }

    private const val MAX_LAT = 85.051_128_78
}

/** Meters east and north of an origin (the wearer's fix), with tile pixels converted in one multiply-add. */
internal class LocalFrame(latDeg: Double, lonDeg: Double) {
    private val x0 = WebMercator.worldX(lonDeg)
    private val y0 = WebMercator.worldY(latDeg)
    private val metersPerWorld = WebMercator.EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(latDeg))

    fun east(tile: TileKey, extent: Int, px: Int): Double =
        ((tile.x + px.toDouble() / extent) / (1 shl tile.z) - x0) * metersPerWorld

    fun north(tile: TileKey, extent: Int, py: Int): Double =
        -((tile.y + py.toDouble() / extent) / (1 shl tile.z) - y0) * metersPerWorld
}
