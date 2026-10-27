package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.lecture.AssistantSettings
import io.github.valerioisufi.drawview.lecture.LectureAnalysis
import io.github.valerioisufi.drawview.lecture.LecturePrompts
import io.github.valerioisufi.drawview.lecture.TranscriptSegment
import io.github.valerioisufi.drawview.lecture.audio.AudioChunk
import io.github.valerioisufi.drawview.lecture.audio.LECTURE_SAMPLE_RATE
import io.github.valerioisufi.drawview.lecture.audio.RecordingFile
import io.github.valerioisufi.drawview.lecture.audio.SpeechChunker
import io.github.valerioisufi.drawview.lecture.audio.Wav
import io.github.valerioisufi.drawview.lecture.formatLectureTime
import io.github.valerioisufi.drawview.lecture.transcription.WhisperTranscriber
import io.github.valerioisufi.drawview.lecture.transcription.whisperEngine
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import java.io.File
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private class TranscribedLecture(val transcript: List<TranscriptSegment> = emptyList())

/**
 * Ten minutes of a lecture of `<bench>/material/pencilnotes` (from its tenth minute), transcribed
 * by whisper.cpp (`<bench>/whisper/ggml-large-v3-turbo-q5_0.bin`, pieces of a minute cut at a
 * pause, the prompt of the course) and by every generation model of the bench (clips of at most
 * 30 seconds cut at a pause, the request of [LecturePrompts.transcriptionOnlyPrompt]), against the
 * transcript of the lecture, written by Gemini from its audio: the words wrong and the time.
 */
internal class SpeechBench(
    private val bench: Bench,
    private val documentId: String = "eb419115-8ec7-4602-a552-75bfebb25f5e",
    private val lectureId: String = "2026-10-09_09-39-11-5849ef",
    private val fromMs: Long = 10 * 60_000L,
    private val lengthMs: Long = 10 * 60_000L
) {
    private val settings = AssistantSettings(spokenLanguage = "it")

    suspend fun run(report: StringBuilder) {
        report.appendLine("## Speech").appendLine()
        val documentDirectory = File(bench.material, "pencilnotes/lectures/doc-$documentId")
        val lectureDirectory = File(documentDirectory, lectureId)
        val lectureFile = File(lectureDirectory, "lecture.json")
        if (!lectureFile.isFile) {
            report.appendLine("No lecture $lectureFile.").appendLine()
            return
        }
        val json = Json { ignoreUnknownKeys = true }
        val transcript = json.decodeFromString(TranscribedLecture.serializer(), lectureFile.readText()).transcript
        // The window on the segments of the reference, so that it holds what is said in the audio
        val first = transcript.firstOrNull { it.startMs >= fromMs } ?: return
        val last = transcript.lastOrNull { it.endMs <= first.startMs + lengthMs } ?: return
        val reference = transcript.filter { it.startMs >= first.startMs && it.endMs <= last.endMs }
        val referenceText = reference.joinToString(" ") { it.text }
        val samples = RecordingFile(lectureDirectory.path).readSegment(first.startMs, last.endMs - first.startMs) ?: return
        val audioSeconds = samples.size.toDouble() / LECTURE_SAMPLE_RATE
        val context = File(documentDirectory, "context.json").takeIf { it.isFile }
            ?.let { json.decodeFromString(CourseContextFile.serializer(), it.readText()).text }.orEmpty()
        report.appendLine(
            "`$lectureId`, from ${formatLectureTime(first.startMs)} to ${formatLectureTime(last.endMs)} (${"%.0f".format(audioSeconds)} s, " +
                "${spokenWords(referenceText).size} words in the transcript of Gemini, the reference)."
        )
        report.appendLine().appendLine("| Engine | Pieces | Words wrong (WER) | Time | Time / audio |").appendLine("|---|---|---|---|---|")
        val output = bench.output("speech/reference.txt")
        output.writeText(referenceText)

        val whisperModel = File(bench.directory, "whisper/ggml-large-v3-turbo-q5_0.bin")
        if (whisperModel.isFile && whisperEngine() != null) {
            val whisper = WhisperTranscriber(whisperModel.path, whisperEngine())
            val hints = LecturePrompts.speechHints(settings, context, emptyList())
            val pieces = pieces(samples, targetSeconds = 60, maxSeconds = 90)
            val start = TimeSource.Monotonic.markNow()
            val text = pieces.map { piece -> whisper.transcribe(Wav.encode(piece.samples), hints).segments.joinToString(" ") { it.text.trim() } }.joinToString(" ")
            val millis = start.elapsedNow().inWholeMilliseconds
            whisper.close()
            bench.output("speech/whisper.txt").writeText(text)
            report.appendLine("| whisper.cpp large-v3-turbo q5 | ${pieces.size} | ${"%.1f".format(wordErrorRate(text, referenceText) * 100)} % | ${seconds(millis)} | ${"%.2f".format(millis / 1000.0 / audioSeconds)} |")
        } else {
            report.appendLine("| whisper.cpp | - | not available | | |")
        }

        val clips = pieces(samples, targetSeconds = 25, maxSeconds = 30)
        for (model in bench.generators.filter { it.spec.id != "gemma-4-12b" }) {
            val options = GeneratorOptions(maxTokens = 4096, audio = true)
            var millis = 0L
            var failed = 0
            val texts = clips.map { clip ->
                val prompt = LecturePrompts.transcriptionOnlyPrompt(settings, context, Wav.encode(clip.samples))
                val answer = bench.ask(model, options, prompt, SchemaMode.ASKED)
                millis += answer.millis
                try {
                    LectureAnalysis.parseTranscript(jsonOf(answer.text, prompt.schema))
                } catch (_: Exception) {
                    failed++
                    answer.error?.let { "[$it]" } ?: answer.text
                }
            }
            bench.loaded.unload(model.spec.id)
            val text = texts.joinToString(" ")
            bench.output("speech/${model.spec.id}.txt").writeText(texts.joinToString("\n"))
            report.appendLine(
                "| ${model.spec.name}, schema asked | ${clips.size}${if (failed > 0) " ($failed not JSON)" else ""} | " +
                    "${"%.1f".format(wordErrorRate(text, referenceText) * 100)} % | ${seconds(millis)} | ${"%.2f".format(millis / 1000.0 / audioSeconds)} |"
            )
            println("speech ${model.spec.id}: ${seconds(millis)}")
        }
        report.appendLine()
    }

    /** [samples] in pieces of about [targetSeconds] cut at a pause, at most [maxSeconds]. */
    private fun pieces(samples: ShortArray, targetSeconds: Int, maxSeconds: Int): List<AudioChunk> {
        val chunker = SpeechChunker(targetSeconds, maxSeconds, overlapSeconds = 0)
        return chunker.push(samples) + listOfNotNull(chunker.cutNow())
    }
}
