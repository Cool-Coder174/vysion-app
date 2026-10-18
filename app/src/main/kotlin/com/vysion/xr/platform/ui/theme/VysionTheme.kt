package com.vysion.xr.platform.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.PI

/*
 * Vysion XR design tokens, taken from Michelle Bui's approved designs (Oct 2026; DESIGN.md records them).
 *
 * World: smoked neutral glass over passthrough on the headset; deep indigo aurora under the onboarding
 * cards. Color carries meaning, one job per hue (DESIGN.md "Meaning colors"): yellow is reading and the
 * phrase being spoken, cyan is navigation, green is done/arrived, orange is a warning, red is stop/error,
 * purple is thinking, blue is talking to Vysion. Typeface is the device's system font (PRODUCT.md).
 */

/** Meaning colors (the designs use Apple's dark system palette; contrast checked against [Glass.graphite]). */
object Accent {
    /** Talk / Listening / primary action. White label 4.0:1 at ≥ 18 sp bold. */
    val blue = Color(0xFF0A7AFF)

    /** Reading, the spoken phrase, read/describe outlines. 11.9:1 on graphite. */
    val yellow = Color(0xFFFFD60A)

    /** Navigation, the door, guidance. 9.4:1 on graphite. */
    val cyan = Color(0xFF64D2FF)

    /** Done, arrived. 8.0:1 on graphite. */
    val green = Color(0xFF30D158)

    /** Warning, obstacle. 7.6:1 on graphite. */
    val orange = Color(0xFFFF9F0A)

    /** Stop, error. White label 3.6:1 at ≥ 18 sp bold (WCAG large text). */
    val red = Color(0xFFFF453A)

    /** Thinking. */
    val purple = Color(0xFFBF5AF2)
}

/** Text on glass. Secondary and tertiary are tinted neutrals, never a flat gray on color. */
object Ink {
    val primary = Color(0xFFFFFFFF)

    /** 10.9:1 on graphite: the unspoken part of an answer, subtitles. */
    val secondary = Color(0xFFD1D1D6)

    /** 6.1:1 on graphite: footnotes. */
    val tertiary = Color(0xFFA1A1A8)

    /** Labels on white pills and colored blocks. */
    val onLight = Color(0xFF111114)
}

/** The two glass materials and the aurora ground. */
object Glass {
    /**
     * Headset status window, rail, toolbar, diagnostics: smoked graphite over passthrough. Near-opaque on purpose:
     * on the Galaxy XR the compositor thins panel alpha, and at 85% a busy room (people, windows) showed through
     * and cost the secondary text its contrast (headset captures, 2026-10-17). Low vision first: 7:1 holds.
     */
    val graphite = Color(0xFF2A2A2F)
    val graphiteTop = Color(0xF53A3A40)
    val graphiteBottom = Color(0xFA242428)

    /** Onboarding cards: deep indigo. */
    val indigoTop = Color(0xD9232542)
    val indigoBottom = Color(0xE6141529)

    /** The hairline that defines a glass edge instead of a drop shadow. */
    val edge = Color(0x29FFFFFF)

    /** The soft top sheen. */
    val sheen = Color(0x14FFFFFF)

    /** Graphite pill (secondary buttons, chips). */
    val pill = Color(0xFF3A3A40)
    val pillPressed = Color(0xFF4A4A52)

    /** A white disc / pill: the selected rail item and the primary button. */
    val white = Color(0xFFFFFFFF)

    /** Behind every orbiter and toolbar: the darker glass under the pills. */
    val tray = Color(0xF21C1C20)
}

/** The aurora that sits behind onboarding "before the camera turns on" (designs pages 2-8). */
object Aurora {
    val ground = Color(0xFF07081A)
    val blue = Color(0xFF2448B8)
    val violet = Color(0xFF5A35A8)
    val teal = Color(0xFF0D4A58)
    val ember = Color(0xFF2A0C14)
}

/**
 * Type ramp in sp so it follows the user's font size. Answer text is ≥ 48 sp (SRS §3 rule 4) on both
 * devices; the headset values are sized for a panel at the default 1.75 m.
 */
