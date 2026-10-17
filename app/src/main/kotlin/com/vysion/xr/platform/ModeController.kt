package com.vysion.xr.platform

import android.graphics.Bitmap
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandEvent
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.HeadPoseSource
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModePhrasebook
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.NavigationEngine
import com.vysion.xr.core.NormalizedBox
import com.vysion.xr.core.OverlayRenderer
import com.vysion.xr.core.PromptSet
import com.vysion.xr.core.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The mode state machine (SRS §6; docs/architecture.md "ModeController state machine"; SRS §8 R1
 * "mode state machine", skeleton due Sat 13:00, full P0 Sat 15:00). Owner: R1. Constructor per
 * `AppGraph`: `(frames, engine, commands, speaker, navigation, anchors, headPose, overlay, scope)`,
 * plus the two seams [phrasebook] and [prompts] that default to unwired placeholders.
 *
 * Pure coroutine logic: no Android class is touched except `Bitmap` as a value passed through, so
 * `ModeControllerTest` runs it on the JVM against `FakeInferenceEngine` and in-test fakes.
 *
 * What it does:
 * - [start]: subscribes to the command bus, the listening state, navigation cues and detections;
 *   starts the frame source and warms the engine up; speaks the welcome (FR-G1).
 * - One-shot commands (Read FR-T1, Describe FR-D1, FindDoor FR-N3, arrival FR-N6): pause
 *   Navigation (ADR-0004) → THINKING earcon → `captureStill()` → `describe()` under the 15 s
 *   watchdog (INV-5) with a THINKING earcon every 4 s (NFR reliability: never silent > 10 s) →
 *   transcript → `Speaker.say` → resume Navigation. The still is recycled in `finally` (INV-9).
 *   Any failure speaks [ModePhrasebook.inferenceFailed] and returns to IDLE.
 * - Busy policy (plan.md Q3): a non-Stop command while CAPTURING/INFERRING is answered with
 *   [ModePhrasebook.busy] and dropped; while SPEAKING it preempts the speech.
 * - Stop (FR-G4, INV-6): a separate collector that never waits behind a command calls the
 *   non-suspending `Speaker.stop()` and cancels the in-flight command `Job`; nothing inside the
 *   engine's `Mutex` is involved.
 * - Door vote (FR-N3, RK-6): two stills, two `describe(door)` calls, [DoorVote] allowlist parse,
 *   agreement required, else [ModePhrasebook.doorNotFound].
 * - Mode changes are announced (FR-G3) and exposed on [mode] for the panel.
 *
 * Clocks: [commandLatencyNanos] uses `System.nanoTime()` only (H-21).
 * Assumptions: A-1 (frames), A-7 (voice; the panel buttons inject the same commands when it fails).
 */
