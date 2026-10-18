package com.vysion.xr.voice

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.Speaker
import com.vysion.xr.core.SpeechProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * The thin device boundary under [AndroidSpeaker]: exactly the calls `android.speech.tts.TextToSpeech`
 * needs (`docs/stack.md` §3-R4), so the queue, interrupt and suspend logic above it is unit-tested with a
 * fake. [AndroidTtsPort] is the real one. Callbacks on [Listener] may arrive
 * on any thread ("can be called from multiple threads", stack.md); everything that receives them is
 * thread-safe.
 */
interface TtsPort {
    /** Create the engine; [Listener.onInit] reports readiness. Main thread. */
    fun initialize(listener: Listener)

    /**
     * Queue [text] under [utteranceId]; [flush] maps to `QUEUE_FLUSH`, else `QUEUE_ADD`. Returns `true`
     * when the engine accepted it (`TextToSpeech.SUCCESS`); on `false` the caller completes the utterance
     * itself and never waits for a callback.
     */
    fun speak(text: String, utteranceId: String, flush: Boolean): Boolean

    /** `TextToSpeech.stop()`: non-suspending; the engine answers with `onStop(id, interrupted = true)`. */
    fun stop()

    /** `setSpeechRate`; 1.0 normal, [Speaker.SLOWER_RATE] for "slower". */
    fun setRate(rate: Float)

    /**
     * Bind the best OFFLINE voice for [requested] (see [VoiceSelection]) and return the locale actually
     * bound, or `null` when no offline, installed voice exists for that language (A-8).
     */
    fun setLocale(requested: Locale): Locale?

    fun shutdown()

    interface Listener {
        fun onInit(ready: Boolean)
        fun onStart(utteranceId: String)
        fun onRangeStart(utteranceId: String, start: Int, end: Int)
        fun onDone(utteranceId: String)
        fun onError(utteranceId: String, errorCode: Int)
        fun onStop(utteranceId: String, interrupted: Boolean)
    }
}

/** One installed TTS voice, as [VoiceSelection] sees it (built from `android.speech.tts.Voice` on the device). */
data class VoiceCandidate(
    val name: String,
    val locale: Locale,
    /** `Voice.isNetworkConnectionRequired`: such voices are useless in airplane mode and never chosen. */
    val networkRequired: Boolean,
    /** `false` when `Voice.getFeatures()` contains `TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED`. */
    val installed: Boolean,
    /** `Voice.getQuality()`: 100 (very low) … 500 (very high). */
    val quality: Int,
)

/**
 * Pure voice-preference logic (A-8 / plan.md Q4: "es-MX preferred, es-ES fallback, en-US notice").
 * Offline and installed only; country preference first, then quality.
 */
object VoiceSelection {
    private val ES_ES: Locale = Locale.forLanguageTag("es-ES")

    /** Countries tried in order for a language; anything else of the same language comes after. */
    fun preferredLocales(requested: Locale): List<Locale> = when (requested.language) {
        "es" -> listOf(Speaker.ES_MX, ES_ES)
        else -> listOf(requested)
    }

    fun pick(voices: Collection<VoiceCandidate>, requested: Locale): VoiceCandidate? {
        val preferred = preferredLocales(requested)
        fun rank(candidate: VoiceCandidate): Int {
            val country = normalize(candidate.locale).country
            val index = preferred.indexOfFirst { it.country == country }
            return if (index < 0) preferred.size else index
        }
        return voices
            .filter { normalize(it.locale).language == requested.language && !it.networkRequired && it.installed }
            .sortedWith(compareBy<VoiceCandidate> { rank(it) }.thenByDescending { it.quality })
            .firstOrNull()
    }

    /**
     * Two-letter language and country, whatever the engine reports: Google's engine says "en-US", Samsung's SMT
     * engine on the Galaxy XR says ISO-3 "eng_USA" / "spa_ESP" (seen on the device 2026-10-17). Variants are dropped.
     */
    fun normalize(locale: Locale): Locale {
        val language = locale.language.let { if (it.length == 3) iso3Languages[it] ?: it else it }
        val country = locale.country.let { if (it.length == 3) iso3Countries[it] ?: it else it }
        if (language == locale.language && country == locale.country && locale.variant.isEmpty()) return locale
        return Locale.Builder().setLanguage(language).setRegion(country).build()
    }

