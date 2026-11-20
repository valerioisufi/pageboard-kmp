package io.github.valerioisufi.pageboard.handwriting.bench

import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.model.StrokePoint
import java.io.File
import java.text.Normalizer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A glyph of a Hershey font: its strokes in font units (y down) and its left and right bounds. */
internal class Glyph(val left: Int, val right: Int, val strokes: List<List<Pair<Int, Int>>>)

/**
 * A Hershey font read from a JHF file (a glyph a line from the space, the first line a header): its
 * vertices are pairs of characters, offset from `R`, and ` R` lifts the pen.
 */
internal class HersheyFont(val name: String, private val glyphs: Map<Char, Glyph>) {
    operator fun get(c: Char): Glyph? = glyphs[c]

    companion object {
        fun read(file: File): HersheyFont {
            val lines = file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }
            val glyphs = lines.withIndex().associate { (i, line) ->
                val body = line.substring(8)
                val left = body[0] - 'R'
                val right = body[1] - 'R'
                val strokes = mutableListOf<MutableList<Pair<Int, Int>>>(mutableListOf())
                var k = 2
                while (k + 1 < body.length) {
                    if (body[k] == ' ' && body[k + 1] == 'R') strokes.add(mutableListOf()) else strokes.last().add((body[k] - 'R') to (body[k + 1] - 'R'))
                    k += 2
                }
                (' ' + i) to Glyph(left, right, strokes.filter { it.isNotEmpty() })
            }
            return HersheyFont(file.nameWithoutExtension, glyphs)
        }
    }
}

/**
 * Writes a line of text as ink with a Hershey script font, varied at random as a hand would vary
 * it: the size, the slant, the spacing, a baseline that drifts, a tremor, the speed. The accents of
 * Italian are drawn as a short stroke over their letter. Cleaner than real writing: the letters are
 * not joined and keep their shape.
 */
internal class SyntheticWriter(private val fonts: List<HersheyFont>, seed: Int) {
    private val random = Random(seed)

    class Written(val sample: InkSample, val font: String, val widthMm: Float)

    /** [text] written from ([x], [y]) (the top of the line, page millimeters), from [startMs]. */
    fun write(text: String, x: Float, y: Float, startMs: Long = 1_000_000L): Written {
        val font = fonts[random.nextInt(fonts.size)]
        val mmPerUnit = CAP_MM / CAP_UNITS * random.range(0.85f, 1.2f)
        val slant = random.range(-0.1f, 0.3f)
        val spacing = random.range(0.92f, 1.12f)
        val drift = random.range(-0.6f, 0.6f)
        val tremor = random.range(0.01f, 0.06f)
        val speed = random.range(30f, 70f) // millimeters a second
        var penX = 0f
        val strokes = mutableListOf<List<Pair<Float, Float>>>()
        for (c in Normalizer.normalize(text, Normalizer.Form.NFD)) {
            val accent = ACCENTS[c]
            if (accent != null) {
                // Over the letter before
                val center = penX - lastWidth * 0.55f
                strokes.add(listOf((center - accent * 1.5f) to -13f, (center + accent * 1.5f) to -16f))
                continue
            }
            val glyph = font[c] ?: font['?'] ?: continue
            val width = (glyph.right - glyph.left) * spacing
            for (stroke in glyph.strokes) strokes.add(stroke.map { (gx, gy) -> (penX + gx - glyph.left) to gy.toFloat() })
            penX += width
            lastWidth = width
        }
        val baseline = y + CAP_UNITS * mmPerUnit * 0.6f
        var t = startMs
        val points = strokes.map { stroke ->
            val placed = stroke.map { (ux, uy) ->
                val px = x + (ux - uy * slant) * mmPerUnit
                val py = baseline + uy * mmPerUnit + drift * sin(px / 40f)
                px to py
            }
            val dense = densify(placed).map { (px, py) ->
                StrokePoint(px + random.range(-tremor, tremor), py + random.range(-tremor, tremor), 0.5f, 0L)
            }
            val timed = dense.mapIndexed { i, p ->
                if (i > 0) t += max(4L, (hypot(p.x - dense[i - 1].x, p.y - dense[i - 1].y) / speed * 1000f).toLong())
                p.copy(timestampMs = t)
            }
            t += random.nextLong(80L, 220L)
            timed
        }
        return Written(InkSample(points), font.name, penX * mmPerUnit)
    }

    private var lastWidth = 10f

    private fun densify(points: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (points.size < 2) return points + points.map { (px, py) -> (px + 0.05f) to (py + 0.05f) }
        val out = mutableListOf(points.first())
        for ((a, b) in points.zipWithNext()) {
            val steps = max(1, (hypot(b.first - a.first, b.second - a.second) / STEP_MM).toInt())
            for (s in 1..steps) out.add((a.first + (b.first - a.first) * s / steps) to (a.second + (b.second - a.second) * s / steps))
        }
        return out
    }

    private fun Random.range(from: Float, to: Float) = from + nextFloat() * (to - from)

    private companion object {
        /** The height of a capital, from the cap line to the base line of the Hershey fonts. */
        const val CAP_UNITS = 21f
        const val CAP_MM = 4.5f
        const val STEP_MM = 0.35f

        /** The combining accents of Italian, with the direction of their stroke. */
        val ACCENTS = mapOf('̀' to -1f, '́' to 1f)
    }
}

/** The doodles of Quick, Draw! (simplified: strokes of x and y from 0 to 255), as ink [sizeMm] wide at ([x], [y]). */
internal fun quickDraw(line: String, x: Float, y: Float, sizeMm: Float, startMs: Long): InkSample {
    val numbers = Regex("\"drawing\":(\\[.*\\])\\}?").find(line)?.groupValues?.get(1) ?: return InkSample(emptyList())
    val strokes = Regex("\\[\\[([0-9,]*)\\],\\[([0-9,]*)\\]\\]").findAll(numbers).map { m ->
        val xs = m.groupValues[1].split(',').filter { it.isNotEmpty() }.map { it.toFloat() }
        val ys = m.groupValues[2].split(',').filter { it.isNotEmpty() }.map { it.toFloat() }
        xs.zip(ys)
    }.toList()
    var t = startMs
    val scale = sizeMm / 255f
    return InkSample(strokes.map { stroke ->
        stroke.map { (sx, sy) -> StrokePoint(x + sx * scale, y + sy * scale, 0.5f, t.also { t += 12L }) }.also { t += 150L }
    })
}

internal fun InkSample.plus(other: InkSample): InkSample = InkSample(strokes + other.strokes)

internal fun InkSample.right(): Float = bounds?.right ?: 0f

internal fun InkSample.height(): Float = bounds?.let { it.bottom - it.top } ?: 0f

internal fun clampedMin(a: Float, b: Float) = min(a, b)
