package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.model.geometry.Rect
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class SearchIndexTest {
    @Test
    fun searchesWordsWithDiacriticsAndPrefixOnLastWord() {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            index.replaceChunks(
                documentId = "doc-1",
                sourceId = "document",
                version = "v1",
                chunks = listOf(
                    SearchChunk(
                        sourceId = "document",
                        kind = SearchChunkKind.TEXT,
                        anchor = SearchAnchor.Page("doc-1", "page-1", Rect(10f, 20f, 100f, 50f), pageNumber = 2),
                        title = "Sistemi distribuiti",
                        text = "Perché l'algoritmo di Chord costruisce una finger table logaritmica"
                    ),
                    SearchChunk(
                        sourceId = "document",
                        kind = SearchChunkKind.TEXT,
                        anchor = SearchAnchor.Page("doc-1", "page-2", null, pageNumber = 3),
                        title = "Sistemi distribuiti",
                        text = "Architettura publish subscribe basata su topic"
                    )
                )
            )

            // Unaccented "perche" matches accented "Perché", and prefix "algorit" matches "algoritmo"
            val hits = index.searchWords("perche algorit")
            assertEquals(1, hits.size)
            val hit = hits.single().indexed.chunk
            assertEquals("doc-1", hit.anchor.documentId)
            val pageAnchor = hit.anchor as SearchAnchor.Page
            assertEquals("page-1", pageAnchor.pageId)
            assertEquals(2, pageAnchor.pageNumber)
            assertEquals(Rect(10f, 20f, 100f, 50f), pageAnchor.bounds)
        }
    }

    @Test
    fun keepsVectorsOfUnchangedChunksWhenDocumentIsUpdated() {
        SearchIndex(SearchIndex.MEMORY_PATH, dimensions = 4).use { index ->
            val chunkA = SearchChunk(
                sourceId = "document",
                kind = SearchChunkKind.TEXT,
                anchor = SearchAnchor.Page("doc-1", "p1"),
                title = "Note",
                text = "Primo paragrafo invariato"
            )
            val chunkB = SearchChunk(
                sourceId = "document",
                kind = SearchChunkKind.TEXT,
                anchor = SearchAnchor.Page("doc-1", "p2"),
                title = "Note",
                text = "Secondo paragrafo originale"
            )
            index.replaceChunks("doc-1", "document", "v1", listOf(chunkA, chunkB))

            val unembeddedInitial = index.unembeddedChunks()
            assertEquals(2, unembeddedInitial.size)
            index.putVectors(
                unembeddedInitial.associate {
                    it.hash to QuantizedVector.fromFloatArray(floatArrayOf(1f, 0f, 0f, 0f), dimensions = 4)
                }
            )
            assertEquals(0, index.unembeddedCount())

            // Update chunkB while keeping chunkA identical
            val chunkBModified = chunkB.copy(text = "Secondo paragrafo modificato")
            index.replaceChunks("doc-1", "document", "v2", listOf(chunkA, chunkBModified))

            val unembeddedAfterEdit = index.unembeddedChunks()
            assertEquals(1, unembeddedAfterEdit.size)
            assertEquals("Secondo paragrafo modificato", unembeddedAfterEdit.single().text)
        }
    }

    @Test
    fun quantizesAndSearchesVectorsInMemory() {
        SearchIndex(SearchIndex.MEMORY_PATH, dimensions = 4).use { index ->
            val chunk1 = SearchChunk(
                sourceId = "document",
                kind = SearchChunkKind.TEXT,
                anchor = SearchAnchor.Page("doc-1", "p1"),
                title = "Reti",
                text = "Routing su alberi di copertura"
            )
            val chunk2 = SearchChunk(
                sourceId = "document",
                kind = SearchChunkKind.TEXT,
                anchor = SearchAnchor.Page("doc-2", "p1"),
                title = "Crittografia",
                text = "Cifrario a blocchi in modalità contatore"
            )
            index.replaceChunks("doc-1", "document", "v1", listOf(chunk1))
            index.replaceChunks("doc-2", "document", "v1", listOf(chunk2))

            val hash1 = EmbeddingPrompts.hash(chunk1.title, chunk1.text)
            val hash2 = EmbeddingPrompts.hash(chunk2.title, chunk2.text)
            index.putVectors(
                mapOf(
                    hash1 to QuantizedVector.fromFloatArray(floatArrayOf(0.9f, 0.1f, 0f, 0f), dimensions = 4),
                    hash2 to QuantizedVector.fromFloatArray(floatArrayOf(0.0f, 0.1f, 0.9f, 0f), dimensions = 4)
                )
            )

            val results = index.searchVectors(floatArrayOf(0.0f, 0.05f, 0.95f, 0f), limit = 5)
            assertEquals(2, results.size)
            assertEquals("doc-2", results[0].indexed.chunk.anchor.documentId)
            assertEquals("doc-1", results[1].indexed.chunk.anchor.documentId)
            assertTrue(abs(results[0].score - 1.0) < 0.05)
        }
    }

    @Test
    fun removesSourceAndDocumentCleanly() {
        SearchIndex(SearchIndex.MEMORY_PATH).use { index ->
            index.replaceChunks(
                documentId = "doc-1",
                sourceId = "document",
                version = "v1",
                chunks = listOf(
                    SearchChunk("document", SearchChunkKind.TITLE, SearchAnchor.Document("doc-1"), "Algebra", "Algebra")
                )
            )
            index.replaceChunks(
                documentId = "doc-1",
                sourceId = "lecture",
                version = "v1",
                chunks = listOf(
                    SearchChunk("lecture", SearchChunkKind.LECTURE_TRANSCRIPT, SearchAnchor.Lecture("doc-1", "lec-1", 60_000L), "Algebra", "Spiegazione degli autovalori")
                )
            )

            assertEquals(1, index.searchWords("autovalori").size)
            index.removeSource("doc-1", "lecture")
            assertTrue(index.searchWords("autovalori").isEmpty())
            assertEquals(1, index.searchWords("algebra").size)

            index.removeDocument("doc-1")
            assertTrue(index.searchWords("algebra").isEmpty())
            assertTrue(index.indexedDocumentIds().isEmpty())
        }
    }

    @Test
    fun buildsNewIndexBesideOldOneWhenMetadataChanges() {
        val tempDir = Path("/tmp/pageboard-search-test-${kotlin.random.Random.nextLong().toULong().toString(16)}")
        SystemFileSystem.createDirectories(tempDir)
        val dbPath = "$tempDir/index.db"
        try {
            SearchIndex(path = dbPath, formatVersion = 1, dimensions = 512).use { oldIndex ->
                oldIndex.replaceChunks(
                    documentId = "doc-old",
                    sourceId = "document",
                    version = "v1",
                    chunks = listOf(
                        SearchChunk("document", SearchChunkKind.TEXT, SearchAnchor.Document("doc-old"), "Vecchio", "Contenuto del vecchio indice")
                    )
                )
            }

            // Reopen with a new formatVersion: old index stays readable until completeRebuild()
            SearchIndex(path = dbPath, formatVersion = 2, dimensions = 512).use { upgraded ->
                assertTrue(upgraded.isRebuilding)
                assertEquals("1", upgraded.metadata()["format"])
                assertEquals(1, upgraded.searchWords("vecchio").size)

                // Populate the new index being built beside the old one
                upgraded.replaceChunks(
                    documentId = "doc-new",
                    sourceId = "document",
                    version = "v1",
                    chunks = listOf(
                        SearchChunk("document", SearchChunkKind.TEXT, SearchAnchor.Document("doc-new"), "Nuovo", "Contenuto del nuovo indice ricostruito")
                    )
                )
                // Before completeRebuild(), queries still read the old index
                assertEquals(1, upgraded.searchWords("vecchio").size)
                assertTrue(upgraded.searchWords("ricostruito").isEmpty())

                upgraded.completeRebuild()
                assertFalse(upgraded.isRebuilding)
                assertEquals("2", upgraded.metadata()["format"])
                assertTrue(upgraded.searchWords("vecchio").isEmpty())
                assertEquals(1, upgraded.searchWords("ricostruito").size)
            }
        } finally {
            runCatching { SystemFileSystem.delete(Path(dbPath), mustExist = false) }
            runCatching { SystemFileSystem.delete(Path("$dbPath.new"), mustExist = false) }
            runCatching { SystemFileSystem.delete(tempDir, mustExist = false) }
        }
    }
}
