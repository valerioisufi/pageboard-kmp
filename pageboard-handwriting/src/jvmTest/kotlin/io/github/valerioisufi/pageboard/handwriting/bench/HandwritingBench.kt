package io.github.valerioisufi.pageboard.handwriting.bench

import io.github.valerioisufi.pageboard.handwriting.InkLines
import io.github.valerioisufi.pageboard.handwriting.InkMl
import java.io.File
import java.text.Normalizer
import kotlin.math.max
import kotlin.random.Random
import kotlin.test.Test

/**
 * The bench of the readers of handwriting (phase 0 of docs/plans/handwriting.md), run only with
 * `-Ppageboard.bench=<directory>`:
 *
 * ```
 * ./gradlew :pageboard-handwriting:jvmTest --tests '*HandwritingBench*' -Ppageboard.bench=<directory>
 *   [-Ppageboard.bench.parts=generate,lines,score]
 * ```
 *
 * * `generate` writes the synthetic ink of `tools/handwriting-bench` (the sentences, the Hershey
 *   fonts) as InkML in `<directory>/ink/text`, a line a file with its `label` and `language`, and
 *   blocks of two lines beside a doodle of the `.ndjson` files of Quick, Draw! in `<directory>/quickdraw` in
 *   `<directory>/ink/blocks`;
 * * `lines` measures `InkLines` on the blocks: the lines found, the doodles left out, the writing
 *   left out by mistake (`<directory>/lines.md`);
 * * `score` scores the readings of each reader, `<directory>/readings/<reader>.tsv` (a line a file:
 *   its name, its reading, its candidates split by `|`, the milliseconds), into
 *   `<directory>/results.md`.
 *
 * Nothing is written outside the directory.
 */
