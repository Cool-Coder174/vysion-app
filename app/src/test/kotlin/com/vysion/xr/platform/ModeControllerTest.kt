package com.vysion.xr.platform

import com.vysion.xr.core.ModePhrasebook
import com.vysion.xr.core.PromptSet
import android.graphics.Bitmap
import com.vysion.xr.core.AnchorId
import com.vysion.xr.core.AnchorStore
import com.vysion.xr.core.Command
import com.vysion.xr.core.CommandBus
import com.vysion.xr.core.CommandEvent
import com.vysion.xr.core.CommandSource
import com.vysion.xr.core.Detection
import com.vysion.xr.core.Earcon
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.FrameStats
import com.vysion.xr.core.HeadPose
import com.vysion.xr.core.HeadPoseSource
import com.vysion.xr.core.HorizontalBearing
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.NavigationEngine
import com.vysion.xr.core.RelativeTarget
import com.vysion.xr.core.Speaker
import com.vysion.xr.core.SpeechProgress
import com.vysion.xr.inference.FakeInferenceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * JVM spec-as-tests for [ModeController] (plan.md F3 3.3, H-48: "the watchdog and the busy policy
 * have no test yet"). Uses R2's [FakeInferenceEngine] plus the tiny in-file fakes below; virtual
 * time from `kotlinx-coroutines-test` drives the 15 s watchdog (INV-5) and the 4 s thinking earcon.
 * No Android class is exercised except `Bitmap` as an opaque value (see [TestFrameSource.newBitmap]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeControllerTest {

    // ── FR-G1 ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `welcome is spoken on start then the state returns to IDLE`() = controllerTest { f ->
        f.controller.start()
        runCurrent()
        assertEquals(ModeState.SPEAKING, f.controller.state.value)
        assertEquals(listOf(TestPhrasebook.welcome), f.speaker.said)
        advanceUntilIdle()
        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertTrue("frame source started", f.frames.started)
        assertNotNull("engine warmed up (vlmInfo non-null after warmUp)", f.engine.vlmInfo.value)
    }

    // ── FR-T1 / FR-T2 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `read walks IDLE CAPTURING INFERRING SPEAKING IDLE and sets the transcript`() = controllerTest { f ->
        startAndSettle(f)
        val states = mutableListOf<ModeState>()
        backgroundScope.launch { f.controller.state.collect { states += it } }
        runCurrent()

        f.commands.inject(Command.Read)
        advanceUntilIdle()

        assertEquals(
            listOf(ModeState.IDLE, ModeState.CAPTURING, ModeState.INFERRING, ModeState.SPEAKING, ModeState.IDLE),
            states,
        )
        assertEquals(READ_ANSWER, f.controller.transcript.value)
        assertEquals(Mode.TEXT_READER, f.controller.mode.value)
        assertEquals(listOf(TestPhrasebook.modeAnnounced(Mode.TEXT_READER), READ_ANSWER), f.speaker.said) // FR-G3 then text
        assertEquals(1, f.frames.captures)
        assertTrue("thinking earcon before capture", f.speaker.earcons.contains(Earcon.THINKING))
        assertNotNull("command→action latency measured", f.controller.commandLatencyNanos.value)
    }

    @Test
    fun `describe uses the describe prompt and announces the mode once`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        assertEquals(DESCRIBE_ANSWER, f.controller.transcript.value)
        assertEquals(
            listOf(TestPhrasebook.modeAnnounced(Mode.AUDIO_DESCRIPTION), DESCRIBE_ANSWER, DESCRIBE_ANSWER),
            f.speaker.said,
        )
    }

    // ── NFR reliability ───────────────────────────────────────────────────────────────────────────

    @Test
    fun `a failed inference speaks inferenceFailed and returns to IDLE`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.failNextDescribe = true
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertTrue(f.speaker.said.contains(TestPhrasebook.inferenceFailed))
        assertNull("no transcript from a failed call", f.controller.transcript.value)
        assertTrue(f.controller.lastError.value is InferenceException)
    }

    @Test
    fun `a hung inference hits the 15 s watchdog, keeps the thinking earcon going and speaks the failure`() =
        controllerTest { f ->
            startAndSettle(f)
            f.engine.hangNextDescribe = true
            f.commands.inject(Command.Read)
            advanceTimeBy(SAY_MS + CAPTURE_MS + 1)
            assertEquals(ModeState.INFERRING, f.controller.state.value)
            val thinkingAtStart = f.speaker.earcons.count { it == Earcon.THINKING }

            advanceTimeBy(InferenceEngine.VLM_TIMEOUT_MS - 1)
            assertEquals("still inferring one ms before the watchdog", ModeState.INFERRING, f.controller.state.value)
            assertFalse(f.speaker.said.contains(TestPhrasebook.inferenceFailed))
            // Never silent > 10 s: a THINKING earcon every 4 s → at 4, 8 and 12 s.
            assertEquals(thinkingAtStart + 3, f.speaker.earcons.count { it == Earcon.THINKING })

            advanceTimeBy(2)
            runCurrent()
            assertTrue("failure spoken within 2 s of the timeout", f.speaker.said.contains(TestPhrasebook.inferenceFailed))
            advanceUntilIdle()
            assertEquals(ModeState.IDLE, f.controller.state.value)
            assertTrue(f.controller.lastError.value is InferenceException)
        }

    // ── FR-G4 / INV-6 ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `stop during INFERRING cancels the command and returns to IDLE without advancing time`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.hangNextDescribe = true
        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + 1)
        assertEquals(ModeState.INFERRING, f.controller.state.value)

        f.commands.inject(Command.Stop)
        runCurrent() // no virtual time passes: the Stop lane does not wait for the VLM

        assertEquals(ModeState.IDLE, f.controller.state.value)
        assertEquals(1, f.speaker.stopCalls)
        assertFalse("a cancelled command speaks nothing", f.speaker.said.contains(TestPhrasebook.inferenceFailed))
    }

    @Test
    fun `stop during SPEAKING silences the speaker`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + DESCRIBE_MS + 1)
        assertEquals(ModeState.SPEAKING, f.controller.state.value)
        f.commands.inject(Command.Stop)
        runCurrent()
        assertEquals(1, f.speaker.stopCalls)
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    // ── Busy policy (plan.md Q3) ──────────────────────────────────────────────────────────────────

    @Test
    fun `a second command while INFERRING is dropped with the busy cue`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.hangNextDescribe = true
        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + 1)
        assertEquals(ModeState.INFERRING, f.controller.state.value)

        f.commands.inject(Command.Describe)
        runCurrent()

        assertEquals(TestPhrasebook.busy, f.speaker.said.last())
        assertEquals(ModeState.INFERRING, f.controller.state.value)
        assertEquals(Mode.TEXT_READER, f.controller.mode.value)
        assertEquals("the dropped command captured nothing", 1, f.frames.captures)
    }

    @Test
    fun `a command while SPEAKING preempts the utterance`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + DESCRIBE_MS + 1)
        assertEquals(ModeState.SPEAKING, f.controller.state.value)
        f.commands.inject(Command.Describe)
        advanceUntilIdle()
        assertEquals(1, f.speaker.stopCalls)
        assertEquals(Mode.AUDIO_DESCRIPTION, f.controller.mode.value)
        assertEquals(DESCRIBE_ANSWER, f.controller.transcript.value)
    }

    // ── FR-N3 / RK-6 door vote ────────────────────────────────────────────────────────────────────

    @Test
    fun `door vote with two agreeing answers places an anchor and guides to it`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedAnswers[TestPrompts.door] = "Left."
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()

        assertEquals(2, f.frames.captures)
        assertEquals(listOf(HorizontalBearing.LEFT), f.anchors.placed)
        assertEquals(listOf(f.anchors.lastId), f.navigation.guided)
        assertFalse(f.speaker.said.contains(TestPhrasebook.doorNotFound))
        assertEquals(Mode.NAVIGATION, f.controller.mode.value)
        assertTrue("navigation started for the mode", f.navigation.isRunning.value)
        assertEquals("paused once around the VLM (ADR-0004)", 1, f.navigation.pauseCount)
        assertEquals("resumed once after", 1, f.navigation.resumeCount)
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `door vote with disagreeing answers says doorNotFound and places nothing`() =
        controllerTest(engineWrapper = { ScriptedDoorEngine(it, "left", "right") }) { f ->
            startAndSettle(f)
            f.commands.inject(Command.FindDoor)
            advanceUntilIdle()

            assertEquals(2, f.frames.captures)
            assertTrue(f.anchors.placed.isEmpty())
            assertTrue(f.navigation.guided.isEmpty())
            assertTrue(f.speaker.said.contains(TestPhrasebook.doorNotFound))
            assertEquals(ModeState.IDLE, f.controller.state.value)
            assertEquals(1, f.navigation.resumeCount)
        }

    @Test
    fun `door vote answering none twice says doorNotFound`() = controllerTest { f ->
        startAndSettle(f)
        f.engine.cannedAnswers[TestPrompts.door] = "none"
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        assertTrue(f.anchors.placed.isEmpty())
        assertTrue(f.speaker.said.contains(TestPhrasebook.doorNotFound))
    }

    @Test
    fun `door vote with no usable depth says doorNotFound`() = controllerTest { f ->
        startAndSettle(f)
        f.anchors.failPlacement = true
        f.commands.inject(Command.FindDoor)
        advanceUntilIdle()
        assertEquals(listOf(HorizontalBearing.LEFT), f.anchors.placed)
        assertTrue(f.navigation.guided.isEmpty())
        assertTrue(f.speaker.said.contains(TestPhrasebook.doorNotFound))
    }

    // ── Navigation (FR-N1, FR-N2, ADR-0004, ADR-0006) ─────────────────────────────────────────────

    @Test
    fun `guide me starts navigation and a one-shot pauses and resumes it`() = controllerTest { f ->
        startAndSettle(f)
        f.commands.inject(Command.GuideMe)
        advanceUntilIdle()
        assertTrue(f.navigation.isRunning.value)
        assertEquals(Mode.NAVIGATION, f.controller.mode.value)

        f.commands.inject(Command.Read)
        advanceTimeBy(SAY_MS + CAPTURE_MS + 1)
        assertEquals(1, f.navigation.pauseCount)
        assertEquals(0, f.navigation.resumeCount)
        advanceUntilIdle()
        assertEquals(1, f.navigation.resumeCount)
    }

    @Test
    fun `guide me on a device without navigation speaks navigationUnavailable`() = controllerTest { f ->
        f.navigation.available = false
        startAndSettle(f)
        f.commands.inject(Command.GuideMe)
        advanceUntilIdle()
        assertTrue(f.speaker.said.contains(TestPhrasebook.navigationUnavailable))
        assertEquals(ModeState.IDLE, f.controller.state.value)
    }

    @Test
    fun `navigation cues are spoken through the phrasebook and drive the overlay`() = controllerTest { f ->
        startAndSettle(f)
        f.navigation.cuesFlow.tryEmit(NavigationCue.Proximity(0.8f))
        advanceUntilIdle()
        assertTrue(f.speaker.earcons.contains(Earcon.PROXIMITY))
        assertEquals(TestPhrasebook.navigationCue(NavigationCue.Proximity(0.8f)), f.speaker.said.last())

        f.navigation.cuesFlow.tryEmit(NavigationCue.DoorBearing(clock = 1, distanceM = 3f, inView = true))
        advanceUntilIdle()
        assertNotNull("door highlighted while in view", f.overlay.door.value)
        f.navigation.cuesFlow.tryEmit(NavigationCue.DoorBearing(clock = 1, distanceM = 3f, inView = false))
        advanceUntilIdle()
        assertNull("highlight cleared out of view (FR-N4)", f.overlay.door.value)

        f.navigation.detectionsFlow.tryEmit(listOf(Detection("person", 0.9f, DoorHighlight.boxFor(12, true)!!)))
        advanceUntilIdle()
        assertEquals(1, f.overlay.detections.value.size)
    }

    @Test
    fun `stop tears down collectors and clears the overlay`() = controllerTest { f ->
        startAndSettle(f)
        f.navigation.detectionsFlow.tryEmit(listOf(Detection("chair", 0.5f, DoorHighlight.boxFor(12, true)!!)))
        advanceUntilIdle()
        f.controller.stop()
        advanceUntilIdle()
        assertTrue(f.overlay.detections.value.isEmpty())
        assertFalse(f.navigation.isRunning.value)
        f.commands.inject(Command.Read)
        advanceUntilIdle()
        assertEquals("no command handled after stop", 0, f.frames.captures)
    }

    // ── Harness ───────────────────────────────────────────────────────────────────────────────────

    private class Fixture(scope: CoroutineScope, engineWrapper: (FakeInferenceEngine) -> InferenceEngine) {
        val frames = TestFrameSource(captureMs = CAPTURE_MS)
        val engine = FakeInferenceEngine(describeDelayMs = DESCRIBE_MS)
        val commands = TestCommandBus()
        val speaker = TestSpeaker(sayMs = SAY_MS)
        val navigation = TestNavigationEngine()
        val anchors = TestAnchorStore()
        val overlay = PassthroughOverlayRenderer()
        val controller = ModeController(
            frames = frames,
            engine = engineWrapper(engine),
            commands = commands,
            speaker = speaker,
            navigation = navigation,
            anchors = anchors,
            headPose = TestHeadPoseSource(),
            overlay = overlay,
            scope = scope,
            phrasebook = TestPhrasebook,
            prompts = TestPrompts,
        )

        init {
            engine.cannedAnswers[TestPrompts.read] = READ_ANSWER
            engine.cannedAnswers[TestPrompts.describe] = DESCRIBE_ANSWER
            engine.cannedAnswers[TestPrompts.door] = "left"
            engine.cannedAnswers[TestPrompts.arrival] = ARRIVAL_ANSWER
        }
    }

    private fun controllerTest(
        engineWrapper: (FakeInferenceEngine) -> InferenceEngine = { it },
        body: suspend TestScope.(Fixture) -> Unit,
    ) = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val fixture = Fixture(scope, engineWrapper)
        try {
            body(fixture)
        } finally {
            fixture.controller.stop()
            scope.cancel()
        }
    }

    /** `start()` and let the welcome finish, so each test begins in IDLE with the collectors subscribed. */
    private fun TestScope.startAndSettle(f: Fixture) {
        f.controller.start()
        advanceUntilIdle()
        assertEquals(ModeState.IDLE, f.controller.state.value)
        f.speaker.said.clear()
        f.speaker.earcons.clear()
    }

    private companion object {
        const val SAY_MS = 100L
        const val CAPTURE_MS = 10L
        const val DESCRIBE_MS = 50L
        const val READ_ANSWER = "<vlm:read>"
        const val DESCRIBE_ANSWER = "<vlm:describe>"
        const val ARRIVAL_ANSWER = "<vlm:arrival>"
    }
}

