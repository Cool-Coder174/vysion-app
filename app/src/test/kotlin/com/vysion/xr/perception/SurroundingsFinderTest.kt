package com.vysion.xr.perception

import com.vysion.xr.core.NamedPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

/**
 * FR-N9: nearest street, cross streets and places, on the real Civic Center tile and on hand-made streets. The
 * Civic Center expectations (Larkin 9.9 m, Fulton 45.8 m, Grove 49.9 m away; the three nearest places) come from an
 * independent Python computation over the same tile, and match the city: the Main Library's block of Larkin Street
 * runs between Fulton and Grove.
 */
class SurroundingsFinderTest {

    private val civicCenter = MapTile.decode(TileKey(14, 2620, 6332), VectorTileTest.civicCenterTile())

    @Test
    fun `on Larkin Street between Fulton and Grove`() {
        // 10 m east of Larkin Street, halfway between its Fulton and Grove crossings (the library side).
        val s = SurroundingsFinder.find(listOf(civicCenter), LocalFrame(37.779136, -122.416605), accuracyM = 30f)
        assertEquals("Larkin Street", s.street?.name)
        assertEquals("secondary", s.street?.kind)
        assertEquals(9.9f, s.street!!.distanceM, 0.5f)
        assertEquals(setOf("Fulton Street", "Grove Street"), s.crossStreets.map { it.name }.toSet())
        // Distances to the crossings themselves: Grove 50 m almost straight south, Fulton 50 m north and 18 m west.
        val grove = s.crossStreets.first { it.name == "Grove Street" }
        val fulton = s.crossStreets.first { it.name == "Fulton Street" }
        assertEquals(50f, grove.distanceM, 3f)
        assertEquals(53f, fulton.distanceM, 3f)
        assertEquals(30f, s.accuracyM)
    }

    @Test
    fun `the three nearest named places, nearest first, with their kinds`() {
        val s = SurroundingsFinder.find(listOf(civicCenter), LocalFrame(37.779136, -122.416605), accuracyM = 30f)
        assertEquals(
            listOf("Portrait of a Phenomenal Woman", "Larkin Street & Grove Street", "Double L Excentric Gyratory"),
            s.places.map { it.name },
        )
        assertEquals(listOf("monument", "bus stop", "artwork"), s.places.map { it.kind })
        assertEquals(11.9f, s.places[0].distanceM, 0.5f)
        assertEquals(23.4f, s.places[1].distanceM, 0.5f)
        assertEquals(25.6f, s.places[2].distanceM, 0.5f)
    }

    @Test
    fun `indoor corridors and motorways are never the street you are on`() {
        val names = civicCenter.streets.map { it.name }.toSet()
        // The library's named staircase is an indoor line 52 m away in the same tile; it must not count as a street.
        assertTrue("Functional and Fantasy Stair" !in names)
        assertTrue(civicCenter.streets.none { it.kind == "motorway" })
    }

    @Test
    fun `between two cross streets on a plain grid`() {
        // Main Street runs east-west; First Street crosses it 40 m west, Second Street 60 m east.
        val s = SurroundingsFinder.find(
            listOf(grid(line("Main Street", -500, 0, 500, 0), line("First Street", -40, -300, -40, 300), line("Second Street", 60, -300, 60, 300))),
            ORIGIN, accuracyM = 20f,
        )
        val street = s.street!!
        assertEquals(NamedPlace("Main Street", "minor", 5f), street.copy(distanceM = Math.round(street.distanceM).toFloat()))
        assertEquals(listOf("First Street", "Second Street"), s.crossStreets.map { it.name })
        assertEquals(40.3f, s.crossStreets[0].distanceM, 0.5f)
        assertEquals(60.2f, s.crossStreets[1].distanceM, 0.5f)
    }

    @Test
    fun `a T junction counts, and a street crossing twice on one side is said once`() {
        // Oak Street ends 3 m short of Main Street (a T); Loop Lane crosses Main twice to the east.
        val s = SurroundingsFinder.find(
            listOf(
                grid(
                    line("Main Street", -500, 0, 500, 0),
                    line("Oak Street", -30, -200, -30, -3),
                    line("Loop Lane", 50, -100, 50, 100, 120, 100, 120, -100),
                ),
            ),
            ORIGIN, accuracyM = 20f,
        )
        assertEquals(listOf("Oak Street", "Loop Lane"), s.crossStreets.map { it.name })
    }

    @Test
    fun `with crossings on one side only, only that one is named`() {
        val s = SurroundingsFinder.find(
            listOf(grid(line("Main Street", -500, 0, 500, 0), line("First Street", 80, -300, 80, 300))),
            ORIGIN, accuracyM = 20f,
        )
        assertEquals(listOf("First Street"), s.crossStreets.map { it.name })
    }

    @Test
    fun `no streets in the map means no street, and places still count`() {
        val tile = MapTile(KEY, EXTENT, emptyList(), listOf(MapTile.Point("Cafe", "cafe", px(10), py(0))))
        val s = SurroundingsFinder.find(listOf(tile), ORIGIN, accuracyM = 20f)
        assertNull(s.street)
        assertTrue(s.crossStreets.isEmpty())
        assertEquals(listOf("Cafe"), s.places.map { it.name })
    }

    @Test
    fun `a street cut by a tile edge is one street, and duplicate places across tiles are said once`() {
        val east = TileKey(KEY.z, KEY.x + 1, KEY.y)
        val west = MapTile(KEY, EXTENT, listOf(MapTile.Line("Main Street", "minor", listOf(intArrayOf(0, py(0), EXTENT, py(0))))),
            listOf(MapTile.Point("Kiosk", null, EXTENT - 2, py(0))))
        val eastTile = MapTile(east, EXTENT, listOf(MapTile.Line("Main Street", "minor", listOf(intArrayOf(0, py(0), EXTENT, py(0))))),
            listOf(MapTile.Point("Kiosk", null, 2, py(0))))
        val s = SurroundingsFinder.find(listOf(west, eastTile), ORIGIN, accuracyM = 20f)
        assertEquals("Main Street", s.street?.name)
        assertEquals(listOf("Kiosk"), s.places.map { it.name })
    }

    private companion object {
        val KEY = TileKey(14, 2620, 6332)
        const val EXTENT = 4096

        /** The center of [KEY]; hand-made lines are drawn in meters around it. */
        val CENTER_LAT = WebMercator.latOf((KEY.y + 0.5) / (1 shl KEY.z))
        val CENTER_LON = (KEY.x + 0.5) / (1 shl KEY.z) * 360.0 - 180.0
        val ORIGIN = LocalFrame(CENTER_LAT, CENTER_LON)

        /** Meters per tile pixel at the center (Mercator scale × cos(lat)). */
        val M_PER_PX = WebMercator.EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(CENTER_LAT)) / (1 shl KEY.z) / EXTENT

        fun px(eastM: Int): Int = EXTENT / 2 + Math.round(eastM / M_PER_PX).toInt()
        fun py(northM: Int): Int = EXTENT / 2 - Math.round(northM / M_PER_PX).toInt()

        /** A line through meter points (east, north, east, north, …), 5 m north of the wearer's row offset. */
        fun line(name: String, vararg meters: Int): MapTile.Line {
            val pixels = IntArray(meters.size) { i -> if (i % 2 == 0) px(meters[i]) else py(meters[i] + 5) }
            return MapTile.Line(name, "minor", listOf(pixels))
        }

        fun grid(vararg lines: MapTile.Line): MapTile = MapTile(KEY, EXTENT, lines.toList(), emptyList())
    }
}
