package com.vysion.xr.platform.ui

import androidx.compose.runtime.compositionLocalOf
import com.vysion.xr.core.Command
import com.vysion.xr.core.Detection
import com.vysion.xr.core.ListeningState
import com.vysion.xr.core.Mode
import com.vysion.xr.core.ModeState
import com.vysion.xr.core.NavigationCue
import com.vysion.xr.core.NormalizedBox
import com.vysion.xr.core.SpeechProgress

/**
 * DEBUG BUILDS ONLY. Puts one of the designs' screens on the device with synthetic content, so the UI can be
 * checked against Michelle Bui's comps without models, a camera or a door. `MainActivity` honors it only when
 * `BuildConfig.DEBUG` and the launch intent asks for it:
 *
 * ```
 * adb shell am start -n com.vysion.xr/.MainActivity --es vysion.preview reading
 * adb shell am start -n com.vysion.xr/.MainActivity --ei vysion.onboarding 3      # onboarding page 3 of 7
 * ```
 *
 * Every word and number here is synthetic sample content taken from the comps; none of it can reach a release
 * build, the overlay of a real session, or the README (NFR Honesty).
 */
enum class PreviewScreen {
    READY, LISTENING, THINKING, LOOKING, READING, DESCRIBING, GUIDING, ARRIVED, LOST, OBSTACLE, NO_TEXT, ERROR, DIAGNOSTICS;

    companion object {
        fun parse(value: String?): PreviewScreen? = value?.let { v -> entries.firstOrNull { it.name.equals(v.replace('-', '_'), ignoreCase = true) } }
    }
}

/** The preview requested by the launch intent, if any (debug builds only). */
val LocalDesignPreview = compositionLocalOf<PreviewScreen?> { null }

/** The onboarding page to open on (0-based), set by a debug preview intent; 0 otherwise. */
val LocalOnboardingStart = compositionLocalOf { 0 }

internal object DesignPreview {
    private const val READ_SAMPLE =
        "Meeting rooms are on floor 2. Also on 2: California Lawyers for the Arts, FMCAC Main Office."
    private const val DESCRIBE_SAMPLE = "Bags on the floor ahead, on your right. Tables with people on both sides."
    private const val NO_TEXT = "No text found"
    private const val NOW = 10_000L

    fun input(screen: PreviewScreen): ScreenInput {
        val base = ScreenInput(state = ModeState.IDLE, mode = null, transcript = null, listening = ListeningState.IDLE, noTextSentinel = NO_TEXT, nowMs = NOW)
        return when (screen) {
            PreviewScreen.READY, PreviewScreen.DIAGNOSTICS -> base
            PreviewScreen.LISTENING -> base.copy(listening = ListeningState.LISTENING)
            PreviewScreen.THINKING -> base.copy(state = ModeState.INFERRING, mode = Mode.AUDIO_DESCRIPTION, lastCommand = Command.Describe, lastCommandAtMs = NOW)
            PreviewScreen.LOOKING -> base.copy(state = ModeState.CAPTURING, mode = Mode.NAVIGATION, lastCommand = Command.FindDoor, lastCommandAtMs = NOW)
            PreviewScreen.READING -> base.copy(state = ModeState.SPEAKING, mode = Mode.TEXT_READER, transcript = READ_SAMPLE)
            PreviewScreen.DESCRIBING -> base.copy(state = ModeState.SPEAKING, mode = Mode.AUDIO_DESCRIPTION, transcript = DESCRIBE_SAMPLE)
            PreviewScreen.GUIDING -> base.copy(mode = Mode.NAVIGATION, navCue = NavigationCue.DoorBearing(12, 18f, true), navCueAtMs = NOW, lastDoorClock = 12)
            PreviewScreen.ARRIVED -> base.copy(mode = Mode.NAVIGATION, navCue = NavigationCue.Arrived, navCueAtMs = NOW, transcript = "It’s straight ahead, about half a meter.")
            PreviewScreen.LOST -> base.copy(mode = Mode.NAVIGATION, navCue = NavigationCue.TargetLost, navCueAtMs = NOW, lastDoorClock = 12)
            PreviewScreen.OBSTACLE -> base.copy(mode = Mode.NAVIGATION, hazard = NavigationCue.Proximity(0.9f), hazardAtMs = NOW)
            PreviewScreen.NO_TEXT -> base.copy(mode = Mode.TEXT_READER, transcript = NO_TEXT)
            PreviewScreen.ERROR -> base.copy(lastCommand = Command.Read, lastCommandAtMs = NOW - 1, lastErrorAtMs = NOW)
        }
    }

    /** Mid-utterance progress so the spoken sentence shows yellow, as in the comps. */
    fun progress(screen: PreviewScreen): SpeechProgress? = when (screen) {
        PreviewScreen.READING -> SpeechProgress("preview", READ_SAMPLE, 0, 8, done = false)
        PreviewScreen.DESCRIBING -> SpeechProgress("preview", DESCRIBE_SAMPLE, 0, 4, done = false)
        else -> null
    }

    /** Sample outlines for the overlay panel. */
    fun detections(screen: PreviewScreen): List<Detection> = when (screen) {
        PreviewScreen.DESCRIBING -> listOf(Detection("bag", 0.9f, NormalizedBox(0.62f, 0.55f, 0.74f, 0.78f)))
        PreviewScreen.OBSTACLE -> listOf(Detection("trash can", 0.9f, NormalizedBox(0.42f, 0.5f, 0.6f, 0.95f)))
        else -> emptyList()
    }

    fun door(screen: PreviewScreen): NormalizedBox? = when (screen) {
        PreviewScreen.GUIDING, PreviewScreen.ARRIVED, PreviewScreen.LOST -> NormalizedBox(0.44f, 0.22f, 0.58f, 0.8f)
        else -> null
    }
}
