package com.vysion.xr.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder // privacy-allow: only its AudioSource constant; audio stays in memory (ADR-0011)
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * [RecognizerPort] that hears commands on the headset: `AudioRecord` at 16 kHz mono while the wearer talks, Silero
 * VAD to end the clip on silence, Whisper-tiny ([WhisperTranscriber]) for the words (ADR-0011). Owner R4, FR-G2.
 * The text goes to the bus listener, so [CommandGrammar.parse] decides the command exactly as before.
 *
 * A clip ends when the wearer lets go of the pinch or taps Done ([stopListening]), when the VAD hears
 * [SpeechEndpointer.TRAILING_SILENCE_MS] of silence after speech (the Talk button needs no Done), or at [MAX_CLIP_MS],
 * kept under the bus's 8 s cap so a long sentence is still transcribed. A clip in which the VAD heard no speech is
 * not transcribed (Whisper invents words for silence) and ends like the system recognizer's speech timeout.
 *
 * [fallback] (the system recognizer, [AndroidRecognizerPort]) takes a session when Whisper is unavailable: its
 * files are missing, it failed to load, or it failed [WhisperTranscriber.MAX_FAILURES] times in a row; also for one
 * session when the microphone cannot be opened.
 *
 * Threads: the API runs on the main thread (like `SpeechRecognizer`); each clip is recorded on its own
 * "vysion-mic" thread and transcribed on "vysion-asr"; every listener call is posted back to the main thread and
 * dropped when the session was cancelled or replaced.
 */
