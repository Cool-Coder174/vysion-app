package com.vysion.xr.platform

import android.app.Application
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.vysion.xr.core.FrameSource
import com.vysion.xr.core.FrameStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Provides [FrameSource] on the headset and the phone: the world-facing camera through CameraX
 * (FR-T1, FR-D1, FR-N2; SRS §8 R1 "camera pipeline", handoff due Sat 13:00). Owner: R1.
 * Constructor `(Application, CoroutineScope)` per `AppGraph`.
 *
 * **Two-stream design (SRS §6, ADR-0004).** One `ImageAnalysis` use case delivers the 640x480
 * RGBA_8888 analysis stream with `STRATEGY_KEEP_ONLY_LATEST` into [analysisFrames], a
 * `SharedFlow` with a one-slot buffer that drops the oldest frame, so the detector never queues
 * stale frames (collectors still `conflate()`); one `ImageCapture` use case
 * (`CAPTURE_MODE_MINIMIZE_LATENCY`) answers [captureStill] on demand, center-cropped and resized to
 * the VLM input edge ([stillEdge]). Both bind to the Camera2 id "0" selector (world camera per
 * docs/stack.md → R1; A-1 verifies it on the device Sat 11:00, RK-1 fallback at Sat 12:00).
 *
 * **Bitmap ownership (INV-9).** A `Bitmap` returned by [captureStill] has exactly one owner, the
 * caller (`ModeController`), who `recycle()`s it. Bitmaps emitted on [analysisFrames] are owned by
 * this source and are never recycled by a collector: each frame is a FRESH `Bitmap`
 * (`ImageProxy.toBitmap()`), reclaimed by the GC once no collector holds it. There is deliberately no
 * reuse pool: with `conflate()` up to three emitted frames are referenced at once (the SharedFlow slot,
 * the conflate slot, the consumer's in-flight frame, which `engine.detect` may read much later behind a
 * VLM call), so a small pool would be repainted while still being read (plan.md F2 task 2.1).
 *
 * **Privacy (ADR-0003, INV-3).** Frames exist only in memory. The still is taken with the
 * in-memory `takePicture` overload only; there is no file-output capture, no bitmap encoding to a
 * stream, no media-store insert and no log line with pixel data anywhere in this file;
 * `scripts/check_privacy.sh` greps for all of them.
 *
 * APIs: docs/stack.md → R1 table rows "Camera id filter" (D6 opt-in), "Resolution",
 * "Output format", "Bitmap", "Still", "Provider"; all VERIFIED BS-PS/BS-AC and re-checked with
 * `javap` against camera-core/camera-camera2/camera-lifecycle 1.6.2 in the Gradle cache.
 * Assumptions: A-1 (frames arrive with only `CAMERA`), A-12 (same class on the S25 Ultra).
 */
