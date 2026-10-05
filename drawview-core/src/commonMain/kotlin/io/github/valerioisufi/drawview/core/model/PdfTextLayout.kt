package io.github.valerioisufi.drawview.core.model

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Characters of the text of a PDF page with their boxes, for the selection and the marking of
 * the text.
 *
 * The characters are in reading order (the order of PDFium, where the spaces and the line breaks
 * inferred from the layout have no box); [boxes] holds four values per character (left, top,
 * right, bottom) in points from the top-left corner of the page as it is displayed, which is
 * [widthPt] x [heightPt].
 */
class PdfTextLayout(
    val widthPt: Float,
    val heightPt: Float,
    private val codePoints: IntArray,
    private val boxes: FloatArray
) {
    init {
        require(boxes.size >= codePoints.size * 4) { "Four box values per character" }
    }

    val charCount: Int get() = codePoints.size

    /** Whether the page has text that can be selected (a scanned page has none). */
    val hasText: Boolean = codePoints.indices.any { hasBox(it) }

    fun codePointAt(index: Int): Int = codePoints[index]

    /** Whether the character [index] has a box (it is not a space or a line break inferred by PDFium). */
    fun hasBox(index: Int): Boolean {
        val i = index * 4
        return boxes[i + 2] > boxes[i] && boxes[i + 3] > boxes[i + 1]
    }

    fun boxOf(index: Int): Rect {
        val i = index * 4
        return Rect(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3])
    }

    /**
     * Index of the character whose box is the nearest to ([x], [y]) (points), when it is at most
     * [maxDistance] points away, or -1.
     */
    fun charIndexNear(x: Float, y: Float, maxDistance: Float): Int {
        var best = -1
        var bestDistance = 0f
        var bestCenter = 0f
        for (index in codePoints.indices) {
            if (!hasBox(index)) continue
            val i = index * 4
            val dx = axisDistance(x, boxes[i], boxes[i + 2])
            val dy = axisDistance(y, boxes[i + 1], boxes[i + 3])
            val distance = dx * dx + dy * dy
            if (distance > maxDistance * maxDistance) continue
            val center = centerDistance(index, x, y)
            if (best < 0 || distance < bestDistance || (distance == bestDistance && center < bestCenter)) {
                best = index
                bestDistance = distance
                bestCenter = center
            }
        }
        return best
    }

    /**
     * Character a selection extends to when the pointer is at ([x], [y]) (points): the nearest
     * one of the lines at the height of the pointer (beyond the end of a line, its last one),
     * otherwise the nearest one of the page. -1 when the page has no text.
     */
    fun charIndexForSelection(x: Float, y: Float): Int {
        var inRow = -1
        var inRowDx = 0f
        var inRowCenter = 0f
        var nearest = -1
        var nearestDistance = 0f
        var nearestCenter = 0f
        for (index in codePoints.indices) {
            if (!hasBox(index)) continue
            val i = index * 4
            val dx = axisDistance(x, boxes[i], boxes[i + 2])
            val dy = axisDistance(y, boxes[i + 1], boxes[i + 3])
            val center = centerDistance(index, x, y)
            if (dy == 0f && (inRow < 0 || dx < inRowDx || (dx == inRowDx && center < inRowCenter))) {
                inRow = index
                inRowDx = dx
                inRowCenter = center
            }
            val distance = dx * dx + dy * dy
            if (nearest < 0 || distance < nearestDistance || (distance == nearestDistance && center < nearestCenter)) {
                nearest = index
                nearestDistance = distance
                nearestCenter = center
            }
        }
        return if (inRow >= 0) inRow else nearest
    }

    /**
     * The word around the character [index]: for a space or a punctuation mark, the word just
     * before it (or else just after it), otherwise the character alone.
     */
    fun wordRange(index: Int): IntRange {
        if (index !in codePoints.indices) return IntRange.EMPTY
        if (!isWordChar(index)) {
            return when {
                index > 0 && isWordChar(index - 1) -> wordRange(index - 1)
                index < charCount - 1 && isWordChar(index + 1) -> wordRange(index + 1)
                else -> index..index
            }
        }
        var start = index
        while (start > 0 && isWordChar(start - 1)) start--
        var end = index
        while (end < charCount - 1 && isWordChar(end + 1)) end++
        return start..end
    }

    /**
     * Rectangles covering the characters from [start] until [end] (exclusive), one per line, in
     * points. Lines overlapping each other are split halfway, so that a translucent mark is not
     * darker where two lines meet.
     */
    fun lineRects(start: Int, end: Int): List<Rect> {
        val lines = mutableListOf<FloatArray>()
        var line: FloatArray? = null
        var lastRight = 0f
        for (index in max(start, 0) until min(end, charCount)) {
            if (!hasBox(index)) continue
            val i = index * 4
            val l = boxes[i]
            val t = boxes[i + 1]
            val r = boxes[i + 2]
            val b = boxes[i + 3]
            val current = line
            if (current != null && isSameLine(current, lastRight, l, t, r, b)) {
                current[0] = min(current[0], l)
                current[1] = min(current[1], t)
                current[2] = max(current[2], r)
                current[3] = max(current[3], b)
            } else {
                line = floatArrayOf(l, t, r, b).also { lines.add(it) }
            }
            lastRight = r
        }
        for (k in 0 until lines.size - 1) {
            val a = lines[k]
            val b = lines[k + 1]
            val overlapsHorizontally = a[0] < b[2] && b[0] < a[2]
            if (overlapsHorizontally && b[1] > a[1] && b[1] < a[3]) {
                val middle = (a[3] + b[1]) / 2f
                a[3] = middle
                b[1] = middle
            }
        }
        return lines.map { Rect(it[0], it[1], it[2], it[3]) }
    }

    /**
     * Text of the characters from [start] until [end] (exclusive), with the line breaks of the
     * layout.
     */
    fun text(start: Int, end: Int): String {
        val text = StringBuilder()
        var previous = 0
        for (index in max(start, 0) until min(end, charCount)) {
            val codePoint = codePoints[index]
            when {
                codePoint == '\r'.code -> text.append('\n')
                codePoint == '\n'.code -> if (previous != '\r'.code) text.append('\n')
                // Hyphen at the end of a line (PDFium)
                codePoint == 2 -> text.append('-')
                codePoint < 0x20 && codePoint != '\t'.code -> {}
                else -> text.appendCodePoint(codePoint)
            }
            previous = codePoint
        }
        return text.toString()
    }

    private fun isWordChar(index: Int): Boolean {
        if (!hasBox(index)) return false
        val codePoint = codePoints[index]
        return codePoint > 0xFFFF || codePoint.toChar().isLetterOrDigit()
    }

    /** Whether the character (l, t, r, b) continues [line], whose last character ends at [lastRight]. */
    private fun isSameLine(line: FloatArray, lastRight: Float, l: Float, t: Float, r: Float, b: Float): Boolean {
        val overlap = min(line[3], b) - max(line[1], t)
        if (overlap < min(line[3] - line[1], b - t) * 0.5f) return false
        // Back to the start of a line, or a jump to another column at the same height
        val height = max(line[3] - line[1], b - t)
        return l >= lastRight - height && l <= lastRight + height * 3f
    }

    private fun centerDistance(index: Int, x: Float, y: Float): Float {
        val i = index * 4
        return abs((boxes[i] + boxes[i + 2]) / 2f - x) + abs((boxes[i + 1] + boxes[i + 3]) / 2f - y)
    }

    private fun axisDistance(value: Float, from: Float, to: Float): Float = when {
        value < from -> from - value
        value > to -> value - to
        else -> 0f
    }
}

private fun StringBuilder.appendCodePoint(codePoint: Int) {
    if (codePoint <= 0xFFFF) {
        append(codePoint.toChar())
    } else if (codePoint <= 0x10FFFF) {
        val offset = codePoint - 0x10000
        append((0xD800 + (offset shr 10)).toChar())
        append((0xDC00 + (offset and 0x3FF)).toChar())
    }
}
