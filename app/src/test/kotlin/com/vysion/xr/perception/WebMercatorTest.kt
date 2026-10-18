package com.vysion.xr.perception

import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-N9: which z14 tiles hold a fix, and tile pixels as local meters. */
class WebMercatorTest {

    @Test
    fun `Civic Center is tile 14 2620 6332`() {
        assertEquals(TileKey(14, 2620, 6332), WebMercator.tileOf(37.779136, -122.416605))
    }

    @Test
    fun `the middle of a tile needs only that tile, an edge adds a neighbor, a corner a 2x2 block`() {
        val n = 1 shl 14
        fun lat(fy: Double) = WebMercator.latOf(fy / n)
        fun lon(fx: Double) = fx / n * 360.0 - 180.0
        assertEquals(listOf(TileKey(14, 2620, 6332)), WebMercator.tilesAround(lat(6332.5), lon(2620.5), marginM = 250.0))
        assertEquals(
            listOf(TileKey(14, 2620, 6332), TileKey(14, 2621, 6332)),
            WebMercator.tilesAround(lat(6332.5), lon(2620.98), marginM = 250.0),
        )
        assertEquals(
            setOf(TileKey(14, 2620, 6332), TileKey(14, 2619, 6332), TileKey(14, 2620, 6331), TileKey(14, 2619, 6331)),
            WebMercator.tilesAround(lat(6332.02), lon(2620.02), marginM = 250.0).toSet(),
        )
    }

    @Test
    fun `x wraps at the antimeridian`() {
        val tiles = WebMercator.tilesAround(0.0, 179.9999, marginM = 250.0)
        assertEquals(setOf(16383, 0), tiles.map { it.x }.toSet())
    }

    @Test
    fun `a tile's corner pixel is that many meters from the wearer`() {
        val tile = TileKey(14, 2620, 6332)
        val n = (1 shl 14).toDouble()
        val frame = LocalFrame(WebMercator.latOf(6332 / n), 2620 / n * 360.0 - 180.0)
        assertEquals(0.0, frame.east(tile, 4096, 0), 1e-6)
        assertEquals(0.0, frame.north(tile, 4096, 0), 1e-6)
        // One tile is 2445.98 m of Mercator at the equator × cos(37.78°) ≈ 1933 m across, and y grows southward.
        assertEquals(1933.0, frame.east(tile, 4096, 4096), 2.0)
        assertEquals(-1933.0, frame.north(tile, 4096, 4096), 2.0)
    }
}
