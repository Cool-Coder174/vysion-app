package com.vysion.xr.inference

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.vysion.xr.core.InferenceEngine
import com.vysion.xr.core.InferenceException
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * ADR-0010 row "Cloud Describe": the [InferenceEngine] `AppGraph` wires, wrapping the on-device engine ([headset]).
 * Everything is the headset's except [InferenceEngine.describeScene] with `cloud = true`, which asks [cloud] first:
 *
 * - off, no key built in, or offline ([CloudRoute]): the headset answers, nothing leaves the device;
 * - otherwise the cloud request starts at once, and the headset starts too when the cloud has no text after
 *   [hedgeAfterMs] or fails earlier ([CloudHedge]). The first to produce text answers; the other is cancelled.
 *
 * Why a hedge and not "cloud, then headset after a timeout": the headset needs about 6 s from start to its first
 * words (prefill 6.0 s, then 36 tok/s; `docs/benchmarks.md`, dev runs), so a cloud that misses a short deadline would
 * push the first word past FR-D1's 8 s, and a long deadline would waste a cloud answer that comes at 1.5 s. Starting
 * the headset at [hedgeAfterMs] keeps the worst case at about [hedgeAfterMs] + 6 s + the first sentence, while a
 * cloud that answers any time before the headset still wins. Read, Find the door and arrival never use the cloud.
 *
 * Privacy (ADR-0003 rule 2, INV-3): the still is scaled and JPEG-encoded in memory ([CloudJpeg]) and sent; it is
 * never written to storage or logged. The [Bitmap] stays the caller's and is only read (INV-9); the flow completes
 * only after both sides stopped reading it.
 */
