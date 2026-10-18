package com.vysion.xr.platform.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Vysion's icon set: authored for this app on a 24-unit grid, one 2-unit round stroke throughout, so the rail,
 * toolbar and onboarding read as one family (no icon library is on the classpath, and none is added).
 * The "Aa" Read glyph and the "Es" Spanish chip are set as type on purpose: the designs draw them as letters.
 * Icons are tinted at the call site (`Icon(…, tint = …)`), so every path here is white.
 */
object VysionIcons {
    val Eye: ImageVector by lazy {
        stroked(
            "Eye",
            "M2.5 12s3.5-6.5 9.5-6.5S21.5 12 21.5 12s-3.5 6.5-9.5 6.5S2.5 12 2.5 12z",
            "M12 9.25a2.75 2.75 0 1 0 0 5.5a2.75 2.75 0 1 0 0-5.5z",
        )
    }

    /** Navigation: a heading needle pointing the way. */
    val Navigate: ImageVector by lazy { filled("Navigate", "M12 2.75l6.25 17.5L12 16.6l-6.25 3.65z") }

    val More: ImageVector by lazy {
        filled(
            "More",
            "M5 10.4a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2z",
            "M12 10.4a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2z",
            "M19 10.4a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2z",
        )
    }

    val Mic: ImageVector by lazy {
        stroked(
            "Mic",
            "M12 3a3 3 0 0 1 3 3v6a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3z",
            "M18.5 11a6.5 6.5 0 0 1-13 0",
            "M12 17.5V21",
        )
    }

    val Stop: ImageVector by lazy {
        filled("Stop", "M8.75 6.5h6.5a2.25 2.25 0 0 1 2.25 2.25v6.5a2.25 2.25 0 0 1-2.25 2.25h-6.5A2.25 2.25 0 0 1 6.5 15.25v-6.5A2.25 2.25 0 0 1 8.75 6.5z")
    }

    val Repeat: ImageVector by lazy {
        stroked("Repeat", "M3.75 12a8.25 8.25 0 1 0 2.6-6", "M3.5 3.75v4.5H8")
    }

    val Next: ImageVector by lazy { stroked("Next", "M9.5 5.5L16 12l-6.5 6.5") }

    val Plus: ImageVector by lazy { stroked("Plus", "M12 5v14", "M5 12h14") }

    val Check: ImageVector by lazy { stroked("Check", "M5.5 12.5l4.25 4.25L18.5 8") }

    val Alert: ImageVector by lazy { stroked("Alert", "M12 6.5v7", "M12 17.5v0.01") }

    val Question: ImageVector by lazy {
        stroked("Question", "M9.25 9.25a2.75 2.75 0 1 1 4 2.45c-.75.38-1.25 1.08-1.25 1.9v.4", "M12 17.5v0.01")
    }

    val Play: ImageVector by lazy { filled("Play", "M9.5 7.25v9.5c0 .6.65.97 1.17.67l7.9-4.75a.78.78 0 0 0 0-1.34l-7.9-4.75a.78.78 0 0 0-1.17.67z") }

    val Close: ImageVector by lazy { stroked("Close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11") }

    /** Privacy row 1: no network. */
    val NoNetwork: ImageVector by lazy {
        stroked(
            "NoNetwork",
            "M3.5 3.5l17 17",
            "M8.6 15.6a4.8 4.8 0 0 1 6.1-.5",
            "M5.4 12.3a9.4 9.4 0 0 1 3.5-2.2",
            "M13.3 9.3a9.4 9.4 0 0 1 5.3 3",
            "M2.25 9a13.9 13.9 0 0 1 3.9-2.6",
            "M10.4 5.1A14 14 0 0 1 21.75 9",
            "M12 19.25v0.01",
        )
    }

    /** Privacy row 2: the camera. */
    val Camera: ImageVector by lazy {
        stroked(
            "Camera",
            "M4.5 7.25h2.75L9 4.75h6l1.75 2.5h2.75a2 2 0 0 1 2 2v8.5a2 2 0 0 1-2 2h-15a2 2 0 0 1-2-2v-8.5a2 2 0 0 1 2-2z",
            "M12 10a3.25 3.25 0 1 0 0 6.5a3.25 3.25 0 1 0 0-6.5z",
        )
    }

    /** Onboarding 3: pinch and hold (thumb meets index). */
    val PinchHand: ImageVector by lazy {
        stroked(
            "PinchHand",
            "M9 21.25v-3.5l-3-3.9a1.55 1.55 0 0 1 2.45-1.9l1.55 1.8V9.5",
            "M10 9.5V6.25a1.5 1.5 0 0 1 3 0V12",
            "M13 11a1.5 1.5 0 0 1 3 0v1.5",
            "M16 12a1.5 1.5 0 0 1 3 0v2.75a6.5 6.5 0 0 1-6.5 6.5",
            "M7.25 8.25a3.75 3.75 0 0 1 5.4-4.4",
        )
    }

    /** Onboarding 4 and the stop gesture: an open palm facing out. */
    val Palm: ImageVector by lazy {
        stroked(
            "Palm",
            "M7.5 12.25V6.5a1.5 1.5 0 0 1 3 0v4.75",
            "M10.5 10.75V4.5a1.5 1.5 0 0 1 3 0v6.25",
            "M13.5 10.75V5.75a1.5 1.5 0 0 1 3 0v5.75",
            "M16.5 11.5V8.75a1.5 1.5 0 0 1 3 0v5.5a7 7 0 0 1-7 7h-.9a6.3 6.3 0 0 1-4.6-2l-3.3-3.6a1.55 1.55 0 0 1 2.3-2.1l1.5 1.6",
        )
    }

    private fun stroked(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { d ->
                    addPath(
                        pathData = addPathNodes(d),
                        fill = null,
                        stroke = SolidColor(Color.White),
                        strokeLineWidth = STROKE,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()

    private fun filled(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply { paths.forEach { d -> addPath(pathData = addPathNodes(d), fill = SolidColor(Color.White)) } }
            .build()

    private const val STROKE = 2f
}