class WhisperRecognizerPort(
    private val app: Application,
    private val transcriber: WhisperTranscriber,
    private val fallback: RecognizerPort,
) : RecognizerPort {

    /**
     * `true`: Whisper loads in the background and the fallback covers a failed load. If neither works, the session
     * ends with [RecognizerPort.ERROR_LANGUAGE_UNAVAILABLE] and the bus turns UNAVAILABLE.
     */
    override val onDeviceAvailable: Boolean = true

    private val main = Handler(Looper.getMainLooper())

    /** The live Whisper session; `null` while idle or while [fallback] runs the session. */
    private var current: Session? = null
    private var usingFallback = false

    override fun startListening(languageTag: String, listener: RecognizerPort.Listener) = onMain {
        current?.cancel() // a new session replaces one still transcribing; its result is dropped
        current = null
        if (transcriber.state == WhisperTranscriber.State.FAILED) {
            useFallback(languageTag, listener)
            return@onMain
        }
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            listener.onError(RecognizerPort.ERROR_INSUFFICIENT_PERMISSIONS)
            return@onMain
        }
        usingFallback = false
        val session = Session(languageTag, listener)
        if (session.start()) {
            current = session
        } else {
            useFallback(languageTag, listener) // the microphone did not open for us; let the system recognizer try
        }
    }

    private fun useFallback(languageTag: String, listener: RecognizerPort.Listener) {
        usingFallback = true
        fallback.startListening(languageTag, listener)
    }

    override fun stopListening() = onMain {
        if (usingFallback) fallback.stopListening() else current?.finish(EndReason.RELEASE)
    }

    override fun cancel() = onMain {
        if (usingFallback) {
            fallback.cancel()
        } else {
            current?.cancel()
            current = null
        }
    }

    override fun destroy() = onMain {
        current?.cancel()
        current = null
        fallback.destroy()
        transcriber.close()
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private enum class EndReason(val wire: String) { RELEASE("release"), SILENCE("silence"), CAP("cap") }

    /** One clip: record, end, transcribe, report. */
    private inner class Session(val languageTag: String, val listener: RecognizerPort.Listener) {
        @Volatile private var cancelled = false

        @Volatile private var endReason: EndReason? = null

        /** `elapsedRealtimeNanos` when the wearer let go or tapped Done; the VAD's end otherwise. */
        @Volatile private var endedAtNanos = 0L

        private var record: AudioRecord? = null

        /** Opens the microphone and starts the recording thread; `false` when the microphone did not open. */
        @SuppressLint("MissingPermission") // RECORD_AUDIO is checked in startListening, just before
        fun start(): Boolean {
            val minBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
            val r = runCatching {
                AudioRecord(SOURCE, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBytes, 8 * FRAME * 2))
            }.getOrNull()
            if (r == null || r.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "asr: microphone did not open (min buffer $minBytes); system recognizer for this session")
                r?.release()
                return false
            }
            if (runCatching { r.startRecording() }.isFailure || r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "asr: microphone did not start; system recognizer for this session")
                r.release()
                return false
            }
            record = r
            Thread({ record(r) }, "vysion-mic").start()
            listener.onReadyForSpeech() // the LISTENING earcon; the VAD ignores its first frames
            return true
        }

        fun finish(reason: EndReason) {
            if (endReason != null) return
            endedAtNanos = SystemClock.elapsedRealtimeNanos()
            endReason = reason
        }

        fun cancel() {
            cancelled = true
            finish(EndReason.RELEASE)
        }

        private fun record(r: AudioRecord) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val pcm = ShortArray(MAX_CLIP_MS * SAMPLE_RATE / 1000)
            val vad = transcriber.vad?.also { it.reset() }
            val endpointer = SpeechEndpointer(FRAME_MS)
            var n = 0
            var vadMs = 0L
            var readError = 0
            try {
                while (endReason == null && n + FRAME <= pcm.size) {
                    val got = r.read(pcm, n, FRAME, AudioRecord.READ_BLOCKING)
                    if (got < 0) {
                        readError = got
                        break
                    }
                    n += got
                    if (vad == null || got != FRAME) continue // READ_BLOCKING returns whole frames; only those feed the VAD
                    val v0 = SystemClock.elapsedRealtimeNanos()
                    val decision = endpointer.feed(vad.probability(pcm, n - FRAME))
                    vadMs += (SystemClock.elapsedRealtimeNanos() - v0) / 1_000_000
                    if (decision != SpeechEndpointer.Decision.CONTINUE) finish(EndReason.SILENCE)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "asr: recording failed: ${t.javaClass.simpleName} ${t.message}")
                readError = SpeechRecognizer.ERROR_AUDIO
            } finally {
                runCatching { r.stop() }
                r.release()
            }
            finish(EndReason.CAP) // no-op unless the clip filled up
            if (cancelled) return
            val reason = endReason ?: EndReason.CAP
            val clipMs = n * 1000L / SAMPLE_RATE
            // What the VAD did, for tuning on the headset: timings and decisions only, never audio or words.
            Log.i(TAG, "asr clip end=${reason.wire} clip_ms=$clipMs speech=${vad?.let { endpointer.heardSpeech } ?: "no-vad"} vad_ms=$vadMs")
            if (readError != 0) {
                post { listener.onError(SpeechRecognizer.ERROR_AUDIO) }
                return
            }
            if (vad != null && !endpointer.heardSpeech) {
                post { listener.onError(RecognizerPort.ERROR_SPEECH_TIMEOUT) } // nothing said: quiet end, no Whisper
                return
            }
            // Speech ended: for the wearer's release or Done that moment; for the VAD, the last speech frame.
            val speechEndNanos = if (reason == EndReason.SILENCE && endpointer.lastSpeechEndMs >= 0) {
                endedAtNanos - (endpointer.elapsedMs - endpointer.lastSpeechEndMs) * 1_000_000L
            } else {
                endedAtNanos
            }
            post { listener.onEndOfSpeech() } // the bus shows "thinking" and stops its 8 s cap
            transcriber.transcribe(pcm, n, languageTag) { text ->
                post {
                    when {
                        text == null -> listener.onError(SpeechRecognizer.ERROR_CLIENT)
                        text.isEmpty() -> listener.onError(RecognizerPort.ERROR_NO_MATCH)
                        else -> {
                            listener.onResults(listOf(text))
                            val command = CommandGrammar.parse(text, Locale.forLanguageTag(languageTag))
                            Log.i(
                                LATENCY_TAG,
                                "asr speech_end_to_command_ms=${(SystemClock.elapsedRealtimeNanos() - speechEndNanos) / 1_000_000} " +
                                    "end=${reason.wire} clip_ms=$clipMs command=${command?.let { it::class.simpleName } ?: "none"}",
                            ) // the command's kind, never the words
                        }
                    }
                }
            }
        }

        /** Runs [block] on the main thread unless this session was cancelled or replaced meanwhile. */
        private fun post(block: () -> Unit) {
            main.post { if (!cancelled && current === this) block() }
        }
    }

    private companion object {
        const val TAG = "vysion"
        const val LATENCY_TAG = "vysion.latency"
        const val SAMPLE_RATE = WhisperTranscriber.SAMPLE_RATE_HZ
        /** Tuned for speech recognition: no automatic gain or noise suppression where the device honors it. */
        const val SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION // privacy-allow: a constant, nothing written

        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** One Silero window: 512 samples = 32 ms. */
        const val FRAME = SileroVad.WINDOW
        const val FRAME_MS = FRAME * 1000 / SAMPLE_RATE

        /** Under `SpeechCommandBus.LISTENING_CAP_MS` (8 s), so a clip that fills up is still transcribed. */
        const val MAX_CLIP_MS = 7_500
    }
}

