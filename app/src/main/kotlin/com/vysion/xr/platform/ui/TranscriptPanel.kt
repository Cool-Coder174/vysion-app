package com.vysion.xr.platform.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vysion.xr.R
import com.vysion.xr.core.SpeechProgress

/**
 * The large-print transcript (FR-T2; SRS §3 rule 4; R4's panel spec: "48 dp+ text, 7:1 contrast,
 * auto-scroll synced to speech"). Owner: R1, due Sat 18:00.
 *
 * - Text is [TRANSCRIPT_TEXT_SIZE] (48 sp ≥ 48 dp at default font scale) in `#FFFFFF` on `#000000`,
 *   21:1 (docs/stack.md → R4 contrast row; WCAG 2.2 SC 1.4.6 asks for 7:1).
 * - Auto-scroll is driven by [Speaker.progress]: when the utterance being spoken is this transcript,
 *   the view scrolls proportionally to `charEnd / text.length` and the spoken range is underlined.
 * - With no transcript yet it shows the product sentence from `strings.xml` (SRS §3 rule 6,
 *   RK-9: the assistive-not-safety sentence appears in app onboarding).
 * - TalkBack: one `contentDescription` for the panel; the text itself is read by TTS already, so
 *   no live region (it would double-speak).
 *
 * Anchoring where the user was looking at capture time is P1 (SRS §8 R1, Sun 09:00).
 */
@Composable
fun TranscriptPanel(
    transcript: String?,
    progress: SpeechProgress?,
    modifier: Modifier = Modifier,
) {
    val text = transcript ?: stringResource(R.string.product_sentence)
    val panelLabel = stringResource(R.string.a11y_transcript_panel)
    val scroll = rememberScrollState()
    val spoken = progress?.takeIf { it.text == text && !it.done }

    // R4 spec: scroll in sync with speech, proportional to how much of the text has been spoken.
    LaunchedEffect(spoken?.charEnd, text, scroll.maxValue) {
        val end = spoken?.charEnd ?: return@LaunchedEffect
        if (text.isEmpty() || scroll.maxValue == 0) return@LaunchedEffect
        val fraction = (end.toFloat() / text.length).coerceIn(0f, 1f)
        scroll.animateScrollTo((scroll.maxValue * fraction).toInt())
    }

    Box(
        modifier = modifier
            .background(PANEL_BACKGROUND)
            .semantics { contentDescription = panelLabel }
            .verticalScroll(scroll)
            .padding(PANEL_PADDING),
    ) {
        Text(
            text = buildAnnotatedString {
                if (spoken == null) {
                    append(text)
                } else {
                    val start = spoken.charStart.coerceIn(0, text.length)
                    val end = spoken.charEnd.coerceIn(start, text.length)
                    append(text.substring(0, start))
                    withStyle(SPOKEN_STYLE) { append(text.substring(start, end)) }
                    append(text.substring(end))
                }
            },
            color = PANEL_FOREGROUND,
            fontSize = TRANSCRIPT_TEXT_SIZE,
            lineHeight = TRANSCRIPT_LINE_HEIGHT,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** SRS §3 rule 4: 48 dp or larger. */
val TRANSCRIPT_TEXT_SIZE = 48.sp
private val TRANSCRIPT_LINE_HEIGHT = 60.sp
private val PANEL_PADDING = 24.dp
private val PANEL_BACKGROUND = Color.Black // #000000
private val PANEL_FOREGROUND = Color.White // #FFFFFF → 21:1
private val SPOKEN_STYLE = SpanStyle(textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Bold)