class HandwritingBench {
    @Test
    fun bench() {
        val directory = System.getProperty("pageboard.bench")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val parts = System.getProperty("pageboard.bench.parts").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("generate", "lines", "score") }
        val tools = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "tools/handwriting-bench") }.first { it.isDirectory }
        if ("generate" in parts) generate(tools, directory)
        if ("lines" in parts) lines(directory)
        if ("score" in parts) score(directory)
    }

    private fun generate(tools: File, directory: File) {
        val fonts = File(tools, "fonts").listFiles { f -> f.extension == "jhf" }.orEmpty().sortedBy { it.name }.map { HersheyFont.read(it) }
        val writer = SyntheticWriter(fonts, seed = 2026)
        val text = File(directory, "ink/text").apply { mkdirs() }
        val sentences = listOf("it", "en").associateWith { language -> File(tools, "sentences-$language.txt").readLines().filter { it.isNotBlank() } }
        for ((language, lines) in sentences) {
            lines.forEachIndexed { i, sentence ->
                val written = writer.write(sentence, 20f, 30f)
                File(text, "$language-%03d.inkml".format(i + 1)).writeText(
                    InkMl.write(written.sample, mapOf("label" to sentence, "language" to language, "font" to written.font))
                )
            }
        }
        val doodles = File(directory, "quickdraw").listFiles { f -> f.extension == "ndjson" }.orEmpty().sortedBy { it.name }
            .flatMap { file -> file.readLines().filter { it.isNotBlank() }.take(4).map { file.nameWithoutExtension to it } }
        val blocks = File(directory, "ink/blocks").apply { mkdirs() }
        val random = Random(7)
        val italian = sentences.getValue("it")
        doodles.forEachIndexed { i, (category, doodle) ->
            val first = italian[random.nextInt(italian.size)]
            val second = italian[random.nextInt(italian.size)]
            val a = writer.write(first, 20f, 30f, 1_000_000L)
            val b = writer.write(second, 20f, 30f + max(a.sample.height(), 5f) + random.nextFloat() * 3f + 2f, 2_000_000L)
            val writing = a.sample.plus(b.sample)
            // Beside the lines, or under them
            val beside = random.nextBoolean()
            val size = 18f + random.nextFloat() * 22f
            val drawing = if (beside) quickDraw(doodle, writing.right() + 6f, 26f, size, 3_000_000L)
            else quickDraw(doodle, 25f, 30f + writing.height() + 8f, size, 3_000_000L)
            File(blocks, "block-%03d.inkml".format(i + 1)).writeText(
                InkMl.write(
                    writing.plus(drawing),
                    mapOf("label" to "$first\n$second", "language" to "it", "writingStrokes" to writing.strokes.size.toString(), "drawing" to category)
                )
            )
        }
        println("Written: ${text.listFiles().orEmpty().size} lines, ${blocks.listFiles().orEmpty().size} blocks, in $directory")
    }

    private fun lines(directory: File) {
        val files = File(directory, "ink/blocks").listFiles { f -> f.extension == "inkml" }.orEmpty().sortedBy { it.name }
        var twoLines = 0
        var drawingStrokes = 0
        var drawingLeftOut = 0
        var writingStrokes = 0
        var writingLeftOut = 0
        var doodlesLeftOut = 0
        val byCategory = sortedMapOf<String, Pair<Int, Int>>()
        for (file in files) {
            val ink = InkMl.read(file.readText())
            val writing = ink.annotations.getValue("writingStrokes").toInt()
            val layout = InkLines().split(ink.sample)
            // The strokes are the same objects, in the same order: by identity
            val leftOut = layout.drawing.strokes.map { s -> ink.sample.strokes.indexOfFirst { it === s } }.toSet()
            val textLeftOut = leftOut.count { it < writing }
            val doodleLeftOut = leftOut.count { it >= writing }
            val doodle = ink.sample.strokes.size - writing
            writingStrokes += writing
            writingLeftOut += textLeftOut
            drawingStrokes += doodle
            drawingLeftOut += doodleLeftOut
            if (doodleLeftOut * 2 >= doodle) doodlesLeftOut++
            if (layout.lines.size == 2) twoLines++
            val category = ink.annotations["drawing"].orEmpty()
            val (n, out) = byCategory[category] ?: (0 to 0)
            byCategory[category] = (n + 1) to (out + if (doodleLeftOut * 2 >= doodle) 1 else 0)
        }
        val report = buildString {
            appendLine("# InkLines on the blocks of the bench\n")
            appendLine("${files.size} blocks: two lines of synthetic Italian ink and a doodle of Quick, Draw! beside them or under them.\n")
            appendLine("| Measure | Value |")
            appendLine("|---|---|")
            appendLine("| Blocks split into their two lines | $twoLines of ${files.size} |")
            appendLine("| Doodles left out (half of their strokes or more) | $doodlesLeftOut of ${files.size} |")
            appendLine("| Strokes of the doodles left out | $drawingLeftOut of $drawingStrokes (${percent(drawingLeftOut, drawingStrokes)}) |")
            appendLine("| Strokes of writing left out by mistake | $writingLeftOut of $writingStrokes (${percent(writingLeftOut, writingStrokes)}) |")
            appendLine()
            appendLine("| Doodle | Blocks | Left out |")
            appendLine("|---|---|---|")
            for ((category, value) in byCategory) appendLine("| $category | ${value.first} | ${value.second} |")
        }
        File(directory, "lines.md").writeText(report)
        println(report)
    }

    private fun score(directory: File) {
        val labels = File(directory, "ink/text").listFiles { f -> f.extension == "inkml" }.orEmpty()
            .associate { file -> file.name to InkMl.read(file.readText()).let { (it.label.orEmpty()) to it.annotations["language"].orEmpty() } }
        val readers = File(directory, "readings").listFiles { f -> f.extension == "tsv" }.orEmpty().sortedBy { it.name }
        val report = StringBuilder("# The readers on the synthetic ink\n\n")
        report.appendLine("CER: the characters wrong over the characters of the text (as written, the spaces collapsed); WER: the words wrong, in lower case without punctuation; in the candidates: the words of the text found among the candidates of the reader. Time: a line, the median and the 90th percentile.\n")
        report.appendLine("| Reader | Language | Lines read | CER | WER | Words in the candidates | Exact lines | Time (median / p90) |")
        report.appendLine("|---|---|---|---|---|---|---|---|")
        for (file in readers) {
            val rows = file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                val cells = line.split('\t')
                val (label, language) = labels[cells[0]] ?: return@mapNotNull null
                Row(language, label, cells.getOrElse(1) { "" }, cells.getOrElse(2) { "" }, cells.getOrElse(3) { "0" }.toDoubleOrNull() ?: 0.0)
            }
            for ((language, of) in rows.groupBy { it.language }.toSortedMap()) {
                val total = labels.values.count { it.second == language }
                val chars = of.sumOf { normalizeSpaces(it.label).length }
                val charErrors = of.sumOf { distance(normalizeSpaces(it.label), normalizeSpaces(it.reading)) }
                val words = of.sumOf { words(it.label).size }
                val wordErrors = of.sumOf { distance(words(it.label), words(it.reading)) }
                val inCandidates = of.sumOf { row -> val candidates = words(row.reading + " " + row.candidates.replace('|', ' ').replace('/', ' ')).toSet(); words(row.label).count { it in candidates } }
                val exact = of.count { normalizeSpaces(it.label) == normalizeSpaces(it.reading) }
                val times = of.map { it.ms }.sorted()
                report.appendLine(
                    "| ${file.nameWithoutExtension} | $language | ${of.size} of $total | ${percent(charErrors, chars)} | ${percent(wordErrors, words)} | " +
                        "${percent(inCandidates, words)} | ${percent(exact, of.size)} | ${"%.0f".format(times[times.size / 2])} / ${"%.0f".format(times[(times.size * 9) / 10])} ms |"
                )
            }
        }
        File(directory, "results.md").writeText(report.toString())
        println(report)
    }

    private class Row(val language: String, val label: String, val reading: String, val candidates: String, val ms: Double)

    private fun normalizeSpaces(text: String) = Normalizer.normalize(text, Normalizer.Form.NFC).trim().replace(Regex("\\s+"), " ")

    private fun words(text: String): List<String> =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase().replace('’', '\'').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private fun <T> distance(a: List<T>, b: List<T>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val current = IntArray(b.size + 1)
            current[0] = i
            for (j in 1..b.size) current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            previous = current
        }
        return previous[b.size]
    }

    private fun distance(a: String, b: String) = distance(a.toList(), b.toList())

    private fun percent(part: Int, whole: Int) = if (whole == 0) "–" else "%.1f %%".format(100.0 * part / whole)
}