class ModeController(
    private val frames: FrameSource,
    private val engine: InferenceEngine,
    private val commands: CommandBus,
    private val speaker: Speaker,
    private val navigation: NavigationEngine,
    private val anchors: AnchorStore,
    @Suppress("unused") private val headPose: HeadPoseSource, // FR-T5 (P1) "hold still" reads angularSpeedDegPerS
    private val overlay: OverlayRenderer,
    private val scope: CoroutineScope,
    private val phrasebook: ModePhrasebook = ModePhrasebook.Unwired,
    private val prompts: PromptSet = PromptSet.Unwired,
) {

    private val _state = MutableStateFlow(ModeState.IDLE)

    /** SRS §6 states. Navigation keeps running in the background while IDLE. */
    val state: StateFlow<ModeState> = _state

    private val _mode = MutableStateFlow<Mode?>(null)

    /** FR-G3: the active mode, `null` until the first command. Shown on the control panel. */
    val mode: StateFlow<Mode?> = _mode

    private val _transcript = MutableStateFlow<String?>(null)

    /** FR-T2: the last VLM text, for the transcript panel. `null` until the first result. */
    val transcript: StateFlow<String?> = _transcript

    private val _commandLatencyNanos = MutableStateFlow<Long?>(null)

    /** NFR latency "command recognized → action started" (≤ 1.5 s), measured per one-shot; `null` until measured. */
    val commandLatencyNanos: StateFlow<Long?> = _commandLatencyNanos

    private val _lastError = MutableStateFlow<Throwable?>(null)

    /** The last failure the controller recovered from, for `MainActivity` to log under `vysion.reliability`. */
    val lastError: StateFlow<Throwable?> = _lastError

    /** Lives from [start] to [stop]; owns every collector so [stop] cancels them in one call. */
    private var runScope: CoroutineScope? = null

    /** The in-flight one-shot, cancelled by Stop (INV-6). Read from two collectors, hence volatile. */
    @Volatile
    private var active: Job? = null

    /** FR-D3 (P1): "brief" / "detailed" choose the describe prompt variant for the next Describe. */
    @Volatile
    private var describeVariant: DescribeVariant = DescribeVariant.DEFAULT

    /** Idempotent. Subscribes, starts the frame source, warms the engine up, speaks the welcome (FR-G1). */
    fun start() {
        if (runScope != null) return
        val run = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        runScope = run

        // One upstream subscription, two consumers: the Stop lane must never wait behind a command (INV-6).
        val events: SharedFlow<CommandEvent> = commands.commands.shareIn(run, SharingStarted.Eagerly)
        run.launch { events.filter { it.command is Command.Stop }.collect { onStop() } }
        run.launch { events.filter { it.command !is Command.Stop }.collect { onCommand(it) } }
        run.launch { commands.state.collect { onListeningState(it) } }
        run.launch { navigation.cues.collect { onCue(it) } }
        run.launch { navigation.detections.collect { overlay.showDetections(it) } } // FR-N2
        run.launch { guarded { frames.start() } } // A-1; the real source logs a TODO until bound
        run.launch { guarded { engine.warmUp() } } // A-11; a failure is recorded, never a crash at launch

        launchOneShot(ModeState.SPEAKING) { say(phrasebook.welcome) } // FR-G1
    }

    /** Start-path rule: nothing thrown by a module may crash the skeleton app; cancellation still propagates. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e
        } catch (e: NotImplementedError) {
            _lastError.value = e
        }
    }

    /** Silences, cancels the in-flight command and every collector, stops Navigation and the frames. Never throws. */
    fun stop() {
        val run = runScope ?: return
        runScope = null
        speaker.stop()
        active?.cancel()
        active = null
        run.cancel()
        overlay.clear()
        _state.value = ModeState.IDLE
        // UNDISPATCHED + NonCancellable: completes even if the caller tears the graph down right after.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                runCatching { navigation.stop() }
                runCatching { frames.stop() }
            }
        }
    }

    /** FR-G1: spoken by `MainActivity` before the permission dialogs (`Cues.permissionExplanation`, R4). */
    fun speakPermissionExplanation(): Job = scope.launch { say(phrasebook.permissionExplanation) }

    /** FR-G1: spoken when camera or microphone is denied (`Cues.permissionDenied`, R4). */
    fun speakPermissionDenied(): Job = scope.launch { say(phrasebook.permissionDenied, interrupt = true) }

    // ── Command lanes ─────────────────────────────────────────────────────────────────────────────

    /** INV-6 / FR-G4: runs outside any inference; `Speaker.stop()` is non-suspending, so this is sub-millisecond. */
    private fun onStop() {
        speaker.stop()
        active?.cancel(CancellationException("Command.Stop"))
    }

    private suspend fun onCommand(event: CommandEvent) {
        val current = active
        if (current?.isActive == true) {
            when (_state.value) {
                // Busy policy (plan.md Q3): say why, drop the command; Stop stays available on its own lane.
                ModeState.CAPTURING, ModeState.INFERRING -> {
                    say(phrasebook.busy)
                    return
                }
                // SPEAKING / LISTENING: a new command preempts the current utterance (SRS §3 "interruptible").
                else -> {
                    speaker.stop()
                    current.cancelAndJoin()
                }
            }
        }
        when (val command = event.command) {
            Command.Read -> launchOneShot(ModeState.CAPTURING) {
                captureAndDescribe(Mode.TEXT_READER, prompts.read, event) // FR-T1
            }
            Command.Describe -> launchOneShot(ModeState.CAPTURING) {
                captureAndDescribe(Mode.AUDIO_DESCRIPTION, describePrompt(), event) // FR-D1
            }
            Command.GuideMe -> launchOneShot(ModeState.SPEAKING) { guideMe(event) } // FR-N1/FR-N2
            Command.FindDoor -> launchOneShot(ModeState.CAPTURING) { findDoor(event) } // FR-N3
            Command.Repeat -> launchOneShot(ModeState.SPEAKING) { speaker.repeatLast() } // FR-T3
            Command.Slower -> launchOneShot(ModeState.SPEAKING) { speaker.repeatLast(Speaker.SLOWER_RATE) } // FR-T3
            Command.InSpanish -> speaker.setLocale(Speaker.ES_MX) // FR-G6; FR-T4 re-prompt needs the retained still (P1, module doc)
            Command.Brief -> describeVariant = DescribeVariant.BRIEF // FR-D3 (P1)
            Command.Detailed -> describeVariant = DescribeVariant.DETAILED // FR-D3 (P1)
            is Command.FollowUp -> Unit // TODO(FR-D2): re-run describe on the retained still with prompts.followUp(question) (P1)
            Command.Next -> Unit // TODO(FR-T6): capture and read again without a new command (P2)
            is Command.SelectDoor -> Unit // TODO(FR-N7): choose among door candidates (P2)
            Command.Stop -> Unit // handled on its own lane
        }
    }

    /** FR-G2: the panel shows LISTENING while the bus listens, if nothing else is in progress. */
    private fun onListeningState(listening: ListeningState) {
        when (listening) {
            ListeningState.LISTENING -> _state.compareAndSet(ModeState.IDLE, ModeState.LISTENING)
            else -> _state.compareAndSet(ModeState.LISTENING, ModeState.IDLE)
        }
    }

    // ── One-shots ─────────────────────────────────────────────────────────────────────────────────

    /** Sets [initialState] before the job runs, so the busy policy sees it with no race, and restores IDLE after. */
    private fun launchOneShot(initialState: ModeState, block: suspend () -> Unit) {
        val run = runScope ?: return
        _state.value = initialState
        active = run.launch {
            try {
                block()
            } finally {
                withContext(NonCancellable) { _state.value = ModeState.IDLE }
            }
        }
    }

    /**
     * FR-T1 / FR-D1 / FR-N6: the capture → infer → speak flow shared by Read, Describe and the
     * arrival check. Text Reader target: first spoken word ≤ 6 s after capture; Describe ≤ 8 s.
     */
    private suspend fun captureAndDescribe(mode: Mode, prompt: String, event: CommandEvent?) {
        announceMode(mode) // FR-G3
        val wasRunning = pauseNavigation() // ADR-0004
        var still: Bitmap? = null
        try {
            _state.value = ModeState.CAPTURING
            event?.let { markActionStarted(it) }
            speaker.earcon(Earcon.THINKING)
            still = frames.captureStill() // INV-9: this controller owns it from here
            _state.value = ModeState.INFERRING
            val text = inferWithWatchdog(still, prompt)
            _transcript.value = text // FR-T2
            _state.value = ModeState.SPEAKING
            say(text)
        } catch (e: CancellationException) {
            throw e // Stop, or shutdown: nothing to say
        } catch (e: Exception) {
            speakFailure(e) // InferenceException, timeout (wrapped), anything else a module threw
        } catch (e: NotImplementedError) {
            speakFailure(e) // skeleton stubs (TODO(FR-xx)) must not crash the app
        } finally {
            withContext(NonCancellable) {
                still?.let { release(it) } // INV-9
                if (wasRunning) navigation.resume() // ADR-0004
            }
        }
    }

    /** FR-N3 / RK-6: two stills, two answers, agreement required; then anchor and guide. */
    private suspend fun findDoor(event: CommandEvent) {
        announceMode(Mode.NAVIGATION)
        if (!ensureNavigationRunning()) {
            _state.value = ModeState.SPEAKING
            say(phrasebook.navigationUnavailable) // ADR-0006
            return
        }
        val wasRunning = pauseNavigation()
        var first: Bitmap? = null
        var second: Bitmap? = null
        try {
            _state.value = ModeState.CAPTURING
            markActionStarted(event)
            speaker.earcon(Earcon.THINKING)
            first = frames.captureStill()
            second = frames.captureStill()
            _state.value = ModeState.INFERRING
            val a = DoorVote.parse(inferWithWatchdog(first, prompts.door))
            val b = DoorVote.parse(inferWithWatchdog(second, prompts.door))
            val bearing = DoorVote.agree(a, b)
            _state.value = ModeState.SPEAKING
            val anchor = bearing?.let { anchors.placeFromBearing(it) }
            if (anchor == null) {
                say(phrasebook.doorNotFound) // "I can't find a door, turn slowly"
                return
            }
            navigation.guideTo(anchor) // R3 emits DoorBearing every 3 s; spoken in [onCue]
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            speakFailure(e)
        } catch (e: NotImplementedError) {
            speakFailure(e)
        } finally {
            withContext(NonCancellable) {
                first?.let { release(it) }
                second?.let { release(it) }
                if (wasRunning) navigation.resume()
            }
        }
    }

    /** "Guide me": Navigation mode on; proximity + outlines run in the background (FR-N1, FR-N2). */
    private suspend fun guideMe(event: CommandEvent) {
        announceMode(Mode.NAVIGATION)
        markActionStarted(event)
        if (!ensureNavigationRunning()) say(phrasebook.navigationUnavailable) // ADR-0006 (S25 Ultra)
    }

    /** FR-N6 (P1): one ARRIVAL prompt, then the target is cleared. */
    private suspend fun arrival() {
        captureAndDescribe(Mode.NAVIGATION, prompts.arrival, event = null)
        runCatching { navigation.clearTarget() }
    }

    // ── Navigation cues ───────────────────────────────────────────────────────────────────────────

    /** R3 never builds strings: every cue goes through [ModePhrasebook.navigationCue] (INV-4). */
    private suspend fun onCue(cue: NavigationCue) {
        when (cue) {
            is NavigationCue.Proximity -> {
                speaker.earcon(Earcon.PROXIMITY) // FR-N1: rising tone, then the words; hazards first (SRS §3)
                phrasebook.navigationCue(cue)?.let { say(it, interrupt = true) }
            }
            is NavigationCue.DoorBearing -> {
                overlay.highlightDoor(DoorHighlight.boxFor(cue.clock, cue.inView)) // FR-N3/FR-N4
                phrasebook.navigationCue(cue)?.let { say(it) }
            }
            NavigationCue.Arrived -> {
                overlay.highlightDoor(null)
                phrasebook.navigationCue(cue)?.let { say(it) }
                if (active?.isActive != true) launchOneShot(ModeState.CAPTURING) { arrival() } // FR-N6
            }
            NavigationCue.TargetLost -> {
                overlay.highlightDoor(null)
                phrasebook.navigationCue(cue)?.let { say(it) }
            }
            is NavigationCue.WallAhead, is NavigationCue.FloorEnds -> {
                phrasebook.navigationCue(cue)?.let { say(it, interrupt = true) } // FR-N5: hazards first
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────────────

    /**
     * INV-5: `describe` under [InferenceEngine.VLM_TIMEOUT_MS]; a THINKING earcon every
     * [THINKING_EARCON_PERIOD_MS] keeps the app from being silent for more than 10 s. A blank answer
     * is a failure: nothing is spoken that was not seen (SRS §3 rule 5).
     */
    private suspend fun inferWithWatchdog(still: Bitmap, prompt: String): String = coroutineScope {
        val thinking = launch {
            while (isActive) {
                delay(THINKING_EARCON_PERIOD_MS)
                speaker.earcon(Earcon.THINKING)
            }
        }
        try {
            val text = try {
                withTimeout(InferenceEngine.VLM_TIMEOUT_MS) { engine.describe(still, prompt) }
            } catch (e: TimeoutCancellationException) {
                throw InferenceException("VLM exceeded ${InferenceEngine.VLM_TIMEOUT_MS} ms", e)
            }
            if (text.isBlank()) throw InferenceException("VLM returned no text")
            text
        } finally {
            thinking.cancel()
        }
    }

    /** NFR reliability: the error is spoken within 2 s and the state goes back to IDLE. */
    private suspend fun speakFailure(cause: Throwable) {
        _lastError.value = cause
        _state.value = ModeState.SPEAKING
        say(phrasebook.inferenceFailed, interrupt = true)
    }

    /** FR-G3: announce only when the mode actually changes. */
    private suspend fun announceMode(mode: Mode) {
        if (_mode.value == mode) return
        _mode.value = mode
        say(phrasebook.modeAnnounced(mode), interrupt = true)
    }

    /** ADR-0004: returns whether Navigation was running (and is now paused), so the caller resumes it. */
    private suspend fun pauseNavigation(): Boolean {
        if (!navigation.isRunning.value) return false
        navigation.pause()
        return true
    }

    /** Starts Navigation if needed; `false` means the engine cannot run here (NoOp on the phone, ADR-0006). */
    private suspend fun ensureNavigationRunning(): Boolean {
        if (!navigation.isRunning.value) navigation.start()
        return navigation.isRunning.value
    }

    private fun describePrompt(): String = when (describeVariant) {
        DescribeVariant.BRIEF -> prompts.describeBrief ?: prompts.describe
        DescribeVariant.DETAILED -> prompts.describeDetailed ?: prompts.describe
        DescribeVariant.DEFAULT -> prompts.describe
    }

    /** NFR latency: `recognizedAtNanos` → the state change to CAPTURING/LISTENING, monotonic (H-21). */
    private fun markActionStarted(event: CommandEvent) {
        _commandLatencyNanos.value = System.nanoTime() - event.recognizedAtNanos
    }

    /** INV-4: only [Speaker.say] speaks; a blank text means the phrasebook is not wired and is skipped. */
    private suspend fun say(text: String, interrupt: Boolean = false) {
        if (text.isBlank()) return
        speaker.say(text, interrupt)
    }

    /** INV-9: the one owner recycles. */
    private fun release(bitmap: Bitmap) {
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private enum class DescribeVariant { DEFAULT, BRIEF, DETAILED }

    companion object {
        /** docs/architecture.md: "the thinking earcon repeats every 4 s while inferring". */
        const val THINKING_EARCON_PERIOD_MS: Long = 4_000
    }
}

/**
 * FR-N3 / RK-6: the allowlist parse of the DOOR answer. Pure; unit-tested. The VLM is asked for one
 * word; anything else (two sides, prose with no side, an empty string) is `null`, and `null` never
 * agrees with anything, so the user is never guided to a wall on a guess.
 */
internal object DoorVote {

    private val NON_LETTERS = Regex("[^a-z]+")

    private val allowed: Map<String, HorizontalBearing> = mapOf(
        "left" to HorizontalBearing.LEFT,
        "center" to HorizontalBearing.CENTER,
        "right" to HorizontalBearing.RIGHT,
        "none" to HorizontalBearing.NONE,
    )

    /** The single allowed word in [answer] (case-insensitive, punctuation ignored), or `null` if zero or several. */
    fun parse(answer: String): HorizontalBearing? {
        val words = answer.lowercase().split(NON_LETTERS).filter { it.isNotEmpty() }
        return words.mapNotNull { allowed[it] }.distinct().singleOrNull()
    }

    /** RK-6: both answers must exist, agree and not be NONE. */
    fun agree(first: HorizontalBearing?, second: HorizontalBearing?): HorizontalBearing? =
        if (first != null && first == second && first != HorizontalBearing.NONE) first else null
}

/**
 * FR-N3: where to draw the door highlight from a clock-face bearing. The engine reports hours, not
 * pixels, so the highlight is a vertical band in the left, center or right third of the view (the
 * same granularity as the VLM answer). Pure; unit-tested. `null` when the door is out of view (FR-N4).
 */
internal object DoorHighlight {

    private const val BAND_TOP = 0.2f
    private const val BAND_BOTTOM = 0.8f
    private const val THIRD = 1f / 3f

    fun boxFor(clock: Int, inView: Boolean): NormalizedBox? {
        if (!inView) return null
        val third = when (clock) {
            11, 12, 1 -> 1 // straight ahead
            9, 10 -> 0 // left
            2, 3 -> 2 // right
            else -> return null // behind the user: never in view
        }
        return NormalizedBox(
            left = third * THIRD,
            top = BAND_TOP,
            right = (third + 1) * THIRD,
            bottom = BAND_BOTTOM,
        )
    }
}
