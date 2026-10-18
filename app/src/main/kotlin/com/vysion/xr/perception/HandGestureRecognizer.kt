package com.vysion.xr.perception

import com.vysion.xr.core.HandGesture

/**
 * Turns hand-joint frames into [HandGesture]s for people who cannot see the panels. Pure and JVM-tested
 * (`HandGestureRecognizerTest`); [XrHandGestures] feeds it from hand tracking.
 *
 * Rules, all chosen to stay clear of Galaxy XR's system gestures:
 * - The index finger is never used (the system's select and 2 s options pinch).
 * - Nothing fires while the palm faces the user (the system's Home pinch); a talk in progress ends instead.
 * - A pinch counts only when exactly one finger touches the thumb, so a fist does not trigger anything.
 * - Pinches use hysteresis ([PINCH_ON_M] / [PINCH_OFF_M]) so a trembling hand does not chatter.
 *
 * Gestures: middle-finger pinch held past [tapMaxNanos] → TALK_START, its release → TALK_END; a shorter middle
 * pinch → ACTIVATE; a ring-finger pinch shorter than [ringTapMaxNanos] → NEXT_PAGE; an open hand, palm facing away,
 * held [stopHoldNanos] → STOP (once, re-armed when the hand changes).
 */
internal class HandGestureRecognizer(
    private val tapMaxNanos: Long = 400_000_000L,
    private val ringTapMaxNanos: Long = 800_000_000L,
    private val stopHoldNanos: Long = 500_000_000L,
) {
    private class SideState {
        var middleDownAt: Long? = null
        var talking = false
        var ringDownAt: Long? = null
        var palmOutSince: Long? = null
        var stopFired = false
    }

    private val sides = HashMap<HandSide, SideState>()

    /** One frame of one hand; [head] is the head position in the same space as the joints (`null` if unknown). */
    fun onFrame(hand: HandObservation, head: Vec3?): List<HandGesture> {
        val st = sides.getOrPut(hand.side) { SideState() }
        val now = hand.timestampNanos
        val out = ArrayList<HandGesture>(1)

        if (hand.tracking != Tracking.TRACKING || hand.thumbTip == null) {
            endTalk(st, out)
            reset(st)
            return out
        }
        if (palmFacing(hand, head) == Facing.TOWARD_USER) { // the system's Home gesture owns this pose
            endTalk(st, out)
            reset(st)
            return out
        }

        val thumb = hand.thumbTip
        val middle = pinch(thumb, hand.middleTip, st.middleDownAt != null)
        val ring = pinch(thumb, hand.ringTip, st.ringDownAt != null)
        val index = hand.indexTip?.let { (it - thumb).length < PINCH_OFF_M } ?: false
        val little = hand.littleTip?.let { (it - thumb).length < PINCH_OFF_M } ?: false

        // Middle finger: exactly one finger on the thumb.
        val middleAlone = middle && !ring && !index && !little
        when {
            middleAlone && st.middleDownAt == null -> st.middleDownAt = now
            middleAlone && !st.talking && now - st.middleDownAt!! >= tapMaxNanos -> {
                st.talking = true
                out += HandGesture.TALK_START
            }
            !middle && st.middleDownAt != null -> {
                if (st.talking) out += HandGesture.TALK_END else if (now - st.middleDownAt!! < tapMaxNanos) out += HandGesture.ACTIVATE
                st.talking = false
                st.middleDownAt = null
            }
        }

        // Ring finger tap.
        val ringAlone = ring && !middle && !index && !little
        when {
            ringAlone && st.ringDownAt == null -> st.ringDownAt = now
            !ring && st.ringDownAt != null -> {
                if (now - st.ringDownAt!! <= ringTapMaxNanos) out += HandGesture.NEXT_PAGE
                st.ringDownAt = null
            }
        }

        // Palm out, open hand, held still: stop.
        val open = isOpen(hand)
        if (open && palmFacing(hand, head) == Facing.AWAY) {
            val since = st.palmOutSince ?: now.also { st.palmOutSince = it }
            if (!st.stopFired && now - since >= stopHoldNanos) {
                st.stopFired = true
                if (st.talking) {
                    out += HandGesture.TALK_END
                    st.talking = false
                }
                out += HandGesture.STOP
            }
        } else {
            st.palmOutSince = null
            st.stopFired = false
        }
        return out
    }

    private fun endTalk(st: SideState, out: MutableList<HandGesture>) {
        if (st.talking) out += HandGesture.TALK_END
    }

    private fun reset(st: SideState) {
        st.middleDownAt = null
        st.talking = false
        st.ringDownAt = null
        st.palmOutSince = null
        st.stopFired = false
    }

    /** Touching with hysteresis: closer than [PINCH_ON_M] to start, farther than [PINCH_OFF_M] to end. */
    private fun pinch(thumb: Vec3, tip: Vec3?, wasDown: Boolean): Boolean {
        tip ?: return false
        val d = (tip - thumb).length
        return if (wasDown) d < PINCH_OFF_M else d < PINCH_ON_M
    }

    /** All four fingertips far from the wrist and from the thumb: an open hand. */
    private fun isOpen(hand: HandObservation): Boolean {
        val wrist = hand.wrist ?: return false
        val thumb = hand.thumbTip ?: return false
        val tips = listOf(hand.indexTip, hand.middleTip, hand.ringTip, hand.littleTip)
        if (tips.any { it == null }) return false
        return tips.all { tip -> (tip!! - wrist).length >= OPEN_TIP_FROM_WRIST_M && (tip - thumb).length >= OPEN_TIP_FROM_THUMB_M }
    }

    enum class Facing { TOWARD_USER, AWAY, SIDEWAYS, UNKNOWN }

    /**
     * Which way the palm faces, from the wrist and the index and little knuckles. For a right hand,
     * (index − wrist) × (little − wrist) points out of the palm; for a left hand the cross product flips.
     */
    fun palmFacing(hand: HandObservation, head: Vec3?): Facing {
        val wrist = hand.wrist ?: return Facing.UNKNOWN
        val index = hand.indexProximal ?: return Facing.UNKNOWN
        val little = hand.littleProximal ?: return Facing.UNKNOWN
        head ?: return Facing.UNKNOWN
        val cross = (index - wrist).cross(little - wrist)
        val palm = (if (hand.side == HandSide.RIGHT) cross else cross * -1f).normalized()
        val toHead = (head - wrist).normalized()
        val d = palm.dot(toHead)
        return when {
            d > FACING_DOT -> Facing.TOWARD_USER
            d < -FACING_DOT -> Facing.AWAY
            else -> Facing.SIDEWAYS
        }
    }

    companion object {
        const val PINCH_ON_M = 0.022f
        const val PINCH_OFF_M = 0.035f
        const val OPEN_TIP_FROM_WRIST_M = 0.11f
        const val OPEN_TIP_FROM_THUMB_M = 0.05f
        const val FACING_DOT = 0.4f
    }
}
