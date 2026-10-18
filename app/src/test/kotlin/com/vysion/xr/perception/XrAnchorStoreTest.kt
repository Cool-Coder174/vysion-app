package com.vysion.xr.perception

import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.RelativeTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-N3/FR-N4 as tests: placement along the VLM bearing and tracking relative to the head. */
@OptIn(ExperimentalCoroutinesApi::class)
class XrAnchorStoreTest {

    private fun store(source: FakePerceptionSource) = XrAnchorStore(source, NoOpHeadPoseSource(), clock = { 0L })

    @Test
    fun `placeFromBearing needs a tracked head pose`() = runTest {
        val source = FakePerceptionSource()
        val store = store(source)
        assertNull(store.placeFromBearing(HorizontalBearing.CENTER))
        source.headPose.value = head(tracking = Tracking.PAUSED)
        assertNull(store.placeFromBearing(HorizontalBearing.CENTER))
        assertTrue(source.sampledYawDeg.isEmpty())
        assertEquals(2, source.ensureStartedCalls)
    }

    @Test
    fun `placeFromBearing samples depth at head yaw plus the bearing offset and creates an anchor`() = runTest {
        val source = FakePerceptionSource().apply {
            headPose.value = head(yawDeg = 10f)
            depthPoint = Vec3(1f, 0f, -2f)
        }
        val store = store(source)
        assertEquals(AnchorId("anchor-1"), store.placeFromBearing(HorizontalBearing.RIGHT))
        assertEquals(listOf(40f), source.sampledYawDeg)
        assertEquals(AnchorId("anchor-1"), store.placeFromBearing(HorizontalBearing.LEFT))
        assertEquals(listOf(40f, -20f), source.sampledYawDeg)
    }

    @Test
    fun `placeFromBearing returns null for NONE and for no usable depth`() = runTest {
        val source = FakePerceptionSource().apply { headPose.value = head() }
        val store = store(source)
        assertNull(store.placeFromBearing(HorizontalBearing.NONE))
        assertTrue(source.sampledYawDeg.isEmpty())
        assertNull(store.placeFromBearing(HorizontalBearing.CENTER)) // depthPoint is null
        source.depthPoint = Vec3(0f, 0f, -2f)
        source.nextAnchorId = null // AnchorCreateTrackingUnavailable
        assertNull(store.placeFromBearing(HorizontalBearing.CENTER))
    }

    @Test
    fun `placeAtGaze samples depth along yaw and pitch, so a sign below eye level is hit`() = runTest {
        val source = FakePerceptionSource().apply {
            headPose.value = head(yawDeg = 15f, pitchDeg = -30f)
            depthPoint = Vec3(0.3f, 1.0f, -1.2f)
        }
        assertEquals(AnchorId("anchor-1"), store(source).placeAtGaze())
        assertEquals(listOf(15f), source.sampledYawDeg)
        assertEquals(listOf(-30f), source.sampledPitchDeg)
        assertEquals(listOf(Vec3(0.3f, 1.0f, -1.2f)), source.createdAt)
    }

    @Test
    fun `placeAtGaze without depth falls back along the gaze, and needs a tracked head`() = runTest {
        val source = FakePerceptionSource()
        val store = store(source)
        assertNull(store.placeAtGaze())
        source.headPose.value = head(x = 1f, z = 2f, pitchDeg = -90f) // looking straight down, no depth hit
        assertEquals(AnchorId("anchor-1"), store.placeAtGaze(fallbackDistanceM = 0.5f))
        val p = source.createdAt.single()
        assertEquals(1f, p.x, 1e-4f)
        assertEquals(head().position.y - 0.5f, p.y, 1e-4f)
        assertEquals(2f, p.z, 1e-4f)
    }

    @Test
    fun `gaze geometry matches the head pose conventions`() {
        val o = Vec3(0f, 1.6f, 0f)
        val ahead = GazeGeometry.pointAlong(o, 0f, 0f, 2f)
        assertEquals(0f, ahead.x, 1e-4f); assertEquals(1.6f, ahead.y, 1e-4f); assertEquals(-2f, ahead.z, 1e-4f)
        val right = GazeGeometry.pointAlong(o, 90f, 0f, 1f)
        assertEquals(1f, right.x, 1e-4f); assertEquals(0f, right.z, 1e-4f)
        val up = GazeGeometry.pointAlong(o, 0f, 30f, 1f)
        assertEquals(1.6f + 0.5f, up.y, 1e-4f); assertEquals(-0.8660f, up.z, 1e-3f)
    }

    @Test
    fun `track follows the head turning and completes when the anchor stops tracking`() = runTest {
        val source = FakePerceptionSource()
        val store = store(source)
        // As in production (placeFromBearing → createAnchor → track): the session knows the anchor before
        // anyone tracks it. An id the session never created is STOPPED at once (next test).
        val id = requireNotNull(source.createAnchor(Vec3(0f, 0f, -3f)))
        val seen = mutableListOf<RelativeTarget>()
        val job = launch { store.track(id).collect { seen += it } }
        runCurrent()
        assertTrue(seen.isEmpty()) // no anchor pose and no head pose yet
        assertFalse(job.isCompleted)

        source.anchorAt(id, Vec3(0f, 0f, -3f))
        source.headPose.value = head(yawDeg = 0f, nanos = 1L)
        runCurrent()
        assertEquals(RelativeTarget(0f, 12, 3.0f, true), seen.last())

        source.headPose.value = head(yawDeg = 180f, nanos = 2L) // FR-N4: door behind, still tracked
        runCurrent()
        assertEquals(6, seen.last().clock)
        assertFalse(seen.last().inView)

        // untrusted head pose: silent
        source.headPose.value = head(yawDeg = 180f, tracking = Tracking.PAUSED, nanos = 3L)
        val before = seen.size
        runCurrent()
        assertEquals(before, seen.size)

        source.anchorAt(id, Vec3(0f, 0f, -3f), tracking = Tracking.STOPPED)
        runCurrent()
        assertTrue(job.isCompleted)
    }

    @Test
    fun `track of an unknown anchor completes at once and remove detaches`() = runTest {
        val source = FakePerceptionSource()
        val store = store(source)
        val seen = mutableListOf<RelativeTarget>()
        val job = launch { store.track(AnchorId("nope")).collect { seen += it } }
        runCurrent()
        assertTrue(job.isCompleted) // even with no head pose at all: completion depends on the anchor only
        assertTrue(seen.isEmpty())
        store.remove(AnchorId("x"))
        assertEquals(listOf(AnchorId("x")), source.detached)
    }

    @Test
    fun `track completes when the anchor is detached even while the head pose is missing`() = runTest {
        val source = FakePerceptionSource()
        val store = store(source)
        val id = requireNotNull(source.createAnchor(Vec3(0f, 0f, -3f)))
        source.anchorAt(id, Vec3(0f, 0f, -3f))
        val seen = mutableListOf<RelativeTarget>()
        val job = launch { store.track(id).collect { seen += it } }
        runCurrent()
        assertFalse(job.isCompleted) // tracked anchor, no head pose: silent, alive
        assertTrue(seen.isEmpty())

        store.remove(id) // FR-N4 TargetLost path: detach → final STOPPED sample
        runCurrent()
        assertTrue(job.isCompleted)
        assertTrue(seen.isEmpty())
    }
}