/**
 * Ends a clip on silence from per-frame speech probabilities (Silero VAD). Pure, JVM-tested. Speech starts after
 * [MIN_SPEECH_MS] of frames at or above [START_THRESHOLD] and ends after [TRAILING_SILENCE_MS] below
 * [END_THRESHOLD] (hysteresis, as in Silero's own iterator). The first [IGNORE_FIRST_MS] are skipped: the LISTENING
 * earcon plays into the microphone. With no speech within [NO_SPEECH_TIMEOUT_MS] the clip ends unheard.
 */
class SpeechEndpointer(private val frameMs: Int) {

    enum class Decision { CONTINUE, END_OF_SPEECH, NO_SPEECH }

    /** `true` once speech started. */
    var heardSpeech: Boolean = false
        private set

    /** Milliseconds from the clip start to the end of the last speech frame; -1 before speech. */
    var lastSpeechEndMs: Int = -1
        private set

    /** Milliseconds of audio fed so far. */
    var elapsedMs: Int = 0
        private set

    private var speechRunMs = 0
    private var silenceMs = 0

    fun feed(probability: Float): Decision {
        elapsedMs += frameMs
        if (elapsedMs <= IGNORE_FIRST_MS) return Decision.CONTINUE
        if (!heardSpeech) {
            speechRunMs = if (probability >= START_THRESHOLD) speechRunMs + frameMs else 0
            if (speechRunMs >= MIN_SPEECH_MS) {
                heardSpeech = true
                lastSpeechEndMs = elapsedMs
                return Decision.CONTINUE
            }
            return if (elapsedMs >= NO_SPEECH_TIMEOUT_MS) Decision.NO_SPEECH else Decision.CONTINUE
        }
        if (probability >= END_THRESHOLD) {
            silenceMs = 0
            lastSpeechEndMs = elapsedMs
        } else {
            silenceMs += frameMs
        }
        return if (silenceMs >= TRAILING_SILENCE_MS) Decision.END_OF_SPEECH else Decision.CONTINUE
    }

    companion object {
        const val START_THRESHOLD: Float = 0.5f
        const val END_THRESHOLD: Float = 0.35f
        const val MIN_SPEECH_MS: Int = 96
        const val TRAILING_SILENCE_MS: Int = 640
        const val IGNORE_FIRST_MS: Int = 256
        const val NO_SPEECH_TIMEOUT_MS: Int = 5_000
    }
}
