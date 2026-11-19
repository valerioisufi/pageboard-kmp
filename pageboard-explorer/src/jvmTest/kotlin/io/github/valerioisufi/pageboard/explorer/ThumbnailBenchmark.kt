package io.github.valerioisufi.pageboard.explorer

import androidx.room.Room
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.room.RoomDocumentStore
import io.github.valerioisufi.pageboard.store.room.db.DrawDatabase
import io.github.valerioisufi.pageboard.store.room.db.createDrawDatabase
import io.github.valerioisufi.pageboard.store.testing.SyntheticDocuments
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The cost of the thumbnail of a note changed (see `docs/plans/performance.md`, M3): drawn again after a
 * stroke on its last page, which does not change it, and after a stroke on its first page, which
 * does. A note of 50 pages of 600 strokes in the Room store, on a database file of the data
 * directory of the tests.
 */
class ThumbnailBenchmark {

    @Test
    fun theThumbnailOfANoteChanged() = runBlocking {
        val root = File(checkNotNull(System.getProperty("pageboard.dataDir")), "benchmarks/${newElementId()}")
        root.mkdirs()
        val database = createDrawDatabase(Room.databaseBuilder<DrawDatabase>(name = File(root, "notes.db").absolutePath))
        val store = RoomDocumentStore(database, InkStrokeModeller)
        val notebook = SyntheticDocuments.notebook(pages = 50, strokesPerPage = 600, textsPerPage = 2, removedFraction = 0.1f)
        store.insertDocument(notebook)
        val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1))
        val thumbnails = DocumentThumbnails(documents, textAdapter = null, directory = File(root, "thumbnails").path)

        // The first drawing warms the code up
        assertNotNull(thumbnails.of(notebook.id, modifiedAt(store, notebook.id), WIDTH_PX))
        val lastPage = List(RUNS) {
            write(documents, notebook.id) { pages -> pages.last() }
            val modifiedAt = modifiedAt(store, notebook.id)
            measureTime { assertNotNull(thumbnails.of(notebook.id, modifiedAt, WIDTH_PX)) }
        }
        val firstPage = List(RUNS) {
            write(documents, notebook.id) { pages -> pages.first() }
            val modifiedAt = modifiedAt(store, notebook.id)
            measureTime { assertNotNull(thumbnails.of(notebook.id, modifiedAt, WIDTH_PX)) }
        }
        report("M3 the thumbnail of a note changed on its last page (50 pages x 600 strokes)", "${lastPage.median()}")
        report("M3 the thumbnail of a note changed on its first page (600 strokes)", "${firstPage.median()}")
        database.close()
    }

    /** Writes a stroke on the page of the note [documentId] that [page] picks, then closes it. */
    private suspend fun write(documents: OpenDocuments, documentId: String, page: (List<DrawPage>) -> DrawPage) {
        // A date of change after the last one
        delay(5)
        val open = assertNotNull(documents.open(documentId))
        open.edit { session -> session.submit(AddElements.of(page(session.document.pages), listOf(stroke()))) }
        documents.close(open)
    }

    private suspend fun modifiedAt(store: RoomDocumentStore, documentId: String): Long =
        store.documents.first().single { it.id == documentId }.modifiedAt

    private fun stroke() = StrokeData(
        id = newElementId(),
        points = List(120) { i -> StrokePoint(20f + i * 0.15f, 280f + (i % 10) * 0.3f, 0.5f, i * 8L) },
        tool = StrokeTool.PRESSURE_PEN,
        size = 0.5f,
        color = 0xFF000000.toInt(),
        inputToolType = InputToolType.STYLUS
    ).toElement(SyntheticDocuments.NoMeshes)

    private fun List<Duration>.median(): Duration = sorted()[size / 2]

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        const val RUNS = 5
        const val WIDTH_PX = 240
    }
}
