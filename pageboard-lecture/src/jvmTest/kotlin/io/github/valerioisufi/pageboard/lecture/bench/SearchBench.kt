package io.github.valerioisufi.pageboard.lecture.bench

import io.github.valerioisufi.pageboard.ml.EmbedderOptions
import java.io.File
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private class EvalWorkspace(val snapshot: EvalSnapshot)

@Serializable
private class EvalSnapshot(val documents: List<EvalDocument> = emptyList())

@Serializable
private class EvalDocument(val id: String, val title: String = "")

/**
 * Phase 0 of docs/plans/search.md: the pages of the notebooks of the workspace of lecture-eval (the text
 * of their slides and the notes typed on them, a chunk a page), searched by [QUERIES], each with
 * the page it should find:
 *
 * * by their words, as FTS5 ranks them (BM25, `unicode61 remove_diacritics 2`);
 * * by EmbeddingGemma 2, at 768, 512 and 256 dimensions, with the prompt of a query of its model
 *   card (`task: search result | query:`) and of the page of LiteRT-LM (`task: search query | text:`);
 * * by both, merged by Reciprocal Rank Fusion (k = 60).
 *
 * The page found in the first five, within one page (a slide continued on the next one), and the
 * reciprocal rank; the time to compute the vectors of every page.
 *
 * Phase 6 of docs/plans/library-search.md: what a least score of the vectors would keep, of the pages of
 * [QUERIES] and of the pages found for [NO_ANSWER], searches the notebooks say nothing about.
 */
internal class SearchBench(private val bench: Bench, private val workspace: File) {
    class Page(val documentId: String, val title: String, val number: Int, val text: String)

    /** A search of the student: the page of the document whose id starts with [document]; [paraphrase] when it does not use the words of the page. */
    class Query(val text: String, val document: String, val page: Int, val paraphrase: Boolean)

    private class Ranking(val name: String, val ranks: List<List<Int>>)

    suspend fun run(report: StringBuilder) {
        report.appendLine("## Search (phase 0 of docs/plans/search.md)").appendLine()
        val pages = pages()
        if (pages.isEmpty()) {
            report.appendLine("No page in $workspace.").appendLine()
            return
        }
        val targets = QUERIES.map { query -> pages.indexOfFirst { it.documentId.startsWith(query.document) && it.number == query.page } }
        require(targets.none { it < 0 }) { "A query without its page: ${QUERIES.filterIndexed { i, _ -> targets[i] < 0 }.map { it.text }}" }
        report.appendLine(
            "${pages.size} pages of ${pages.map { it.documentId }.distinct().size} notebooks (the text of the slides and the notes typed, a chunk a page); " +
                "${QUERIES.size} searches in Italian written for this bench, ${QUERIES.count { it.paraphrase }} without the words of their page."
        )
        val rankings = mutableListOf<Ranking>()
        val words = Bm25(pages.map { indexWords(it.text) })
        val byWords = Ranking("words (BM25)", QUERIES.map { words.rank(indexWords(it.text)) })
        rankings.add(byWords)

        val model = bench.embedder
        if (model != null) {
            val documents = pages.map { "title: ${it.title} | text: ${it.text.take(MAX_PAGE_CHARS)}" }
            val start = TimeSource.Monotonic.markNow()
            var accelerator = ""
            val vectors = bench.withEmbedder(model, EmbedderOptions()) { embedder ->
                accelerator = embedder.accelerator.name
                documents.chunked(16).flatMap { embedder.embed(it) }
            }
            val indexMillis = start.elapsedNow().inWholeMilliseconds
            report.appendLine().appendLine("The vectors of the ${pages.size} pages: ${seconds(indexMillis)} on the $accelerator (${indexMillis / pages.size} ms a page).")
            for ((format, prefix) in listOf("model card" to "task: search result | query: ", "LiteRT-LM" to "task: search query | text: ")) {
                val queries = bench.withEmbedder(model, EmbedderOptions()) { embedder -> embedder.embed(QUERIES.map { prefix + it.text }) }
                for (dimensions in listOf(768, 512, 256)) {
                    val pageVectors = vectors.map { truncate(it, dimensions) }
                    val ranks = queries.map { query -> truncate(query, dimensions).let { q -> pageVectors.indices.sortedByDescending { dot(q, pageVectors[it]) } } }
                    val ranking = Ranking("vectors $dimensions, $format", ranks)
                    rankings.add(ranking)
                    if (dimensions == 512) {
                        rankings.add(Ranking("words + vectors 512, $format (RRF)", byWords.ranks.zip(ranks) { a, b -> fuse(a, b) }))
                        rankings.add(Ranking("words × 0.5 + vectors 512, $format (RRF)", byWords.ranks.zip(ranks) { a, b -> fuse(a, b, wordsWeight = 0.5) }))
                    }
                }
            }
            val prompt = "task: search result | query: "
            val answered = bench.withEmbedder(model, EmbedderOptions()) { embedder -> embedder.embed(QUERIES.map { prompt + it.text }) }
            val unanswered = bench.withEmbedder(model, EmbedderOptions()) { embedder -> embedder.embed(NO_ANSWER.map { prompt + it }) }
            thresholds(report, pages, targets, vectors, answered, unanswered)
            bench.loaded.unload(model.spec.id)
        } else {
            report.appendLine().appendLine("No embedding model in ${bench.directory}/models.")
        }

        report.appendLine().appendLine("| Search by | In the first 5 | Within one page, first 5 | Reciprocal rank | In the first 5, with the words | In the first 5, paraphrased |")
        report.appendLine("|---|---|---|---|---|---|")
        for (ranking in rankings) {
            val rankOf = ranking.ranks.mapIndexed { i, ranks -> ranks.indexOf(targets[i]) }
            val near = ranking.ranks.mapIndexed { i, ranks -> ranks.take(5).any { found -> pages[found].documentId == pages[targets[i]].documentId && kotlin.math.abs(pages[found].number - pages[targets[i]].number) <= 1 } }
            fun top5(filter: (Query) -> Boolean): String {
                val chosen = QUERIES.indices.filter { filter(QUERIES[it]) }
                return percent(chosen.count { rankOf[it] in 0..4 }, chosen.size)
            }
            val mrr = rankOf.meanOf { if (it < 0) 0.0 else 1.0 / (it + 1) }
            report.appendLine(
                "| ${ranking.name} | ${top5 { true }} | ${percent(near.count { it }, near.size)} | ${"%.2f".format(mrr)} | ${top5 { !it.paraphrase }} | ${top5 { it.paraphrase }} |"
            )
        }
        val details = StringBuilder()
        for ((i, query) in QUERIES.withIndex()) {
            details.appendLine("### ${query.text} → ${pages[targets[i]].title} p. ${query.page}")
            for (ranking in rankings) details.appendLine("* ${ranking.name}: ${ranking.ranks[i].take(5).joinToString { "${pages[it].title.take(20)} p. ${pages[it].number}" }}")
        }
        bench.output("search/rankings.md").writeText(details.toString())
        report.appendLine()
    }

