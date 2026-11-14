package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.handwriting.HandwritingReader
import io.github.valerioisufi.drawview.lecture.handwriting.InkBlock
import io.github.valerioisufi.drawview.lecture.model.OllamaBackend
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import io.github.valerioisufi.drawview.ml.InstalledModel
import io.github.valerioisufi.drawview.model.geometry.Rect
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A handwritten formula of MathWriting: its strokes (points in the units of its device) and its LaTeX. */
internal class Ink(val id: String, val strokes: List<List<Pair<Float, Float>>>, val latex: String)

/** The inks of MathWriting (InkML), the formula in `normalizedLabel`. */
internal object MathWriting {
    fun read(directory: File): List<Ink> = directory.listFiles { file -> file.extension == "inkml" }.orEmpty().sortedBy { it.name }.map { file ->
        val xml = file.readText()
        val label = Regex("""<annotation type="normalizedLabel">([^<]*)</annotation>""").find(xml)?.groupValues?.get(1).orEmpty()
        val strokes = Regex("""<trace[^>]*>([^<]*)</trace>""").findAll(xml).map { trace ->
            trace.groupValues[1].split(',').mapNotNull { point ->
                val values = point.trim().split(Regex("\\s+"))
                if (values.size >= 2) values[0].toFloat() to values[1].toFloat() else null
            }
        }.filter { it.isNotEmpty() }.toList()
        Ink(file.nameWithoutExtension, strokes, unescapeXml(label))
    }

    private fun unescapeXml(text: String) = text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
}

/**
 * An ink drawn as the editor sends a block of handwriting to the model (`ControllerNotebookSource`):
 * black on white, [PX_PER_MM] pixels a millimeter, a margin of [MARGIN_MM], at most [MAX_SIDE_PX]
 * pixels a side, a JPEG of quality 82. The ink is scaled so that its glyphs are about [GLYPH_MM]
 * high, written with a pen of [PEN_MM].
 */
internal object InkImages {
    private const val PX_PER_MM = 8f
    private const val MARGIN_MM = 6f
    private const val MAX_SIDE_PX = 1024f
    private const val GLYPH_MM = 5f
    private const val PEN_MM = 0.5f

    fun render(ink: Ink): ByteArray {
        val points = ink.strokes.flatten()
        val left = points.minOf { it.first }
        val top = points.minOf { it.second }
        val width = points.maxOf { it.first } - left
        val height = points.maxOf { it.second } - top
        // The size of a glyph: the median of the sizes of the strokes
        val glyph = ink.strokes.map { stroke ->
            max(stroke.maxOf { it.first } - stroke.minOf { it.first }, stroke.maxOf { it.second } - stroke.minOf { it.second })
        }.sorted().let { it[it.size / 2] }.coerceAtLeast(1f)
        val mmPerUnit = GLYPH_MM / glyph
        val widthMm = width * mmPerUnit + 2 * MARGIN_MM
        val heightMm = height * mmPerUnit + 2 * MARGIN_MM
        val scale = min(PX_PER_MM, MAX_SIDE_PX / max(widthMm, heightMm))
        val image = BufferedImage(ceil(widthMm * scale).toInt().coerceAtLeast(1), ceil(heightMm * scale).toInt().coerceAtLeast(1), BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color.WHITE
        graphics.fillRect(0, 0, image.width, image.height)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        graphics.color = Color.BLACK
        graphics.stroke = BasicStroke(PEN_MM * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        fun x(unit: Float) = ((unit - left) * mmPerUnit + MARGIN_MM) * scale.toDouble()
        fun y(unit: Float) = ((unit - top) * mmPerUnit + MARGIN_MM) * scale.toDouble()
        for (stroke in ink.strokes) {
            val path = Path2D.Double()
            path.moveTo(x(stroke.first().first), y(stroke.first().second))
            // A dot is a stroke of one point
            if (stroke.size == 1) path.lineTo(x(stroke.first().first) + 0.1, y(stroke.first().second))
            for ((px, py) in stroke.drop(1)) path.lineTo(x(px), y(py))
            graphics.draw(path)
        }
        graphics.dispose()
        return jpeg(image)
    }

    private fun jpeg(image: BufferedImage): ByteArray {
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val bytes = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(bytes).use { output ->
            writer.output = output
            val parameters = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.82f
            }
            writer.write(null, IIOImage(image, null, null), parameters)
        }
        writer.dispose()
        return bytes.toByteArray()
    }
}

/**
 * The handwritten formulas of the test split of MathWriting (`<bench>/material/mathwriting`), read
 * by the request of [HandwritingReader] (three blocks a request, as the local models) by every
 * generation model of the bench, at the three largest budgets of an image its file allows (E2B and
 * E4B: 70, 140, 280; 12B: 280, 560, 1,120) and with its schema enforced and asked: the answers
 * usable, the formulas read exactly (normalized LaTeX), the characters wrong.
 */
internal class FormulaBench(private val bench: Bench, private val count: Int = 60) {
    private class Read(val ink: Ink, val text: String?)

