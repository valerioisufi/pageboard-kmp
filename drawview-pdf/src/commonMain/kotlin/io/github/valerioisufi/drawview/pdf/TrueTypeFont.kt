package io.github.valerioisufi.drawview.pdf

/**
 * A TrueType font (outlines in its `glyf` table): the glyphs of the characters, their advances
 * and outlines, and subsets of it to embed in a PDF (see [subset]).
 *
 * Throws [IllegalArgumentException] when [data] is not a TrueType font.
 */
public class TrueTypeFont(private val data: ByteArray) {
    private val tables: Map<String, Table>

    /** Units of the coordinates of the glyphs in one em. */
    public val unitsPerEm: Int

    /** Number of glyphs of the font. */
    internal val glyphCount: Int

    private val longLoca: Boolean
    private val hMetricCount: Int
    private val cmap: Map<Int, Int>

    /** Whether the characters are mapped from the private area of a symbol font (`0xF000` + code). */
    private val symbolCmap: Boolean

    private class Table(val offset: Int, val length: Int)

    init {
        require(data.size >= 12) { "Not a font" }
        val version = u32(0)
        require(version == 0x00010000 || version == 0x74727565) { "Not a TrueType font" }
        val count = u16(4)
        tables = (0 until count).associate { i ->
            val entry = 12 + i * 16
            require(entry + 16 <= data.size) { "Truncated font" }
            tag(entry) to Table(u32(entry + 8), u32(entry + 12))
        }
        for (required in listOf("head", "maxp", "hhea", "hmtx", "loca", "glyf", "cmap")) {
            val table = requireNotNull(tables[required]) { "No $required table" }
            require(table.offset >= 0 && table.offset + table.length <= data.size) { "Truncated $required table" }
        }
        val head = table("head")
        unitsPerEm = u16(head + 18)
        longLoca = s16(head + 50) == 1
        glyphCount = u16(table("maxp") + 4)
        hMetricCount = u16(table("hhea") + 34)
        val (map, symbol) = readCmap()
        cmap = map
        symbolCmap = symbol
    }

    /** The glyph of [codePoint], or 0 (the missing glyph). */
    public fun glyphId(codePoint: Int): Int {
        cmap[codePoint]?.let { return it }
        // A symbol font maps its characters in the private area (as FreeType and Skia read it)
        if (symbolCmap && codePoint in 0..0xFF) cmap[0xF000 or codePoint]?.let { return it }
        return 0
    }

    /**
     * Place of the strikeout above the baseline and its thickness, in font units (from the `OS/2`
     * table), or `null`.
     */
    public fun strikeout(): Pair<Int, Int>? {
        val os2 = tables["OS/2"] ?: return null
        if (os2.length < 30) return null
        return s16(os2.offset + 28) to s16(os2.offset + 26)
    }

    /**
     * Place of the underline relative to the baseline (negative below it) and its thickness, in
     * font units (from the `post` table), or `null`.
     */
    public fun underline(): Pair<Int, Int>? {
        val post = tables["post"] ?: return null
        if (post.length < 12) return null
        return s16(post.offset + 8) to s16(post.offset + 10)
    }

    /** Whether the font has a glyph for [codePoint]. */
    public fun covers(codePoint: Int): Boolean = glyphId(codePoint) != 0

    /** Advance of [glyphId] in font units. */
    public fun advance(glyphId: Int): Int {
        val hmtx = table("hmtx")
        val index = if (glyphId < hMetricCount) glyphId else hMetricCount - 1
        if (index < 0) return 0
        return u16(hmtx + index * 4)
    }

    /** Advance of [text] in font units (its characters one after the other). */
    public fun advance(text: String): Int {
        var total = 0
        var i = 0
        while (i < text.length) {
            val codePoint = codePointAt(text, i)
            total += advance(glyphId(codePoint))
            i += if (codePoint > 0xFFFF) 2 else 1
        }
        return total
    }

    /**
     * Outline of [glyphId] in font units (y going up): closed contours of quadratic Bézier curves,
     * as points with whether they are on the curve (two points off the curve imply one on it, in
     * the middle). Empty for a glyph without outline (a space).
     */
    public fun outline(glyphId: Int): List<GlyphContour> = outline(glyphId, 0)

