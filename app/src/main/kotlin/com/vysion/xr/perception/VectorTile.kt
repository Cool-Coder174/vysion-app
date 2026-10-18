package com.vysion.xr.perception

/**
 * A Mapbox Vector Tile 2.1 decoder (github.com/mapbox/vector-tile-spec/tree/master/2.1), for FR-N9 "where am I".
 * Pure Kotlin with no protobuf library: protobuf varints and length-delimited fields, zigzag geometry commands.
 * OpenFreeMap serves the OpenMapTiles schema (openmaptiles.org/schema), so a z14 tile has a `transportation_name`
 * layer of named street lines and a `poi` layer of named points; [decode] can skip every other layer.
 *
 * Unit-tested against a real OpenFreeMap tile in `VectorTileTest`. Malformed input throws [IllegalArgumentException].
 */
internal class VectorTile(val layers: List<Layer>) {

    fun layer(name: String): Layer? = layers.firstOrNull { it.name == name }

    class Layer(val name: String, val extent: Int, val features: List<Feature>)

    enum class GeomType { UNKNOWN, POINT, LINESTRING, POLYGON }

    /** [properties] values are `String`, `Float`, `Double`, `Long` or `Boolean`; [geometry] is the raw command stream. */
    class Feature(val type: GeomType, val properties: Map<String, Any>, val geometry: IntArray) {

        /**
         * The geometry in tile pixels (0..extent, y down; a feature may reach a little past the edges), one array per
         * part as `x0, y0, x1, y1, …`: each point of a multipoint, each line of a multiline, each ring of a polygon
         * (closed by repeating its first point).
         */
        fun parts(): List<IntArray> = decodeGeometry(geometry)
    }

    companion object {
        /** OpenMapTiles' extent; the spec's default when a layer omits it. */
        const val DEFAULT_EXTENT: Int = 4096

        /** Decodes [bytes]; with [layerNames], other layers are skipped without decoding their features. */
        fun decode(bytes: ByteArray, layerNames: Set<String>? = null): VectorTile {
            val layers = mutableListOf<Layer>()
            val tile = Proto(bytes, 0, bytes.size)
            while (tile.hasMore()) {
                val key = tile.varint().toInt()
                if (key ushr 3 == TILE_LAYERS && key and 7 == WIRE_BYTES) {
                    val len = tile.length()
                    decodeLayer(bytes, tile.pos, len, layerNames)?.let(layers::add)
                    tile.pos += len
                } else {
                    tile.skip(key and 7)
                }
            }
            return VectorTile(layers)
        }

        private fun decodeLayer(bytes: ByteArray, start: Int, len: Int, wanted: Set<String>?): Layer? {
            val p = Proto(bytes, start, start + len)
            var name = ""
            var extent = DEFAULT_EXTENT
            val keys = mutableListOf<String>()
            val values = mutableListOf<Any>()
            val features = mutableListOf<Int>() // start, length pairs: decoded once the name and the tables are known
            while (p.hasMore()) {
                val key = p.varint().toInt()
                when (key ushr 3) {
                    LAYER_NAME -> name = p.string()
                    LAYER_FEATURES -> {
                        val n = p.length()
                        features += p.pos
                        features += n
                        p.pos += n
                    }
                    LAYER_KEYS -> keys += p.string()
                    LAYER_VALUES -> {
                        val n = p.length()
                        values += decodeValue(Proto(bytes, p.pos, p.pos + n))
                        p.pos += n
                    }
                    LAYER_EXTENT -> extent = p.varint().toInt()
                    else -> p.skip(key and 7)
                }
            }
            if (wanted != null && name !in wanted) return null
            require(extent > 0) { "layer $name has extent $extent" }
            val decoded = ArrayList<Feature>(features.size / 2)
            for (i in features.indices step 2) {
                decoded += decodeFeature(Proto(bytes, features[i], features[i] + features[i + 1]), keys, values)
            }
            return Layer(name, extent, decoded)
        }

        private fun decodeFeature(p: Proto, keys: List<String>, values: List<Any>): Feature {
            var type = GeomType.UNKNOWN
            var tags = IntArray(0)
            var geometry = IntArray(0)
            while (p.hasMore()) {
                val key = p.varint().toInt()
                when (key ushr 3) {
                    FEATURE_TAGS -> tags = p.uints(key and 7, tags)
                    FEATURE_TYPE -> type = GeomType.entries.getOrElse(p.varint().toInt()) { GeomType.UNKNOWN }
                    FEATURE_GEOMETRY -> geometry = p.uints(key and 7, geometry)
                    else -> p.skip(key and 7) // id
                }
            }
            require(tags.size % 2 == 0) { "odd tag count ${tags.size}" }
            val properties = HashMap<String, Any>(tags.size)
            for (i in tags.indices step 2) {
                val k = keys.getOrNull(tags[i]) ?: throw IllegalArgumentException("tag key ${tags[i]} out of range")
                val v = values.getOrNull(tags[i + 1]) ?: throw IllegalArgumentException("tag value ${tags[i + 1]} out of range")
                properties[k] = v
            }
            return Feature(type, properties, geometry)
        }

        private fun decodeValue(p: Proto): Any {
            var value: Any = ""
            while (p.hasMore()) {
                val key = p.varint().toInt()
                value = when (key ushr 3) {
                    1 -> p.string()
                    2 -> Float.fromBits(p.fixed32())
                    3 -> Double.fromBits(p.fixed64())
                    4, 5 -> p.varint()
                    6 -> zigzag(p.varint())
                    7 -> p.varint() != 0L
                    else -> {
                        p.skip(key and 7)
                        value
                    }
                }
            }
            return value
        }

        /** Spec §4.3: MoveTo (1) and LineTo (2) carry `count` zigzag (dx, dy) pairs, ClosePath (7) none. */
        internal fun decodeGeometry(commands: IntArray): List<IntArray> {
            val parts = mutableListOf<IntArray>()
            var current = IntList()
            var x = 0
            var y = 0
            var i = 0
            while (i < commands.size) {
                val command = commands[i] and 7
                val count = commands[i] ushr 3
                i++
                when (command) {
                    MOVE_TO, LINE_TO -> repeat(count) {
                        require(i + 1 < commands.size) { "geometry ends inside a command" }
                        x += zigzag(commands[i].toLong()).toInt()
                        y += zigzag(commands[i + 1].toLong()).toInt()
                        i += 2
                        if (command == MOVE_TO) {
                            if (current.size > 0) parts += current.toArray()
                            current = IntList()
                        }
                        current.add(x, y)
                    }
                    CLOSE_PATH -> if (current.size >= 2) current.add(current[0], current[1])
                    else -> throw IllegalArgumentException("geometry command $command")
                }
            }
            if (current.size > 0) parts += current.toArray()
            return parts
        }

        internal fun zigzag(n: Long): Long = (n ushr 1) xor -(n and 1)

        private const val TILE_LAYERS = 3
        private const val LAYER_NAME = 1
        private const val LAYER_FEATURES = 2
        private const val LAYER_KEYS = 3
        private const val LAYER_VALUES = 4
        private const val LAYER_EXTENT = 5
        private const val FEATURE_TAGS = 2
        private const val FEATURE_TYPE = 3
        private const val FEATURE_GEOMETRY = 4
        private const val MOVE_TO = 1
        private const val LINE_TO = 2
        private const val CLOSE_PATH = 7
        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_BYTES = 2
        private const val WIRE_FIXED32 = 5
    }