// ── Pure helpers ───────────────────────────────────────────────────────────────────────────────────

class DoorVoteTest {

    @Test
    fun `parse accepts exactly one allowed word, any case, any punctuation`() {
        assertEquals(HorizontalBearing.LEFT, DoorVote.parse("left"))
        assertEquals(HorizontalBearing.CENTER, DoorVote.parse("Center."))
        assertEquals(HorizontalBearing.RIGHT, DoorVote.parse("  RIGHT\n"))
        assertEquals(HorizontalBearing.NONE, DoorVote.parse("none"))
        assertEquals(HorizontalBearing.LEFT, DoorVote.parse("The door is on the left."))
    }

    @Test
    fun `parse rejects prose with no side, two sides, or spelling variants`() {
        assertNull(DoorVote.parse(""))
        assertNull(DoorVote.parse("I think there might be a door somewhere"))
        assertNull(DoorVote.parse("left or right"))
        assertNull(DoorVote.parse("centre"))
        assertNull(DoorVote.parse("leftish"))
    }

    @Test
    fun `agree needs two identical non-none answers`() {
        assertEquals(HorizontalBearing.RIGHT, DoorVote.agree(HorizontalBearing.RIGHT, HorizontalBearing.RIGHT))
        assertNull(DoorVote.agree(HorizontalBearing.LEFT, HorizontalBearing.RIGHT))
        assertNull(DoorVote.agree(HorizontalBearing.NONE, HorizontalBearing.NONE))
        assertNull(DoorVote.agree(null, HorizontalBearing.LEFT))
        assertNull(DoorVote.agree(HorizontalBearing.LEFT, null))
        assertNull(DoorVote.agree(null, null))
    }