class CameraFrameSource(
    private val app: Application,
    private val scope: CoroutineScope,
) : FrameSource {

    private val _frames = MutableSharedFlow<Bitmap>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST, // latest-wins, like STRATEGY_KEEP_ONLY_LATEST
    )

    /** 640x480 analysis stream; see the class KDoc for ownership. */
    override val analysisFrames: Flow<Bitmap> = _frames.asSharedFlow()

    private val _stats = MutableStateFlow<FrameStats?>(null)

    /** `null` until the first analysis frame arrives (A-1, NFR "Honesty"); fps over a 5 s window. */
    override val stats: StateFlow<FrameStats?> = _stats

    /**
     * Edge of the square still handed to the VLM. The VLM input size is `ModelInfo.inputWidth` from
     * R2's engine, which this constructor cannot see; `AppGraph` collects `InferenceEngine.vlmInfo` and
     * sets it once the VLM has loaded (r1 module doc, open issue 1, closed). Default per
     * docs/model-plan.md (Gemma 3 4B, 896, UNVERIFIED).
     */
    @Volatile
    var stillEdge: Int = DEFAULT_STILL_EDGE

    private val fpsMeter = FpsMeter()
    /**
     * The analyzer thread. Lives as long as the process: [stop] is reversible (AppGraph.stop on every onStop)
     * and [analysis] stays bound to this executor, so it is never shut down (FrameSource has no release).
     */
    private val analysisExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    /**
     * Camera2 id "0" = world-facing per Google's statement (docs/stack.md → R1, secondary source,
     * unverified on device). Change the literal after the Sat 11:00 probe; `DEFAULT_BACK_CAMERA`
     * selects `LENS_FACING_BACK`, not literally id 0.
     */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class) // docs/stack.md D6
    val worldCameraSelector: CameraSelector by lazy {
        CameraSelector.Builder()
            .addCameraFilter { infos -> infos.filter { Camera2CameraInfo.from(it).getCameraId() == WORLD_CAMERA_ID } }
            .build()
    }

    /** The analysis use case; the analyzer runs on [analysisExecutor], never on main. */
    private val analysis: ImageAnalysis by lazy {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase -> useCase.setAnalyzer(analysisExecutor) { image -> onAnalysisFrame(image) } }
    }

    /** The still use case for [captureStill]. */
    private val still: ImageCapture by lazy {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }

    /**
     * FR-T1 / FR-N2: bind both use cases to the world camera. Device-only; stub per the skeleton
     * rules. The intended body (docs/stack.md → R1 "Provider", VERIFIED) is:
     * ```
     * val provider = ProcessCameraProvider.awaitInstance(app)      // import androidx.camera.lifecycle.awaitInstance
     * provider.unbindAll()
     * provider.bindToLifecycle(lifecycleOwner, worldCameraSelector, analysis, still)
     * ```
     * where `lifecycleOwner` is a `LifecycleRegistry` this class drives from [start]/[stop] on
     * `Dispatchers.Main` (the constructor has no Activity; `AppGraph` is Application-scoped). Owner snippet
     * (`LifecycleRegistry(LifecycleOwner)`, `currentState` get/set, `LifecycleOwner.lifecycle`: javap against
     * lifecycle-runtime / lifecycle-common 2.11.0, docs/stack.md "Corrections found at build time"):
     * ```
     * private val owner = object : LifecycleOwner {
     *     val registry = LifecycleRegistry(this)
     *     override val lifecycle: Lifecycle get() = registry
     * }
     * // start(): withContext(Dispatchers.Main) {
     * //     if (owner.registry.currentState == Lifecycle.State.INITIALIZED) {
     * //         owner.registry.currentState = Lifecycle.State.CREATED
     * //         provider.bindToLifecycle(owner, worldCameraSelector, analysis, still)   // bind once
     * //     }
     * //     owner.registry.currentState = Lifecycle.State.STARTED                         // frames flow
     * // }
     * // stop():  withContext(Dispatchers.Main) { owner.registry.currentState = Lifecycle.State.CREATED }
     * ```
     * Never move the registry to DESTROYED: a destroyed lifecycle cannot start again and [stop] is reversible.
     * Hour-one probe (A-1): log `CameraManager.cameraIdList` and the chosen id's characteristics
     * under tag `vysion`; if id "0" yields nothing, take RK-1 at Sat 12:00. Also log the horizontal FOV of
     * the still after the center crop, which R3 needs for `BearingAngles` (stack.md Q3 correction):
     * `hfov = 2·atan(SENSOR_INFO_PHYSICAL_SIZE.width / (2·LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0]))`, then for the
     * square crop of a W×H (W ≥ H) frame `hfovCrop = 2·atan((H / W)·tan(hfov / 2))`; log
     * `vysion: still hfov=<hfovCrop> deg (A-1)`. Metadata only (INV-3).
     */
    override suspend fun start() {
        Log.w(
            TAG,
            "TODO(FR-T1) CameraFrameSource.start: ProcessCameraProvider.awaitInstance(app) then " +
                "bindToLifecycle(lifecycleOwner, worldCameraSelector, analysis, still); log FrameStats (A-1)",
        )
    }

    /**
     * Unbind the use cases (reversible: [start] rebinds). Keeps [analysisExecutor], which lives as long as
     * the process. [stats] goes back to `null` (no frames while stopped, NFR "Honesty"). Never throws.
     */
    override suspend fun stop() {
        _stats.value = null
        Log.w(
            TAG,
            "TODO(FR-T1) CameraFrameSource.stop: lifecycleRegistry.currentState = CREATED (or provider.unbindAll()) " +
                "on Main; keep analysisExecutor, stop is reversible (AGENTS.md §5)",
        )
    }

    /**
     * FR-T1 / FR-D1: one still, center-cropped and resized to [stillEdge] x [stillEdge]. The caller
     * owns the result (INV-9). Intended body (docs/stack.md → R1 "Still", VERIFIED):
     * ```
     * val full = suspendCancellableCoroutine<Bitmap> { cont ->
     *     still.takePicture(ContextCompat.getMainExecutor(app), object : ImageCapture.OnImageCapturedCallback() {
     *         override fun onCaptureSuccess(image: ImageProxy) { cont.resume(image.use { it.toBitmap() }) }
     *         override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
     *     })
     * }
     * return CenterCrop.apply(full, stillEdge, stillEdge).also { if (it !== full) full.recycle() }
     * ```
     * Rotation (docs/stack.md → R1 "Bitmap", corrected): read it inside `onCaptureSuccess`, before the
     * `ImageProxy` closes, and rotate before cropping:
     * ```
     * val r = image.imageInfo.rotationDegrees
     * val raw = image.use { it.toBitmap() }
     * val upright = if (r == 0) raw else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height,
     *     Matrix().apply { postRotate(r.toFloat()) }, true).also { if (it !== raw) raw.recycle() }
     * cont.resume(upright)
     * ```
     * Log once per process after the crop, metadata only (INV-3; name locals so `scripts/check_privacy.sh` §4 does not
     * see `bitmap.` / `frame.` in the log call): `val w = upright.width; val h = upright.height`, then
     * `Log.i(TAG, "still ${w}x$h rotation=$r -> ${stillEdge}x$stillEdge (A-1)")`. Record it in the A-1 Evidence column.
     */
    override suspend fun captureStill(): Bitmap =
        TODO("FR-T1: ImageCapture.takePicture(mainExecutor, OnImageCapturedCallback) + CenterCrop.apply " +
            "(docs/stack.md → R1 'Still')")

    /** Analyzer callback: measure fps, convert, publish. Runs on [analysisExecutor]. */
    private fun onAnalysisFrame(image: ImageProxy) {
        image.use { frame ->
            val nowNanos = SystemClock.elapsedRealtimeNanos() // H-21: monotonic clock only
            val fps = fpsMeter.onFrame(nowNanos)
            if (fps != null) {
                // NFR Honesty / A-1 "note resolution and format": size and format are read from the frame, not
                // typed. The id is the only one the selector admits ([worldCameraSelector] filters on it).
                _stats.value = FrameStats(
                    cameraId = WORLD_CAMERA_ID,
                    width = frame.width,
                    height = frame.height,
                    format = imageFormatName(frame.format),
                    fps = fps,
                )
            }
            // docs/stack.md → R1 "Bitmap": ImageProxy.toBitmap() supports RGBA_8888;
            // rotation via imageInfo.rotationDegrees.
            _frames.tryEmit(frame.toBitmap())
        }
    }

    companion object {
        private const val TAG = "vysion"

        /** Camera2 id of the world-facing stream (docs/stack.md → R1 "World camera id"). */
        const val WORLD_CAMERA_ID: String = "0"

        /** SRS §6: "a 640x480 analysis stream for the detector". */
        const val ANALYSIS_WIDTH: Int = 640
        const val ANALYSIS_HEIGHT: Int = 480

        /** docs/model-plan.md: Gemma 3 4B image input 896×896 (UNVERIFIED); overridden from `ModelInfo` at runtime. */
        const val DEFAULT_STILL_EDGE: Int = 896
    }
}

