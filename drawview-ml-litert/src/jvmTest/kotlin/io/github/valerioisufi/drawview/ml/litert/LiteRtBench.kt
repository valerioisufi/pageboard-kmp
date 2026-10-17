package io.github.valerioisufi.drawview.ml.litert

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
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
import kotlinx.serialization.json.jsonPrimitive

/**
 * The bench of the local models on this computer, run only when asked (see the build of the
 * module): every model of the catalog found in `<bench>/models` (`<id>/<file>`, as
 * [InstalledModels] keeps them) is loaded, asked a question in Italian and an answer in the JSON
 * of a schema, enforced and only asked (generation), or the vectors of three sentences
 * (embedding); the times go to `<bench>/bench.md`, with why the GPU cannot be used, if it cannot.
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
            report.appendLine("* the GPU: ${gpuFailure(model, "$directory/cache")}")
            if (spec.kind == ModelKind.GENERATION) report.appendLine("* a raw answer of LiteRT-LM: ${rawAnswer(model, "$directory/cache")}")
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

            // The same request with its schema enforced (constrained decoding), and only asked in the prompt
            for (constrained in listOf(true, false)) {
                val structured = GenerationRequest(
                    system = "Riassumi l'argomento per gli appunti di uno studente, in italiano, in tre o quattro punti brevi. " +
                        "Rispondi solo con un oggetto JSON di questo schema, senza altro testo: $SCHEMA",
                    parts = listOf(GenerationPart.Text("Il teorema di Rolle: una funzione continua in [a, b], derivabile in (a, b), con f(a) = f(b).")),
                    jsonSchema = SCHEMA.takeIf { constrained },
                    maxOutputTokens = 512
                )
                val (json, jsonTime) = measureTimedValue { generator.generate(structured) }
                val kind = if (constrained) "its schema enforced" else "its schema asked only"
                report.appendLine("* an answer in JSON, $kind, in $jsonTime: ${check(json)}")
                report.appendLine("  > " + json.trim().replace("\n", " ").take(600))
            }
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
            report.appendLine("* asked the $accelerator, loaded on the ${embedder.accelerator} in $loadTime; 3 sentences in $time, ${vectors.first().size} dimensions")
            report.appendLine("  * cosine of the query and the derivative: ${cosine(vectors[1], vectors[0])}, and the recipe: ${cosine(vectors[1], vectors[2])}")
        }
    }

    /** Whether [answer] is an object of [SCHEMA] exactly: its two keys, a title, points that say something. */
    private fun check(answer: String): String = try {
        val text = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
        val json = Json.parseToJsonElement(text).jsonObject
        val title = json["title"]?.jsonPrimitive?.content.orEmpty()
        val points = json["points"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        when {
            json.keys != setOf("title", "points") -> "other keys, ${json.keys}"
            title.count { it.isLetter() } < 3 -> "no title"
            points.isEmpty() || points.any { point -> point.count { it.isLetter() } < 3 } -> "empty points"
            else -> "valid, ${points.size} points"
        }
    } catch (e: Exception) {
        "not JSON (${e.message?.lineSequence()?.firstOrNull()?.take(80)})"
    }

    /** Why LiteRT-LM cannot load [model] on the GPU of this computer, if it cannot. */
    private fun gpuFailure(model: InstalledModel, cacheDirectory: String): String = try {
        when (model.spec.kind) {
            ModelKind.GENERATION ->
                Engine(EngineConfig(modelPath = model.path, backend = Backend.GPU(), cacheDir = cacheDirectory)).use { it.initialize() }
            ModelKind.EMBEDDING ->
                EmbeddingEngine(EmbeddingEngineConfig(modelPath = model.path, backend = Backend.GPU(), cacheDir = cacheDirectory)).use { it.initialize() }
        }
        "it loads"
    } catch (e: Throwable) {
        "cannot be used: " + (e.message ?: e::class.simpleName.orEmpty()).replace("\n", " ")
    }

    /**
     * The messages of LiteRT-LM to a JSON asked in the prompt only, as they come (contents, tool
     * calls, channels): with the constrained decoding of the conversations on and off, and with a
     * prompt asking to start with the brace.
     */
    @OptIn(ExperimentalApi::class)
    private fun rawAnswer(model: InstalledModel, cacheDirectory: String): String = try {
        Engine(EngineConfig(modelPath = model.path, backend = Backend.CPU(), cacheDir = cacheDirectory)).use { engine ->
            engine.initialize()
            val constrainedBefore = ExperimentalFlags.enableConversationConstrainedDecoding
            val answers = listOf(true, false).flatMap { constrained ->
                ExperimentalFlags.enableConversationConstrainedDecoding = constrained
                listOf("", " Comincia la risposta con { e non usare blocchi di codice.").map { hint ->
                    val config = ConversationConfig(
                        systemInstruction = Contents.of("Rispondi solo con un oggetto JSON di questo schema, senza altro testo: $SCHEMA.$hint"),
                        maxOutputToken = 256
                    )
                    engine.createConversation(config).use { conversation ->
                        val message = conversation.sendMessage(Contents.of("Il teorema di Rolle, in tre punti."))
                        "\n  * flag $constrained, hint ${hint.isNotEmpty()}: contents " +
                            "${message.contents.contents.map { it.toString().replace("\n", " ").take(300) }}, tool calls ${message.toolCalls}, channels ${message.channels}"
                    }
                }
            }
            ExperimentalFlags.enableConversationConstrainedDecoding = constrainedBefore
            answers.joinToString("")
        }
    } catch (e: Throwable) {
        "failed: ${e.message}"
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
                "required" to Json.parseToJsonElement("""["title", "points"]"""),
                "additionalProperties" to Json.parseToJsonElement("false")
            )
        ).toString()
    }
}