    /** What each least score keeps, at the dimensions of the index, with the scores behind it in `search/scores.md`. */
    private fun thresholds(
        report: StringBuilder,
        pages: List<Page>,
        targets: List<Int>,
        vectors: List<FloatArray>,
        answered: List<FloatArray>,
        unanswered: List<FloatArray>
    ) {
        val pageVectors = vectors.map { truncate(it, THRESHOLD_DIMENSIONS) }
        fun scores(query: FloatArray) = truncate(query, THRESHOLD_DIMENSIONS).let { q -> pageVectors.map { dot(q, it).toDouble() } }
        val answeredScores = answered.map(::scores)
        val unansweredScores = unanswered.map(::scores)
        val targetScores = targets.mapIndexed { i, target -> answeredScores[i][target] }
        val paraphrased = QUERIES.indices.filter { QUERIES[it].paraphrase }

        report.appendLine().appendLine("### A least score of the vectors (phase 6 of docs/plans/library-search.md)").appendLine()
        report.appendLine("${NO_ANSWER.size} searches with no answer in the notebooks. The scores, $THRESHOLD_DIMENSIONS dimensions:").appendLine()
        report.appendLine("* the page wanted: ${spread(targetScores)};")
        report.appendLine("* the best page of a search with an answer: ${spread(answeredScores.map { it.max() })};")
        report.appendLine("* the best page of a search without one: ${spread(unansweredScores.map { it.max() })}.").appendLine()
        report.appendLine("| Least score | Page wanted kept | Paraphrased kept | No answer, nothing shown | Pages shown, with an answer (mean) | Pages shown, without (mean) |")
        report.appendLine("|---|---|---|---|---|---|")
        for (step in 50..76 step 2) {
            val least = step / 100.0
            fun shown(all: List<List<Double>>) = "%.1f".format(all.meanOf { scores -> scores.count { it >= least }.coerceAtMost(SHOWN_AT_MOST).toDouble() })
            report.appendLine(
                "| ${"%.2f".format(least)} | ${percent(targetScores.count { it >= least }, targetScores.size)} | " +
                    "${percent(paraphrased.count { targetScores[it] >= least }, paraphrased.size)} | " +
                    "${percent(unansweredScores.count { scores -> scores.none { it >= least } }, unansweredScores.size)} | " +
                    "${shown(answeredScores)} | ${shown(unansweredScores)} |"
            )
        }
        val details = StringBuilder()
        fun best(scores: List<Double>) = scores.indices.sortedByDescending { scores[it] }.take(3).joinToString { "%.3f %s p. %d".format(scores[it], pages[it].title.take(24), pages[it].number) }
        for ((i, query) in QUERIES.withIndex()) details.appendLine("* ${"%.3f".format(targetScores[i])} ${query.text}: ${best(answeredScores[i])}")
        details.appendLine()
        for ((i, query) in NO_ANSWER.withIndex()) details.appendLine("* $query: ${best(unansweredScores[i])}")
        bench.output("search/scores.md").writeText(details.toString())
    }