    private fun outline(glyphId: Int, depth: Int): List<GlyphContour> {
        if (glyphId !in 0 until glyphCount || depth > MAX_COMPOSITE_DEPTH) return emptyList()
        val (start, end) = glyphRange(glyphId) ?: return emptyList()
        if (end - start < 10) return emptyList()
        val contourCount = s16(start)
        return if (contourCount >= 0) simpleOutline(start, end, contourCount) else compositeOutline(start, end, depth)
    }

    private fun simpleOutline(start: Int, end: Int, contourCount: Int): List<GlyphContour> {
        var p = start + 10
        val endPoints = IntArray(contourCount) { u16(p + it * 2) }
        p += contourCount * 2
        val pointCount = if (contourCount == 0) 0 else endPoints.last() + 1
        val instructionLength = u16(p)
        p += 2 + instructionLength
        val flags = IntArray(pointCount)
        var i = 0
        while (i < pointCount) {
            check(p < end) { "Truncated glyph" }
            val flag = byte(p++)
            flags[i++] = flag
            if (flag and REPEAT != 0) {
                val repeat = byte(p++)
                repeat(repeat) { if (i < pointCount) flags[i++] = flag }
            }
        }
        val xs = IntArray(pointCount)
        var x = 0
        for (k in 0 until pointCount) {
            val flag = flags[k]
            if (flag and X_SHORT != 0) {
                val dx = byte(p++)
                x += if (flag and X_SAME_OR_POSITIVE != 0) dx else -dx
            } else if (flag and X_SAME_OR_POSITIVE == 0) {
                x += s16(p)
                p += 2
            }
            xs[k] = x
        }
        val ys = IntArray(pointCount)
        var y = 0
        for (k in 0 until pointCount) {
            val flag = flags[k]
            if (flag and Y_SHORT != 0) {
                val dy = byte(p++)
                y += if (flag and Y_SAME_OR_POSITIVE != 0) dy else -dy
            } else if (flag and Y_SAME_OR_POSITIVE == 0) {
                y += s16(p)
                p += 2
            }
            ys[k] = y
        }
        var first = 0
        return endPoints.map { last ->
            val contour = GlyphContour(
                FloatArray(last - first + 1) { xs[first + it].toFloat() },
                FloatArray(last - first + 1) { ys[first + it].toFloat() },
                BooleanArray(last - first + 1) { flags[first + it] and ON_CURVE != 0 }
            )
            first = last + 1
            contour
        }
    }

    private fun compositeOutline(start: Int, end: Int, depth: Int): List<GlyphContour> {
        val contours = mutableListOf<GlyphContour>()
        var p = start + 10
        while (true) {
            check(p + 4 <= end) { "Truncated composite glyph" }
            val flags = u16(p)
            val component = u16(p + 2)
            p += 4
            val dx: Float
            val dy: Float
            if (flags and ARG_1_AND_2_ARE_WORDS != 0) {
                dx = s16(p).toFloat()
                dy = s16(p + 2).toFloat()
                p += 4
            } else {
                dx = data[p].toFloat()
                dy = data[p + 1].toFloat()
                p += 2
            }
            var a = 1f
            var b = 0f
            var c = 0f
            var d = 1f
            when {
                flags and WE_HAVE_A_SCALE != 0 -> {
                    a = f2dot14(p)
                    d = a
                    p += 2
                }
                flags and WE_HAVE_AN_X_AND_Y_SCALE != 0 -> {
                    a = f2dot14(p)
                    d = f2dot14(p + 2)
                    p += 4
                }
                flags and WE_HAVE_A_TWO_BY_TWO != 0 -> {
                    a = f2dot14(p)
                    b = f2dot14(p + 2)
                    c = f2dot14(p + 4)
                    d = f2dot14(p + 6)
                    p += 8
                }
            }
            // Point matching (ARGS_ARE_XY_VALUES not set) is rare: the component is not moved
            val offset = flags and ARGS_ARE_XY_VALUES != 0
            for (contour in outline(component, depth + 1)) {
                contours += GlyphContour(
                    FloatArray(contour.size) { a * contour.xs[it] + c * contour.ys[it] + if (offset) dx else 0f },
                    FloatArray(contour.size) { b * contour.xs[it] + d * contour.ys[it] + if (offset) dy else 0f },
                    contour.onCurve
                )
            }
            if (flags and MORE_COMPONENTS == 0) break
        }
        return contours
    }

