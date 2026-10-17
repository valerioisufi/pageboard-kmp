package io.github.valerioisufi.drawview.ml.litert

import io.github.valerioisufi.drawview.ml.Accelerator
import io.github.valerioisufi.drawview.ml.EmbedderOptions
import io.github.valerioisufi.drawview.ml.GenerationPart
import io.github.valerioisufi.drawview.ml.GenerationRequest
import io.github.valerioisufi.drawview.ml.GeneratorOptions
import io.github.valerioisufi.drawview.ml.InstalledModel
import io.github.valerioisufi.drawview.ml.InstalledModels
import io.github.valerioisufi.drawview.ml.LoadedModels
import io.github.valerioisufi.drawview.ml.ModelCatalog
import io.github.valerioisufi.drawview.ml.ModelDownloads
import io.github.valerioisufi.drawview.ml.ModelKind
import java.io.File
import kotlin.test.Test
import kotlin.time.measureTimedValue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The bench of the local models on this computer, run only when asked (see the build of the
 * module): every model of the catalog found in `<bench>/models` (`<id>/<file>`, as
 * [InstalledModels] keeps them) is loaded, asked a question in Italian and an answer in the JSON
 * of a schema (generation), or the vectors of three sentences (embedding); the times go to
 * `<bench>/bench.md`.
 *
 * `drawview.bench.download` lists the ids of the models to download first into `<bench>/models`.
 * Nothing is read or written outside the directory of the bench.
 */
class LiteRtBench {
    @Test
    fun bench() = runBlocking {
        val directory = System.getProperty("drawview.bench")?.takeIf { it.isNotBlank() } ?: return@runBlocking
        val models = InstalledModels(ModelCatalog.Default, "$directory/models")
        download(models)

        val runtime = LiteRtRuntime(cacheDirectory = "$directory/cache")
        val loaded = LoadedModels(runtime)
        val report = StringBuilder()
        report.appendLine("# Bench of the local models").appendLine()
        report.appendLine("${System.getProperty("os.name")} ${System.getProperty("os.arch")}, ${Runtime.getRuntime().availableProcessors()} processors; LiteRT-LM available: ${runtime.isAvailable}")
        for (spec in ModelCatalog.Default.models) {
            val model = models[spec] ?: continue
            report.appendLine().appendLine("## ${spec.name}").appendLine()
            try {
                when (spec.kind) {
                    ModelKind.GENERATION -> benchGenerator(loaded, model, report)
                    ModelKind.EMBEDDING -> for (accelerator in Accelerator.entries) benchEmbedder(loaded, model, accelerator, report)
                }
            } catch (e: Exception) {
                report.appendLine("* failed: ${e.message}")
            }
        }
        loaded.unloadAll()
        File(directory, "bench.md").writeText(report.toString())
        println(report)
    }

    private suspend fun download(models: InstalledModels) {
        val ids = System.getProperty("drawview.bench.download").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return
        val downloads = ModelDownloads(models)
        for (id in ids) {
            val spec = ModelCatalog.Default[id] ?: error("No model $id in the catalog")
            println("Downloading ${spec.name} (${spec.sizeBytes / 1_000_000} MB)")
            downloads.download(spec)?.join()
            downloads.failures.value[id]?.let { error("The download of $id failed: $it") }
        }
    }

    private suspend fun benchGenerator(loaded: LoadedModels, model: InstalledModel, report: StringBuilder) {
        val (_, loadTime) = measureTimedValue {
            loaded.withGenerator(model, GeneratorOptions(), keepLoadedMillis = 60_000) { it.accelerator }
        }
        loaded.withGenerator(model, GeneratorOptions(), keepLoadedMillis = 60_000) { generator ->
            report.appendLine("* loaded on the ${generator.accelerator} in $loadTime")
            val question = GenerationRequest(
                system = "Rispondi in italiano, in modo breve e preciso.",
                parts = listOf(GenerationPart.Text("Che cos'è la derivata di una funzione? Due frasi.")),
                maxOutputTokens = 256
            )
            var tokens = 0
            val (answer, answerTime) = measureTimedValue { generator.generate(question) { tokens = it } }
            report.appendLine("* a question: $tokens pieces in $answerTime")
            report.appendLine("  > " + answer.trim().replace("\n", " "))

            val structured = GenerationRequest(
                system = "Riassumi l'argomento per gli appunti di uno studente.",
                parts = listOf(GenerationPart.Text("Il teorema di Rolle: una funzione continua in [a, b], derivabile in (a, b), con f(a) = f(b).")),
                jsonSchema = SCHEMA,
                maxOutputTokens = 512
            )
            val (json, jsonTime) = measureTimedValue { generator.generate(structured) }
            val valid = try {
                val answerObject = Json.parseToJsonElement(json).jsonObject
                "title" in answerObject && answerObject.getValue("points").jsonArray.isNotEmpty()
            } catch (_: Exception) {
                false
            }
            report.appendLine("* an answer in JSON in $jsonTime, of its schema: $valid")
            report.appendLine("  > " + json.trim().replace("\n", " "))
        }
        loaded.unload(model.spec.id)
    }

    private suspend fun benchEmbedder(loaded: LoadedModels, model: InstalledModel, accelerator: Accelerator, report: StringBuilder) {
        val sentences = listOf(
            "title: none | text: La derivata misura la pendenza della retta tangente al grafico.",
            "task: search result | query: rapporto incrementale e tangente",
            "title: none | text: La carbonara si prepara con guanciale, uova e pecorino."
        )
        val options = EmbedderOptions(accelerator = accelerator)
        val (_, loadTime) = measureTimedValue { loaded.withEmbedder(model, options, keepLoadedMillis = 60_000) { it.dimensions } }
        loaded.withEmbedder(model, options) { embedder ->
            val (vectors, time) = measureTimedValue { embedder.embed(sentences) }
            report.appendLine("* asked the ${accelerator}, loaded on the ${embedder.accelerator} in $loadTime; 3 sentences in $time, ${vectors.first().size} dimensions")
            report.appendLine("  * cosine of the query and the derivative: ${cosine(vectors[1], vectors[0])}, and the recipe: ${cosine(vectors[1], vectors[2])}")
        }
    }

    private fun cosine(a: FloatArray, b: FloatArray): String {
        var dot = 0.0
        for (i in a.indices) dot += a[i] * b[i]
        return "%.3f".format(dot)
    }

    private companion object {
        val SCHEMA = JsonObject(
            mapOf(
                "type" to Json.parseToJsonElement("\"object\""),
                "properties" to Json.parseToJsonElement("""{"title": {"type": "string"}, "points": {"type": "array", "items": {"type": "string"}}}"""),
                "required" to Json.parseToJsonElement("""["title", "points"]""")
            )
        ).toString()
    }
}