    @Test
    fun `door highlight is a third of the view by clock hour and null out of view or behind`() {
        val center = DoorHighlight.boxFor(12, true)!!
        assertEquals(1f / 3f, center.left, 1e-6f)
        assertEquals(2f / 3f, center.right, 1e-6f)
        assertEquals(0f, DoorHighlight.boxFor(10, true)!!.left, 1e-6f)
        assertEquals(1f, DoorHighlight.boxFor(2, true)!!.right, 1e-6f)
        assertNull(DoorHighlight.boxFor(12, false))
        assertNull(DoorHighlight.boxFor(6, true))
    }
}

class FrameMathTest {

    @Test
    fun `fps meter needs two frames and measures over the window`() {
        val meter = FpsMeter(windowNanos = 5_000_000_000L)
        assertNull(meter.onFrame(0L))
        assertEquals(5f, meter.onFrame(200_000_000L)!!, 1e-3f)
        assertEquals(5f, meter.onFrame(400_000_000L)!!, 1e-3f)
        // A 2 s gap, then frames again: the window averages over what it has seen.
        val fps = meter.onFrame(2_400_000_000L)!!
        assertTrue(fps < 5f && fps > 1f)
    }

    @Test
    fun `fps meter drops frames older than the window`() {
        val meter = FpsMeter(windowNanos = 1_000_000_000L)
        meter.onFrame(0L)
        meter.onFrame(100_000_000L)
        // 10 s later only this frame and the next count.
        assertNull(meter.onFrame(10_000_000_000L))
        assertEquals(10f, meter.onFrame(10_100_000_000L)!!, 1e-3f)
    }

