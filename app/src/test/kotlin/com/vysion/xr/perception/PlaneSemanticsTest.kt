package com.vysion.xr.perception

import com.vysion.xr.core.NavigationCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-N5 as tests: wall-ahead and floor-ends within 1.5 m on the heading. JVM only. */
class PlaneSemanticsTest {

    /** A wall facing +Z (toward a head at the origin looking down -Z), at z = -[distance]. */
    private fun wall(distance: Float, xFrom: Float = -2f, xTo: Float = 2f, tracking: Tracking = Tracking.TRACKING) =
        PlaneObservation(
            id = "wall-$distance",
            kind = PlaneKind.WALL,
            tracking = tracking,
            center = Vec3(0f, 0f, -distance),
            normal = Vec3(0f, 0f, 1f),
            axisX = Vec3(1f, 0f, 0f),
            axisZ = Vec3(0f, 1f, 0f),
            polygon = listOf(Vec2(xFrom, -1.5f), Vec2(xTo, -1.5f), Vec2(xTo, 1.5f), Vec2(xFrom, 1.5f)),
            timestampNanos = 0L,
        )

    /** A floor 1.6 m below the head; polygon in plane-local (x, z) with local +Z = world +Z. */
    private fun floor(zFrom: Float, zTo: Float, id: String = "floor", tracking: Tracking = Tracking.TRACKING) =
        PlaneObservation(
            id = id,
            kind = PlaneKind.FLOOR,
            tracking = tracking,
            center = Vec3(0f, -1.6f, 0f),
            normal = Vec3(0f, 1f, 0f),
            axisX = Vec3(1f, 0f, 0f),
            axisZ = Vec3(0f, 0f, 1f),
            polygon = listOf(Vec2(-2f, zFrom), Vec2(2f, zFrom), Vec2(2f, zTo), Vec2(-2f, zTo)),
            timestampNanos = 0L,
        )

    @Test
    fun `a wall 1,4 m ahead on the heading is WallAhead rounded to half meters`() {
        val cues = PlaneSemantics.evaluate(listOf(wall(1.4f)), head())
        assertEquals(listOf<NavigationCue>(NavigationCue.WallAhead(1.5f)), cues)
    }

    @Test
    fun `a wall beyond 1,5 m is silent`() {
        assertTrue(PlaneSemantics.evaluate(listOf(wall(2.0f)), head()).isEmpty())
    }

    @Test
    fun `a wall whose polygon the heading misses is not ahead`() {
        assertTrue(PlaneSemantics.evaluate(listOf(wall(1.0f, xFrom = 2f, xTo = 4f)), head()).isEmpty())
        assertNull(PlaneGeometry.wallDistanceAlongHeading(wall(1.0f, xFrom = 2f, xTo = 4f), head()))
    }

    @Test
    fun `turning 90 degrees takes the wall off the heading`() {
        assertTrue(PlaneSemantics.evaluate(listOf(wall(1.0f)), head(yawDeg = 90f)).isEmpty())
        // and a wall placed to the right is found when turned right
        val rightWall = wall(1.0f).copy(center = Vec3(1.0f, 0f, 0f), normal = Vec3(-1f, 0f, 0f), axisX = Vec3(0f, 0f, 1f))
        assertEquals(listOf<NavigationCue>(NavigationCue.WallAhead(1.0f)), PlaneSemantics.evaluate(listOf(rightWall), head(yawDeg = 90f)))
    }

    @Test
    fun `the nearest of several walls is reported once`() {
        val cues = PlaneSemantics.evaluate(listOf(wall(1.2f), wall(0.6f), wall(3f)), head())
        assertEquals(listOf<NavigationCue>(NavigationCue.WallAhead(0.5f)), cues)
    }

    @Test
    fun `the floor polygon ending one meter ahead is FloorEnds`() {
        val cues = PlaneSemantics.evaluate(listOf(floor(zFrom = -1f, zTo = 3f)), head())
        assertEquals(listOf<NavigationCue>(NavigationCue.FloorEnds(1.0f)), cues)
    }

    @Test
    fun `an adjacent floor plane past the edge means the floor continues`() {
        val cues = PlaneSemantics.evaluate(listOf(floor(-1f, 3f, "a"), floor(-4f, -1f, "b")), head())
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `a floor edge beyond the range, or a head not above the polygon, is silent`() {
        assertTrue(PlaneSemantics.evaluate(listOf(floor(-5f, 3f)), head()).isEmpty())
        assertTrue(PlaneSemantics.evaluate(listOf(floor(-5f, -3f)), head()).isEmpty())
        assertNull(PlaneGeometry.floorEdgeDistanceAlongHeading(floor(-5f, -3f), head()))
    }

    @Test
    fun `untracked planes never produce cues`() {
        val planes = listOf(wall(1.0f, tracking = Tracking.PAUSED), floor(-1f, 3f, tracking = Tracking.STOPPED))
        assertTrue(PlaneSemantics.evaluate(planes, head()).isEmpty())
        assertTrue(PlaneSemantics.cuesFor(listOf(HeadingPlane(PlaneKind.WALL, Tracking.PAUSED, 0.5f))).isEmpty())
    }

    @Test
    fun `cuesFor works on labels and distances alone`() {
        val cues = PlaneSemantics.cuesFor(
            listOf(
                HeadingPlane(PlaneKind.WALL, Tracking.TRACKING, 1.3f),
                HeadingPlane(PlaneKind.WALL, Tracking.TRACKING, null),
                HeadingPlane(PlaneKind.FLOOR, Tracking.TRACKING, 0.7f),
                HeadingPlane(PlaneKind.CEILING, Tracking.TRACKING, 0.2f),
            ),
        )
        assertEquals(listOf(NavigationCue.WallAhead(1.5f), NavigationCue.FloorEnds(0.5f)), cues)
    }

    @Test
    fun `ray plane and ray polygon primitives`() {
        assertEquals(2f, PlaneGeometry.rayPlaneDistance(Vec3.ZERO, Vec3.FORWARD, Vec3(0f, 0f, -2f), Vec3(0f, 0f, 1f))!!, 1e-6f)
        assertNull(PlaneGeometry.rayPlaneDistance(Vec3.ZERO, Vec3.FORWARD, Vec3(0f, 0f, 2f), Vec3(0f, 0f, 1f))) // behind
        assertNull(PlaneGeometry.rayPlaneDistance(Vec3.ZERO, Vec3.FORWARD, Vec3(0f, -1f, 0f), Vec3.UP)) // parallel
        val square = listOf(Vec2(-1f, -1f), Vec2(1f, -1f), Vec2(1f, 1f), Vec2(-1f, 1f))
        assertTrue(PlaneGeometry.contains(square, Vec2(0f, 0f)))
        assertTrue(!PlaneGeometry.contains(square, Vec2(2f, 0f)))
        assertEquals(1f, PlaneGeometry.rayPolygonExit(Vec2(0f, 0f), Vec2(0f, -1f), square)!!, 1e-6f)
        assertEquals(0.5f, PlaneGeometry.rayPolygonExit(Vec2(0.5f, 0f), Vec2(1f, 0f), square)!!, 1e-6f)
        assertNull(PlaneGeometry.rayPolygonExit(Vec2(0f, 2f), Vec2(1f, 0f), square))
    }
}