    private class Run(val usable: Int, val requests: Int, val reads: List<Read>, val millis: List<Long>)

    suspend fun run(report: StringBuilder) {
        val directory = File(bench.material, "mathwriting/mathwriting-2024-excerpt/test")
        val inks = MathWriting.read(directory).take(count)
        report.appendLine("## Formulas (MathWriting, test split)").appendLine()
        if (inks.isEmpty()) {
            report.appendLine("No inks in $directory.").appendLine()
            return
        }
        val images = inks.associateWith { InkImages.render(it) }
        // A few images, to see what the models read
        inks.take(6).forEach { bench.output("formulas/images/${it.id}.jpg").writeBytes(images.getValue(it)) }
        report.appendLine("${inks.size} formulas written by hand by people, drawn as the editor draws a block (8 px a mm, a pen of 0.5 mm), three in a request.")
        report.appendLine().appendLine("| Model | Image tokens | Schema | Usable answers | Read exactly | Characters wrong | Time a request |")
        report.appendLine("|---|---|---|---|---|---|---|")
        val answers = StringBuilder()
        for (model in bench.generators) {
            for (budget in BUDGETS.filter { it <= model.spec.maxImageTokens }.takeLast(3)) {
                for (mode in bench.modes) {
                    val run = run(model, budget, mode, inks, images, answers)
                    val exact = run.reads.count { it.text != null && normalizeLatex(it.text) == normalizeLatex(it.ink.latex) }
                    val cer = run.reads.meanOf { read -> characterErrorRate(normalizeLatex(read.text.orEmpty()), normalizeLatex(read.ink.latex)) }
                    report.appendLine(
                        "| ${model.spec.name} | $budget | ${mode.name.lowercase()} | ${run.usable}/${run.requests} | " +
                            "${percent(exact, run.reads.size)} | ${"%.0f".format(cer * 100)} % | ${seconds(run.millis.average().toLong())} |"
                    )
                    println("formulas ${model.spec.id} $budget $mode: exact $exact/${run.reads.size}")
                }
            }
            bench.loaded.unload(model.spec.id)
        }
        bench.output("formulas/answers.md").writeText(answers.toString())
        report.appendLine()
    }

    private suspend fun run(
        model: InstalledModel,
        budget: Int,
        mode: SchemaMode,
        inks: List<Ink>,
        images: Map<Ink, ByteArray>,
        answers: StringBuilder
    ): Run {
        val options = GeneratorOptions(maxTokens = 8192, images = true, imageTokens = budget)
        var usable = 0
        val reads = mutableListOf<Read>()
        val millis = mutableListOf<Long>()
        val batches = inks.chunked(OllamaBackend.LIMITS.handwritingBatch)
        for (batch in batches) {
            val blocks = batch.map { ink -> InkBlock(0, "page", ink.id, setOf(ink.id), Rect(0f, 0f, 1f, 1f)) to images.getValue(ink) }
            val prompt = HandwritingReader.prompt(blocks, emptyList())
            val answer = bench.ask(model, options, prompt, mode)
            millis.add(answer.millis)
            val json = jsonOf(answer.text, prompt.schema)
            val read = try {
                LectureAnalysis.parseHandwriting(json).also { if (holdsKeys(json, requiredKeys(prompt.schema!!))) usable++ }
            } catch (_: Exception) {
                emptyMap()
            }
            batch.forEachIndexed { i, ink -> reads.add(Read(ink, read[i + 1])) }
            answers.appendLine("### ${model.spec.id} $budget $mode ${batch.joinToString { it.id }}")
            batch.forEach { answers.appendLine("* expected: ${it.latex}") }
            answers.appendLine("```").appendLine(answer.error ?: answer.text).appendLine("```")
        }
        return Run(usable, batches.size, reads, millis)
    }

    private companion object {
        /** The budgets of an image of Gemma 4. */
        val BUDGETS = listOf(70, 140, 280, 560, 1120)
    }
}