    @Test
    fun `center crop keeps the target aspect and centers the rectangle`() {
        assertEquals(CropRect(left = 80, top = 0, width = 480, height = 480), CenterCrop.rect(640, 480, 896, 896))
        assertEquals(CropRect(left = 0, top = 80, width = 480, height = 480), CenterCrop.rect(480, 640, 896, 896))
        assertEquals(CropRect(left = 0, top = 0, width = 896, height = 896), CenterCrop.rect(896, 896, 896, 896))
        assertEquals(CropRect(left = 0, top = 160, width = 1280, height = 640), CenterCrop.rect(1280, 960, 640, 320))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `center crop rejects empty sizes`() {
        CenterCrop.rect(0, 480, 896, 896)
    }
}

// ── In-file fakes (R1 owns FrameSource/OverlayRenderer; the others are the minimum the controller needs) ──

/**
 * Captures return a real `Bitmap` instance so the controller can hand it to the engine and recycle it
 * (INV-9). The unit-test android.jar (`isReturnDefaultValues = true`) turns `Bitmap`'s package-private
 * constructor and `recycle()` into no-ops, so reflection is enough; no mocking library is needed.
 */
private class TestFrameSource(private val captureMs: Long) : FrameSource {
    var started = false
    var captures = 0
    override val analysisFrames: Flow<Bitmap> = emptyFlow()
    override val stats: StateFlow<FrameStats?> = MutableStateFlow(null)

