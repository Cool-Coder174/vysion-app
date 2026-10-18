package com.vysion.xr.perception

import com.vysion.xr.core.HandGesture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HandGestureRecognizerTest {

    private val head = Vec3(0f, 1.6f, 0f) // the wearer looks down -Z; the hand is 40 cm in front
    private val wrist = Vec3(0.1f, 1.3f, -0.4f)
    private val ms = 1_000_000L

    private enum class Touch { NONE, MIDDLE, RING, INDEX, FIST }

    /** A synthetic hand. [palmAway] false mirrors it so the palm faces the wearer. */
    private fun hand(
        tMs: Long,
        touch: Touch = Touch.NONE,
        open: Boolean = false,
        palmAway: Boolean = true,
        side: HandSide = HandSide.RIGHT,
        tracking: Tracking = Tracking.TRACKING,
    ): HandObservation {
        var sx = if (palmAway) 1f else -1f
        if (side == HandSide.LEFT) sx = -sx
        fun at(x: Float, y: Float, z: Float) = wrist + Vec3(x * sx, y, z)
        val thumb = at(-0.06f, 0.07f, -0.02f)
        val reach = if (open) 0.17f else 0.10f
        var index = at(-0.03f, reach, -0.03f)
        var middle = at(0f, reach, -0.03f)
        var ring = at(0.02f, reach - 0.005f, -0.03f)
        val little = at(0.035f, reach - 0.015f, -0.03f)
        val near = thumb + Vec3(0.005f, 0f, 0f)
        when (touch) {
            Touch.MIDDLE -> middle = near
            Touch.RING -> ring = near
            Touch.INDEX -> index = near
            Touch.FIST -> {
                middle = near
                ring = thumb + Vec3(0f, 0.005f, 0f)
            }
            Touch.NONE -> Unit
        }
        return HandObservation(
            side = side, tracking = tracking, thumbTip = thumb, indexTip = index, indexProximal = at(-0.03f, 0.09f, 0f),
            timestampNanos = tMs * ms, wrist = wrist, middleTip = middle, ringTip = ring, littleTip = little,
            littleProximal = at(0.03f, 0.08f, 0f),
        )
    }

    private fun run(r: HandGestureRecognizer, vararg frames: HandObservation): List<HandGesture> =
        frames.flatMap { r.onFrame(it, head) }

    @Test
    fun `palm direction is judged against the head for both hands`() {
        val r = HandGestureRecognizer()
        assertEquals(HandGestureRecognizer.Facing.AWAY, r.palmFacing(hand(0), head))
        assertEquals(HandGestureRecognizer.Facing.TOWARD_USER, r.palmFacing(hand(0, palmAway = false), head))
        assertEquals(HandGestureRecognizer.Facing.AWAY, r.palmFacing(hand(0, side = HandSide.LEFT), head))
        assertEquals(HandGestureRecognizer.Facing.TOWARD_USER, r.palmFacing(hand(0, side = HandSide.LEFT, palmAway = false), head))
        assertEquals(HandGestureRecognizer.Facing.UNKNOWN, r.palmFacing(hand(0), null))
    }

    @Test
    fun `a quick middle-finger tap activates the page`() {
        val out = run(HandGestureRecognizer(), hand(0), hand(50, Touch.MIDDLE), hand(200, Touch.MIDDLE), hand(250))
        assertEquals(listOf(HandGesture.ACTIVATE), out)
    }

    @Test
    fun `a held middle-finger pinch talks until it is released`() {
        val out = run(
            HandGestureRecognizer(),
            hand(0, Touch.MIDDLE), hand(300, Touch.MIDDLE), hand(450, Touch.MIDDLE), hand(1500, Touch.MIDDLE), hand(1600),
        )
        assertEquals(listOf(HandGesture.TALK_START, HandGesture.TALK_END), out)
    }

    @Test
    fun `a ring-finger tap moves to the next page`() {
        val out = run(HandGestureRecognizer(), hand(0, Touch.RING), hand(200, Touch.RING), hand(300))
        assertEquals(listOf(HandGesture.NEXT_PAGE), out)
    }

    @Test
    fun `nothing fires while the palm faces the wearer, which is the system's Home gesture`() {
        val out = run(
            HandGestureRecognizer(),
            hand(0, Touch.MIDDLE, palmAway = false), hand(600, Touch.MIDDLE, palmAway = false), hand(700, palmAway = false),
            hand(800, Touch.RING, palmAway = false), hand(900, palmAway = false),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `the index finger and a fist never fire`() {
        val out = run(
            HandGestureRecognizer(),
            hand(0, Touch.INDEX), hand(600, Touch.INDEX), hand(700),
            hand(800, Touch.FIST), hand(1500, Touch.FIST), hand(1600),
        )
        assertTrue(out.toString(), out.isEmpty())
    }

    @Test
    fun `an open palm held out stops once, and again after the hand changes`() {
        val r = HandGestureRecognizer()
        val first = run(r, hand(0, open = true), hand(300, open = true), hand(520, open = true), hand(900, open = true))
        assertEquals(listOf(HandGesture.STOP), first)
        val again = run(r, hand(1000), hand(1100, open = true), hand(1700, open = true))
        assertEquals(listOf(HandGesture.STOP), again)
    }

    @Test
    fun `losing tracking or turning the palm in ends a talk instead of leaving the mic open`() {
        val r = HandGestureRecognizer()
        assertEquals(listOf(HandGesture.TALK_START), run(r, hand(0, Touch.MIDDLE), hand(500, Touch.MIDDLE)))
        assertEquals(listOf(HandGesture.TALK_END), run(r, hand(600, Touch.MIDDLE, tracking = Tracking.PAUSED)))
        assertEquals(listOf(HandGesture.TALK_START), run(r, hand(700, Touch.MIDDLE), hand(1200, Touch.MIDDLE)))
        assertEquals(listOf(HandGesture.TALK_END), run(r, hand(1300, Touch.MIDDLE, palmAway = false)))
    }
}
