package com.vysion.xr.voice

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandEvent
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * The thin device boundary under [SpeechCommandBus]: exactly what `android.speech.SpeechRecognizer` needs
 * (`docs/stack.md` §3-R4), so the state machine, the 8 s cap and the flow plumbing are unit-tested with a
 * fake. [WhisperRecognizerPort] (Whisper on the headset, ADR-0011) is the real one, with [AndroidRecognizerPort]
 * (the system recognizer) as its fallback.
 */
interface RecognizerPort {
    /**
     * `SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` (A-7, RK-4). Must never throw: it is read
     * in the `AppGraph` constructor. `false` means voice commands are [ListeningState.UNAVAILABLE] and
     * pinch-and-hold / panel buttons are the P0 path.
     */
    val onDeviceAvailable: Boolean

    /** Start one recognition session for [languageTag] ("en-US" / "es-MX"); results on [listener]. Main thread. */
    fun startListening(languageTag: String, listener: Listener)

    /** `stopListening()`: stop capturing, deliver what was heard. */
    fun stopListening()

    /** `cancel()`: drop the session, no results. */
    fun cancel()

    /** `destroy()` (mandatory per stack.md). */
    fun destroy()

    interface Listener {
        fun onReadyForSpeech()

        /**
         * The clip ended on its own (silence, or the clip filled up) and the words are being worked out: the bus
         * shows PROCESSING and stops its cap, so a slow transcription is not cancelled. Ports that deliver results
         * at the end of speech never call it.
         */
        fun onEndOfSpeech() = Unit

        /** `RESULTS_RECOGNITION`, most likely first. */
        fun onResults(hypotheses: List<String>)

        /** `SpeechRecognizer.ERROR_*` code. */
        fun onError(code: Int)
    }

    companion object {
        // SpeechRecognizer error codes, copied from docs/stack.md §3-R4 so the bus stays free of Android imports.
        const val ERROR_SPEECH_TIMEOUT: Int = 6
        const val ERROR_NO_MATCH: Int = 7
        const val ERROR_INSUFFICIENT_PERMISSIONS: Int = 9
        const val ERROR_LANGUAGE_NOT_SUPPORTED: Int = 12
        const val ERROR_LANGUAGE_UNAVAILABLE: Int = 13
    }
}

/**
 * The real [RecognizerPort] over `android.speech.SpeechRecognizer` (`docs/stack.md` §3-R4, VERIFIED:
 * `isOnDeviceRecognitionAvailable`, `createOnDeviceSpeechRecognizer` (API 31, throws
 * `UnsupportedOperationException` when unavailable), `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with
 * `EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM`, `EXTRA_LANGUAGE`, `EXTRA_PREFER_OFFLINE = true`;
 * `RESULTS_RECOGNITION`; `checkRecognitionSupport` (API 33) and `triggerModelDownload` (API 34); all methods on
 * the main thread; `destroy()` mandatory; `RECORD_AUDIO` required). Owner R4, FR-G2.
 *
 * Two recognizers, in order:
 * 1. the on-device one (`createOnDeviceSpeechRecognizer`): audio never leaves the headset;
 * 2. the system's default recognizer (Speech Services by Google on the Galaxy XR) with `EXTRA_PREFER_OFFLINE`, used
 *    once the first one fails to bind (ADR-0010 row "Voice commands"). On the worn headset every on-device session
 *    failed: SpeechRecognizer only logged "Bind to system recognition service failed with error 10"
 *    (ERROR_TOO_MANY_REQUESTS) and never called the listener, so voice commands were silently dead.
 *
 * A session that is not ready within [READY_TIMEOUT_MS] counts as a failed bind: the port switches to the next
 * recognizer and restarts the same session if the wearer is still talking. When neither binds twice in a row, the
 * session ends with [RecognizerPort.ERROR_LANGUAGE_UNAVAILABLE], the bus turns UNAVAILABLE and the controller says so.
 *
 * Every session biases recognition toward the command phrases (`EXTRA_BIASING_STRINGS`,
 * [CommandGrammar.biasingPhrases]), so short commands said in a noisy room are heard as commands.
 */
class AndroidRecognizerPort(private val app: Application) : RecognizerPort {