    /** A protobuf reader over `bytes[pos, end)`. */
    private class Proto(private val bytes: ByteArray, var pos: Int, private val end: Int) {
        init {
            require(pos in 0..end && end <= bytes.size) { "range $pos..$end outside ${bytes.size} bytes" }
        }

        fun hasMore(): Boolean = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(pos < end) { "varint runs past the end" }
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                require(shift < 64) { "varint longer than 10 bytes" }
            }
        }

        fun length(): Int {
            val n = varint()
            require(n >= 0 && pos + n <= end) { "length $n runs past the end" }
            return n.toInt()
        }

        fun string(): String {
            val n = length()
            return String(bytes, pos, n, Charsets.UTF_8).also { pos += n }
        }

        fun fixed32(): Int {
            require(pos + 4 <= end) { "fixed32 runs past the end" }
            var v = 0
            for (i in 0 until 4) v = v or ((bytes[pos + i].toInt() and 0xff) shl (8 * i))
            pos += 4
            return v
        }

        fun fixed64(): Long {
            require(pos + 8 <= end) { "fixed64 runs past the end" }
            var v = 0L
            for (i in 0 until 8) v = v or ((bytes[pos + i].toLong() and 0xff) shl (8 * i))
            pos += 8
            return v
        }

        /** A repeated uint32 field, packed (wire type 2) or not (wire type 0), appended to [into]. */
        fun uints(wireType: Int, into: IntArray): IntArray = when (wireType) {
            WIRE_BYTES -> {
                val n = length() // before reading pos: length() moves it past the length varint
                val stop = pos + n
                val out = IntList(into)
                while (pos < stop) out.add(varint().toInt())
                require(pos == stop) { "packed field overruns" }
                out.toArray()
            }
            WIRE_VARINT -> into + varint().toInt()
            else -> throw IllegalArgumentException("repeated uint32 with wire type $wireType")
        }

        fun skip(wireType: Int) {
            when (wireType) {
                WIRE_VARINT -> varint()
                WIRE_FIXED64 -> pos += 8
                WIRE_BYTES -> {
                    val n = length()
                    pos += n
                }
                WIRE_FIXED32 -> pos += 4
                else -> throw IllegalArgumentException("wire type $wireType")
            }
            require(pos <= end) { "field runs past the end" }
        }
    }

    /** A growable `IntArray`, so a tile's ~1 M geometry ints are never boxed. */
    private class IntList(initial: IntArray = IntArray(0)) {
        private var data = initial.copyOf(maxOf(initial.size * 2, 8))
        var size = initial.size
            private set

        operator fun get(i: Int): Int = data[i]

        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = v
        }

        fun add(a: Int, b: Int) {
            add(a)
            add(b)
        }

        fun toArray(): IntArray = data.copyOf(size)
    }
}
