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