    private val onDevice: Boolean = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false)
    private val systemDefault: Boolean = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false)

    /** True when either recognizer exists; `false` makes voice commands UNAVAILABLE from the start. */
    override val onDeviceAvailable: Boolean = (onDevice || systemDefault).also {
        Log.i(TAG, "A-7 speech recognition on-device=$onDevice system=$systemDefault (neither -> RK-4: gestures/panel only)")
    }

    private val main = Handler(Looper.getMainLooper())

    /** Created on the first session, on the main thread; `null` again after [destroy] or a failed bind. */
    private var recognizer: SpeechRecognizer? = null
    private var recognizerIsOnDevice = false

    /** Set when the on-device recognizer failed to bind: the rest of this run uses the system recognizer. */
    private var onDeviceFailed = false

    /** Consecutive sessions that never became ready. */
    private var bindFailures = 0

    /** The live session's watchdog and whether the wearer already let go (then a failed bind is not retried). */
    private var watchdog: Runnable? = null
    private var stopRequested = false

    private val checkedLanguages = mutableSetOf<String>()

    override fun startListening(languageTag: String, listener: RecognizerPort.Listener) = onMain {
        stopRequested = false
        begin(languageTag, listener)
    }

    private fun begin(languageTag: String, listener: RecognizerPort.Listener) {
        val r = recognizer ?: create() ?: run {
            listener.onError(RecognizerPort.ERROR_LANGUAGE_UNAVAILABLE) // no recognizer at all: UNAVAILABLE
            return
        }
        val kind = if (recognizerIsOnDevice) "on-device" else "system"
        val intent = intent(languageTag)
        if (recognizerIsOnDevice && checkedLanguages.add(languageTag)) checkSupport(r, intent, languageTag)
        var answered = false
        fun settle(): Boolean {
            if (answered) return false
            answered = true
            watchdog?.let(main::removeCallbacks)
            watchdog = null
            return true
        }
        val guard = Runnable {
            if (!settle()) return@Runnable
            bindFailures++
            Log.w(TAG, "recognizer ($kind) not ready in $READY_TIMEOUT_MS ms: bind failed ($bindFailures in a row)")
            runCatching { r.destroy() }
            if (recognizer === r) recognizer = null
            if (recognizerIsOnDevice) onDeviceFailed = true
            when {
                bindFailures >= MAX_BIND_FAILURES -> listener.onError(RecognizerPort.ERROR_LANGUAGE_UNAVAILABLE)
                !stopRequested -> begin(languageTag, listener) // still talking: retry on the next recognizer now
                else -> listener.onError(ERROR_CLIENT)
            }
        }
        watchdog = guard
        r.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    main.removeCallbacks(guard) // bound: results or an error will follow
                    bindFailures = 0
                    Log.i(TAG, "recognizer ready ($kind, $languageTag)")
                    listener.onReadyForSpeech()
                }

                override fun onResults(results: Bundle?) {
                    if (!settle()) return
                    val hypotheses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                    Log.i(TAG, "recognizer results: ${hypotheses.size} hypotheses ($kind, $languageTag)") // never the words
                    listener.onResults(hypotheses)
                }

                override fun onError(error: Int) {
                    if (!settle()) return
                    Log.i(TAG, "recognizer error $error ($kind, $languageTag)")
                    listener.onError(error)
                }

                override fun onBeginningOfSpeech() = Unit

                override fun onRmsChanged(rmsdB: Float) = Unit

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() = Unit

                override fun onPartialResults(partialResults: Bundle?) = Unit

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            },
        )
        runCatching { r.startListening(intent) }
            .onSuccess { main.postDelayed(guard, READY_TIMEOUT_MS) }
            .onFailure {
                Log.w(TAG, "recognizer start failed ($kind): ${it.message}")
                if (settle()) listener.onError(ERROR_CLIENT)
            }
    }

    override fun stopListening() = onMain {
        stopRequested = true
        runCatching { recognizer?.stopListening() }
    }

    override fun cancel() = onMain {
        stopRequested = true
        watchdog?.let(main::removeCallbacks) // cancelled on purpose: no error sound for a bind that never came
        watchdog = null
        runCatching { recognizer?.cancel() }
    }

    override fun destroy() = onMain {
        watchdog?.let(main::removeCallbacks)
        watchdog = null
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun create(): SpeechRecognizer? {
        if (onDevice && !onDeviceFailed) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }
                .onFailure { Log.w(TAG, "on-device recognizer unavailable: ${it.message}") }
                .getOrNull()
                ?.let {
                    recognizer = it
                    recognizerIsOnDevice = true
                    return it
                }
        }
        if (!systemDefault) return null
        // network-free unless its offline pack is missing (EXTRA_PREFER_OFFLINE); ADR-0010 row "Voice commands".
        return runCatching { SpeechRecognizer.createSpeechRecognizer(app) }
            .onFailure { Log.w(TAG, "system recognizer unavailable: ${it.message}") }
            .getOrNull()
            ?.also {
                recognizer = it
                recognizerIsOnDevice = false
                Log.i(TAG, "using the system recognizer (offline preferred)")
            }
    }

    private fun intent(languageTag: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_RESULTS)
        putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(CommandGrammar.biasingPhrases(languageTag)))
    }

    /** Q12: log the installed on-device languages; request a supported pack that is not installed yet. */
    private fun checkSupport(r: SpeechRecognizer, intent: Intent, languageTag: String) {
        runCatching {
            r.checkRecognitionSupport(
                intent,
                app.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(recognitionSupport: RecognitionSupport) {
                        val installed = recognitionSupport.installedOnDeviceLanguages
                        val supported = recognitionSupport.supportedOnDeviceLanguages
                        Log.i(TAG, "A-7 on-device languages installed=$installed pending=${recognitionSupport.pendingOnDeviceLanguages}")
                        val wanted = languageTag.lowercase()
                        if (installed.none { it.lowercase() == wanted } && supported.any { it.lowercase() == wanted }) {
                            Log.i(TAG, "A-7 requesting the on-device $languageTag pack")
                            runCatching { r.triggerModelDownload(intent) }
                        }
                    }

                    override fun onError(error: Int) {
                        Log.i(TAG, "A-7 support check unavailable (error $error)")
                    }
                },
            )
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private companion object {
        const val TAG = "vysion"
        const val MAX_RESULTS = 5
        const val ERROR_CLIENT = SpeechRecognizer.ERROR_CLIENT

        /**
         * A bound recognizer reports ready within a few hundred ms, and a refused bind fails in a few ms; after this
         * long the session counts as a failed bind and the next recognizer takes over while the wearer still talks.
         */
        const val READY_TIMEOUT_MS = 1_500L
        const val MAX_BIND_FAILURES = 2
    }
}

