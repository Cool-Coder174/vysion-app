package com.vysion.xr.perception

import com.vysion.xr.core.Distances
import com.vysion.xr.core.NavigationCue
import kotlin.math.abs

/**
 * Ray / plane / polygon math for FR-N5, in world and plane-local coordinates. Pure, tested.
 * Conventions: see [PlaneObservation] (center, normal, axisX, axisZ in world; polygon in plane-local).
 */
object PlaneGeometry {
    private const val EPS = 1e-6f

    /**
     * Distance t > 0 along the ray to the infinite plane through [point] with [normal]; `null` if parallel
     * or behind.
     */
    fun rayPlaneDistance(origin: Vec3, dir: Vec3, point: Vec3, normal: Vec3): Float? {
        val denom = dir.dot(normal)
        if (abs(denom) < EPS) return null
        val t = (point - origin).dot(normal) / denom
        return if (t > 0f) t else null
    }

    /** World point → plane-local (x, z). */
    fun toLocal(plane: PlaneObservation, world: Vec3): Vec2 {
        val d = world - plane.center
        return Vec2(d.dot(plane.axisX), d.dot(plane.axisZ))
    }

    /** World direction → plane-local direction (not normalized). */
    fun toLocalDir(plane: PlaneObservation, worldDir: Vec3): Vec2 =
        Vec2(worldDir.dot(plane.axisX), worldDir.dot(plane.axisZ))

    /** Even-odd point-in-polygon test. */
    fun contains(polygon: List<Vec2>, p: Vec2): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val xCross = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < xCross) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Smallest t > 0 at which the 2-D ray `origin + t · dir` crosses an edge of [polygon], i.e. where
     * it leaves the polygon when it starts inside. `null` if it never crosses.
     */
    fun rayPolygonExit(origin: Vec2, dir: Vec2, polygon: List<Vec2>): Float? {
        if (polygon.size < 2) return null
        var best: Float? = null
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[j]
            val b = polygon[i]
            val ex = b.x - a.x
            val ey = b.y - a.y
            val denom = dir.x * ey - dir.y * ex
            if (abs(denom) > EPS) {
                val ox = a.x - origin.x
                val oy = a.y - origin.y
                val t = (ox * ey - oy * ex) / denom // along the ray
                val s = (ox * dir.y - oy * dir.x) / denom // along the edge, 0..1
                if (t > EPS && s >= 0f && s <= 1f) {
                    if (best == null || t < best) best = t
                }
            }
            j = i
        }
        return best
    }

    /**
     * Distance along the head's horizontal heading to a wall plane's surface, only when the hit point
     * lies inside the plane's polygon (an infinite wall off to the side is not "ahead"). `null` otherwise.
     */
    fun wallDistanceAlongHeading(plane: PlaneObservation, head: RawHeadPose): Float? {
        val dir = head.forwardFlat
        val t = rayPlaneDistance(head.position, dir, plane.center, plane.normal) ?: return null
        val hit = head.position + dir * t
        return if (contains(plane.polygon, toLocal(plane, hit))) t else null
    }

    /**
     * Distance along the heading at which the floor polygon under the head ends. The head position is
     * projected onto the plane (local x, z); `null` when the head is not above this polygon.
     */
    fun floorEdgeDistanceAlongHeading(plane: PlaneObservation, head: RawHeadPose): Float? {
        val origin = toLocal(plane, head.position)
        if (!contains(plane.polygon, origin)) return null
        val dir = toLocalDir(plane, head.forwardFlat)
        val len = kotlin.math.sqrt(dir.x * dir.x + dir.y * dir.y)
        if (len < EPS) return null
        val unit = Vec2(dir.x / len, dir.y / len)
        return rayPolygonExit(origin, unit, plane.polygon)
    }
}

/** A plane reduced to what FR-N5 needs: its label and its distance along the heading (`null` = not on the heading). */
data class HeadingPlane(
    val kind: PlaneKind,
    val tracking: Tracking,
    val distanceM: Float?,
)

/**
 * FR-N5 plane semantics, pure and tested: "say 'wall ahead' when a wall plane is within 1.5 m on the
 * heading; say 'floor ends' when the floor plane stops within 1.5 m". R3 emits the cue types; the
 * words are R4's (`Cues.kt`, INV-4). Distances are rounded to 0.5 m (SRS §8 R3 phrasing rule).
 *
 * - Owner: R3, P1, SRS due Sun 09:00. Input: [XrPerceptionSession.planes] + [XrPerceptionSession.headPose]
 *   (docs/stack.md → R3 "Planes": `Plane.subscribe(session)`, `state.label`, `centerPose`, `extents`, `vertices`).
 * - Assumption A-5 (planes return data); the plane-local frame mapping is UNVERIFIED (see [PlaneObservation]).
 */
object PlaneSemantics {
    /** Labels + distances → cues. Only [Tracking.TRACKING] planes count. */
    fun cuesFor(planes: List<HeadingPlane>, rangeM: Float = Distances.PLANE_CUE_RANGE_M): List<NavigationCue> {
        val cues = ArrayList<NavigationCue>(2)
        val wall = planes.asSequence()
            .filter { it.tracking == Tracking.TRACKING && it.kind == PlaneKind.WALL }
            .mapNotNull { it.distanceM }
            .filter { it > 0f && it <= rangeM }
            .minOrNull()
        if (wall != null) cues.add(NavigationCue.WallAhead(Distances.roundToHalfMeter(wall)))
        val floorEdge = planes.asSequence()
            .filter { it.tracking == Tracking.TRACKING && it.kind == PlaneKind.FLOOR }
            .mapNotNull { it.distanceM }
            .filter { it >= 0f && it <= rangeM }
            .minOrNull()
        if (floorEdge != null) cues.add(NavigationCue.FloorEnds(Distances.roundToHalfMeter(floorEdge)))
        return cues
    }

    /**
     * Geometry → labels + distances. For a WALL the distance is the ray hit on its polygon; for a FLOOR
     * it is where the polygon under the head ends, unless another tracked floor polygon continues just
     * past that edge (adjacent floor planes are one floor, not an edge). [stepM] is how far past the
     * edge that continuation is probed.
     */
    fun project(planes: List<PlaneObservation>, head: RawHeadPose, stepM: Float = 0.05f): List<HeadingPlane> {
        val tracked = planes.filter { it.tracking == Tracking.TRACKING }
        return planes.map { plane ->
            val d: Float? = when {
                plane.tracking != Tracking.TRACKING -> null
                plane.kind == PlaneKind.WALL -> PlaneGeometry.wallDistanceAlongHeading(plane, head)
                plane.kind == PlaneKind.FLOOR -> {
                    val edge = PlaneGeometry.floorEdgeDistanceAlongHeading(plane, head)
                    if (edge == null) {
                        null
                    } else {
                        val beyond = head.position + head.forwardFlat * (edge + stepM)
                        val continues = tracked.any { other ->
                            other !== plane && other.kind == PlaneKind.FLOOR &&
                                PlaneGeometry.contains(other.polygon, PlaneGeometry.toLocal(other, beyond))
                        }
                        if (continues) null else edge
                    }
                }
                else -> null
            }
            HeadingPlane(plane.kind, plane.tracking, d)
        }
    }

    fun evaluate(
        planes: List<PlaneObservation>,
        head: RawHeadPose,
        rangeM: Float = Distances.PLANE_CUE_RANGE_M,
    ): List<NavigationCue> = cuesFor(project(planes, head), rangeM)
}
