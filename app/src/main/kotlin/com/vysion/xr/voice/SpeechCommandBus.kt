package com.vysion.xr.voice

import android.app.Application
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
 * fake. [AndroidRecognizerPort] is the real one (stub bodies in the skeleton).
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
 * `EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM`, `EXTRA_LANGUAGE`, `EXTRA_PREFER_OFFLINE = true`,
 * `EXTRA_PARTIAL_RESULTS`; `RESULTS_RECOGNITION`; all methods on the main thread; `destroy()` mandatory;
 * `RECORD_AUDIO` required). Owner R4, FR-G2, due Sat 14:00 together with the A-7 check: the
 * availability line below IS that check (plan.md "create the on-device recognizer, airplane mode on, log
 * the result; fill A-7"). Everything else is a skeleton stub.
 */
class AndroidRecognizerPort(private val app: Application) : RecognizerPort {

    override val onDeviceAvailable: Boolean = runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }
        .getOrDefault(false)
        .also {
            Log.i(TAG, "A-7 on-device speech recognition available=$it (false -> RK-4: pinch/panel P0, voice P1)")
        }

    override fun startListening(languageTag: String, listener: RecognizerPort.Listener) {
        Log.w(
            TAG,
            "TODO(FR-G2) AndroidRecognizerPort.startListening: on MAIN, recognizer ?: " +
                "createOnDeviceSpeechRecognizer(app); setRecognitionListener(RecognitionListener forwarding " +
                "onReadyForSpeech / onResults(RESULTS_RECOGNITION) / onError to listener); " +
                "startListening(Intent(ACTION_RECOGNIZE_SPEECH) with LANGUAGE_MODEL_FREE_FORM, " +
                "EXTRA_LANGUAGE=$languageTag, EXTRA_PREFER_OFFLINE=true, EXTRA_PARTIAL_RESULTS=true) " +
                "(docs/stack.md §3-R4)",
        )
    }

    override fun stopListening() {
        Log.w(TAG, "TODO(FR-G2) AndroidRecognizerPort.stopListening: recognizer.stopListening() on MAIN")
    }

    override fun cancel() {
        Log.w(TAG, "TODO(FR-G2) AndroidRecognizerPort.cancel: recognizer.cancel() on MAIN (8 s listening cap)")
    }

    override fun destroy() {
        Log.w(TAG, "TODO(FR-G2) AndroidRecognizerPort.destroy: recognizer.destroy() on MAIN (mandatory)")
    }

    private companion object {
        const val TAG = "vysion"
    }
}

/**
 * [CommandBus] over on-device `SpeechRecognizer` (FR-G2 "Voice commands via on-device SpeechRecognizer,
 * started by pinch-and-hold or the panel mic button"; SRS §8 R4 "CommandBus (parsed commands as a flow)",
 * due Sat 15:00). Owner R4. Consumed by R1's `ModeController` ([commands], [state]) and panels
 * ([startListening], [inject]). Constructor `(Application, CoroutineScope)` is the `AppGraph` contract;
 * the internal one takes a [RecognizerPort] so tests drive it with a fake.
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

    /** The `AppGraph` constructor (ADR-0008). Main thread: `SpeechRecognizer` factories require it. */
    constructor(app: Application, scope: CoroutineScope) : this(AndroidRecognizerPort(app), Earcons(app), scope)

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

        private val UNAVAILABLE_ERRORS = setOf(
            RecognizerPort.ERROR_INSUFFICIENT_PERMISSIONS,
            RecognizerPort.ERROR_LANGUAGE_NOT_SUPPORTED,
            RecognizerPort.ERROR_LANGUAGE_UNAVAILABLE,
        )
    }
}
