package io.github.valerioisufi.drawview.lecture.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.ui.adaptive.AdaptiveRoot
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.screen.EditorScreen
import io.github.valerioisufi.drawview.ui.theme.DrawViewTheme
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/**
 * The window while whisper.cpp transcribes (docs/transcription-queue.md, phase 0): a real window of
 * the editor on a page of notes, panned at every frame, and the time between its frames: alone, and
 * while a recording is transcribed in each of the ways whisper.cpp may use the machine.
 *
 * It opens a window on the screen and needs a speech model and a recording, so it runs alone:
 *
 * ```
 * JAVA_TOOL_OPTIONS="-Ddrawview.bench.whisper=<ggml model> -Ddrawview.bench.audio=<wav, 16 kHz mono 16 bit>" \
 *   ./gradlew :drawview-lecture-ui:jvmTest -Pdrawview.benchmarks=true --tests '*TranscriptionWindowBenchmark'
 * ```
 *
 * `-Ddrawview.bench.ways=gpu:4,cpu:2` chooses the ways (the GPU or the processor, the threads of the
 * decoder), `-Ddrawview.bench.seconds=20` how long each one is measured.
 */
class TranscriptionWindowBenchmark {

    /** whisper.cpp as the module loads it, reached without its internal types: the bench only. */
    private class Whisper {
        private val type = Class.forName("io.github.valerioisufi.drawview.lecture.transcription.WhisperNative")
        private val native = type.getField("INSTANCE").get(null)
        private val load = type.methods.first { it.name == "load" }
        private val transcribe = type.methods.first { it.name == "transcribe" }
        private val abort = type.methods.first { it.name == "abort" }
        private val free = type.methods.first { it.name == "free" }

        fun load(model: String, gpu: Boolean): Long = load.invoke(native, model, gpu) as Long
        fun transcribe(handle: Long, samples: FloatArray, threads: Int): ByteArray? =
            transcribe.invoke(native, handle, samples, "it", "", threads) as ByteArray?

        fun abort(handle: Long) {
            abort.invoke(native, handle)
        }

        fun free(handle: Long) {
            free.invoke(native, handle)
        }
    }

    /** A way whisper.cpp uses the machine: `gpu:4`, `cpu:4`. */
    private class Way(val name: String) {
        private val parts = name.split(":")
        val gpu = parts[0] == "gpu"
        val threads = parts.getOrNull(1)?.toIntOrNull() ?: 4
    }

    @Test
    fun theFramesOfAPanWhileARecordingIsTranscribed() {
        val model = System.getProperty("drawview.bench.whisper")
        val audio = System.getProperty("drawview.bench.audio")
        if (model == null || audio == null) {
            println("TranscriptionWindowBenchmark: no -Ddrawview.bench.whisper and -Ddrawview.bench.audio, nothing measured")
            return
        }
        val seconds = System.getProperty("drawview.bench.seconds")?.toLongOrNull() ?: 20L
        val ways = (System.getProperty("drawview.bench.ways") ?: "gpu:4,gpu:2,gpu:1,cpu:4").split(",").map { Way(it.trim()) }
        val samples = readWav(File(audio))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DrawViewController(document = DrawDocument(title = "Benchmark"), coroutineScope = scope)
        writeNotes(controller)
        val intervals = Collections.synchronizedList(mutableListOf<Long>())
        lateinit var window: ComposeWindow
        SwingUtilities.invokeAndWait {
            window = ComposeWindow().apply {
                title = "Transcription window benchmark"
                setSize(1200, 900)
                setContent {
                    DrawViewTheme(darkTheme = false, dynamicColor = false) {
                        AdaptiveRoot(modifier = Modifier.fillMaxSize()) {
                            EditorScreen(context = EditorContext(controller), onClose = {}, modifier = Modifier.fillMaxSize())
                        }
                    }
                    // A pan at every frame, up and down the page, and the time since the frame before
                    LaunchedEffect(Unit) {
                        var last = 0L
                        var frames = 0
                        while (isActive) {
                            withFrameNanos {
                                val now = System.nanoTime()
                                if (last != 0L) intervals += now - last
                                last = now
                                controller.viewport.panBy(0f, if ((frames++ / 90) % 2 == 0) -6f else 6f)
                            }
                        }
                    }
                }
                isVisible = true
            }
        }
        try {
            Thread.sleep(3_000)
            SwingUtilities.invokeAndWait { repeat(3) { controller.viewport.zoomIn() } }
            Thread.sleep(2_000)
            val whisper = Whisper()
            println("| What transcribes | Frames a second | Median | 95% | 99% | Longest | Frames over 25 ms | Frames over 50 ms | Speed of the transcription |")
            println("|---|---|---|---|---|---|---|---|---|")
            report("Nothing", measure(intervals, seconds), speed = null)
            for (way in ways) {
                val handle = whisper.load(model, way.gpu)
                check(handle != 0L) { "cannot load $model" }
                val stop = AtomicBoolean(false)
                // The recording transcribed again and again, a piece of a minute at a time, as an import does
                val worker = thread(name = "bench-whisper") {
                    var from = 0
                    while (!stop.get()) {
                        val piece = samples.copyOfRange(from, minOf(from + PIECE_SAMPLES, samples.size))
                        whisper.transcribe(handle, piece, way.threads) ?: break
                        from = if (from + PIECE_SAMPLES >= samples.size) 0 else from + PIECE_SAMPLES
                    }
                }
                // The model loaded and the first piece under way
                Thread.sleep(3_000)
                val measured = measure(intervals, seconds)
                stop.set(true)
                whisper.abort(handle)
                worker.join(60_000)
                whisper.free(handle)
                report(way.name, measured, speed = speedOf(whisper, model, way, samples))
                Thread.sleep(2_000)
            }
        } finally {
            SwingUtilities.invokeAndWait { window.dispose() }
            controller.dispose()
            scope.cancel()
        }
    }