/**
 * Frames-per-second over a sliding window of monotonic timestamps (NFR "Honesty": fps is measured,
 * never typed). Pure; unit-tested in `ModeControllerTest.kt` (`FrameMathTest`). Not thread-safe:
 * call it from the single analysis thread.
 */
internal class FpsMeter(private val windowNanos: Long = DEFAULT_WINDOW_NANOS) {

    private val stamps = ArrayDeque<Long>()

    /** Records a frame at [nowNanos]; returns the window fps, or `null` until two frames are in the window. */
    fun onFrame(nowNanos: Long): Float? {
        stamps.addLast(nowNanos)
        while (stamps.size > 1 && nowNanos - stamps.first() > windowNanos) stamps.removeFirst()
        if (stamps.size < 2) return null
        val spanNanos = nowNanos - stamps.first()
        if (spanNanos <= 0L) return null
        return (stamps.size - 1) * NANOS_PER_SECOND / spanNanos
    }

    companion object {
        /** docs/nfr.md: detector throughput is a 5 s sliding window; the camera uses the same window. */
        const val DEFAULT_WINDOW_NANOS: Long = 5_000_000_000L
        private const val NANOS_PER_SECOND: Float = 1_000_000_000f
    }
}

/**
 * Name of an `ImageProxy.getFormat()` value for `FrameStats.format` (A-1 evidence). CameraX reports
 * `PixelFormat.RGBA_8888` (1) for `OUTPUT_IMAGE_FORMAT_RGBA_8888`, `ImageFormat.YUV_420_888` (35) and
 * `ImageFormat.JPEG` (256); anything else is printed as its number, never guessed.
 */
