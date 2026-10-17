package com.vysion.xr.perception

import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.Detection
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.NormalizedBox
import com.vysion.xr.core.RelativeTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Navigation loop with every device call faked: start idempotence (H-39), pause/resume gating
 * (ADR-0004), the 3 s guidance cadence (FR-N3), arrival (FR-N6) and target loss (FR-N4).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class XrNavigationEngineTest {

    private class Rig(scope: TestScope) {
        val source = FakePerceptionSource()
        val frames = FakeFrameSource()
        val inference = FakeInferenceEngine(
            result = listOf(Detection("person", 0.9f, NormalizedBox(0.1f, 0.1f, 0.5f, 0.9f))),
        )
        val anchors = FakeAnchorStore()
        val pinger = FakePinger()
        val clock: () -> Long = { scope.testScheduler.currentTime * 1_000_000L } // virtual ms → nanos
        val engine = XrNavigationEngine(
            source = source,
            frames = frames,
            engine = inference,
            anchors = anchors,
            headPose = NoOpHeadPoseSource(),
            scope = scope.backgroundScope,
            pinger = pinger,
            clock = clock,
            proximity = ProximityMonitor(clock = clock),
        )
        val cues = mutableListOf<NavigationCue>()

        init {
            scope.backgroundScope.launch { engine.cues.collect { cues += it } }
        }

        fun near(m: Float = 0.5f) = depthFrame(centerM = m, timestampNanos = clock())
    }

    @Test
    fun `start is idempotent and stop ends the run`() = runTest {
        val rig = Rig(this)
        runCurrent()
        assertFalse(rig.engine.isRunning.value)
        rig.engine.start()
        rig.engine.start()
        runCurrent()
        assertTrue(rig.engine.isRunning.value)
        assertEquals(1, rig.source.ensureStartedCalls) // one Job (H-39)
        rig.engine.stop()
        runCurrent()
        assertFalse(rig.engine.isRunning.value)
        assertEquals("stop frees the ping SoundPool (attach recreates it)", 1, rig.pinger.releases)
        rig.engine.stop() // no-op when stopped
        assertEquals(1, rig.source.ensureStartedCalls)
        assertEquals(1, rig.pinger.releases)
        rig.engine.start() // can start again
        runCurrent()
        assertTrue(rig.engine.isRunning.value)
    }

    @Test
    fun `proximity cues flow while running, are gated while paused, and resume after`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.engine.start()
        runCurrent()

        rig.source.depth.tryEmit(rig.near())
        advanceTimeBy(50)
        runCurrent()
        assertEquals(listOf<NavigationCue>(NavigationCue.Proximity(0.5f)), rig.cues)

        rig.engine.pause()
        advanceTimeBy(2500)
        rig.source.depth.tryEmit(rig.near())
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, rig.cues.size) // gated: nothing spoken during a one-shot command

        rig.engine.resume()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(1, rig.cues.size) // the map buffered during the pause is stale and ignored

        rig.source.depth.tryEmit(rig.near(0.4f))
        advanceTimeBy(200)
        runCurrent()
        assertEquals(NavigationCue.Proximity(0.4f), rig.cues.last())
        assertEquals(2, rig.cues.size)
    }

    @Test
    fun `detector runs on frames, skips them while paused, and clears detections on pause`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.engine.start()
        runCurrent()

        rig.frames.frames.tryEmit(TestBitmaps.blank())
        runCurrent()
        assertEquals(1, rig.inference.detectCalls)
        assertEquals("person", rig.engine.detections.first().single().label)

        rig.engine.pause()
        assertTrue(rig.engine.detections.first().isEmpty())
        rig.frames.frames.tryEmit(TestBitmaps.blank())
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, rig.inference.detectCalls) // skipped, never queued (ADR-0004)

        rig.engine.resume()
        rig.frames.frames.tryEmit(TestBitmaps.blank())
        runCurrent()
        assertEquals(2, rig.inference.detectCalls)
        assertEquals(1, rig.engine.detections.first().size)

        rig.inference.failWith = InferenceException("boom")
        rig.frames.frames.tryEmit(TestBitmaps.blank())
        runCurrent()
        assertTrue(rig.engine.detections.first().isEmpty()) // detector off, loop alive
        rig.inference.failWith = null
        rig.frames.frames.tryEmit(TestBitmaps.blank())
        runCurrent()
        assertEquals(1, rig.engine.detections.first().size)
    }

    @Test
    fun `guidance speaks the bearing every 3 s with a ping and arrives within one meter`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.engine.start()
        runCurrent()
        val door = AnchorId("door")
        rig.engine.guideTo(door)
        runCurrent()
        assertEquals(listOf(door), rig.pinger.attached)

        rig.anchors.channel(door).trySend(RelativeTarget(60f, 2, 3.0f, true))
        advanceTimeBy(3001)
        runCurrent()
        val bearings = rig.cues.filterIsInstance<NavigationCue.DoorBearing>()
        assertEquals(listOf(NavigationCue.DoorBearing(2, 3.0f, true)), bearings)
        assertEquals(1, rig.pinger.pings)

        advanceTimeBy(3000)
        runCurrent()
        assertEquals(2, rig.cues.filterIsInstance<NavigationCue.DoorBearing>().size)
        assertEquals(2, rig.pinger.pings)

        rig.anchors.channel(door).trySend(RelativeTarget(0f, 12, 0.5f, true))
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(NavigationCue.Arrived, rig.cues.last()) // FR-N6, once
        assertEquals(1, rig.pinger.detaches)

        advanceTimeBy(6000)
        runCurrent()
        assertEquals(NavigationCue.Arrived, rig.cues.last()) // target cleared: nothing more
        assertEquals(2, rig.pinger.pings)
    }

    @Test
    fun `guidance is paused with the rest and continues on resume`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.engine.start()
        runCurrent()
        val door = AnchorId("door")
        rig.engine.guideTo(door)
        rig.anchors.channel(door).trySend(RelativeTarget(0f, 12, 4.0f, true))
        rig.engine.pause()
        advanceTimeBy(7000)
        runCurrent()
        assertTrue(rig.cues.isEmpty())
        rig.engine.resume()
        advanceTimeBy(3001)
        runCurrent()
        assertEquals(1, rig.cues.filterIsInstance<NavigationCue.DoorBearing>().size)
    }

    @Test
    fun `a short pause between ticks does not add a bearing and the cadence restarts on resume`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.engine.start()
        runCurrent()
        val door = AnchorId("door")
        rig.engine.guideTo(door)
        rig.anchors.channel(door).trySend(RelativeTarget(0f, 12, 4.0f, true))
        advanceTimeBy(3001)
        runCurrent()
        assertEquals(1, rig.cues.filterIsInstance<NavigationCue.DoorBearing>().size) // t = 3 s

        advanceTimeBy(1999) // t = 5 s
        rig.engine.pause()
        advanceTimeBy(2000) // the 6 s tick falls inside the pause: dropped
        rig.engine.resume() // t = 7 s
        advanceTimeBy(2999)
        runCurrent()
        assertEquals(1, rig.cues.filterIsInstance<NavigationCue.DoorBearing>().size) // nothing delivered late
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, rig.cues.filterIsInstance<NavigationCue.DoorBearing>().size) // 3 s after resume
        assertEquals(2, rig.pinger.pings)
    }

    @Test
    fun `door bearing says whether the spatial ping played and falls back to every 2 s (A-6)`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.engine.start()
        runCurrent()
        val door = AnchorId("door")
        rig.engine.guideTo(door)
        // track() emits on every head or anchor pose change; one sample per step keeps the target fresh.
        fun step(ms: Long) {
            rig.anchors.channel(door).trySend(RelativeTarget(60f, 2, 3.0f, true))
            advanceTimeBy(ms)
            runCurrent()
        }
        step(3001)
        // Not probed yet (null) counts as pinged: the default the contract gives.
        assertEquals(listOf(NavigationCue.DoorBearing(2, 3.0f, true, pinged = true)), bearings(rig))

        rig.pinger.playsSpatially = false // SceneCore cannot play the point source (A-6 failed)
        step(3000) // t = 6 s
        assertEquals(NavigationCue.DoorBearing(2, 3.0f, true, pinged = false), bearings(rig).last())
        assertEquals(2, rig.pinger.pings) // still asked to ping every tick

        step(2000) // t = 8 s: fallback cadence, every 2 s instead of 3 s
        assertEquals(3, bearings(rig).size)
        assertFalse(bearings(rig).last().pinged)

        rig.pinger.playsSpatially = true // spatial audio works again: pinged, and back to 3 s
        step(2000) // t = 10 s
        assertEquals(4, bearings(rig).size)
        assertTrue(bearings(rig).last().pinged)
        step(2000) // t = 12 s
        assertEquals(4, bearings(rig).size)
        step(1000) // t = 13 s
        assertEquals(5, bearings(rig).size)
        assertTrue(rig.cues.none { it is NavigationCue.TargetLost })
    }

    private fun bearings(rig: Rig) = rig.cues.filterIsInstance<NavigationCue.DoorBearing>()

    @Test
    fun `target is lost when tracking ends or no sample arrives for two periods`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.engine.start()
        runCurrent()
        val door = AnchorId("door")
        rig.engine.guideTo(door)
        rig.anchors.channel(door).trySend(RelativeTarget(0f, 12, 4.0f, true))
        advanceTimeBy(3001)
        runCurrent()
        assertEquals(1, rig.cues.size)

        rig.anchors.channel(door).close() // anchor detached / tracking stopped → track() completes
        advanceTimeBy(3000)
        runCurrent()
        assertEquals(NavigationCue.TargetLost, rig.cues.last())
        assertEquals(1, rig.pinger.detaches)

        val other = AnchorId("other")
        rig.engine.guideTo(other) // never sends a sample
        advanceTimeBy(XrNavigationEngine.TRACK_STALE_NANOS / 1_000_000 + 3001)
        runCurrent()
        assertEquals(NavigationCue.TargetLost, rig.cues.last())
        assertEquals(2, rig.cues.filterIsInstance<NavigationCue.TargetLost>().size)
        assertEquals(2, rig.pinger.detaches)
    }

    @Test
    fun `plane cues come from tracked planes on the heading and repeat at most every 3 s`() = runTest {
        val rig = Rig(this)
        runCurrent()
        rig.engine.start()
        runCurrent()
        val wall = PlaneObservation(
            id = "wall",
            kind = PlaneKind.WALL,
            tracking = Tracking.TRACKING,
            center = Vec3(0f, 0f, -1.2f),
            normal = Vec3(0f, 0f, 1f),
            axisX = Vec3(1f, 0f, 0f),
            axisZ = Vec3(0f, 1f, 0f),
            polygon = listOf(Vec2(-2f, -1f), Vec2(2f, -1f), Vec2(2f, 1f), Vec2(-2f, 1f)),
            timestampNanos = 0L,
        )
        rig.source.headPose.value = head()
        rig.source.planes.value = listOf(wall)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(listOf<NavigationCue>(NavigationCue.WallAhead(1.0f)), rig.cues)

        advanceTimeBy(1000)
        runCurrent()
        assertEquals(1, rig.cues.size) // gated
        advanceTimeBy(2500)
        runCurrent()
        assertEquals(2, rig.cues.size)

        rig.source.headPose.value = head(yawDeg = 90f) // wall no longer on the heading
        advanceTimeBy(4000)
        runCurrent()
        assertEquals(2, rig.cues.size)
    }
}