    /** How fast [way] transcribes a piece of a minute with the window still panning: seconds of audio in a second. */
    private fun speedOf(whisper: Whisper, model: String, way: Way, samples: FloatArray): Double {
        val loading = System.nanoTime()
        val handle = whisper.load(model, way.gpu)
        val loaded = (System.nanoTime() - loading) / 1e9
        val piece = samples.copyOfRange(0, minOf(PIECE_SAMPLES, samples.size))
        var elapsed = 0L
        var text = ""
        val worker = thread {
            // The first run loads and warms the model up
            whisper.transcribe(handle, piece.copyOfRange(0, SAMPLE_RATE * 5), way.threads)
            val started = System.nanoTime()
            text = whisper.transcribe(handle, piece, way.threads)?.decodeToString().orEmpty()
            elapsed = System.nanoTime() - started
        }
        worker.join()
        whisper.free(handle)
        // What was heard, to see that a faster way still hears the same
        val words = Regex("\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).joinToString("") { it.groupValues[1] }
        println("${way.name}: model loaded in ${"%.1f".format(loaded)} s, heard \"${words.trim().take(300)}\"")
        return piece.size.toDouble() / SAMPLE_RATE / (elapsed / 1e9)
    }

    /** The times between the frames of the next [seconds], in milliseconds, sorted. */
    private fun measure(intervals: MutableList<Long>, seconds: Long): List<Double> {
        intervals.clear()
        Thread.sleep(seconds * 1_000)
        return synchronized(intervals) { intervals.map { it / 1e6 } }.sorted()
    }

    private fun report(what: String, times: List<Double>, speed: Double?) {
        if (times.isEmpty()) {
            println("| $what | no frame | | | | | | | |")
            return
        }
        fun at(share: Double) = times[((times.size - 1) * share).toInt()]
        val total = times.sum() / 1_000
        println(
            "| $what | ${"%.0f".format(times.size / total)} | ${"%.1f".format(at(0.5))} ms | ${"%.1f".format(at(0.95))} ms | ${"%.1f".format(at(0.99))} ms | " +
                "${"%.0f".format(times.last())} ms | ${times.count { it > 25 }} | ${times.count { it > 50 }} | ${speed?.let { "%.1f×".format(it) } ?: "–"} |"
        )
    }

    /** A page of notes in the document of [controller]: texts and strokes all over it, so that a frame of a pan has something to draw. */
    private fun writeNotes(controller: DrawViewController) {
        val page = controller.document.pages.first()
        val random = Random(7)
        val texts = List(30) { index ->
            DrawText().apply {
                text = "**Theorem ${index + 1}.** Every bounded sequence of real numbers has a convergent subsequence, and its limit lies between the bounds."
                x = 15f; y = 12f + index * 9.2f; width = 180f; height = 8f; fontSize = 11f
            }
        }
        controller.session.submit(AddElements.of(page, texts))
        val strokes = List(250) {
            val x0 = random.nextFloat() * 180f + 10f
            val y0 = random.nextFloat() * 270f + 10f
            StrokeData(
                id = newElementId(),
                points = List(24) { i -> StrokePoint(x0 + i * 0.8f, y0 + 3f * sin(i / 2f), 0.5f, i * 8L) },
                tool = StrokeTool.PRESSURE_PEN,
                size = 0.5f,
                color = 0xFF1A237E.toInt()
            )
        }
        controller.session.submit(AddElements(page.id, strokes))
    }

    /** The samples of a WAV file of 16-bit mono PCM at 16 kHz, between -1 and 1. */
    private fun readWav(file: File): FloatArray {
        val bytes = file.readBytes()
        var offset = 12
        while (offset + 8 <= bytes.size) {
            val id = String(bytes, offset, 4)
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (id == "data") {
                val data = ByteBuffer.wrap(bytes, offset + 8, minOf(size, bytes.size - offset - 8)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                return FloatArray(data.remaining()) { data.get(it) / 32768f }
            }
            offset += 8 + size + (size and 1)
        }
        error("no audio in $file")
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val PIECE_SAMPLES = 60 * SAMPLE_RATE
    }
}
