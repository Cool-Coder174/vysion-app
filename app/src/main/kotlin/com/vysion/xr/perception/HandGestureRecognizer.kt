package com.vysion.xr.perception

import com.vysion.xr.core.HandGesture

/**
 * Turns hand-joint frames into [HandGesture]s for people who cannot see the panels. Pure and JVM-tested
 * (`HandGestureRecognizerTest`); [XrHandGestures] feeds it from hand tracking.
 *
 * Rules, all chosen to stay clear of Galaxy XR's system gestures:
 * - The index finger is never used (the system's select and 2 s options pinch).
 * - No pinch fires while the palm faces the user (the system's Home pinch); a talk in progress ends instead. Stop is
 *   not a pinch, so it works with the palm any way round.
 * - A pinch counts only when exactly one finger touches the thumb, so a fist does not trigger anything.
 * - Pinches use hysteresis ([PINCH_ON_M] / [PINCH_OFF_M]) so a trembling hand does not chatter.
 *
 * Gestures: middle-finger pinch held past [tapMaxNanos] → TALK_START, its release → TALK_END; a shorter middle
 * pinch → ACTIVATE; a ring-finger pinch shorter than [ringTapMaxNanos] → NEXT_PAGE (each at most once per
 * [repeatGapNanos], so a bouncing tap counts once); "hang loose" (thumb and little finger out, index, middle and ring
 * curled) held [stopHoldNanos] → STOP (once, re-armed when the hand changes). Stop used to be an open palm held out,
 * which fired while the wearer carried things (worn test, 2026-10-18); nobody holds a cup or a phone in this pose.
 */
internal class HandGestureRecognizer(
    private val tapMaxNanos: Long = 400_000_000L,
    private val ringTapMaxNanos: Long = 800_000_000L,
    private val stopHoldNanos: Long = 500_000_000L,
    private val repeatGapNanos: Long = 400_000_000L,
) {
    private class SideState {
        var middleDownAt: Long? = null
        var talking = false
        var ringDownAt: Long? = null
        var stopPoseSince: Long? = null
        var stopFired = false
    }

    private val sides = HashMap<HandSide, SideState>()

    /**
     * Last ACTIVATE / NEXT_PAGE, either hand. A second tap closer than [repeatGapNanos] is dropped: on the worn
     * headset one middle-finger tap fired ACTIVATE twice 233 ms apart (a release-and-touch bounce).
     */
    private var lastTapNanos = HashMap<HandGesture, Long>()

    private fun tap(gesture: HandGesture, now: Long, out: MutableList<HandGesture>) {
        val last = lastTapNanos[gesture]
        if (last != null && now - last < repeatGapNanos) return
        lastTapNanos[gesture] = now
        out += gesture
    }

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
        // Stop first, and in any palm direction: hang loose is not a pinch, so it cannot be the system's Home gesture.
        if (isHangLoose(hand)) {
            val since = st.stopPoseSince ?: now.also { st.stopPoseSince = it }
            if (!st.stopFired && now - since >= stopHoldNanos) {
                st.stopFired = true
                endTalk(st, out)
                st.talking = false
                st.middleDownAt = null
                out += HandGesture.STOP
            }
            return out
        }
        st.stopPoseSince = null
        st.stopFired = false
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
                if (st.talking) out += HandGesture.TALK_END else if (now - st.middleDownAt!! < tapMaxNanos) tap(HandGesture.ACTIVATE, now, out)
                st.talking = false
                st.middleDownAt = null
            }
        }

        // Ring finger tap.
        val ringAlone = ring && !middle && !index && !little
        when {
            ringAlone && st.ringDownAt == null -> st.ringDownAt = now
            !ring && st.ringDownAt != null -> {
                if (now - st.ringDownAt!! <= ringTapMaxNanos) tap(HandGesture.NEXT_PAGE, now, out)
                st.ringDownAt = null
            }
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
        st.stopPoseSince = null
        st.stopFired = false
    }

    /** Touching with hysteresis: closer than [PINCH_ON_M] to start, farther than [PINCH_OFF_M] to end. */
    private fun pinch(thumb: Vec3, tip: Vec3?, wasDown: Boolean): Boolean {
        tip ?: return false
        val d = (tip - thumb).length
        return if (wasDown) d < PINCH_OFF_M else d < PINCH_ON_M
    }

    /**
     * "Hang loose": index, middle and ring fingertips curled in near the wrist, the little finger stretched out, and the
     * thumb stretched away from the index knuckle (in a fist it rests against the fingers).
     */
    internal fun isHangLoose(hand: HandObservation): Boolean {
        val wrist = hand.wrist ?: return false
        val thumb = hand.thumbTip ?: return false
        val knuckle = hand.indexProximal ?: return false
        val curled = listOf(hand.indexTip, hand.middleTip, hand.ringTip)
        if (curled.any { it == null || (it - wrist).length > CURLED_TIP_FROM_WRIST_M }) return false
        val little = hand.littleTip ?: return false
        return (little - wrist).length >= LITTLE_OUT_FROM_WRIST_M && (thumb - knuckle).length >= THUMB_OUT_FROM_KNUCKLE_M
    }

    /** One line for threshold tuning: thumb-to-finger distances in cm and the palm direction. */
    fun describe(hand: HandObservation, head: Vec3?): String {
        val thumb = hand.thumbTip ?: return "${hand.side} ${hand.tracking} no thumb"
        fun cm(tip: Vec3?) = tip?.let { "%.1f".format((it - thumb).length * 100f) } ?: "-"
        return "${hand.side} ${hand.tracking} index=${cm(hand.indexTip)} middle=${cm(hand.middleTip)} " +
            "ring=${cm(hand.ringTip)} little=${cm(hand.littleTip)} cm palm=${palmFacing(hand, head)} hangLoose=${isHangLoose(hand)}"
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
        /** Hang loose: index, middle and ring tips this close to the wrist count as curled (a fist is ~7-9 cm). */
        const val CURLED_TIP_FROM_WRIST_M = 0.095f

        /** Hang loose: the little fingertip at least this far from the wrist (stretched ~13-15 cm, curled ~7 cm). */
        const val LITTLE_OUT_FROM_WRIST_M = 0.115f

        /** Hang loose: the thumb tip at least this far from the index knuckle (resting on a fist it is ~4 cm). */
        const val THUMB_OUT_FROM_KNUCKLE_M = 0.065f
        const val FACING_DOT = 0.4f
    }
}
