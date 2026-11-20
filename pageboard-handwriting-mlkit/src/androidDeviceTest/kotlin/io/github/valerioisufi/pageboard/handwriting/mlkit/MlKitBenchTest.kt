package io.github.valerioisufi.pageboard.handwriting.mlkit

import androidx.test.platform.app.InstrumentationRegistry
import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.handwriting.InkMl
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.model.StrokePoint
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ML Kit on a device or an emulator with Google Play services:
 *
 * * always: the Italian and English models downloaded, a word of block capitals read;
 * * the bench of phase 0 of docs/plans/handwriting.md when the InkML of the bench is in
 *   `files/ink` of the test application (copied there with `adb shell run-as`): a line a file read through
 *   `HandwritingReading`, the readings written to `files/mlkit.tsv` for
 *   `HandwritingBench` (`score`).
 */
class MlKitBenchTest {
    private val models = MlKitLanguageModels()
    private val reading = HandwritingReading(HandwritingEngines(listOf(MlKitTextRecognizer(models)), languageModels = models))

    @Test
    fun theModelsDownloadAndAWordIsRead() = runBlocking {
        models.download("it", wifiOnly = false)
        models.download("en", wifiOnly = false)
        assertTrue(reading.readsText("it-IT") && reading.readsText("en"), "languages: ${models.downloaded()}")
        val read = reading.readText(capitals(), "en")
        println("ML Kit read: ${read?.text} (${read?.alternatives})")
        assertTrue(read?.text.orEmpty().uppercase().contains("HELLO") || read?.alternatives.orEmpty().any { it.uppercase().contains("HELLO") }, "read: $read")
    }

    @Test
    fun bench() = runBlocking {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        val ink = File(files, "ink")
        val inks = ink.listFiles { f -> f.extension == "inkml" }.orEmpty().sortedBy { it.name }
        if (inks.isEmpty()) return@runBlocking
        models.download("it", wifiOnly = false)
        models.download("en", wifiOnly = false)
        val lines = inks.map { file ->
            val read = InkMl.read(file.readText())
            val language = read.annotations["language"] ?: "it"
            val start = System.nanoTime()
            val reading = reading.readText(read.sample, language)
            val ms = (System.nanoTime() - start) / 1e6
            listOf(file.name, reading?.text.orEmpty(), (listOfNotNull(reading?.text) + reading?.alternatives.orEmpty()).joinToString("|"), "%.1f".format(java.util.Locale.ROOT, ms))
                .joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') }
        }
        File(files, "mlkit.tsv").writeText(lines.joinToString("\n", postfix = "\n"))
        println("ML Kit bench: ${lines.size} files read")
    }

    /** HELLO in block capitals, 10 mm high. */
    private fun capitals(): InkSample {
        val strokes = mutableListOf<List<Pair<Float, Float>>>()
        fun line(vararg p: Pair<Float, Float>) { strokes.add(p.toList()) }
        // H
        line(0f to 0f, 0f to 10f); line(6f to 0f, 6f to 10f); line(0f to 5f, 6f to 5f)
        // E
        line(10f to 0f, 10f to 10f); line(10f to 0f, 15f to 0f); line(10f to 5f, 14f to 5f); line(10f to 10f, 15f to 10f)
        // L L
        line(19f to 0f, 19f to 10f, 24f to 10f); line(28f to 0f, 28f to 10f, 33f to 10f)
        // O
        line(*((0..24).map { i -> val a = 2 * Math.PI * i / 24; (40f + 3.5f * kotlin.math.cos(a).toFloat()) to (5f + 5f * kotlin.math.sin(a).toFloat()) }).toTypedArray())
        var t = 0L
        return InkSample(strokes.map { stroke ->
            stroke.zipWithNext().flatMap { (a, b) -> (0 until 8).map { s -> (a.first + (b.first - a.first) * s / 8) to (a.second + (b.second - a.second) * s / 8) } }.plus(stroke.last())
                .map { (x, y) -> StrokePoint(x, y, 0.5f, t.also { t += 10L }) }.also { t += 200L }
        })
    }
}
