package io.github.valerioisufi.pageboard.lecture.bench

import io.github.valerioisufi.pageboard.ml.Accelerator
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * The bench of the assistant on the models of the device (phase 2 of docs/plans/local-models.md, phase 0
 * of docs/plans/search.md), run only when asked (see the build of the module). With the models of
 * `<bench>/models` and the material of `<bench>/material`, it writes a report a part,
 * `<bench>/assistant-<part>.md`, and the answers in `<bench>/answers`:
 *
 * * `formulas`: [FormulaBench], the handwritten formulas of MathWriting;
 * * `prompts`: [PromptBench], the requests of the assistant on the lectures of the workspace of
 *   lecture-eval (`pageboard.bench.workspace`, read only);
 * * `speech`: [SpeechBench], whisper.cpp and Gemma 4 on ten minutes of a lecture;
 * * `search`: [SearchBench], EmbeddingGemma 2 and the words on the pages of the workspace;
 * * `notes`: [NotesBench], the live notes of a whole lecture by the configurations of
 *   `pageboard.bench.notes`, a request every `pageboard.bench.interval` seconds (300 by default), on
 *   the lectures of `pageboard.bench.noteLectures` (the one of 2026-10-15 by default);
 * * `recorder`: a lecture of the workspace through the recorder itself, whisper.cpp here and the
 *   notes of Gemini with its cache ([RecorderBench]).
 *
 * `pageboard.bench.parts` chooses the parts (all by default), `pageboard.bench.models` the models
 * (every model found by default), `pageboard.bench.modes` the ways of giving the schema (`enforced`,
 * `asked`: both by default), `pageboard.bench.temperature` the temperature of the requests,
 * `pageboard.bench.kinds` the requests of `prompts` (`live`, `context`, `review`: all by default) and
 * `pageboard.bench.lectures` how many of its lectures, `pageboard.bench.accelerator` where the
 * models run (`gpu`, `cpu`). Nothing is written outside the directory of the bench.
 */
class AssistantBench {
    @Test
    fun bench() = runBlocking {
        val directory = System.getProperty("pageboard.bench")?.takeIf { it.isNotBlank() }?.let(::File) ?: return@runBlocking
        val parts = list("pageboard.bench.parts").ifEmpty { listOf("formulas", "prompts", "speech", "search") }
        val workspace = System.getProperty("pageboard.bench.workspace")?.takeIf { it.isNotBlank() }?.let(::File)
        val bench = Bench(
            directory,
            list("pageboard.bench.models"),
            list("pageboard.bench.modes"),
            System.getProperty("pageboard.bench.temperature")?.toFloatOrNull(),
            Accelerator.entries.firstOrNull { it.name.equals(System.getProperty("pageboard.bench.accelerator"), ignoreCase = true) } ?: Accelerator.GPU
        )
        val machine = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} processors; " +
            "LiteRT-LM available: ${bench.runtime.isAvailable}; models: ${bench.generators.joinToString { it.spec.name }}, ${bench.embedder?.spec?.name}."
        for (part in parts) {
            val section = StringBuilder("# Bench of the assistant on the models of the device\n\n$machine\n\n")
            when (part) {
                "formulas" -> FormulaBench(bench).run(section)
                "prompts" -> workspace?.let { PromptBench(bench, it, kinds = list("pageboard.bench.kinds"), lectureCount = System.getProperty("pageboard.bench.lectures")?.toIntOrNull()).run(section) } ?: section.appendLine("## The requests of the assistant\n\nNo workspace (pageboard.bench.workspace).\n")
                "speech" -> SpeechBench(bench).run(section)
                "notes" -> workspace?.let {
                    NotesBench(
                        bench,
                        it,
                        configs = list("pageboard.bench.notes"),
                        intervalMs = (System.getProperty("pageboard.bench.interval")?.toLongOrNull() ?: 300) * 1000,
                        lectureIds = list("pageboard.bench.noteLectures").ifEmpty { listOf("2026-10-15_09-48-14-2ce0b6") },
                        maxDollars = System.getProperty("pageboard.bench.dollars")?.toDoubleOrNull() ?: 1.2
                    ).run(section)
                } ?: section.appendLine("## The notes of a whole lecture\n\nNo workspace (pageboard.bench.workspace).\n")
                "recorder" -> workspace?.let { RecorderBench(bench, it).run(section) } ?: section.appendLine("## A lecture through the recorder\n\nNo workspace (pageboard.bench.workspace).\n")
                "search" -> workspace?.let { SearchBench(bench, it).run(section) } ?: section.appendLine("## Search\n\nNo workspace (pageboard.bench.workspace).\n")
                else -> section.appendLine("## $part\n\nNo such part.\n")
            }
            File(directory, "assistant-$part.md").writeText(section.toString())
            println(section)
        }
        bench.loaded.unloadAll()
    }

    private fun list(property: String): List<String> =
        System.getProperty(property).orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
