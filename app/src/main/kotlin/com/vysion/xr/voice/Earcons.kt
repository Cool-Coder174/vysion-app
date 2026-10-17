package com.vysion.xr.voice

import android.app.Application
import android.util.Log
import com.vysion.xr.core.Earcon
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** What [AndroidSpeaker] and [SpeechCommandBus] play through: [Earcons] on the device, [NoOpEarconPlayer] in tests. */
interface EarconPlayer {
    fun play(kind: Earcon)
}

/** Silent player for unit tests and for consumers that have no audio output. */
object NoOpEarconPlayer : EarconPlayer {
    override fun play(kind: Earcon) = Unit
}

/**
 * One synthesized tone: a linear chirp from [startHz] to [endHz] over [durationMs], repeated [repeats] times
 * with [gapMs] of silence between repeats. No asset files anywhere (ADR-0003 keeps the APK small and
 * auditable); every earcon is a handful of numbers.
 */
data class Tone(
    val startHz: Int,
    val endHz: Int,
    val durationMs: Int,
    val repeats: Int = 1,
    val gapMs: Int = 0,
) {
    init {
        require(startHz > 0 && endHz > 0 && durationMs > 0 && repeats > 0 && gapMs >= 0) { "bad tone: $this" }
    }

    /** Total length including gaps. */
    val totalMs: Int get() = repeats * durationMs + (repeats - 1) * gapMs
}

/**
 * Pure PCM synthesis for [Tone]s: 16-bit mono at [SAMPLE_RATE_HZ], linear frequency sweep, 5 ms linear
 * fade in and out so there is no click. Unit-tested on the JVM; [Earcons.play] feeds the result to an
 * `AudioTrack` on the device.
 */
object EarconSynth {
    const val SAMPLE_RATE_HZ: Int = 22_050

    /** Peak amplitude as a fraction of `Short.MAX_VALUE`: loud enough over passthrough audio, never clipping. */
    const val AMPLITUDE: Double = 0.5
    const val FADE_MS: Int = 5

    fun pcm(tone: Tone, sampleRateHz: Int = SAMPLE_RATE_HZ): ShortArray {
        val toneSamples = sampleRateHz * tone.durationMs / 1000
        val gapSamples = sampleRateHz * tone.gapMs / 1000
        val out = ShortArray(sampleRateHz * tone.totalMs / 1000)
        val fadeSamples = min(sampleRateHz * FADE_MS / 1000, toneSamples / 2)
        var offset = 0
        repeat(tone.repeats) { r ->
            var phase = 0.0
            for (i in 0 until toneSamples) {
                val t = i.toDouble() / toneSamples
                val hz = tone.startHz + (tone.endHz - tone.startHz) * t
                phase += 2.0 * PI * hz / sampleRateHz
                val envelope = when {
                    i < fadeSamples -> i.toDouble() / fadeSamples
                    i >= toneSamples - fadeSamples -> (toneSamples - i).toDouble() / fadeSamples
                    else -> 1.0
                }
                val index = offset + i
                if (index < out.size) {
                    out[index] = (sin(phase) * envelope * AMPLITUDE * Short.MAX_VALUE).toInt().toShort()
                }
            }
            offset += toneSamples + if (r < tone.repeats - 1) gapSamples else 0
        }
        return out
    }
}

/**
 * The six earcons (SRS §8 R4 "earcons for listening, thinking, done, error"; [Earcon.PROXIMITY] is the
 * rising tone of FR-N1, ≤ 300 ms after the threshold crossing per `docs/nfr.md`; [Earcon.PING] is the
 * non-spatial fallback for A-6 when SceneCore's point source is unavailable). Owner R4, due Sat 15:00
 * with the `Speaker`. Implements [EarconPlayer]; [AndroidSpeaker] and [SpeechCommandBus] delegate to it.
 *
 * Device API: `android.media.AudioTrack` (PCM 16-bit mono, `USAGE_ASSISTANCE_SONIFICATION`,
 * `MODE_STATIC`). It is a platform API not listed in `docs/stack.md` §3-R4 (which covers
 * `TextToSpeech.playEarcon`, unusable here because `addEarcon` needs a resource file); treat the exact
 * constructor as `// stack.md: UNVERIFIED` and check the artifact per AGENTS.md §8 before relying on it.
 * The tones are constants; [EarconSynth.pcm] is real and tested; only [play] is a stub.
 */
class Earcons(private val app: Application) : EarconPlayer {

    override fun play(kind: Earcon) {
        val tone = toneFor(kind)
        // stack.md: UNVERIFIED (AudioTrack is not in the R4 cheat-sheet; constants below are from the platform docs)
        Log.w(
            TAG,
            "TODO(FR-N1) Earcons.play($kind): AudioTrack(AudioAttributes USAGE_ASSISTANCE_SONIFICATION, " +
                "ENCODING_PCM_16BIT mono ${EarconSynth.SAMPLE_RATE_HZ} Hz, MODE_STATIC) <- EarconSynth.pcm($tone); " +
                "play(); release() on completion; keep one track per kind to stay under the 300 ms proximity budget",
        )
    }

    companion object {
        private const val TAG = "vysion"

        /** Short high beep when the recognizer is ready for speech (SpeechRecognizer `onReadyForSpeech`). */
        val LISTENING: Tone = Tone(startHz = 880, endHz = 880, durationMs = 120)

        /** Two soft mid ticks; the controller repeats it every 4 s while INFERRING (NFR "never silent > 10 s"). */
        val THINKING: Tone = Tone(startHz = 660, endHz = 660, durationMs = 80, repeats = 2, gapMs = 80)

        /** One high flat note. */
        val DONE: Tone = Tone(startHz = 1046, endHz = 1046, durationMs = 150)

        /** Low long buzz. */
        val ERROR: Tone = Tone(startHz = 220, endHz = 220, durationMs = 300)

        /** FR-N1 "rising tone": the only sweep, so it is unmistakable. */
        val PROXIMITY: Tone = Tone(startHz = 440, endHz = 880, durationMs = 300)

        /** Very short, very high tick for the 3 s guidance ping (FR-N3, A-6 fallback). */
        val PING: Tone = Tone(startHz = 1760, endHz = 1760, durationMs = 60)

        fun toneFor(kind: Earcon): Tone = when (kind) {
            Earcon.LISTENING -> LISTENING
            Earcon.THINKING -> THINKING
            Earcon.DONE -> DONE
            Earcon.ERROR -> ERROR
            Earcon.PROXIMITY -> PROXIMITY
            Earcon.PING -> PING
        }
    }
}
