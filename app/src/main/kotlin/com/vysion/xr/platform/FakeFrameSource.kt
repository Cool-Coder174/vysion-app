package com.vysion.xr.platform

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.FrameStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

/**
 * Fully working fake of [FrameSource] for the laptop emulator, the S25 Ultra without a usable
 * camera and the `-PuseFakes=true` build (ADR-0006, ADR-0008 `AppGraph` wiring). Owner: R1.
 * Constructor `(Application)` per `AppGraph`.
 *
 * - [analysisFrames]: a cold flow that paints a 640x480 gradient with a dark rectangle sliding
 *   across it at 5 fps (FR-N2 floor). Collected only by `XrNavigationEngine`; under `-PuseFakes`
 *   Navigation is `NoOpNavigationEngine`, so these frames are unused and the overlay stays empty.
 *   Each frame is a fresh `Bitmap`, mirroring the real source's ownership rule: the collector never
 *   recycles it (INV-9) and the GC reclaims it (no reuse pool, see `CameraFrameSource`'s KDoc).
 * - [captureStill]: a fresh 896x896 synthetic "sign" (gradient, a rectangle, dark text-like bars);
 *   the caller owns and recycles it (INV-9).
 * - [stats]: non-null after [start]; fps is measured by [FpsMeter] as frames are emitted, never
 *   assumed (NFR "Honesty").
 *
 * No file output, no logging of pixels (ADR-0003). The `Application` parameter is unused; it is
 * kept so `AppGraph` constructs real and fake sources the same way. Assumption: none (A-1 is about
 * the real camera; this class is what runs when A-1 fails, RK-1).
 */
class FakeFrameSource(
    @Suppress("unused") private val app: Application,
) : FrameSource {

    private val _stats = MutableStateFlow<FrameStats?>(null)
    override val stats: StateFlow<FrameStats?> = _stats

    private val fpsMeter = FpsMeter()

    override val analysisFrames: Flow<Bitmap> = flow {
        // A fresh bitmap per frame: a conflated collector may still hold the previous one (no pool to repaint).
        var index = 0
        while (true) {
            val frame = newFrame(ANALYSIS_WIDTH, ANALYSIS_HEIGHT)
            SyntheticScene.paintAnalysisFrame(frame, index)
            val fps = fpsMeter.onFrame(SystemClock.elapsedRealtimeNanos()) // H-21: monotonic
            if (fps != null) _stats.value = statsFor(fps)
            emit(frame)
            index++
            delay(FRAME_PERIOD_MS) // 5 fps
        }
    }

    /** Publishes a stats row immediately so the overlay never shows a null camera on the fake path. */
    override suspend fun start() {
        _stats.value = statsFor(fps = 0f)
    }

    override suspend fun stop() {
        _stats.value = null
    }

    /** A new 896x896 synthetic still each call; the caller recycles it (INV-9). */
    override suspend fun captureStill(): Bitmap =
        newFrame(STILL_EDGE, STILL_EDGE).also { SyntheticScene.paintStill(it) }

    private fun statsFor(fps: Float) = FrameStats(
        cameraId = CAMERA_ID,
        width = ANALYSIS_WIDTH,
        height = ANALYSIS_HEIGHT,
        format = "ARGB_8888",
        fps = fps,
    )

    private fun newFrame(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    companion object {
        const val CAMERA_ID: String = "fake-0"
        const val ANALYSIS_WIDTH: Int = CameraFrameSource.ANALYSIS_WIDTH
        const val ANALYSIS_HEIGHT: Int = CameraFrameSource.ANALYSIS_HEIGHT
        const val STILL_EDGE: Int = CameraFrameSource.DEFAULT_STILL_EDGE

        /** 200 ms = 5 fps, the FR-N2 floor. */
        const val FRAME_PERIOD_MS: Long = 200
    }
}

/** Draws the synthetic content. `android.graphics` only; runs on a device or emulator, not in JVM tests. */
private object SyntheticScene {

    private const val RECT_WIDTH_FRACTION = 0.2f
    private const val RECT_HEIGHT_FRACTION = 0.45f
    private const val RECT_STEP_PX = 12
    private const val TEXT_BARS = 6

    private val gradientPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 32, 40) }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    /** Gradient background plus one dark rectangle that slides left to right over successive frames. */
    fun paintAnalysisFrame(target: Bitmap, frameIndex: Int) {
        val canvas = Canvas(target)
        drawGradient(canvas, target.width, target.height, Color.rgb(90, 140, 200), Color.rgb(230, 230, 220))
        val rectWidth = (target.width * RECT_WIDTH_FRACTION).toInt()
        val rectHeight = (target.height * RECT_HEIGHT_FRACTION).toInt()
        val travel = (target.width - rectWidth).coerceAtLeast(1)
        val left = (frameIndex * RECT_STEP_PX) % travel
        val top = (target.height - rectHeight) / 2
        canvas.drawRect(
            left.toFloat(),
            top.toFloat(),
            (left + rectWidth).toFloat(),
            (top + rectHeight).toFloat(),
            rectPaint,
        )
    }

    /** A "sign": light gradient, a framed rectangle and a few black bars where text lines would be. */
    fun paintStill(target: Bitmap) {
        val canvas = Canvas(target)
        drawGradient(canvas, target.width, target.height, Color.rgb(240, 240, 235), Color.rgb(200, 205, 215))
        val margin = target.width / 8f
        canvas.drawRect(margin, margin, target.width - margin, target.height - margin, rectPaint)
        val inner = margin * 1.5f
        val barHeight = (target.height - 2 * inner) / (TEXT_BARS * 2f)
        for (i in 0 until TEXT_BARS) {
            val top = inner + i * barHeight * 2
            canvas.drawRect(inner, top, target.width - inner, top + barHeight, barPaint)
        }
    }

    private fun drawGradient(canvas: Canvas, width: Int, height: Int, from: Int, to: Int) {
        gradientPaint.shader =
            LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), from, to, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gradientPaint)
    }
}