class CloudDescribeEngine(
    private val headset: InferenceEngine,
    private val cloud: CloudDescriber?,
    private val isOnline: () -> Boolean,
    private val encodeJpeg: (Bitmap) -> ByteArray = CloudJpeg::encode,
    private val encodeContext: CoroutineContext = Dispatchers.Default,
    private val hedgeAfterMs: Long = HEDGE_AFTER_MS,
    /** `(tag, line)`; evidence lines go to [TAG_LATENCY], recovered failures to [TAG_RELIABILITY]. */
    private val log: (String, String) -> Unit = { tag, line -> Log.i(tag, line) },
) : InferenceEngine by headset {

    override val cloudDescribeAvailable: Boolean get() = cloud != null

    override fun describeScene(bitmap: Bitmap, prompt: String, cloud: Boolean, maxNewTokens: Int): Flow<String> = flow {
        val onHeadset = headset.describeStreaming(bitmap, prompt, maxNewTokens)
        val describer = this@CloudDescribeEngine.cloud
        val skip = CloudRoute.skip(requested = cloud, available = describer != null, isOnline = isOnline)
        if (skip != null || describer == null) {
            if (cloud) log(TAG_LATENCY, "describe source=headset cloud=${skip?.label}")
            emitAll(onHeadset)
            return@flow
        }
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / NANOS_PER_MS
        val fromCloud = flow {
            val jpeg = withContext(encodeContext) { encodeJpeg(bitmap) }
            emitAll(describer.stream(jpeg, prompt))
        }
        emitAll(
            CloudHedge.race(fromCloud, onHeadset, hedgeAfterMs) { event ->
                when (event) {
                    is CloudHedge.Event.Won -> log(
                        TAG_LATENCY,
                        "describe source=${event.source.label} first-chunk ms=${ms()} model=${describer.model}",
                    )
                    CloudHedge.Event.HeadsetStarted -> log(TAG_LATENCY, "describe headset started ms=${ms()}")
                    is CloudHedge.Event.Failed -> log(
                        TAG_RELIABILITY,
                        "describe ${event.source.label} failed ms=${ms()}: ${event.cause.message}",
                    )
                }
            },
        )
    }

    companion object {
        /**
         * FR-D1's 8 s minus the headset's ~6 s to its first words, minus ~1 s for the first sentence and TTS start
         * (dev-run numbers, `docs/benchmarks.md`). Measure capture → first word with the cloud unreachable on the
         * headset before changing it.
         */
        const val HEDGE_AFTER_MS: Long = 1_000

        const val TAG_LATENCY: String = "vysion.latency"
        const val TAG_RELIABILITY: String = "vysion.reliability"
        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}

/** ADR-0010 rule 2: when Describe does not even try the cloud. Pure, unit-tested. */
internal enum class CloudRoute(val label: String) {
    OFF("off"),
    NOT_SET_UP("not-set-up"),
    OFFLINE("offline");

    companion object {
        /** `null` = try the cloud. [isOnline] is only asked when the wearer turned it on and a key exists. */
        fun skip(requested: Boolean, available: Boolean, isOnline: () -> Boolean): CloudRoute? = when {
            !requested -> OFF
            !available -> NOT_SET_UP
            !isOnline() -> OFFLINE
            else -> null
        }
    }
}

/**
 * The race behind [CloudDescribeEngine]: the cloud starts at once, the headset at [race]'s `hedgeAfterMs` or as soon
 * as the cloud fails, and the first side with a non-blank chunk wins and is forwarded as is; the other is cancelled
 * (the headset engine then stops its runner, the cloud drops its socket). A side that fails or ends with no text
 * before winning just loses; when both lose the flow throws [InferenceException]. A winner that fails part way
 * throws too, so the controller says the answer failed instead of passing a cut sentence as complete (SRS §3 rule 5).
 */
internal object CloudHedge {
    enum class Source(val label: String) { CLOUD("cloud"), HEADSET("headset") }

    sealed interface Event {
        data class Won(val source: Source) : Event
        data class Failed(val source: Source, val cause: Throwable) : Event
        data object HeadsetStarted : Event
    }

    fun race(
        cloud: Flow<String>,
        headset: Flow<String>,
        hedgeAfterMs: Long,
        onEvent: (Event) -> Unit = {},
    ): Flow<String> = channelFlow {
        val winner = AtomicReference<Source?>(null)
        val losses = AtomicInteger(0)
        val cloudLost = CompletableDeferred<Unit>()
        lateinit var cloudJob: Job
        lateinit var headsetJob: Job
        lateinit var starter: Job

        suspend fun run(source: Source, chunks: Flow<String>) {
            var won = false
            try {
                chunks.collect { chunk ->
                    if (!won) {
                        if (chunk.isBlank()) return@collect // a leading blank token decides nothing
                        if (!winner.compareAndSet(null, source)) throw CancellationException("lost to ${winner.get()}")
                        won = true
                        onEvent(Event.Won(source))
                        starter.cancel()
                        (if (source == Source.CLOUD) headsetJob else cloudJob).cancel()
                    }
                    send(chunk)
                }
                if (!won) throw InferenceException("${source.label} returned no text")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (won) throw t
                onEvent(Event.Failed(source, t))
                if (losses.incrementAndGet() == 2) throw InferenceException("cloud and headset both failed", t)
                if (source == Source.CLOUD) cloudLost.complete(Unit)
            }
        }

        headsetJob = launch(start = CoroutineStart.LAZY) { run(Source.HEADSET, headset) }
        starter = launch(start = CoroutineStart.LAZY) {
            withTimeoutOrNull(hedgeAfterMs) { cloudLost.await() }
            if (winner.get() == null) {
                onEvent(Event.HeadsetStarted)
                headsetJob.start()
            }
        }
        cloudJob = launch(start = CoroutineStart.LAZY) { run(Source.CLOUD, cloud) }
        cloudJob.start()
        starter.start()
    }
}

/** The still as the cloud gets it: longest edge at most [MAX_EDGE] px, JPEG, in memory only (ADR-0010 row). */
internal object CloudJpeg {
    /** One 768 px tile is plenty for a scene description and keeps the upload near 100 KB. */
    const val MAX_EDGE: Int = 768
    const val QUALITY: Int = 80

    fun encode(bitmap: Bitmap): ByteArray {
        if (bitmap.isRecycled) throw InferenceException("cloud describe: bitmap already recycled (INV-9)")
        val scale = MAX_EDGE.toFloat() / max(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt(), (bitmap.height * scale).roundToInt(), true)
        } else {
            bitmap
        }
        try {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) // privacy-allow: in-memory JPEG for cloud Describe (ADR-0010 row), never stored
            return out.toByteArray()
        } finally {
            if (scaled !== bitmap) scaled.recycle() // ours; the caller's still is only read (INV-9)
        }
    }
}

/**
 * ADR-0010: "online" = the active network has internet and Android validated it, read at every Describe so a
 * dropped Wi-Fi skips the cloud at once instead of waiting for a connect timeout. Needs `ACCESS_NETWORK_STATE`.
 */
class NetworkStatus(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun isOnline(): Boolean {
        val cm = connectivity ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