@Immutable
data class VysionType(
    val display: TextStyle,
    val windowTitle: TextStyle,
    val windowSubtitle: TextStyle,
    val answer: TextStyle,
    val prompt: TextStyle,
    val body: TextStyle,
    val footnote: TextStyle,
    val label: TextStyle,
    /** Mode rail item labels: always visible, sized to fit the rail's item width. */
    val railLabel: TextStyle,
    val chip: TextStyle,
    val distance: TextStyle,
)

private fun style(size: TextUnit, line: TextUnit, weight: FontWeight, tracking: TextUnit = 0.em) =
    TextStyle(fontSize = size, lineHeight = line, fontWeight = weight, letterSpacing = tracking)

/** Galaxy XR panels. */
val SpatialType = VysionType(
    display = style(56.sp, 64.sp, FontWeight.Bold, (-0.02).em),
    windowTitle = style(30.sp, 36.sp, FontWeight.SemiBold, (-0.01).em),
    windowSubtitle = style(20.sp, 26.sp, FontWeight.Normal),
    answer = style(52.sp, 62.sp, FontWeight.SemiBold, (-0.015).em),
    prompt = style(48.sp, 58.sp, FontWeight.SemiBold, (-0.015).em),
    body = style(28.sp, 38.sp, FontWeight.Normal),
    footnote = style(20.sp, 26.sp, FontWeight.Normal),
    label = style(22.sp, 26.sp, FontWeight.SemiBold),
    railLabel = style(17.sp, 20.sp, FontWeight.SemiBold),
    chip = style(18.sp, 22.sp, FontWeight.Medium),
    distance = style(104.sp, 108.sp, FontWeight.Bold, (-0.03).em),
)

/** The S25 Ultra held at reading distance. */
val PhoneType = VysionType(
    display = style(34.sp, 40.sp, FontWeight.Bold, (-0.02).em),
    windowTitle = style(20.sp, 26.sp, FontWeight.SemiBold),
    windowSubtitle = style(15.sp, 20.sp, FontWeight.Normal),
    answer = style(48.sp, 54.sp, FontWeight.Bold, (-0.02).em),
    prompt = style(28.sp, 34.sp, FontWeight.SemiBold),
    body = style(17.sp, 24.sp, FontWeight.Normal),
    footnote = style(13.sp, 18.sp, FontWeight.Normal),
    label = style(16.sp, 20.sp, FontWeight.SemiBold),
    railLabel = style(13.sp, 16.sp, FontWeight.SemiBold),
    chip = style(12.sp, 16.sp, FontWeight.SemiBold, 0.06.em),
    distance = style(72.sp, 76.sp, FontWeight.Bold, (-0.03).em),
)

/** 8-dp spacing scale and the shapes' radii. */
object Space {
    val xs: Dp = 8.dp
    val s: Dp = 12.dp
    val m: Dp = 16.dp
    val l: Dp = 24.dp
    val xl: Dp = 32.dp
    val xxl: Dp = 48.dp
}

object Radius {
    /** The status window and onboarding card: a soft, large corner like the designs' glass. */
    val window: Dp = 44.dp
    val tile: Dp = 20.dp
}

/** Touch targets: Android XR visual design asks ≥ 56 dp; phones ≥ 48 dp. */
object Target {
    val spatial: Dp = 64.dp
    val phone: Dp = 48.dp
}

/**
 * Motion in the designers' two parameters (damping ratio, response in seconds), converted to a
 * Compose spring. Default critically damped: the panel reacts, it does not bounce.
 */
fun <T> vysionSpring(response: Float = 0.35f, damping: Float = 1f): FiniteAnimationSpec<T> {
    val omega = 2f * PI.toFloat() / response
    return spring(dampingRatio = damping, stiffness = omega * omega)
}

/** True when the user turned animations off (Settings > Accessibility > Remove animations). */
val LocalReduceMotion = compositionLocalOf { false }

/** Reads the system "Remove animations" setting once per composition root. */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}
