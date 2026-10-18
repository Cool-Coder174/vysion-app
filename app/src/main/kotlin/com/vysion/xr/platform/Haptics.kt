package com.vysion.xr.platform

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

/**
 * The phone's touch feedback (Michelle Bui's designs, "Phone v6 · End-to-end", the "You feel" row of every note).
 * Every state has a sound, a spoken line and a haptic, so the screen is optional. One small family of patterns,
 * each with one meaning, like the earcons.
 */
enum class Haptic {
    /** "Light tap when you touch": a tap, a swipe up, a capture. */
    TOUCH,

    /** "A selection tick per mode, like a picker wheel." */
    SELECTION,

    /** "Medium thump when listening starts." */
    LISTEN_START,

    /** "Light tap at send." */
    LISTEN_SEND,

    /** "Success buzz when the answer starts." */
    ANSWER,

    /** "Success pattern: two quick taps" (arrived, all set). */
    SUCCESS,

    /** "Two soft taps": no text, didn't catch that, error. */
    SOFT_DOUBLE,

    /** "Double tick" on stop. */
    STOP,

    /** "Three strong pulses": an obstacle. */
    WARNING,

    /** "Faint pulse with each tick" while thinking or looking. */
    PULSE,
}

/**
 * One haptic as a waveform: [timings] alternate off / on in milliseconds (starting with off, as
 * `VibrationEffect.createWaveform` expects) and [amplitudes] are 0..255 per segment. Pure, unit-tested.
 */
data class HapticWave(val timings: LongArray, val amplitudes: IntArray) {
    val totalMs: Long get() = timings.sum()

    override fun equals(other: Any?): Boolean =
        other is HapticWave && timings.contentEquals(other.timings) && amplitudes.contentEquals(other.amplitudes)

    override fun hashCode(): Int = 31 * timings.contentHashCode() + amplitudes.contentHashCode()
}

object HapticPatterns {
    /** Faint, light, medium, strong: four levels are all a hand tells apart. */
    const val FAINT: Int = 50
    const val LIGHT: Int = 110
    const val MEDIUM: Int = 180
    const val STRONG: Int = 255

    fun wave(kind: Haptic): HapticWave = when (kind) {
        Haptic.TOUCH -> pulses(LIGHT, onMs = 12)
        Haptic.SELECTION -> pulses(MEDIUM, onMs = 8)
        Haptic.LISTEN_START -> pulses(MEDIUM, onMs = 40)
        Haptic.LISTEN_SEND -> pulses(LIGHT, onMs = 15)
        Haptic.ANSWER -> pulses(MEDIUM, onMs = 60)
        Haptic.SUCCESS -> pulses(MEDIUM, onMs = 20, count = 2, gapMs = 70)
        Haptic.SOFT_DOUBLE -> pulses(LIGHT, onMs = 25, count = 2, gapMs = 120)
        Haptic.STOP -> pulses(STRONG, onMs = 10, count = 2, gapMs = 60)
        Haptic.WARNING -> pulses(STRONG, onMs = 90, count = 3, gapMs = 80)
        Haptic.PULSE -> pulses(FAINT, onMs = 20)
    }

    private fun pulses(amplitude: Int, onMs: Long, count: Int = 1, gapMs: Long = 0): HapticWave {
        val timings = ArrayList<Long>()
        val amplitudes = ArrayList<Int>()
        repeat(count) { i ->
            timings += if (i == 0) 0L else gapMs
            amplitudes += 0
            timings += onMs
            amplitudes += amplitude
        }
        return HapticWave(timings.toLongArray(), amplitudes.toIntArray())
    }
}

/** Plays a [Haptic]. The phone shell holds one; tests and the headset use [None]. */
fun interface Haptics {
    fun play(kind: Haptic)

    object None : Haptics {
        override fun play(kind: Haptic) = Unit
    }
}

/**
 * The S25 Ultra's vibrator. Amplitude control is checked once; without it every pattern keeps its rhythm at the
 * device's one strength, so a double tick still reads as two. A failure is logged and never thrown: touch feedback
 * is a twin of the sound and the voice, never the only signal.
 */
class AndroidHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? = runCatching {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    }.getOrNull()?.takeIf { it.hasVibrator() }

    private val amplitudeControl = vibrator?.hasAmplitudeControl() == true

    override fun play(kind: Haptic) {
        val v = vibrator ?: return
        val wave = HapticPatterns.wave(kind)
        runCatching {
            val effect = if (amplitudeControl) {
                VibrationEffect.createWaveform(wave.timings, wave.amplitudes, NO_REPEAT)
            } else {
                VibrationEffect.createWaveform(wave.timings, NO_REPEAT)
            }
            v.vibrate(effect)
        }.onFailure { Log.w(TAG, "haptic $kind failed: ${it.message}") }
    }

    private companion object {
        const val NO_REPEAT = -1
        const val TAG = "vysion"
    }
}