    private val iso3Languages: Map<String, String> by lazy {
        Locale.getISOLanguages().mapNotNull { code ->
            runCatching { Locale.Builder().setLanguage(code).build().isO3Language to code }.getOrNull()
        }.toMap()
    }

    private val iso3Countries: Map<String, String> by lazy {
        Locale.getISOCountries().mapNotNull { code ->
            runCatching { Locale.Builder().setRegion(code).build().isO3Country to code }.getOrNull()
        }.toMap()
    }
}

/**
 * The real [TtsPort] over `android.speech.tts.TextToSpeech` (`docs/stack.md` §3-R4, all VERIFIED:
 * `TextToSpeech(Context, OnInitListener)`, `speak(CharSequence, queueMode, Bundle?, utteranceId)`,
 * `QUEUE_FLUSH` / `QUEUE_ADD`, `stop()`, `setSpeechRate`, `getVoices()` (null before init), `setVoice`,
 * `UtteranceProgressListener.onStart/onDone/onError/onStop/onRangeStart`). Construct on the main thread
 * (`AppGraph` does, from `Application.onCreate`). Owner R4, FR-G4. Uses the user's default engine (their
 * accessibility setup wins); [setLocale] picks an offline voice of that engine and logs the Spanish candidates
 * as A-8 evidence. Never throws: `say(Cues.welcome)` runs on the launch path (FR-G1).
 */
class AndroidTtsPort(private val app: Application) : TtsPort {

    @Volatile private var tts: TextToSpeech? = null

    @Volatile private var ready = false

    override fun initialize(listener: TtsPort.Listener) {
        val engine = runCatching {
            TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                Log.i(TAG, "tts init ready=$ready engine=${tts?.defaultEngine}")
                listener.onInit(ready)
            }
        }.onFailure { Log.w(TAG, "tts unavailable: ${it.message}") }.getOrNull() ?: run {
            listener.onInit(false)
            return
        }
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = listener.onStart(utteranceId)

                override fun onDone(utteranceId: String) = listener.onDone(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = listener.onError(utteranceId, TextToSpeech.ERROR)

                override fun onError(utteranceId: String, errorCode: Int) = listener.onError(utteranceId, errorCode)

                override fun onStop(utteranceId: String, interrupted: Boolean) = listener.onStop(utteranceId, interrupted)

                override fun onRangeStart(utteranceId: String, start: Int, end: Int, frame: Int) =
                    listener.onRangeStart(utteranceId, start, end)
            },
        )
        tts = engine
    }

    override fun speak(text: String, utteranceId: String, flush: Boolean): Boolean {
        val engine = tts?.takeIf { ready } ?: return false
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        return runCatching { engine.speak(text, mode, null, utteranceId) == TextToSpeech.SUCCESS }.getOrDefault(false)
    }

    override fun stop() {
        runCatching { tts?.stop() } // -> onStop(id, interrupted = true); INV-6
    }

    override fun setRate(rate: Float) {
        runCatching { tts?.setSpeechRate(rate) }
    }

    override fun setLocale(requested: Locale): Locale? {
        val engine = tts?.takeIf { ready } ?: return null
        val voices = runCatching { engine.voices }.getOrNull().orEmpty().toList()
        val candidates = voices.map {
            VoiceCandidate(
                name = it.name,
                locale = VoiceSelection.normalize(it.locale),
                networkRequired = it.isNetworkConnectionRequired,
                installed = it.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true,
                quality = it.quality,
            )
        }
        val pick = VoiceSelection.pick(candidates, requested)
        if (requested.language == SPANISH || pick == null) { // A-8 evidence: what this engine offers for the language
            val same = candidates.filter { it.locale.language == requested.language }
            Log.i(
                TAG,
                "A-8 ${requested.language} voices (${same.size}): " + same.joinToString { c ->
                    "${c.name} ${c.locale.toLanguageTag()} offline=${!c.networkRequired} installed=${c.installed} q=${c.quality}"
                },
            )
        }
        val voice = pick?.let { p -> voices.firstOrNull { it.name == p.name } }
        if (voice == null) {
            Log.i(TAG, "tts: no offline voice for ${requested.toLanguageTag()} among ${voices.size}")
            return null
        }
        val bound = runCatching { engine.setVoice(voice) == TextToSpeech.SUCCESS }.getOrDefault(false)
        val locale = VoiceSelection.normalize(voice.locale)
        Log.i(TAG, "tts voice ${voice.name} ${locale.toLanguageTag()} bound=$bound")
        return if (bound) locale else null
    }

    override fun shutdown() {
        val engine = tts ?: return
        tts = null
        ready = false
        runCatching {
            engine.stop()
            engine.shutdown()
        }
    }

    private companion object {
        const val TAG = "vysion"
        const val SPANISH = "es"
    }
}