    private fun spread(values: List<Double>): String {
        val sorted = values.sorted()
        return "from %.3f to %.3f, median %.3f".format(sorted.first(), sorted.last(), sorted[sorted.size / 2])
    }

    /** The pages of the notebooks of the lectures of the workspace: the slides, and the notes typed that were not removed. */
    private fun pages(): List<Page> {
        val json = Json { ignoreUnknownKeys = true }
        val titles = File(workspace, "workspace.json").takeIf { it.isFile }
            ?.let { json.decodeFromString(EvalWorkspace.serializer(), it.readText()).snapshot.documents.associate { d -> d.id to d.title.replace('\n', ' ') } }.orEmpty()
        class Building(val number: Int, var slide: String = "", val notes: LinkedHashMap<String, String> = LinkedHashMap())
        val documents = LinkedHashMap<String, LinkedHashMap<String, Building>>()
        val lectures = File(workspace, "lectures").listFiles().orEmpty().sortedBy { it.name }
        for (file in lectures.map { File(it, "prepared.json") }.filter { it.isFile }) {
            val prepared = json.decodeFromString(PreparedLecture.serializer(), file.readText())
            val document = documents.getOrPut(prepared.documentId) { LinkedHashMap() }
            for (slide in prepared.context.slides) document.getOrPut(slide.pageId) { Building(slide.number) }.slide = slide.text
            for (note in prepared.notes.filter { !it.handwritten && it.removedAtMs == null && it.text.isNotBlank() }) {
                document.getOrPut(note.pageId) { Building(note.pageNumber) }.notes[note.text] = note.text
            }
        }
        return documents.flatMap { (id, document) ->
            document.values.map { Page(id, titles[id] ?: id, it.number, (listOf(it.slide) + it.notes.values).filter { text -> text.isNotBlank() }.joinToString("\n")) }
        }.filter { it.text.isNotBlank() }
    }

