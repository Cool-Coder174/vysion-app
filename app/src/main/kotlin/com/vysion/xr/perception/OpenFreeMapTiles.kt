package com.vysion.xr.perception

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * OpenFreeMap z14 vector tiles for FR-N9 (ADR-0010 row "Where am I map"; openfreemap.org: no key, no limits, at most
 * [MAX_PARALLEL] requests at once, a unique `User-Agent`). Downloaded tiles are decoded on the device ([MapTile]) and
 * kept in memory only, the last [capacity] of them; the raw bytes are dropped after decoding and nothing is written
 * to storage. What leaves the headset per request: the tile address, which names a ~1.9 km square (at San Francisco's
 * latitude) around the wearer.
 *
 * Privacy: error messages never carry the URL or the tile numbers (they would locate the wearer in a log line).
 */
internal class OpenFreeMapTiles(
    private val fetch: suspend (TileKey) -> ByteArray,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val decodeContext: CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Mutex()
    private val permits = Semaphore(MAX_PARALLEL)
    private val cache = object : LinkedHashMap<TileKey, MapTile>(capacity, LOAD_FACTOR, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, MapTile>): Boolean = size > capacity
    }

    /** What [get] did, for the `vysion.latency` evidence line (counts and milliseconds only, never which tiles). */
    data class Result(
        val tiles: List<MapTile>,
        val fromMemory: Int,
        val downloaded: Int,
        val failed: Int,
        val bytes: Long,
        val downloadMs: Long,
        val decodeMs: Long,
    )

    /** The tiles for [keys], from memory when there, else downloaded in parallel. A tile that fails is left out. */
    suspend fun get(keys: List<TileKey>): Result = coroutineScope {
        val cached = lock.withLock { keys.associateWith { cache[it] } }
        val missing = keys.filter { cached[it] == null }
        val t0 = System.nanoTime()
        val downloads = missing.map { key ->
            async {
                permits.withPermit {
                    try {
                        key to fetch(key)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null // MapUnavailable or a decoding surprise; the rest of the tiles still answer
                    }
                }
            }
        }.awaitAll()
        val downloadMs = (System.nanoTime() - t0) / NANOS_PER_MS
        val t1 = System.nanoTime()
        val decoded = withContext(decodeContext) {
            downloads.filterNotNull().mapNotNull { (key, bytes) ->
                runCatching { MapTile.decode(key, gunzipIfNeeded(bytes)) }.getOrNull()
            }
        }
        val decodeMs = (System.nanoTime() - t1) / NANOS_PER_MS
        lock.withLock { decoded.forEach { cache[it.key] = it } }
        val byKey = decoded.associateBy { it.key }
        Result(
            tiles = keys.mapNotNull { cached[it] ?: byKey[it] },
            fromMemory = keys.size - missing.size,
            downloaded = decoded.size,
            failed = missing.size - decoded.size,
            bytes = downloads.filterNotNull().sumOf { it.second.size.toLong() },
            downloadMs = if (missing.isEmpty()) 0 else downloadMs,
            decodeMs = decodeMs,
        )
    }

    /** Forget every tile (tests). */
    suspend fun clear() = lock.withLock { cache.clear() }

    companion object {
        /** openfreemap.org: "at most 4 parallel requests". */
        const val MAX_PARALLEL: Int = 4

        /** 16 z14 tiles ≈ 8 × 8 km of city; decoded tiles are a few MB at most. */
        const val DEFAULT_CAPACITY: Int = 16

        const val CONNECT_TIMEOUT_MS: Int = 3_000
        const val READ_TIMEOUT_MS: Int = 6_000

        /** `latest` answered 200 itself, from Cloudflare's cache, with no redirect (checked 2026-10-18). */
        fun urlFor(key: TileKey): String = "https://tiles.openfreemap.org/planet/latest/${key.z}/${key.x}/${key.y}.pbf"

        /**
         * One tile over HTTPS with the app's [userAgent]. The platform client asks for gzip and inflates it; an empty
         * tile (204, or 404 for the sea) is an empty array. Throws [MapUnavailable] with no URL in its message.
         */
        fun httpGet(key: TileKey, userAgent: String): ByteArray {
            val conn = URL(urlFor(key)).openConnection() as java.net.HttpURLConnection // network-fallback: tiles already in memory; else WhereAmI.NoMap, said by Cues
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("User-Agent", userAgent)
                return when (val code = conn.responseCode) {
                    HTTP_OK -> conn.inputStream.use { it.readBytes() }
                    HTTP_NO_CONTENT, HTTP_NOT_FOUND -> ByteArray(0)
                    else -> throw MapUnavailable("tile HTTP $code")
                }
            } catch (e: IOException) {
                throw MapUnavailable("tile download failed: ${e.javaClass.simpleName}")
            } finally {
                conn.disconnect()
            }
        }

        /** A body that is still gzip (a client that did not inflate it) is inflated here; MVT never starts with 1f 8b. */
        internal fun gunzipIfNeeded(bytes: ByteArray): ByteArray =
            if (bytes.size >= 2 && bytes[0] == GZIP_0 && bytes[1] == GZIP_1) {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            } else {
                bytes
            }

        private const val HTTP_OK = 200
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_NOT_FOUND = 404
        private const val GZIP_0: Byte = 0x1f
        private const val GZIP_1: Byte = 0x8b.toByte()
        private const val LOAD_FACTOR = 0.75f
        private const val NANOS_PER_MS = 1_000_000L
    }
}

/** The map could not be downloaded. The message never contains the URL or the tile numbers (location privacy). */
internal class MapUnavailable(message: String) : IOException(message)