    /**
     * This font with the glyphs of [codePoints] only (and the missing glyph): the other glyphs are
     * empty and keep their identifiers, the characters map to the glyphs kept only, the tables of
     * the layout (already done by whoever placed the text) are left out. Its characters are the
     * ones of a PDF, its size a small part of the font's.
     */
    public fun subset(codePoints: Set<Int>): ByteArray {
        // The characters in their order (as the subtables of a cmap want them)
        val mapping = LinkedHashMap<Int, Int>()
        for (codePoint in codePoints.sorted()) glyphId(codePoint).takeIf { it != 0 }?.let { mapping[codePoint] = it }
        val kept = mutableSetOf(0)
        for (glyph in mapping.values) addWithComponents(glyph, kept, 0)

        // glyf and loca (long offsets)
        val glyf = ByteBuilder()
        val loca = ByteBuilder()
        for (glyph in 0 until glyphCount) {
            loca.u32(glyf.size)
            if (glyph in kept) {
                glyphRange(glyph)?.let { (start, end) -> glyf.bytes(data, start, end - start) }
                glyf.pad(4)
            }
        }
        loca.u32(glyf.size)

        val head = tableBytes("head").also {
            // checkSumAdjustment set at the end, long offsets in loca
            it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 0
            it[50] = 0; it[51] = 1
        }
        val post = ByteBuilder().apply {
            u32(0x00030000)
            bytes(data, table("post") + 4, 28)
        }.toByteArray()

        val out = mutableMapOf(
            "head" to head,
            "hhea" to tableBytes("hhea"),
            "maxp" to tableBytes("maxp"),
            "hmtx" to tableBytes("hmtx"),
            "cmap" to cmapTable(mapping),
            "loca" to loca.toByteArray(),
            "glyf" to glyf.toByteArray(),
            "post" to post
        )
        for (optional in listOf("OS/2", "name", "cvt ", "fpgm", "prep", "gasp")) {
            if (optional in tables) out[optional] = tableBytes(optional)
        }
        return writeFont(out)
    }

    private fun addWithComponents(glyph: Int, kept: MutableSet<Int>, depth: Int) {
        if (glyph !in 0 until glyphCount || depth > MAX_COMPOSITE_DEPTH || !kept.add(glyph)) return
        val (start, end) = glyphRange(glyph) ?: return
        if (end - start < 10 || s16(start) >= 0) return
        var p = start + 10
        while (p + 4 <= end) {
            val flags = u16(p)
            addWithComponents(u16(p + 2), kept, depth + 1)
            p += 4 + (if (flags and ARG_1_AND_2_ARE_WORDS != 0) 4 else 2) + when {
                flags and WE_HAVE_A_SCALE != 0 -> 2
                flags and WE_HAVE_AN_X_AND_Y_SCALE != 0 -> 4
                flags and WE_HAVE_A_TWO_BY_TWO != 0 -> 8
                else -> 0
            }
            if (flags and MORE_COMPONENTS == 0) break
        }
    }

    // ==========================================================
    // Reading
    // ==========================================================

