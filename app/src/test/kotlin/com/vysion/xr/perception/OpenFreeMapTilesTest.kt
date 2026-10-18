package com.vysion.xr.perception

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-N9: tiles come from memory when they can, at most four download at once, and a failure only drops that tile. */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenFreeMapTilesTest {

    private val tileBytes = VectorTileTest.civicCenterTile()
    private val home = TileKey(14, 2620, 6332)

    @Test
    fun `a second question about the same area downloads nothing`() = runTest {
        var fetches = 0
        val tiles = OpenFreeMapTiles(fetch = { fetches++; tileBytes }, decodeContext = StandardTestDispatcher(testScheduler))
        val first = tiles.get(listOf(home))
        assertEquals(1, first.downloaded)
        assertEquals(tileBytes.size.toLong(), first.bytes)
        assertEquals(home, first.tiles.single().key)
        val second = tiles.get(listOf(home))
        assertEquals(1, second.fromMemory)
        assertEquals(0, second.downloaded)
        assertEquals(1, fetches)
    }

    @Test
    fun `no more than four downloads run at once`() = runTest {
        var running = 0
        var peak = 0
        val tiles = OpenFreeMapTiles(
            fetch = {
                running++
                peak = maxOf(peak, running)
                delay(100)
                running--
                ByteArray(0)
            },
            decodeContext = StandardTestDispatcher(testScheduler),
        )
        val keys = (0 until 6).map { TileKey(14, 2620 + it, 6332) }
        val result = tiles.get(keys)
        assertEquals(OpenFreeMapTiles.MAX_PARALLEL, peak)
        assertEquals(6, result.tiles.size) // empty tiles (the sea) are still tiles
    }

    @Test
    fun `a tile that fails to download is left out and the others answer`() = runTest {
        val neighbor = TileKey(14, 2621, 6332)
        val tiles = OpenFreeMapTiles(
            fetch = { key -> if (key == neighbor) throw MapUnavailable("tile HTTP 503") else tileBytes },
            decodeContext = StandardTestDispatcher(testScheduler),
        )
        val result = tiles.get(listOf(home, neighbor))
        assertEquals(listOf(home), result.tiles.map { it.key })
        assertEquals(1, result.failed)
    }

    @Test
    fun `the least recently used tile is forgotten past the capacity`() = runTest {
        var fetches = 0
        val tiles = OpenFreeMapTiles(fetch = { fetches++; ByteArray(0) }, capacity = 2, decodeContext = StandardTestDispatcher(testScheduler))
        val a = TileKey(14, 1, 1)
        val b = TileKey(14, 2, 1)
        val c = TileKey(14, 3, 1)
        tiles.get(listOf(a))
        tiles.get(listOf(b))
        tiles.get(listOf(a)) // a is now the most recent
        tiles.get(listOf(c)) // evicts b
        assertEquals(3, fetches)
        assertEquals(1, tiles.get(listOf(a)).fromMemory)
        assertEquals(0, tiles.get(listOf(b)).fromMemory)
    }

    @Test
    fun `a body still gzipped is inflated, a plain one is left alone`() {
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tileBytes) } }.toByteArray()
        assertArrayEquals(tileBytes, OpenFreeMapTiles.gunzipIfNeeded(zipped))
        assertArrayEquals(tileBytes, OpenFreeMapTiles.gunzipIfNeeded(tileBytes))
    }

    @Test
    fun `the URL is OpenFreeMap's planet path, and an error never carries it`() {
        assertEquals("https://tiles.openfreemap.org/planet/latest/14/2620/6332.pbf", OpenFreeMapTiles.urlFor(home))
        val message = MapUnavailable("tile download failed: UnknownHostException").message.orEmpty()
        assertFalse(message.contains("2620"))
        assertTrue(message.startsWith("tile"))
    }
}
