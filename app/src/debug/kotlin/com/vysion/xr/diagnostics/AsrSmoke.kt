package com.vysion.xr.diagnostics

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import com.vysion.xr.VysionApplication
import com.vysion.xr.core.Command
import com.vysion.xr.core.Speaker
import com.vysion.xr.voice.CommandGrammar
import com.vysion.xr.voice.SpeechEndpointer
import com.vysion.xr.voice.WhisperTranscriber
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * DEBUG BUILDS ONLY. One voice-command clip through the path a live session takes, with nobody wearing the headset:
 * Silero VAD in 32 ms frames until it ends the clip on silence, Whisper on what the VAD kept, then
 * [CommandGrammar.parse]. The clip is a test fixture the developer pushed (16 kHz mono 16-bit WAV, e.g. made with
 * macOS `say` by `scripts/asr_clips.sh`), never the wearer's audio, so its words may be logged:
 *
 * ```
 * adb push en-read-this.wav en-read-this.txt /sdcard/Android/data/com.vysion.xr/files/smoke/
 * adb shell am broadcast -a com.vysion.xr.SMOKE -n com.vysion.xr/.diagnostics.ModelSmokeReceiver \
 *     --es model asr --es image en-read-this.wav
 * adb logcat -s vysion.smoke vysion.latency
 * ```
 *
 * The optional `<clip>.txt` holds the phrase that was said; its parse is the expected command, compared by kind
 * (`match=`; a follow-up question matches any follow-up question). Clips whose name starts with `es` run as with the
 * app in Spanish (after "in Spanish"): Whisper leans to Spanish and the grammar tries es-MX first.
 */
internal object AsrSmoke {
    private const val TAG = "vysion.smoke"
    private const val FRAME = 512
    private const val FRAME_MS = 32

    fun run(app: VysionApplication, clip: File) {
        val asr = app.graph.asr ?: throw IllegalStateException("no Whisper in this build (fakes)")
        val deadline = SystemClock.elapsedRealtime() + 20_000 // the broadcast that holds the process open lasts 60 s
        while (asr.state == WhisperTranscriber.State.LOADING && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        check(asr.state == WhisperTranscriber.State.READY) { "Whisper is ${asr.state}" }
        val pcm = WavClip.decode(clip.readBytes())
        val audioMs = pcm.size * 1000L / WhisperTranscriber.SAMPLE_RATE_HZ

        var samples = pcm.size
        var vadNote = "vad=none"
        val vad = asr.vad
        if (vad != null) {
            vad.reset()
            val endpointer = SpeechEndpointer(FRAME_MS)
            var decision = SpeechEndpointer.Decision.CONTINUE
            var n = 0
            val v0 = SystemClock.elapsedRealtimeNanos()
            while (n + FRAME <= pcm.size && decision == SpeechEndpointer.Decision.CONTINUE) {
                decision = endpointer.feed(vad.probability(pcm, n))
                n += FRAME
            }
            val vadMs = (SystemClock.elapsedRealtimeNanos() - v0) / 1_000_000
            samples = n
            vadNote = "speech=${endpointer.heardSpeech} vad_end=${decision.name.lowercase()} vad_end_ms=${endpointer.elapsedMs} " +
                "last_speech_ms=${endpointer.lastSpeechEndMs} vad_ms=$vadMs frames=${n / FRAME}"
            if (!endpointer.heardSpeech) {
                Log.i(TAG, "asr ${clip.name} audio_ms=$audioMs $vadNote -> not transcribed (no speech)")
                return
            }
        }

        val done = CountDownLatch(1)
        var text: String? = null
        val locale = if (clip.name.startsWith("es")) Speaker.ES_MX else Speaker.EN_US
        val t0 = SystemClock.elapsedRealtimeNanos()
        asr.transcribe(pcm, samples, locale.toLanguageTag()) {
            text = it
            done.countDown()
        }
        check(done.await(35, TimeUnit.SECONDS)) { "transcription timed out" }
        val transcribeMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000
        val heard = text?.let { CommandGrammar.parse(it, locale) }
        val said = File(clip.parentFile, clip.nameWithoutExtension + ".txt").takeIf { it.isFile }?.readText()?.trim()
        val expected = said?.let { CommandGrammar.parse(it, locale) }
        Log.i(
            TAG,
            "asr ${clip.name} audio_ms=$audioMs $vadNote clip_to_command_ms=$transcribeMs -> \"$text\" " +
                "=> ${name(heard)} expected=${name(expected)} match=${said != null && name(heard) == name(expected)}",
        )
    }

    /**
     * Plays [clip] through the headset's speaker 400 ms from now (after the LISTENING earcon), so a session opened by
     * `--es model listen` hears it through the real microphone path. A test fixture, never the wearer's audio.
     */
    fun playAfterEarcon(clip: File) {
        val pcm = WavClip.decode(clip.readBytes())
        Thread({
            Thread.sleep(400)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(WhisperTranscriber.SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Log.i(TAG, "listen: playing ${clip.name} (${pcm.size * 1000L / WhisperTranscriber.SAMPLE_RATE_HZ} ms) through the speaker")
            Thread.sleep(pcm.size * 1000L / WhisperTranscriber.SAMPLE_RATE_HZ + 200)
            track.release()
        }, "vysion-asr-play").start()
    }

    private fun name(command: Command?): String = when (command) {
        null -> "none"
        is Command.FollowUp -> "FollowUp"
        else -> command.toString()
    }
}

/** The samples of a 16-bit PCM mono WAV (other chunks skipped), such as the clips `scripts/asr_clips.sh` makes. */
internal object WavClip {
    fun decode(bytes: ByteArray): ShortArray {
        fun tag(at: Int) = String(bytes, at, 4, Charsets.US_ASCII)
        fun u16(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        require(bytes.size >= 12 && tag(0) == "RIFF" && tag(8) == "WAVE") { "not a WAV file" }
        var at = 12
        var pcmMono16 = false
        while (at + 8 <= bytes.size) {
            val size = u32(at + 4)
            val body = at + 8
            when (tag(at)) {
                "fmt " -> pcmMono16 = u16(body) == 1 && u16(body + 2) == 1 && u16(body + 14) == 16
                "data" -> {
                    require(pcmMono16) { "expected 16-bit PCM mono" }
                    val n = (minOf(size, (bytes.size - body).toLong()) / 2).toInt()
                    return ShortArray(n) { u16(body + 2 * it).toShort() }
                }
            }
            at = body + size.toInt() + (size.toInt() and 1)
        }
        throw IllegalArgumentException("no data chunk")
    }
}