    override suspend fun start() {
        started = true
    }

    override suspend fun stop() = Unit

    override suspend fun captureStill(): Bitmap {
        captures++
        delay(captureMs)
        return newBitmap()
    }

    private companion object {
        fun newBitmap(): Bitmap =
            Bitmap::class.java.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    }
}

/** plan.md: `MutableSharedFlow(extraBufferCapacity = 8, onBufferOverflow = DROP_OLDEST)`. */
private class TestCommandBus : CommandBus {
    private val events = MutableSharedFlow<CommandEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val commands: Flow<CommandEvent> = events
    override val state = MutableStateFlow(ListeningState.IDLE)

    override fun startListening() {
        state.value = ListeningState.LISTENING
    }

    override fun stopListening() {
        state.value = ListeningState.IDLE
    }

    override fun inject(command: Command, source: CommandSource) {
        events.tryEmit(CommandEvent(command, source, System.nanoTime()))
    }
}

/** Records everything; `say` takes [sayMs] of virtual time so SPEAKING is observable. */
private class TestSpeaker(private val sayMs: Long) : Speaker {
    val said = mutableListOf<String>()
    val earcons = mutableListOf<Earcon>()
    var stopCalls = 0
    override val progress = MutableSharedFlow<SpeechProgress>()
    override val locale = MutableStateFlow(Speaker.EN_US)
    override val isSpeaking = MutableStateFlow(false)