    private fun readCmap(): Pair<Map<Int, Int>, Boolean> {
        val cmap = table("cmap")
        val count = u16(cmap + 2)
        var best: Int? = null
        var bestRank = Int.MAX_VALUE
        var symbol = false
        for (i in 0 until count) {
            val record = cmap + 4 + i * 8
            val platform = u16(record)
            val encoding = u16(record + 2)
            val offset = cmap + u32(record + 4)
            val format = u16(offset)
            // Full Unicode first, then the Basic Multilingual Plane, then a symbol font
            val rank = when {
                platform == 3 && encoding == 10 && format == 12 -> 0
                platform == 0 && format == 12 -> 1
                platform == 3 && encoding == 1 && format == 4 -> 2
                platform == 0 && format == 4 -> 3
                platform == 3 && encoding == 0 && format == 4 -> 4
                else -> continue
            }
            if (rank < bestRank) {
                bestRank = rank
                best = offset
                symbol = rank == 4
            }
        }
        val offset = best ?: return emptyMap<Int, Int>() to false
        val map = HashMap<Int, Int>()
        if (u16(offset) == 12) {
            val groups = u32(offset + 12)
            for (g in 0 until groups) {
                val group = offset + 16 + g * 12
                val startChar = u32(group)
                val endChar = u32(group + 4)
                val startGlyph = u32(group + 8)
                for (cp in startChar..endChar) map[cp] = startGlyph + (cp - startChar)
            }
        } else {
            val segments = u16(offset + 6) / 2
            val ends = offset + 14
            val starts = ends + segments * 2 + 2
            val deltas = starts + segments * 2
            val rangeOffsets = deltas + segments * 2
            for (s in 0 until segments) {
                val endCode = u16(ends + s * 2)
                val startCode = u16(starts + s * 2)
                val delta = s16(deltas + s * 2)
                val rangeOffset = u16(rangeOffsets + s * 2)
                if (startCode > endCode || startCode == 0xFFFF) continue
                for (cp in startCode..endCode) {
                    val glyph = if (rangeOffset == 0) {
                        (cp + delta) and 0xFFFF
                    } else {
                        val at = rangeOffsets + s * 2 + rangeOffset + (cp - startCode) * 2
                        if (at + 2 > data.size) 0 else u16(at).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                    }
                    if (glyph != 0) map[cp] = glyph
                }
            }
        }
        return map to symbol
    }

    private fun glyphRange(glyphId: Int): Pair<Int, Int>? {
        if (glyphId !in 0 until glyphCount) return null
        val loca = table("loca")
        val glyf = table("glyf")
        val start: Int
        val end: Int
        if (longLoca) {
            start = u32(loca + glyphId * 4)
            end = u32(loca + glyphId * 4 + 4)
        } else {
            start = u16(loca + glyphId * 2) * 2
            end = u16(loca + glyphId * 2 + 2) * 2
        }
        if (end <= start || glyf + end > data.size) return null
        return glyf + start to glyf + end
    }

    private fun table(tag: String): Int = tables.getValue(tag).offset

    private fun tableBytes(tag: String): ByteArray {
        val table = tables.getValue(tag)
        return data.copyOfRange(table.offset, table.offset + table.length)
    }

    private fun tag(at: Int) = CharArray(4) { (data[at + it].toInt() and 0xFF).toChar() }.concatToString()
    private fun byte(at: Int) = data[at].toInt() and 0xFF
    private fun u16(at: Int) = (byte(at) shl 8) or byte(at + 1)
    private fun s16(at: Int) = (data[at].toInt() shl 8) or byte(at + 1)
    private fun u32(at: Int) = (u16(at) shl 16) or u16(at + 2)
    private fun f2dot14(at: Int) = s16(at) / 16384f

    // ==========================================================
    // Writing
    // ==========================================================

    private fun cmapTable(mapping: Map<Int, Int>): ByteArray {
        val bmp = mapping.filterKeys { it <= 0xFFFF }
        val format4 = ByteBuilder().apply {
            // One segment per character, and the final one
            val segments = bmp.size + 1
            u16(4)
            u16(16 + segments * 8)
            u16(0)
            u16(segments * 2)
            var power = 1
            var log = 0
            while (power * 2 <= segments) {
                power *= 2
                log++
            }
            u16(power * 2)
            u16(log)
            u16(segments * 2 - power * 2)
            for (cp in bmp.keys) u16(cp)
            u16(0xFFFF)
            u16(0)
            for (cp in bmp.keys) u16(cp)
            u16(0xFFFF)
            for ((cp, glyph) in bmp) u16((glyph - cp) and 0xFFFF)
            u16(1)
            repeat(segments) { u16(0) }
        }.toByteArray()
        val full = mapping.keys.any { it > 0xFFFF }
        val format12 = if (!full) null else ByteBuilder().apply {
            u16(12)
            u16(0)
            u32(16 + mapping.size * 12)
            u32(0)
            u32(mapping.size)
            for ((cp, glyph) in mapping) {
                u32(cp)
                u32(cp)
                u32(glyph)
            }
        }.toByteArray()
        return ByteBuilder().apply {
            val records = if (format12 != null) 2 else 1
            u16(0)
            u16(records)
            u16(3)
            u16(1)
            u32(4 + records * 8)
            if (format12 != null) {
                u16(3)
                u16(10)
                u32(4 + records * 8 + format4.size)
            }
            bytes(format4, 0, format4.size)
            format12?.let { bytes(it, 0, it.size) }
        }.toByteArray()
    }

