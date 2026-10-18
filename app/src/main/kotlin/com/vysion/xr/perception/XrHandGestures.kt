package com.vysion.xr.perception

import com.vysion.xr.core.HandGesture
import com.vysion.xr.core.HandGestureSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow

/**
 * [HandGestureSource] over hand tracking (`HandTrackingMode.BOTH`, `HAND_TRACKING` permission): every hand frame from
 * [PerceptionSource.hands] goes through one [HandGestureRecognizer] together with the latest head position, so palm
 * direction is judged relative to the wearer. Cold: one recognizer per collector (the app has one, `GestureCommands`).
 */
class XrHandGestures internal constructor(private val source: PerceptionSource) : HandGestureSource {

    /** The `AppGraph` constructor. */
    constructor(session: XrPerceptionSession) : this(session as PerceptionSource)

    override val gestures: Flow<HandGesture> = flow {
        val recognizer = HandGestureRecognizer()
        source.hands.collect { hand ->
            for (gesture in recognizer.onFrame(hand, source.headPose.value?.position)) emit(gesture)
        }
    }
}

/** Off the headset (no hand tracking): no gestures; voice and panels remain. */
class NoOpHandGestures : HandGestureSource {
    override val gestures: Flow<HandGesture> = emptyFlow()
}