    override suspend fun say(text: String, interrupt: Boolean) {
        said += text
        isSpeaking.value = true
        try {
            delay(sayMs)
        } finally {
            isSpeaking.value = false
        }
    }

    override fun stop() {
        stopCalls++
    }

    override fun earcon(kind: Earcon) {
        earcons += kind
    }

    override fun setRate(rate: Float) = Unit

    override fun setLocale(locale: Locale) {
        this.locale.value = locale
    }

    override suspend fun repeatLast(rate: Float) {
        said.lastOrNull()?.let { say(it, interrupt = false) }
    }
}

private class TestNavigationEngine : NavigationEngine {
    /** `false` models the NoOp engine on the S25 Ultra: `start()` never makes it run (ADR-0006). */
    var available = true
    var pauseCount = 0
    var resumeCount = 0
    val guided = mutableListOf<AnchorId>()
    val cuesFlow = MutableSharedFlow<NavigationCue>(extraBufferCapacity = 8)
    val detectionsFlow = MutableSharedFlow<List<Detection>>(extraBufferCapacity = 8)
    override val cues: Flow<NavigationCue> = cuesFlow
    override val detections: Flow<List<Detection>> = detectionsFlow
    override val isRunning = MutableStateFlow(false)

    override suspend fun start() {
        if (available) isRunning.value = true
    }

    override suspend fun pause() {
        pauseCount++
    }

    override suspend fun resume() {
        resumeCount++
    }

    override suspend fun stop() {
        isRunning.value = false
    }

    override suspend fun guideTo(anchor: AnchorId) {
        guided += anchor
    }

    override suspend fun clearTarget() = Unit
}

private class TestAnchorStore : AnchorStore {
    var failPlacement = false
    val placed = mutableListOf<HorizontalBearing>()
    var lastId: AnchorId? = null

    override suspend fun placeFromBearing(bearing: HorizontalBearing): AnchorId? {
        placed += bearing
        if (failPlacement) return null
        return AnchorId("door-${placed.size}").also { lastId = it }
    }

    override fun track(id: AnchorId): Flow<RelativeTarget> = emptyFlow()
    override suspend fun remove(id: AnchorId) = Unit
    override suspend fun clear() = Unit
}

private class TestHeadPoseSource : HeadPoseSource {
    override val pose: StateFlow<HeadPose?> = MutableStateFlow(null)
}

/** Scripts the DOOR answers in call order on top of the fake, so the two vote calls can disagree. */
private class ScriptedDoorEngine(
    private val delegate: FakeInferenceEngine,
    vararg answers: String,
) : InferenceEngine by delegate {
    private val queue = ArrayDeque(answers.toList())

    override suspend fun describe(bitmap: Bitmap, prompt: String, maxNewTokens: Int): String {
        if (prompt == TestPrompts.door && queue.isNotEmpty()) delegate.cannedAnswers[prompt] = queue.removeFirst()
        return delegate.describe(bitmap, prompt, maxNewTokens)
    }
}

/** Test tokens, not product strings: the real words live in voice/Cues.kt (R4). */
private object TestPhrasebook : ModePhrasebook {
    override val welcome = "<cue:welcome>"
    override val busy = "<cue:busy>"
    override val inferenceFailed = "<cue:inferenceFailed>"
    override val doorNotFound = "<cue:doorNotFound>"
    override val navigationUnavailable = "<cue:navigationUnavailable>"
    override val holdStill = "<cue:holdStill>"
    override val permissionExplanation = "<cue:permissionExplanation>"
    override val permissionDenied = "<cue:permissionDenied>"
    override fun modeAnnounced(mode: Mode) = "<cue:mode:${mode.name}>"
    override fun navigationCue(cue: NavigationCue): String? =
        if (cue == NavigationCue.Arrived) null else "<cue:${cue::class.simpleName}>"
}

/** Test tokens, not prompts: the real text lives in inference/PromptLibrary.kt (R2). */
private object TestPrompts : PromptSet {
    override val read = "<prompt:read>"
    override val describe = "<prompt:describe>"
    override val door = "<prompt:door>"
    override val arrival = "<prompt:arrival>"
}
