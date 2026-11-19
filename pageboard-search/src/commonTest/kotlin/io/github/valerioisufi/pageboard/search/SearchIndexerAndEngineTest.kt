package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.ml.Accelerator
import io.github.valerioisufi.pageboard.ml.Embedder
import io.github.valerioisufi.pageboard.ml.InstalledModel
import io.github.valerioisufi.pageboard.ml.ModelCatalog
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SearchIndexerAndEngineTest {
    /**
     * Fake [Embedder] that maps semantic concepts onto orthogonal axes so paraphrases without
     * shared words still have high cosine similarity.
     */
    private class ConceptEmbedder : Embedder {
        override val model: InstalledModel = InstalledModel(ModelCatalog.EMBEDDING_GEMMA_2_TEXT, "/fake/model.litertlm")
        override val accelerator: Accelerator = Accelerator.CPU
        override val dimensions: Int = 4
        val embeddedTexts = mutableListOf<String>()

        override suspend fun embed(texts: List<String>): List<FloatArray> {
            embeddedTexts.addAll(texts)
            return texts.map { raw ->
                val lower = raw.lowercase()
                when {
                    // Axis 0: symmetric block cipher / ECB repetition
                    "ecb" in lower || "blocchi" in lower || "ripete" in lower || "identici" in lower ->
                        floatArrayOf(1f, 0f, 0f, 0f)
                    // Axis 1: distributed hash tables / Kademlia XOR metric
                    "kademlia" in lower || "xor" in lower || "dht" in lower || "nodi vicini" in lower ->
                        floatArrayOf(0f, 1f, 0f, 0f)
                    // Axis 2: real-time scheduling / deadline monotonic & rate monotonic
                    "deadline" in lower || "monotonic" in lower || "schedulazione" in lower ->
                        floatArrayOf(0f, 0f, 1f, 0f)
                    else ->
                        floatArrayOf(0f, 0f, 0f, 1f)
                }
            }
        }

        override fun close() {}
    }

    @Test
    fun thePagesOfADocumentAboutATextTheClosestFirst() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val document = store.createDocument("Reti")
        val first = document.pages.single()
        val second = document.addPage().also { store.savePage(document, it) }
        for ((page, text) in listOf(first to "Kademlia usa la metrica XOR tra i nodi.", second to "Rate monotonic assegna le priorità per periodo.")) {
            val element = TextElement().apply { this.text = text; x = 10f; y = 10f; width = 100f; height = 20f }
            page.add(element)
            store.saveText(page, element)
        }
        val other = store.createDocument("Altro")
        val otherText = TextElement().apply { text = "Schedulazione deadline monotonic."; width = 100f; height = 20f }
        other.pages.single().add(otherText)
        store.saveText(other.pages.single(), otherText)
        store.flush()

        SearchIndex(SearchIndex.MEMORY_PATH, dimensions = 4).use { index ->
            SearchIndexer(catalog = store, index = index, sources = listOf(DocumentSearchSource(store)), embedder = ConceptEmbedder(), quietMillis = 0L).indexNow()
            val engine = SearchEngine(index, embedder = ConceptEmbedder())
            // By meaning, only the pages of the document asked
            assertEquals(second.id, engine.pagesAbout(document.id, "## Schedulazione\nIl deadline monotonic").first())
            assertEquals(first.id, engine.pagesAbout(document.id, "Le DHT e i nodi vicini").first())
            assertTrue(engine.pagesAbout(document.id, "   ").isEmpty())
        }
    }

    @Test
    fun indexesIncrementallyAndSearchesByWordsAndByMeaning() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val cryptoDoc = store.createDocument("Crittografia")
        val cryptoPage = cryptoDoc.pages.single()
        val t1 = TextElement().apply {
            text = "Nella cifratura a blocchi due blocchi di testo in chiaro identici producono lo stesso testo cifrato."
            x = 10f
            y = 10f
            width = 100f
            height = 20f
        }
        cryptoPage.add(t1)
        store.saveText(cryptoPage, t1)

        val p2pDoc = store.createDocument("Sistemi P2P")
        val p2pPage = p2pDoc.pages.single()
        val t2 = TextElement().apply {
            text = "Kademlia calcola la distanza tra gli identificativi con l'operazione XOR."
            x = 10f
            y = 10f
            width = 100f
            height = 20f
        }
        p2pPage.add(t2)
        store.saveText(p2pPage, t2)
        store.flush()

        SearchIndex(SearchIndex.MEMORY_PATH, dimensions = 4).use { index ->
            val source = DocumentSearchSource(store)
            val embedder = ConceptEmbedder()
            val indexer = SearchIndexer(
                catalog = store,
                index = index,
                sources = listOf(source),
                embedder = embedder,
                quietMillis = 0L
            )

            indexer.indexNow()
            assertEquals(2, indexer.progress.value.totalDocuments)
            assertEquals(0, indexer.progress.value.unembeddedChunks)
            val initialEmbedCalls = embedder.embeddedTexts.size
            assertEquals(4, initialEmbedCalls) // 2 titles + 2 text chunks

            // Running indexNow again when nothing changed does not re-read or re-embed
            indexer.indexNow()
            assertEquals(initialEmbedCalls, embedder.embeddedTexts.size)

            // 1. Word-only search (no embedder)
            val wordEngine = SearchEngine(index)
            val wordResults = wordEngine.search("distanza XOR Kademlia")
            assertEquals(1, wordResults.size)
            assertEquals(p2pDoc.id, wordResults.single().documentId)
            assertTrue("XOR" in wordResults.single().hits.first().snippet)

            // 2. Semantic + hybrid search (paraphrase without shared words in the chunk)
            val hybridEngine = SearchEngine(index, embedder = embedder)
            val paraphraseResults = hybridEngine.search("perché ECB non va bene se il messaggio si ripete")
            assertTrue(paraphraseResults.isNotEmpty())
            assertEquals(cryptoDoc.id, paraphraseResults.first().documentId)

            // 3. A document in the trash leaves the index, and is found again once restored
            store.trashDocument(cryptoDoc.id)
            indexer.indexNow()
            assertTrue(wordEngine.search("cifratura blocchi").isEmpty())
            store.restore(cryptoDoc.id)
            indexer.indexNow()
            assertEquals(cryptoDoc.id, wordEngine.search("cifratura blocchi").single().documentId)

            // 4. Deleting a document removes it from the index
            store.deleteDocument(p2pDoc.id)
            indexer.indexNow()
            assertTrue(wordEngine.search("Kademlia").isEmpty())
        }
    }
}