/**
 * [Speaker] over Android `TextToSpeech` (FR-G4 "All speech output through Android TextToSpeech; 'stop'
 * interrupts within 300 ms"; FR-T3 repeat / slower; FR-G6 locale; SRS §8 R4 "TTS queue with
 * interrupt-on-stop, rate control, en-US and es-MX voices, earcons"). Owner R4, due Sat 15:00. Consumed by
 * R1's `ModeController` and transcript panel, and by R3 through R1. Constructor `(Application,
 * CoroutineScope)` is the `AppGraph` contract; the internal one takes a [TtsPort] so the tests drive it with
 * a fake.
 *
 * Plumbing (real, unit-tested):
 * - a [Channel] of capacity [QUEUE_CAPACITY] feeds one consumer loop; [say] suspends until the engine
 *   reports done / error / stop for that utterance (H-33: bounded queue; with one utterance speaking and
 *   four queued, a further caller waits in `send` and is not counted as queued);
 * - `interrupt = true` drops everything queued, interrupts the current utterance and queues with
 *   `QUEUE_FLUSH` (SRS §3 hazards before descriptions);
 * - [stop] is non-suspending (INV-6): it bumps a generation counter so anything queued before it is
 *   skipped, completes the current utterance, and calls [TtsPort.stop];
 * - a per-utterance watchdog ([utteranceTimeoutMs]) fails an utterance whose callbacks never arrive, so the
 *   controller's SPEAKING state cannot hang (NFR reliability: never silent > 10 s);
 * - [progress] mirrors `onStart` / `onRangeStart` / done for the transcript panel's auto-scroll;
 * - [release] is terminal and idempotent: it silences, closes the queue and shuts the engine down once;
 *   afterwards [say] returns at once and [stop] / [setRate] / [setLocale] never reach the port.
 *
 * Depends on A-8 (offline es voice) through [setLocale]'s fallback; A-7 is the bus's concern. All latency
 * is left to the caller's monotonic clock; nothing here reads a wall clock.
 */
