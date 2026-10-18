package com.vysion.xr.perception

import com.vysion.xr.core.NamedPlace
import com.vysion.xr.core.Surroundings
import kotlin.math.hypot

/**
 * The parts of one decoded OpenMapTiles tile that "where am I" (FR-N9) reads: named street lines from
 * `transportation_name` and named points from `poi`, still in tile pixels. Kept in memory between questions
 * ([OpenFreeMapTiles]); the raw tile bytes are dropped after decoding.
 */
internal class MapTile(val key: TileKey, val extent: Int, val streets: List<Line>, val places: List<Point>) {

    class Line(val name: String, val kind: String?, val parts: List<IntArray>)

    class Point(val name: String, val kind: String?, val x: Int, val y: Int)

    companion object {
        /** OpenMapTiles layers this feature reads; every other layer is skipped while decoding. */
        val LAYERS: Set<String> = setOf("transportation_name", "poi")

        /** Not walkable, so never "the street you are on". */
        private val UNWALKABLE: Set<String> = setOf("motorway")

        fun decode(key: TileKey, bytes: ByteArray): MapTile = from(key, VectorTile.decode(bytes, LAYERS))

        fun from(key: TileKey, tile: VectorTile): MapTile {
            val streetLayer = tile.layer("transportation_name")
            val poiLayer = tile.layer("poi")
            val streets = streetLayer?.features.orEmpty().mapNotNull { f ->
                val name = f.name() ?: return@mapNotNull null
                val kind = f.properties["class"] as? String
                // Indoor lines are corridors and stairs inside a building (OpenMapTiles `indoor`), not streets.
                if (f.type != VectorTile.GeomType.LINESTRING || kind in UNWALKABLE || f.isIndoor()) return@mapNotNull null
                Line(name, kind, f.parts().filter { it.size >= 4 })
            }
            val places = poiLayer?.features.orEmpty().mapNotNull { f ->
                val name = f.name() ?: return@mapNotNull null
                if (f.type != VectorTile.GeomType.POINT) return@mapNotNull null
                val point = f.parts().firstOrNull() ?: return@mapNotNull null
                val kind = (f.properties["subclass"] ?: f.properties["class"]) as? String
                Point(name, kind?.replace('_', ' '), point[0], point[1])
            }
            val extent = streetLayer?.extent ?: poiLayer?.extent ?: VectorTile.DEFAULT_EXTENT
            return MapTile(key, extent, streets, places)
        }

        private fun VectorTile.Feature.isIndoor(): Boolean = when (val v = properties["indoor"]) {
            is Boolean -> v
            is Number -> v.toLong() != 0L
            else -> false
        }

        private fun VectorTile.Feature.name(): String? = (properties["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/**
 * FR-N9: from the tiles around a fix to [Surroundings]: the nearest named street (point-to-polyline distance in local
 * meters), the nearest street crossing it on each side along it, and the nearest named places. Pure; unit-tested on a
 * real OpenFreeMap tile (`SurroundingsFinderTest`).
 *
 * "Each side" is measured along the street's direction at the wearer's nearest point on it, so the answer can say
 * "between Grove Street and Fulton Street" without knowing which way the wearer faces. A cross street is a named
 * street whose line crosses the nearest street's line, or ends within [JOIN_M] of it (a T junction). Streets and
 * places cut by tile edges are merged by name.
 */
internal object SurroundingsFinder {
    /** A line end this close to a street meets it (T junctions; tile geometry is quantized to ~0.5 m at z14). */
    const val JOIN_M: Double = 6.0

    /** Cross streets farther than this from the wearer's point on the street are not "next". */
    const val MAX_CROSS_M: Double = 600.0

    const val MAX_PLACES: Int = 3

    fun find(tiles: List<MapTile>, frame: LocalFrame, accuracyM: Float): Surroundings {
        val streets = localStreets(tiles, frame)
        val nearest = streets.minByOrNull { it.distance }
        val crossStreets = nearest?.let { crossStreets(it, streets) }.orEmpty()
        return Surroundings(
            street = nearest?.let { NamedPlace(it.name, it.kind, it.distance.toFloat()) },
            crossStreets = crossStreets,
            places = nearestPlaces(tiles, frame),
            accuracyM = accuracyM,
        )
    }

    /** One named street in local meters: every polyline with its name, from every tile; [closest] is the wearer's. */
    private class Street(val name: String, val kind: String?, val lines: List<DoubleArray>, val closest: Closest) {
        val distance: Double get() = closest.distance
    }

    private class Closest(val x: Double, val y: Double, val dirX: Double, val dirY: Double, val distance: Double)

    private fun localStreets(tiles: List<MapTile>, frame: LocalFrame): List<Street> {
        val byName = LinkedHashMap<String, MutableList<DoubleArray>>()
        val kinds = HashMap<String, String?>()
        for (tile in tiles) {
            for (line in tile.streets) {
                val key = line.name
                kinds.putIfAbsent(key, line.kind)
                val lines = byName.getOrPut(key) { mutableListOf() }
                for (part in line.parts) {
                    lines += DoubleArray(part.size) { i ->
                        if (i % 2 == 0) frame.east(tile.key, tile.extent, part[i]) else frame.north(tile.key, tile.extent, part[i])
                    }
                }
            }
        }
        return byName.mapNotNull { (name, lines) ->
            val closest = lines.flatMap(::segments).minByOrNull { it.distance } ?: return@mapNotNull null
            Street(name, kinds[name], lines, closest)
        }
    }

    /** The origin's closest point on each segment of [line] (x0, y0, x1, y1, …), with that segment's unit direction. */
    private fun segments(line: DoubleArray): List<Closest> = (0 until line.size / 2 - 1).mapNotNull { s ->
        val ax = line[2 * s]
        val ay = line[2 * s + 1]
        val bx = line[2 * s + 2]
        val by = line[2 * s + 3]
        val len = hypot(bx - ax, by - ay)
        if (len == 0.0) return@mapNotNull null
        val t = ((-ax) * (bx - ax) + (-ay) * (by - ay)) / (len * len)
        val c = t.coerceIn(0.0, 1.0)
        val px = ax + c * (bx - ax)
        val py = ay + c * (by - ay)
        Closest(px, py, (bx - ax) / len, (by - ay) / len, hypot(px, py))
    }

    /** One crossing: where [other] meets the wearer's street. */
    private class Crossing(val name: String, val kind: String?, val x: Double, val y: Double)

    private fun crossStreets(street: Street, all: List<Street>): List<NamedPlace> {
        val at = street.closest
        val ahead = mutableListOf<Pair<Double, Crossing>>()
        val behind = mutableListOf<Pair<Double, Crossing>>()
        for (other in all) {
            if (other.name == street.name) continue
            for (c in crossings(street, other)) {
                val dx = c.x - at.x
                val dy = c.y - at.y
                val along = hypot(dx, dy)
                if (along > MAX_CROSS_M) continue
                if (dx * at.dirX + dy * at.dirY >= 0) ahead += along to c else behind += along to c
            }
        }
        val first = (ahead + behind).minByOrNull { it.first }?.second ?: return emptyList()
        val firstSide = if (ahead.any { it.second === first }) ahead else behind
        val otherSide = if (firstSide === ahead) behind else ahead
        val second = otherSide.sortedBy { it.first }.firstOrNull { it.second.name != first.name }?.second
        return listOfNotNull(first, second)
            .map { NamedPlace(it.name, it.kind, hypot(it.x, it.y).toFloat()) }
            .sortedBy { it.distanceM }
    }

    /** Proper segment intersections, plus line ends within [JOIN_M] of the other street (T junctions, both ways). */
    private fun crossings(street: Street, other: Street): List<Crossing> {
        val found = mutableListOf<Crossing>()
        for (a in street.lines) for (b in other.lines) {
            for (i in 0 until a.size / 2 - 1) for (j in 0 until b.size / 2 - 1) {
                intersection(a, i, b, j)?.let { (x, y) -> found += Crossing(other.name, other.kind, x, y) }
            }
        }
        for (b in other.lines) for (end in listOf(0, b.size - 2)) {
            if (street.lines.any { distanceToLine(b[end], b[end + 1], it) <= JOIN_M }) {
                found += Crossing(other.name, other.kind, b[end], b[end + 1])
            }
        }
        for (a in street.lines) for (end in listOf(0, a.size - 2)) {
            if (other.lines.any { distanceToLine(a[end], a[end + 1], it) <= JOIN_M }) {
                found += Crossing(other.name, other.kind, a[end], a[end + 1])
            }
        }
        return found
    }

    private fun intersection(a: DoubleArray, i: Int, b: DoubleArray, j: Int): Pair<Double, Double>? {
        val p0x = a[2 * i]
        val p0y = a[2 * i + 1]
        val rx = a[2 * i + 2] - p0x
        val ry = a[2 * i + 3] - p0y
        val q0x = b[2 * j]
        val q0y = b[2 * j + 1]
        val sx = b[2 * j + 2] - q0x
        val sy = b[2 * j + 3] - q0y
        val denom = rx * sy - ry * sx
        if (denom == 0.0) return null // parallel or degenerate
        val t = ((q0x - p0x) * sy - (q0y - p0y) * sx) / denom
        val u = ((q0x - p0x) * ry - (q0y - p0y) * rx) / denom
        if (t !in 0.0..1.0 || u !in 0.0..1.0) return null
        return (p0x + t * rx) to (p0y + t * ry)
    }

    private fun distanceToLine(x: Double, y: Double, line: DoubleArray): Double {
        var best = Double.MAX_VALUE
        for (s in 0 until line.size / 2 - 1) {
            val ax = line[2 * s]
            val ay = line[2 * s + 1]
            val bx = line[2 * s + 2]
            val by = line[2 * s + 3]
            val len2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay)
            val t = if (len2 == 0.0) 0.0 else (((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(x - (ax + t * (bx - ax)), y - (ay + t * (by - ay))))
        }
        return best
    }

    private fun nearestPlaces(tiles: List<MapTile>, frame: LocalFrame): List<NamedPlace> =
        tiles.asSequence()
            .flatMap { tile ->
                tile.places.asSequence().map { p ->
                    val d = hypot(frame.east(tile.key, tile.extent, p.x), frame.north(tile.key, tile.extent, p.y))
                    NamedPlace(p.name, p.kind, d.toFloat())
                }
            }
            .sortedBy { it.distanceM }
            .distinctBy { it.name.lowercase() }
            .take(MAX_PLACES)
            .toList()
}