/**
 * [CommandBus] over Whisper on the headset (ADR-0011) or `SpeechRecognizer` (FR-G2 "Voice commands via on-device
 * SpeechRecognizer, started by pinch-and-hold or the panel mic button"; SRS §8 R4 "CommandBus (parsed commands as a
 * flow)", due Sat 15:00). Owner R4. Consumed by R1's `ModeController` ([commands], [state]) and panels
 * ([startListening], [inject]). Constructor `(Application, CoroutineScope, WhisperTranscriber?)` is the `AppGraph`
 * contract; the internal one takes a [RecognizerPort] so tests drive it with a fake.
 *
 * Plumbing (real, unit-tested):
 * - [commands] is a `MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = DROP_OLDEST)` (H-33,
 *   plan.md "Command delivery": a stuck queue is worse than a dropped 9th command);
 * - [inject] emits immediately with the given source; this is how panels, pinch (R1) and tests speak;
 * - [startListening] opens one session with an [LISTENING_CAP_MS] cap (plan.md H-15), results go through
 *   [CommandGrammar.parse] and come out as `CommandEvent(cmd, VOICE, System.nanoTime())` (monotonic, H-21;
 *   the ≤ 1.5 s command-to-action NFR is measured from `recognizedAtNanos` by R1);
 * - [state] is [ListeningState.UNAVAILABLE] from construction when on-device recognition is absent
 *   (A-7 failed → RK-4: pinch/panel are P0; R1 speaks [Cues.voiceCommandsUnavailable] once per run, through
 *   `ModePhrasebook.voiceCommandsUnavailable`).
 *
 * The recognition language follows [setLocale] (`CommandBus.setLocale`; R1 calls it alongside
 * `Speaker.setLocale`) from the next session on; both languages parse regardless. [release] is terminal and
 * idempotent. Never throws from [startListening] / [stopListening].
 */