class AndroidSpeaker internal constructor(
    private val port: TtsPort,
    private val earcons: EarconPlayer,
    private val scope: CoroutineScope,
) : Speaker {

    /** The `AppGraph` constructor (ADR-0008). Main thread: `TextToSpeech` is created here. */
    constructor(app: Application, scope: CoroutineScope) : this(AndroidTtsPort(app), Earcons(), scope)

    internal enum class Outcome { DONE, INTERRUPTED, FAILED }

    private class Utterance(
        val id: String,
        val text: String,
        val flush: Boolean,
        val generation: Int,
    ) {
        val outcome = CompletableDeferred<Outcome>()
        val lastCharEnd = AtomicInteger(0)
        fun complete(result: Outcome): Boolean = outcome.complete(result)
    }

    private val queue = Channel<Utterance>(QUEUE_CAPACITY)
    private val pending = AtomicInteger(0)

    /**
     * Utterances accepted into the bounded queue and not started yet, so never more than [QUEUE_CAPACITY]
     * (H-33); a caller back-pressured in [say] is not queued yet. For tests and the diagnostics overlay.
     * `say` counts an utterance after `send` returns, so the consumer may un-count it first; the clamp hides
     * that instant.
     */
    internal val queuedCount: Int get() = pending.get().coerceAtLeast(0)
    private val generation = AtomicInteger(0)
    private val current = AtomicReference<Utterance?>(null)
    private val nextId = AtomicLong(0)

    @Volatile private var ready = false

    /** Completes on the engine's first `onInit`; utterances that arrive earlier (the launch welcome) wait for it. */
    private val initialized = CompletableDeferred<Unit>()

    private val released = AtomicBoolean(false)

    @Volatile private var rate = 1.0f

    @Volatile private var lastText: String? = null

    private val _progress = MutableSharedFlow<SpeechProgress>(
        extraBufferCapacity = PROGRESS_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val progress: Flow<SpeechProgress> = _progress

    private val _locale = MutableStateFlow(Speaker.EN_US)
    override val locale: StateFlow<Locale> = _locale

    /** The locale the engine last bound; A-8's fallback returns here. en-US until a bind succeeds. */
    @Volatile private var boundLocale: Locale = Speaker.EN_US

    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking

    private val listener = object : TtsPort.Listener {
        override fun onInit(ready: Boolean) {
            if (released.get()) return // a late init after release must not wake a shut-down engine
            this@AndroidSpeaker.ready = ready
            if (ready) {
                port.setRate(rate)
                applyLocale(_locale.value)
            }
            initialized.complete(Unit) // a failed init releases the waiters too; port.speak then reports FAILED
        }

        override fun onStart(utteranceId: String) {
            forUtterance(utteranceId) { emitProgress(it, 0, 0, done = false) }
        }

        override fun onRangeStart(utteranceId: String, start: Int, end: Int) {
            forUtterance(utteranceId) {
                it.lastCharEnd.set(end)
                emitProgress(it, start, end, done = false)
            }
        }

        override fun onDone(utteranceId: String) {
            forUtterance(utteranceId) {
                emitProgress(it, it.text.length, it.text.length, done = true)
                it.complete(Outcome.DONE)
            }
        }

        override fun onError(utteranceId: String, errorCode: Int) {
            forUtterance(utteranceId) {
                emitProgress(it, it.lastCharEnd.get(), it.lastCharEnd.get(), done = true)
                it.complete(Outcome.FAILED)
            }
        }

        override fun onStop(utteranceId: String, interrupted: Boolean) {
            forUtterance(utteranceId) {
                emitProgress(it, it.lastCharEnd.get(), it.lastCharEnd.get(), done = true)
                it.complete(Outcome.INTERRUPTED)
            }
        }

        private fun forUtterance(id: String, block: (Utterance) -> Unit) {
            current.get()?.takeIf { it.id == id }?.let(block) // stale ids (after a flush) are ignored
        }
    }

    init {
        port.initialize(listener)
        scope.launch { for (utterance in queue) speakOne(utterance) }
    }

    override suspend fun say(text: String, interrupt: Boolean) = sayInternal(text, interrupt, remember = true)

    /** FR-G4: silence within [Speaker.STOP_LATENCY_BUDGET_MS]; never suspends or waits for the engine (INV-6). */
    override fun stop() {
        if (!released.get()) silence() // after release the engine is shut down and nothing is speaking
    }

    /**
     * `TextToSpeech.shutdown()`; `AppGraph.release()` calls this when the activity finishes. Terminal and
     * idempotent: silences (every waiting [say] returns), closes the queue, shuts the port down exactly once.
     */
    override fun release() {
        if (!released.compareAndSet(false, true)) return
        ready = false // later setRate / setLocale only remember their value
        silence()
        queue.close()
        port.shutdown()
    }

    override fun earcon(kind: Earcon) = earcons.play(kind)

    override fun setRate(rate: Float) {
        this.rate = rate
        if (ready) port.setRate(rate)
    }

    /**
     * FR-G6 / FR-T4. Before the engine is ready the request is remembered and applied in `onInit`. After
     * that, A-8: es-MX, then es-ES (the port's [VoiceSelection]); with no offline Spanish voice the locale
     * stays on the voice in use (en-US) and [Cues.spanishVoiceUnavailable] is spoken (see [applyLocale]).
     */
    override fun setLocale(locale: Locale) {
        if (!ready) {
            _locale.value = locale
            return
        }
        applyLocale(locale)
    }

    /** FR-T3: replays the last utterance at [rate] and restores the previous rate afterwards. */
    override suspend fun repeatLast(rate: Float) {
        val text = lastText
        if (text == null) {
            sayInternal(Cues.nothingToRepeat(_locale.value), interrupt = true, remember = false)
            return
        }
        val previous = this.rate
        setRate(rate)
        try {
            sayInternal(text, interrupt = true, remember = false)
        } finally {
            setRate(previous)
        }
    }

    private suspend fun sayInternal(text: String, interrupt: Boolean, remember: Boolean) {
        if (text.isBlank() || released.get()) return
        if (interrupt) silence() // FR-G4 / SRS §3: hazards first; drops ours, then QUEUE_FLUSH drops the engine's
        val utterance = Utterance(
            id = "vysion-${nextId.incrementAndGet()}",
            text = text,
            flush = interrupt,
            generation = generation.get(),
        )
        if (remember) lastText = text
        try {
            queue.send(utterance) // H-33: suspends while 4 are waiting
        } catch (_: ClosedSendChannelException) {
            return // released while this caller waited; nothing will speak again
        }
        pending.incrementAndGet() // counted once it is in the queue (see queuedCount)
        utterance.outcome.await()
    }

    private fun silence() {
        generation.incrementAndGet() // anything queued (or about to be) before this instant is stale
        drainQueue()
        current.get()?.complete(Outcome.INTERRUPTED) // the loop moves on without waiting for the engine
        _isSpeaking.value = false
        port.stop() // -> onStop(id, interrupted = true) for whatever the engine was saying
    }

    private fun drainQueue() {
        while (true) {
            val stale = queue.tryReceive().getOrNull() ?: break
            pending.decrementAndGet()
            stale.complete(Outcome.INTERRUPTED)
        }
    }

    private suspend fun speakOne(utterance: Utterance) {
        pending.decrementAndGet()
        if (utterance.generation != generation.get() || released.get()) { // a stop() / release() beat it there
            utterance.complete(Outcome.INTERRUPTED)
            return
        }
        current.set(utterance) // from here a stop() completes it
        if (!initialized.isCompleted) { // the engine binds asynchronously; the launch welcome must not be dropped
            withTimeoutOrNull(INIT_WAIT_MS) {
                select {
                    initialized.onAwait {}
                    utterance.outcome.onAwait {}
                }
            }
            if (utterance.outcome.isCompleted || utterance.generation != generation.get() || released.get()) {
                utterance.complete(Outcome.INTERRUPTED)
                current.compareAndSet(utterance, null)
                return
            }
        }
        _isSpeaking.value = true
        val accepted = port.speak(utterance.text, utterance.id, utterance.flush)
        when {
            !accepted -> {
                emitProgress(utterance, 0, 0, done = true)
                utterance.complete(Outcome.FAILED)
            }
            utterance.generation != generation.get() -> { // stop() landed between the check and speak()
                port.stop()
                utterance.complete(Outcome.INTERRUPTED)
            }
            else -> {
                val result = withTimeoutOrNull(utteranceTimeoutMs(utterance.text)) { utterance.outcome.await() }
                if (result == null) { // engine never called back: reliability guard, never hang SPEAKING
                    port.stop()
                    emitProgress(utterance, utterance.lastCharEnd.get(), utterance.lastCharEnd.get(), done = true)
                    utterance.complete(Outcome.FAILED)
                }
            }
        }
        current.compareAndSet(utterance, null)
        _isSpeaking.value = false
    }

    /**
     * A-8 / `docs/nfr.md` fault model "TTS voice for es-MX missing": the port binds es-MX, else es-ES
     * ([VoiceSelection]); with no offline voice for [requested] the locale returns to the last bound one
     * (en-US at first, also when the request was remembered before `onInit`) and, when Spanish was asked for
     * and the voice in use is not Spanish, [Cues.spanishVoiceUnavailable] is spoken in that voice's language.
     */
    private fun applyLocale(requested: Locale) {
        val bound = port.setLocale(requested)
        if (bound != null) {
            boundLocale = bound
            _locale.value = bound
            return
        }
        val fallback = boundLocale
        _locale.value = fallback
        if (requested.language == SPANISH && fallback.language != SPANISH) {
            scope.launch { sayInternal(Cues.spanishVoiceUnavailable(fallback), interrupt = false, remember = false) }
        }
    }

    private fun emitProgress(utterance: Utterance, start: Int, end: Int, done: Boolean) {
        _progress.tryEmit(SpeechProgress(utterance.id, utterance.text, start, end, done))
    }

    companion object {
        /** H-33: at most this many utterances wait behind the current one; a further [say] suspends. */
        const val QUEUE_CAPACITY: Int = 4

        /** Progress events are frequent (one per word); the panel only needs the latest. */
        const val PROGRESS_BUFFER: Int = 64

        /** Watchdog: base plus a per-character allowance (a 60-token read at 0.7x fits with margin). */
        const val UTTERANCE_TIMEOUT_BASE_MS: Long = 10_000
        const val UTTERANCE_TIMEOUT_PER_CHAR_MS: Long = 150

        /** How long an utterance waits for the engine to bind before it is spoken anyway (and fails if still unbound). */
        const val INIT_WAIT_MS: Long = 5_000

        fun utteranceTimeoutMs(text: String): Long =
            UTTERANCE_TIMEOUT_BASE_MS + text.length * UTTERANCE_TIMEOUT_PER_CHAR_MS

        private const val SPANISH = "es"
    }
}
