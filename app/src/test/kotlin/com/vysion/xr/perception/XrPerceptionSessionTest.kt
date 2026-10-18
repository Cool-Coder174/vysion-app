package com.vysion.xr.perception

import android.app.Application
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session lifecycle without a device: when a session is opened ([SessionStartPolicy]) and what
 * [XrPerceptionSession.stop] leaves behind. Nothing here reaches `Session.create`, so these stay valid
 * once the real device step replaces the skeleton stub.
 */
class XrPerceptionSessionTest {

    @Test
    fun `start reopens a stopped session and never doubles a running one`() {
        val explicit = true
        assertTrue(SessionStartPolicy.shouldOpen(SessionState.NOT_STARTED, explicit)) // first onStart
        assertTrue(SessionStartPolicy.shouldOpen(SessionState.STOPPED, explicit)) // headset off and on
        assertTrue(SessionStartPolicy.shouldOpen(SessionState.UNAVAILABLE, explicit)) // retry a failure
        assertFalse(SessionStartPolicy.shouldOpen(SessionState.RUNNING, explicit))
        assertFalse(SessionStartPolicy.shouldOpen(SessionState.STARTING, explicit))
    }

    @Test
    fun `ensureStarted only retries an unavailable session`() {
        val implicit = false
        // e.g. SCENE_UNDERSTANDING_* granted after MainActivity's first start(activity) failed on it
        assertTrue(SessionStartPolicy.shouldOpen(SessionState.UNAVAILABLE, implicit))
        assertFalse(SessionStartPolicy.shouldOpen(SessionState.NOT_STARTED, implicit)) // no Activity yet
        assertFalse(SessionStartPolicy.shouldOpen(SessionState.STOPPED, implicit)) // onStop owns it
        assertFalse(SessionStartPolicy.shouldOpen(SessionState.RUNNING, implicit))
        assertFalse(SessionStartPolicy.shouldOpen(SessionState.STARTING, implicit))
    }

    @Test
    fun `depth goes off at pause so a resumed runtime never reads depth it lost`() {
        // Crash 2026-10-18 11:40:08: resumed with depth configured on a runtime dropped while the headset was off.
        assertEquals(DepthLifecyclePolicy.Action.DEPTH_OFF, DepthLifecyclePolicy.onPause(depthOn = true))
        assertEquals(DepthLifecyclePolicy.Action.NONE, DepthLifecyclePolicy.onPause(depthOn = false))
    }

    @Test
    fun `resume turns depth back on and retries a start that failed while paused`() {
        // Headset off and on, runtime kept: start() at onStart configured without depth; ON_RESUME adds it.
        assertEquals(DepthLifecyclePolicy.Action.DEPTH_ON, DepthLifecyclePolicy.onResume(SessionState.RUNNING, depthOn = false))
        assertEquals(DepthLifecyclePolicy.Action.NONE, DepthLifecyclePolicy.onResume(SessionState.RUNNING, depthOn = true))
        // Runtime dropped: start() at onStart failed ("Has the OpenXrManager been created?"); the resumed runtime takes it.
        assertEquals(DepthLifecyclePolicy.Action.REOPEN, DepthLifecyclePolicy.onResume(SessionState.UNAVAILABLE, depthOn = false))
        // Never started, mid-start, or stopped by onStop: start(activity) owns those.
        for (state in listOf(SessionState.NOT_STARTED, SessionState.STARTING, SessionState.STOPPED)) {
            assertEquals(state.name, DepthLifecyclePolicy.Action.NONE, DepthLifecyclePolicy.onResume(state, depthOn = false))
        }
    }

    @Test
    fun `no state turns depth on at pause or off at resume`() {
        for (depthOn in listOf(true, false)) {
            assertTrue(DepthLifecyclePolicy.onPause(depthOn) != DepthLifecyclePolicy.Action.DEPTH_ON)
            for (state in SessionState.entries) {
                assertTrue(DepthLifecyclePolicy.onResume(state, depthOn) != DepthLifecyclePolicy.Action.DEPTH_OFF)
            }
        }
    }

    @Test
    fun `ensureStarted before start and after stop leaves the session alone`() = runTest {
        val session = XrPerceptionSession(TestInstances.allocate(Application::class.java), backgroundScope)
        session.ensureStarted()
        assertEquals(SessionState.NOT_STARTED, session.state.value)

        session.stop()
        assertEquals(SessionState.STOPPED, session.state.value)
        assertNull(session.headPose.value) // nothing stale is trusted after a restart (RK-5)
        assertTrue(session.planes.value.isEmpty())
        assertNull(session.xrSession)

        session.ensureStarted()
        assertEquals(SessionState.STOPPED, session.state.value) // only start(activity) from onStart reopens
        session.stop() // idempotent
        assertEquals(SessionState.STOPPED, session.state.value)
    }
}