class SpeechCommandBus internal constructor(
    private val recognizer: RecognizerPort,
    private val earcons: EarconPlayer,
    private val scope: CoroutineScope,
) : CommandBus {

    /**
     * The `AppGraph` constructor (ADR-0008). Main thread: `SpeechRecognizer` factories require it. With [asr],
     * Whisper hears the commands and the system recognizer is the fallback (ADR-0011); without it, the system
     * recognizer alone (fakes, or a build without the Whisper files).
     */
    constructor(app: Application, scope: CoroutineScope, asr: WhisperTranscriber? = null) : this(
        asr?.let { WhisperRecognizerPort(app, it, AndroidRecognizerPort(app)) } ?: AndroidRecognizerPort(app),
        Earcons(),
        scope,
    )

    private val _commands = MutableSharedFlow<CommandEvent>(
        extraBufferCapacity = COMMAND_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val commands: Flow<CommandEvent> = _commands

    private val _state = MutableStateFlow(
        if (recognizer.onDeviceAvailable) ListeningState.IDLE else ListeningState.UNAVAILABLE,
    )
    override val state: StateFlow<ListeningState> = _state

    @Volatile private var locale: Locale = Speaker.EN_US

    @Volatile private var released = false

    private var capJob: Job? = null

    private val listener = object : RecognizerPort.Listener {
        override fun onReadyForSpeech() {
            earcons.play(Earcon.LISTENING) // the audio twin of the panel's "listening" state
        }

        override fun onEndOfSpeech() {
            if (_state.value != ListeningState.LISTENING) return
            capJob?.cancel() // the clip is over; the transcription may take longer than the cap leaves
            _state.value = ListeningState.PROCESSING
        }

        override fun onResults(hypotheses: List<String>) {
            capJob?.cancel()
            _state.value = ListeningState.PROCESSING
            val recognizedAt = System.nanoTime() // H-21: monotonic; R1 measures command → action from it
            val command = hypotheses.asSequence().mapNotNull { CommandGrammar.parse(it, locale) }.firstOrNull()
            if (command != null) {
                _commands.tryEmit(CommandEvent(command, CommandSource.VOICE, recognizedAt))
            } else {
                earcons.play(Earcon.ERROR)
            }
            _state.value = ListeningState.IDLE
        }

        override fun onError(code: Int) {
            capJob?.cancel()
            // Only a recognizer that cannot work at all demotes to UNAVAILABLE. A missing microphone permission
            // (ERROR_INSUFFICIENT_PERMISSIONS) is not that: the user can grant it a moment later (onboarding "Not now",
            // then the app's own dialog), and the port re-checks it on the next start, so the bus goes back to IDLE
            // (review 2026-10-18: it used to stay UNAVAILABLE for the whole process).
            _state.value = if (code in UNAVAILABLE_ERRORS) ListeningState.UNAVAILABLE else ListeningState.IDLE
            if (code != RecognizerPort.ERROR_SPEECH_TIMEOUT) earcons.play(Earcon.ERROR)
        }
    }

    /** FR-G2: one session per pinch or mic press. No-op while UNAVAILABLE or already LISTENING. */
    override fun startListening() {
        if (released) return // the recognizer is destroyed
        when (_state.value) {
            ListeningState.UNAVAILABLE -> return // A-7 failed; the panel/pinch path still works through inject()
            ListeningState.LISTENING -> return // idempotent; one session at a time
            else -> Unit
        }
        _state.value = ListeningState.LISTENING
        recognizer.startListening(locale.toLanguageTag(), listener)
        capJob?.cancel()
        capJob = scope.launch { // H-15: a session never outlives the cap
            delay(LISTENING_CAP_MS)
            if (_state.value == ListeningState.LISTENING) {
                recognizer.cancel()
                _state.value = ListeningState.IDLE
            }
        }
    }

    /** The toolbar's Cancel while LISTENING (designs page 10): end the session and deliver nothing. */
    override fun cancelListening() {
        if (_state.value != ListeningState.LISTENING) return
        capJob?.cancel()
        recognizer.cancel()
        _state.value = ListeningState.IDLE
    }

    /** Pinch released / mic button released: deliver what was heard. */
    override fun stopListening() {
        if (_state.value != ListeningState.LISTENING) return
        capJob?.cancel()
        _state.value = ListeningState.PROCESSING
        recognizer.stopListening()
    }

    /** Fully working: panels, pinch (R1) and tests emit through here. Never suspends, never drops below 8 pending. */
    override fun inject(command: Command, source: CommandSource) {
        _commands.tryEmit(CommandEvent(command, source, System.nanoTime()))
    }

    /** FR-G6: recognition language of the next session; a session already open keeps its language. */
    override fun setLocale(locale: Locale) {
        this.locale = locale
    }

    /**
     * `SpeechRecognizer.destroy()` (mandatory); `AppGraph.release()` calls it from `MainActivity.onDestroy`
     * when finishing. Terminal and idempotent: destroys once, ends an open session, and later
     * [startListening] calls are no-ops; [inject] keeps working.
     */
    override fun release() {
        if (released) return
        released = true
        capJob?.cancel()
        if (_state.value != ListeningState.UNAVAILABLE) _state.value = ListeningState.IDLE
        recognizer.destroy()
    }

    companion object {
        /** H-33: commands waiting for a busy collector; the 9th drops the oldest. */
        const val COMMAND_BUFFER: Int = 8

        /** plan.md H-15: a listening session is capped so a missed end-of-speech never leaves the mic open. */
        const val LISTENING_CAP_MS: Long = 8_000

        /** Errors that mean no session can ever succeed in this process; a permission error is retried instead. */
        private val UNAVAILABLE_ERRORS = setOf(
            RecognizerPort.ERROR_LANGUAGE_NOT_SUPPORTED,
            RecognizerPort.ERROR_LANGUAGE_UNAVAILABLE,
        )
    }
}