internal fun imageFormatName(format: Int): String = when (format) {
    PixelFormat.RGBA_8888 -> "RGBA_8888"
    ImageFormat.YUV_420_888 -> "YUV_420_888"
    ImageFormat.JPEG -> "JPEG"
    else -> "format $format"
}

/** A crop rectangle in source pixels. */
internal data class CropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * "Center-cropped and resized to the VLM input size" (SRS §6). [rect] is pure and unit-tested;
 * [apply] is the one `android.graphics` call site and runs only on a device or emulator.
 */
internal object CenterCrop {

    /** Largest centered rectangle of [srcW] x [srcH] with the aspect ratio of [dstW] x [dstH]. */
    fun rect(srcW: Int, srcH: Int, dstW: Int, dstH: Int): CropRect {
        require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0) {
            "sizes must be positive: $srcW x $srcH -> $dstW x $dstH"
        }
        // Integer cross-multiplication: no float drift on the aspect comparison.
        return if (srcW.toLong() * dstH >= srcH.toLong() * dstW) {
            // Source is at least as wide as the target aspect: keep the full height, trim the sides.
            val width = (srcH.toLong() * dstW / dstH).toInt().coerceIn(1, srcW)
            CropRect(left = (srcW - width) / 2, top = 0, width = width, height = srcH)
        } else {
            val height = (srcW.toLong() * dstH / dstW).toInt().coerceIn(1, srcH)
            CropRect(left = 0, top = (srcH - height) / 2, width = srcW, height = height)
        }
    }

    /**
     * Crops then scales [src] to [dstW] x [dstH]. [src] is not recycled here (its owner decides,
     * INV-9); the result may be [src] itself when no change was needed, so compare by identity.
     */
    fun apply(src: Bitmap, dstW: Int, dstH: Int): Bitmap {
        val r = rect(src.width, src.height, dstW, dstH)
        val cropped = Bitmap.createBitmap(src, r.left, r.top, r.width, r.height)
        if (cropped.width == dstW && cropped.height == dstH) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, dstW, dstH, true)
        if (cropped !== src && cropped !== scaled) cropped.recycle()
        return scaled
    }
}