    private fun truncate(vector: FloatArray, dimensions: Int): FloatArray {
        val cut = vector.copyOf(minOf(dimensions, vector.size))
        val norm = sqrt(cut.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
        for (i in cut.indices) cut[i] /= norm
        return cut
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** The ranking of the words and of the vectors merged by Reciprocal Rank Fusion, their first 50 each, the words weighing [wordsWeight]. */
    private fun fuse(words: List<Int>, vectors: List<Int>, wordsWeight: Double = 1.0): List<Int> {
        val scores = HashMap<Int, Double>()
        for ((ranking, weight) in listOf(words to wordsWeight, vectors to 1.0)) {
            ranking.take(50).forEachIndexed { rank, page -> scores[page] = (scores[page] ?: 0.0) + weight / (RRF_K + rank + 1) }
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key } + (words + vectors).distinct().filter { it !in scores }
    }

    /** BM25 as FTS5 computes it (k1 = 1.2, b = 0.75). */
    internal class Bm25(private val documents: List<List<String>>) {
        private val frequencies = documents.map { words -> words.groupingBy { it }.eachCount() }
        private val documentsWith = HashMap<String, Int>().also { counts -> frequencies.forEach { f -> f.keys.forEach { counts[it] = (counts[it] ?: 0) + 1 } } }
        private val averageLength = documents.meanOf { it.size.toDouble() }

        fun rank(query: List<String>): List<Int> = documents.indices.sortedByDescending { score(query, it) }

        private fun score(query: List<String>, document: Int): Double = query.distinct().sumOf { word ->
            val frequency = frequencies[document][word] ?: return@sumOf 0.0
            val n = documentsWith[word] ?: 0
            val idf = ln((documents.size - n + 0.5) / (n + 0.5) + 1)
            idf * frequency * (K1 + 1) / (frequency + K1 * (1 - B + B * documents[document].size / averageLength))
        }

        companion object {
            const val K1 = 1.2
            const val B = 0.75
        }
    }

    companion object {
        private const val RRF_K = 60
        private const val MAX_PAGE_CHARS = 2000
        private const val THRESHOLD_DIMENSIONS = 512

        /** The vector results a search takes from the index, `SearchIndex.DEFAULT_SEARCH_LIMIT`. */
        private const val SHOWN_AT_MOST = 50

        /** Searches with no answer in the notebooks: ten far from their subjects, ten near them. */
        val NO_ANSWER = listOf(
            "ricetta della carbonara con il guanciale",
            "trama dei Promessi Sposi",
            "cause della prima guerra mondiale",
            "come si coltivano i pomodori sul balcone",
            "regola del fuorigioco nel calcio",
            "fotosintesi clorofilliana nelle piante",
            "pittura del Rinascimento a Firenze",
            "diritto di recesso nei contratti di affitto",
            "sintomi dell'influenza stagionale",
            "orari dei treni per Milano",
            "layout flexbox nei fogli di stile CSS",
            "normalizzazione dei database relazionali in terza forma normale",
            "analisi sintattica LALR in un compilatore",
            "garbage collection generazionale nella JVM",
            "teorema del campionamento di Nyquist",
            "integrali di linea e teorema di Green",
            "trasformata di Fourier discreta",
            "equazioni di Maxwell nel vuoto",
            "shader e pipeline grafica di OpenGL",
            "routing BGP tra sistemi autonomi"
        )

        /** The searches, written for the notebooks of the workspace of 2026-10-18 (most slides are in English). */
        val QUERIES = listOf(
            Query("differenza tra architettura software e architettura di sistema", "eb419115", 2, paraphrase = true),
            Query("disaccoppiamento nello spazio e nel tempo tra i componenti", "eb419115", 18, paraphrase = true),
            Query("sottoscrizioni publish subscribe per topic o per contenuto", "eb419115", 26, paraphrase = false),
            Query("come si costruisce la finger table di Chord", "eb419115", 70, paraphrase = false),
            Query("distanza XOR tra identificativi in Kademlia", "eb419115", 82, paraphrase = false),
            Query("inondazione delle richieste ai vicini con TTL", "eb419115", 51, paraphrase = true),
            Query("indirizzare i file per il loro hash invece che per la posizione", "eb419115", 88, paraphrase = true),
            Query("consenso senza autorità centrale per aggiungere un blocco", "eb419115", 105, paraphrase = true),
            Query("super peer nelle architetture ibride", "eb419115", 94, paraphrase = false),
            Query("perché ECB non va bene se il messaggio si ripete", "12ccc186", 10, paraphrase = true),
            Query("la decifratura CBC si può parallelizzare", "12ccc186", 14, paraphrase = false),
            Query("perché l'autenticazione di WEP era rotta col riuso del keystream", "12ccc186", 26, paraphrase = true),
            Query("modalità contatore: il cifrario a blocchi diventa a flusso", "12ccc186", 21, paraphrase = true),
            Query("a cosa serve il campo authenticator nella Access-Request", "d4b1b0da", 12, paraphrase = false),
            Query("come viene nascosta la password dell'utente in RADIUS", "d4b1b0da", 15, paraphrase = true),
            Query("perché Diameter usa un trasporto affidabile al posto di UDP", "d4b1b0da", 37, paraphrase = true),
            Query("autenticazione 2G con RAND e SRES", "f25aa4bd", 16, paraphrase = false),
            Query("numero di sequenza come nonce implicito nell'AKA", "f25aa4bd", 26, paraphrase = true),
            Query("come il 5G protegge l'identità dell'abbonato", "f25aa4bd", 10, paraphrase = true),
            Query("scomposizione dell'errore in bias, varianza e rumore", "45e3eec6", 17, paraphrase = true),
            Query("stimare l'errore con l'out-of-bag senza cross validation", "45e3eec6", 31, paraphrase = false),
            Query("perché le random forest scelgono a caso le feature a ogni split", "45e3eec6", 37, paraphrase = true),
            Query("teorema di approssimazione universale delle reti neurali", "3e1f4bef", 37, paraphrase = true),
            Query("regola della catena nella backpropagation", "24793ea5", 4, paraphrase = false),
            Query("ciclo MAPE monitor analyze plan execute", "2f198835", 13, paraphrase = false),
            Query("scalare in base a soglie sulla CPU: pro e contro", "2f198835", 31, paraphrase = true),
            Query("vincoli sulla dimensione dei frame nello scheduler ciclico", "49f4c7d6", 13, paraphrase = false),
            Query("slack stealing per i job aperiodici", "49f4c7d6", 22, paraphrase = false),
            Query("deadline monotonic è migliore di rate monotonic", "1c4f2849", 21, paraphrase = false),
            Query("fattore di utilizzazione di EDF, teorema di Liu e Layland", "a41ffe77", 13, paraphrase = false)
        )
    }
}
