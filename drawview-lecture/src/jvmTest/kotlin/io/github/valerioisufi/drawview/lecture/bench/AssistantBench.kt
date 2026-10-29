package io.github.valerioisufi.drawview.lecture.bench

import io.github.valerioisufi.drawview.ml.Accelerator
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.runBlocking

/**
 * The bench of the assistant on the models of the device (phase 2 of docs/local-models.md, phase 0
 * of docs/search.md), run only when asked (see the build of the module). With the models of
 * `<bench>/models` and the material of `<bench>/material`, it writes a report a part,
 * `<bench>/assistant-<part>.md`, and the answers in `<bench>/answers`:
 *
 * * `formulas`: [FormulaBench], the handwritten formulas of MathWriting;
 * * `prompts`: [PromptBench], the requests of the assistant on the lectures of the workspace of
 *   lecture-eval (`drawview.bench.workspace`, read only);
 * * `speech`: [SpeechBench], whisper.cpp and Gemma 4 on ten minutes of a lecture;
 * * `search`: [SearchBench], EmbeddingGemma 2 and the words on the pages of the workspace;
 * * `notes`: [NotesBench], the live notes of a whole lecture by the configurations of
 *   `drawview.bench.notes`, a request every `drawview.bench.interval` seconds (300 by default).
 *
 * `drawview.bench.parts` chooses the parts (all by default), `drawview.bench.models` the models
 * (every model found by default), `drawview.bench.modes` the ways of giving the schema (`enforced`,
 * `asked`: both by default), `drawview.bench.temperature` the temperature of the requests,
 * `drawview.bench.kinds` the requests of `prompts` (`live`, `context`, `review`: all by default) and
 * `drawview.bench.lectures` how many of its lectures, `drawview.bench.accelerator` where the
 * models run (`gpu`, `cpu`). Nothing is written outside the directory of the bench.
 */
class AssistantBench {
    @Test
    fun bench() = runBlocking {
        val directory = System.getProperty("drawview.bench")?.takeIf { it.isNotBlank() }?.let(::File) ?: return@runBlocking
        val parts = list("drawview.bench.parts").ifEmpty { listOf("formulas", "prompts", "speech", "search") }
        val workspace = System.getProperty("drawview.bench.workspace")?.takeIf { it.isNotBlank() }?.let(::File)
        val bench = Bench(
            directory,
            list("drawview.bench.models"),
            list("drawview.bench.modes"),
            System.getProperty("drawview.bench.temperature")?.toFloatOrNull(),
            Accelerator.entries.firstOrNull { it.name.equals(System.getProperty("drawview.bench.accelerator"), ignoreCase = true) } ?: Accelerator.GPU
        )
        val machine = "${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} processors; " +
            "LiteRT-LM available: ${bench.runtime.isAvailable}; models: ${bench.generators.joinToString { it.spec.name }}, ${bench.embedder?.spec?.name}."
        for (part in parts) {
            val section = StringBuilder("# Bench of the assistant on the models of the device\n\n$machine\n\n")
            when (part) {
                "formulas" -> FormulaBench(bench).run(section)
                "prompts" -> workspace?.let { PromptBench(bench, it, kinds = list("drawview.bench.kinds"), lectureCount = System.getProperty("drawview.bench.lectures")?.toIntOrNull()).run(section) } ?: section.appendLine("## The requests of the assistant\n\nNo workspace (drawview.bench.workspace).\n")
                "speech" -> SpeechBench(bench).run(section)
                "notes" -> workspace?.let {
                    NotesBench(bench, it, configs = list("drawview.bench.notes"), intervalMs = (System.getProperty("drawview.bench.interval")?.toLongOrNull() ?: 300) * 1000, maxDollars = System.getProperty("drawview.bench.dollars")?.toDoubleOrNull() ?: 1.2).run(section)
                } ?: section.appendLine("## The notes of a whole lecture\n\nNo workspace (drawview.bench.workspace).\n")
                "search" -> workspace?.let { SearchBench(bench, it).run(section) } ?: section.appendLine("## Search\n\nNo workspace (drawview.bench.workspace).\n")
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
