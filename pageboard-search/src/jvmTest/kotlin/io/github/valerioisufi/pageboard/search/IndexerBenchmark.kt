package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.room.RoomDocumentStore
import io.github.valerioisufi.pageboard.store.room.db.createDrawDatabase
import io.github.valerioisufi.pageboard.store.room.db.getInMemoryDatabaseBuilder
import io.github.valerioisufi.pageboard.store.testing.SyntheticDocuments
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * The cost of the search in the notes (see `docs/plans/performance.md`): indexing a library, then a
 * pass after a stroke written in one of its notes.
 */
class IndexerBenchmark {

    @Test
    fun aPassAfterAStroke() = runBlocking {
        val database = createDrawDatabase(getInMemoryDatabaseBuilder())
        val store = RoomDocumentStore(database, InkStrokeModeller)
        repeat(LIBRARY_SIZE) {
            store.insertDocument(SyntheticDocuments.notebook(pages = 4, strokesPerPage = 150, textsPerPage = 3, title = "Note $it", seed = it))
        }
        val written = SyntheticDocuments.notebook(pages = 30, strokesPerPage = 400, textsPerPage = 3, title = "Written")
        store.insertDocument(written)

        val path = File(checkNotNull(System.getProperty("pageboard.dataDir")), "benchmarks/${newElementId()}/index.db")
        path.parentFile.mkdirs()
        val index = SearchIndex(path.absolutePath)
        val indexer = SearchIndexer(store, index, listOf(DocumentSearchSource(store)), quietMillis = 0L)
        val first = measureTime { indexer.indexNow() }
        report("M2 indexing ${LIBRARY_SIZE + 1} notes", "$first")

        // A stroke on the last page of the notebook of 30 pages
        val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1))
        val open = assertNotNull(documents.open(written.id))
        open.edit { session -> session.submit(AddElements.of(session.document.pages.last(), listOf(stroke()))) }
        documents.close(open)

        val pass = measureTime { indexer.indexNow() }
        report("M2 a pass after a stroke (${LIBRARY_SIZE + 1} notes, one of 30 pages changed)", "$pass")
        val again = measureTime { indexer.indexNow() }
        report("M2 a pass with nothing changed", "$again")

        index.close()
        database.close()
    }

    private fun stroke() = StrokeData(
        id = newElementId(),
        points = List(120) { i -> StrokePoint(20f + i * 0.15f, 280f + (i % 10) * 0.3f, 0.5f, i * 8L) },
        tool = StrokeTool.PRESSURE_PEN,
        size = 0.5f,
        color = 0xFF000000.toInt(),
        inputToolType = InputToolType.STYLUS
    ).toElement(SyntheticDocuments.NoMeshes)

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        const val LIBRARY_SIZE = 100
    }
}
