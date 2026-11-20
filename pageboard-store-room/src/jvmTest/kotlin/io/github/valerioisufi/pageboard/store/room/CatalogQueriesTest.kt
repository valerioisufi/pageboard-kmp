package io.github.valerioisufi.pageboard.store.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.room.db.PAGEBOARD_DATABASE_MIGRATIONS
import io.github.valerioisufi.pageboard.store.room.db.PageboardDatabase
import io.github.valerioisufi.pageboard.store.testing.SyntheticDocuments
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The queries of the library while a note is written: run once for all their collectors, as many
 * as PencilNotes has (see `docs/plans/performance.md`, phase 1).
 */
class CatalogQueriesTest {

    @Test
    fun aStrokeQueriesTheLibraryOnceWhateverItsCollectors() = runBlocking {
        val file = File(checkNotNull(System.getProperty("pageboard.dataDir")), "catalog/${newElementId()}.db")
        file.parentFile.mkdirs()
        val driver = CountingSQLiteDriver(BundledSQLiteDriver())
        val database = Room.databaseBuilder<PageboardDatabase>(name = file.absolutePath)
            .addMigrations(*PAGEBOARD_DATABASE_MIGRATIONS)
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        val store = RoomDocumentStore(database, InkStrokeModeller)
        repeat(3) { store.insertDocument(SyntheticDocuments.notebook(pages = 2, strokesPerPage = 5, title = "Note $it", seed = it)) }
        val notebook = SyntheticDocuments.notebook(pages = 2, strokesPerPage = 5, title = "Written")
        store.insertDocument(notebook)
        val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1))
        val open = assertNotNull(documents.open(notebook.id))

        val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repeat(5) { collectors.launch { store.documents.collect {} } }
        repeat(3) { collectors.launch { store.folders.collect {} } }
        repeat(2) { collectors.launch { store.trash.collect {} } }
        settle(driver)

        driver.reset()
        val page = open.edit { it.document.pages.last() }
        open.edit { session -> session.submit(AddElements.of(page, listOf(stroke()))) }
        open.flush()
        settle(driver)
        assertEquals(1, driver.runsOf("AS hasPdf"), "the documents with their content, once")
        assertEquals(1, driver.runsOf("SELECT * FROM documents ORDER BY"), "the documents, once")
        assertEquals(0, driver.runsOf("FROM folders"), "the folders, not changed")
        assertEquals(0, driver.runsOf("FROM shared_documents"), "the shared documents, not changed")

        // What is read after a write has the write, while the queries are shared
        assertTrue(store.createFolder("Shared", null))
        assertTrue(store.folders.first().any { it.name == "Shared" }, "the folder just created")
        assertEquals(open.edit { it.document.modifiedAt }, store.documents.first().single { it.id == notebook.id }.modifiedAt)

        collectors.cancel()
        documents.close(open)
        database.close()
    }

    /** Waits for the statements to stop (the flows of the library caught up). */
    private suspend fun settle(driver: CountingSQLiteDriver) {
        do {
            val before = driver.statements
            delay(300)
        } while (driver.statements != before)
    }

    private fun stroke() = StrokeData(
        id = newElementId(),
        points = List(30) { i -> StrokePoint(20f + i * 0.15f, 280f, 0.5f, i * 8L) },
        tool = StrokeTool.PRESSURE_PEN,
        size = 0.5f,
        color = 0xFF000000.toInt(),
        inputToolType = InputToolType.STYLUS
    ).toElement(SyntheticDocuments.NoMeshes)
}
