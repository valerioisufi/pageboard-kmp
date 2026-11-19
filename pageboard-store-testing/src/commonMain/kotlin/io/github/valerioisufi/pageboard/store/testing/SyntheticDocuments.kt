package io.github.valerioisufi.pageboard.store.testing

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokeModeller
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.newElementId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Documents made up for the benchmarks (see `docs/plans/performance.md`): pages written by hand, a word
 * a stroke along the lines of the page, with a few texts and some strokes erased. The same
 * arguments make the same document, but for the identifiers of its pages and elements.
 */
public object SyntheticDocuments {
    /** Gives no mesh: the strokes keep their inputs only (enough to store them, not to draw them). */
    public val NoMeshes: StrokeModeller = StrokeModeller { _, _, _ -> null }

    /**
     * A notebook of [pages] A4 pages, each with [strokesPerPage] strokes of [pointsPerStroke]
     * inputs of a stylus and [textsPerPage] texts; [removedFraction] of the strokes are erased
     * (kept hidden, as a removal leaves them), the strokes modeled by [modeller].
     */
    public fun notebook(
        pages: Int,
        strokesPerPage: Int,
        pointsPerStroke: Int = 120,
        textsPerPage: Int = 0,
        removedFraction: Float = 0f,
        title: String = "Notebook",
        seed: Int = 1,
        modeller: StrokeModeller = NoMeshes
    ): Document {
        val random = Random(seed)
        val document = Document(title = title, addInitialPage = false, strokeModeller = modeller)
        repeat(pages) {
            val page = document.addPage()
            write(page, strokesPerPage, pointsPerStroke, removedFraction, random, modeller)
            repeat(textsPerPage) { index -> page.add(text(index, random)) }
        }
        return document
    }

    /** The strokes of [count] words written along the lines of [page], [removedFraction] of them erased. */
    private fun write(page: Page, count: Int, points: Int, removedFraction: Float, random: Random, modeller: StrokeModeller) {
        val margin = 20f
        val wordWidth = 18f
        val wordsPerLine = ((page.widthMm - 2 * margin) / (wordWidth + 4f)).toInt().coerceAtLeast(1)
        var time = 0L
        for (index in 0 until count) {
            val line = index / wordsPerLine
            val left = margin + (index % wordsPerLine) * (wordWidth + 4f)
            // Past the bottom of the page: written again from the top, over the first lines
            val baseline = margin + 10f + (line * LINE_MM) % (page.heightMm - 2 * margin - 10f)
            val inputs = List(points) { i ->
                val t = i / (points - 1).coerceAtLeast(1).toFloat()
                time += 8L
                StrokePoint(
                    x = left + t * wordWidth,
                    y = baseline - 2.5f - 2.5f * sin(t * 6f * PI.toFloat() + random.nextFloat()),
                    pressure = 0.4f + 0.4f * random.nextFloat(),
                    timestampMs = time
                )
            }
            val stroke = StrokeData(
                id = newElementId(),
                points = inputs,
                tool = StrokeTool.PRESSURE_PEN,
                size = 0.5f,
                color = 0xFF000000.toInt(),
                inputToolType = InputToolType.STYLUS
            ).toElement(modeller)
            if (random.nextFloat() < removedFraction) {
                page.add(stroke)
                page.remove(stroke)
                stroke.removed = Stamp(1L, index, "synthetic")
                page.load(emptyList(), listOf(stroke))
            } else {
                page.add(stroke)
            }
        }
    }

    private fun text(index: Int, random: Random): TextElement = TextElement().apply {
        text = WORDS.shuffled(random).take(12).joinToString(" ")
        x = 20f
        y = 30f + index * 25f
        width = 170f
        fontSize = 12f
    }

    /** The space between the lines of the strokes, in millimeters. */
    private const val LINE_MM = 9f

    private val WORDS = listOf(
        "matrix", "eigenvalue", "integral", "derivative", "theorem", "proof", "lemma", "vector", "space",
        "linear", "function", "limit", "series", "convergence", "basis", "kernel", "image", "rank"
    )
}
