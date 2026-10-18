package com.vysion.xr.perception

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * FR-N9: the Mapbox Vector Tile decoder against the spec's own examples and one real OpenFreeMap tile (San Francisco
 * Civic Center, `src/test/resources/tiles/README.md`). The expected counts come from an independent Python decoder.
 */
class VectorTileTest {

    @Test
    fun `zigzag decodes the spec's table`() {
        assertEquals(0L, VectorTile.zigzag(0))
        assertEquals(-1L, VectorTile.zigzag(1))
        assertEquals(1L, VectorTile.zigzag(2))
        assertEquals(-2L, VectorTile.zigzag(3))
        assertEquals(2_147_483_647L, VectorTile.zigzag(4_294_967_294L))
        assertEquals(-2_147_483_648L, VectorTile.zigzag(4_294_967_295L))
    }

    @Test
    fun `geometry commands decode the spec's point, multipoint, line and polygon examples`() {
        assertParts(listOf(intArrayOf(25, 17)), VectorTile.decodeGeometry(intArrayOf(9, 50, 34)))
        assertParts(listOf(intArrayOf(5, 7), intArrayOf(3, 2)), VectorTile.decodeGeometry(intArrayOf(17, 10, 14, 3, 9)))
        assertParts(listOf(intArrayOf(2, 2, 2, 10, 10, 10)), VectorTile.decodeGeometry(intArrayOf(9, 4, 4, 18, 0, 16, 16, 0)))
        // A closed ring repeats its first point.
        assertParts(
            listOf(intArrayOf(3, 6, 8, 12, 20, 34, 3, 6)),
            VectorTile.decodeGeometry(intArrayOf(9, 6, 12, 18, 10, 12, 24, 44, 15)),
        )
        // Multilinestring: the second MoveTo starts a new part, relative to the last point.
        assertParts(
            listOf(intArrayOf(2, 2, 2, 10, 10, 10), intArrayOf(1, 1, 3, 5)),
            VectorTile.decodeGeometry(intArrayOf(9, 4, 4, 18, 0, 16, 16, 0, 9, 17, 17, 10, 4, 8)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a geometry that stops inside a command is rejected`() {
        VectorTile.decodeGeometry(intArrayOf(9, 50))
    }

    @Test
    fun `the real tile has the OpenMapTiles layers with the counts an independent decoder found`() {
        val tile = VectorTile.decode(civicCenterTile())
        val counts = tile.layers.associate { it.name to it.features.size }
        assertEquals(
            mapOf(
                "building" to 131, "housenumber" to 2433, "landcover" to 96, "landuse" to 161, "mountain_peak" to 5,
                "park" to 2, "place" to 38, "poi" to 5044, "transportation" to 1008, "transportation_name" to 241,
                "water" to 7, "water_name" to 4,
            ),
            counts,
        )
        assertTrue(tile.layers.all { it.extent == 4096 })
        assertEquals(3315, tile.layer("poi")!!.features.count { "name" in it.properties })
        assertEquals(240, tile.layer("transportation_name")!!.features.count { "name" in it.properties })
        val streets = tile.layer("transportation_name")!!.features
        assertTrue(streets.all { it.type == VectorTile.GeomType.LINESTRING })
        assertTrue(streets.any { it.properties["name"] == "Larkin Street" && it.properties["class"] == "secondary" })
    }

    @Test
    fun `asking for two layers skips the others`() {
        val tile = VectorTile.decode(civicCenterTile(), setOf("poi", "transportation_name"))
        assertEquals(setOf("poi", "transportation_name"), tile.layers.map { it.name }.toSet())
        assertNull(tile.layer("building"))
        val poi = tile.layer("poi")!!.features.first { it.properties["name"] == "Larkin Street & Grove Street" }
        assertEquals("bus_stop", poi.properties["subclass"])
        val point = poi.parts().single()
        assertEquals(2, point.size)
        assertTrue(point[0] in 0..4096 && point[1] in 0..4096)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a truncated tile is rejected, not misread`() {
        val bytes = civicCenterTile()
        VectorTile.decode(bytes.copyOf(bytes.size / 2))
    }

    @Test
    fun `the poi layer's number values decode as numbers`() {
        val poi = VectorTile.decode(civicCenterTile(), setOf("poi")).layer("poi")!!
        val rank = poi.features.firstNotNullOfOrNull { it.properties["rank"] }
        assertNotNull(rank)
        assertTrue(rank is Long || rank is Double || rank is Float)
    }

    private fun assertParts(expected: List<IntArray>, actual: List<IntArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (e, a) -> assertArrayEquals(e, a) }
    }

    companion object {
        /** OpenFreeMap 14/2620/6332, gzipped as served (src/test/resources/tiles/README.md). */
        fun civicCenterTile(): ByteArray =
            GZIPInputStream(checkNotNull(VectorTileTest::class.java.getResourceAsStream("/tiles/14-2620-6332.pbf.gz"))).use {
                it.readBytes()
            }
    }
}