    private fun writeFont(tables: Map<String, ByteArray>): ByteArray {
        val tags = tables.keys.sorted()
        val out = ByteBuilder()
        var power = 1
        var log = 0
        while (power * 2 <= tags.size) {
            power *= 2
            log++
        }
        out.u32(0x00010000)
        out.u16(tags.size)
        out.u16(power * 16)
        out.u16(log)
        out.u16(tags.size * 16 - power * 16)
        var offset = 12 + tags.size * 16
        for (tag in tags) {
            val table = tables.getValue(tag)
            for (ch in tag) out.byte(ch.code)
            out.u32(checksum(table, 0, table.size))
            out.u32(offset)
            out.u32(table.size)
            offset += (table.size + 3) / 4 * 4
        }
        var headOffset = 0
        for (tag in tags) {
            if (tag == "head") headOffset = out.size
            val table = tables.getValue(tag)
            out.bytes(table, 0, table.size)
            out.pad(4)
        }
        val font = out.toByteArray()
        val adjustment = (0xB1B0AFBA - (checksum(font, 0, font.size).toLong() and 0xFFFFFFFF)).toInt()
        for (i in 0 until 4) font[headOffset + 8 + i] = (adjustment ushr (24 - i * 8)).toByte()
        return font
    }

    private fun checksum(bytes: ByteArray, from: Int, length: Int): Int {
        var sum = 0
        var i = 0
        while (i < length) {
            var word = 0
            for (k in 0 until 4) word = (word shl 8) or (if (i + k < length) bytes[from + i + k].toInt() and 0xFF else 0)
            sum += word
            i += 4
        }
        return sum
    }

    private class ByteBuilder {
        private var buffer = ByteArray(1024)
        var size = 0
            private set

        fun byte(value: Int) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            buffer[size++] = value.toByte()
        }

        fun u16(value: Int) {
            byte(value ushr 8)
            byte(value)
        }

        fun u32(value: Int) {
            u16(value ushr 16)
            u16(value)
        }

        fun bytes(from: ByteArray, offset: Int, length: Int) {
            while (size + length > buffer.size) buffer = buffer.copyOf(buffer.size * 2)
            from.copyInto(buffer, size, offset, offset + length)
            size += length
        }

        fun pad(alignment: Int) {
            while (size % alignment != 0) byte(0)
        }

        fun toByteArray() = buffer.copyOf(size)
    }

    private companion object {
        const val MAX_COMPOSITE_DEPTH = 8

        // Flags of the points of a simple glyph
        const val ON_CURVE = 0x01
        const val X_SHORT = 0x02
        const val Y_SHORT = 0x04
        const val REPEAT = 0x08
        const val X_SAME_OR_POSITIVE = 0x10
        const val Y_SAME_OR_POSITIVE = 0x20

        // Flags of the components of a composite glyph
        const val ARG_1_AND_2_ARE_WORDS = 0x0001
        const val ARGS_ARE_XY_VALUES = 0x0002
        const val WE_HAVE_A_SCALE = 0x0008
        const val MORE_COMPONENTS = 0x0020
        const val WE_HAVE_AN_X_AND_Y_SCALE = 0x0040
        const val WE_HAVE_A_TWO_BY_TWO = 0x0080
    }
}

/**
 * A closed contour of a glyph: its points ([xs], [ys], font units, y going up) and whether each
 * is on the curve (the others are control points of quadratic Bézier curves).
 */
public class GlyphContour(public val xs: FloatArray, public val ys: FloatArray, public val onCurve: BooleanArray) {
    public val size: Int get() = xs.size
}

/** The code point of [text] at [index] (a surrogate pair read as one). */
internal fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
    }
    return high.code
}
