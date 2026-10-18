package com.vysion.xr.perception

import android.util.Log
import com.vysion.xr.BuildConfig
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
        var lastProbeNanos = 0L
        source.hands.collect { hand ->
            val head = source.headPose.value?.position
            for (gesture in recognizer.onFrame(hand, head)) emit(gesture)
            // Threshold tuning on real hands (docs/xr-and-glasses.md): once a second, how close each finger came
            // to the thumb and which way the palm faced. Joint distances only; no image, no identity.
            if (BuildConfig.DEBUG && hand.timestampNanos - lastProbeNanos > PROBE_PERIOD_NANOS) {
                lastProbeNanos = hand.timestampNanos
                Log.d(TAG, recognizer.describe(hand, head))
            }
        }
    }

    private companion object {
        const val TAG = "vysion.gesture"
        const val PROBE_PERIOD_NANOS = 1_000_000_000L
    }
}

/** Off the headset (no hand tracking): no gestures; voice and panels remain. */
class NoOpHandGestures : HandGestureSource {
    override val gestures: Flow<HandGesture> = emptyFlow()
}
