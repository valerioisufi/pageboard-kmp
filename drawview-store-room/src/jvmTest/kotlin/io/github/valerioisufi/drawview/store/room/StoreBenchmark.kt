package io.github.valerioisufi.drawview.store.room

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.StrokeData
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.room.db.DRAW_DATABASE_MIGRATIONS
import io.github.valerioisufi.drawview.store.room.db.DrawDatabase
import io.github.valerioisufi.drawview.store.testing.SyntheticDocuments
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The costs of the Room store (see `docs/plans/performance.md`): the statements a stroke makes run while
 * the library follows the documents, and the opening of a large notebook. On a database file of
 * the data directory of the tests, as an application keeps it.
 */
class StoreBenchmark {
    private lateinit var file: File
    private lateinit var driver: CountingSQLiteDriver
    private lateinit var database: DrawDatabase
    private lateinit var store: RoomDocumentStore

    @BeforeTest
    fun open() {
        file = File(checkNotNull(System.getProperty("drawview.dataDir")), "benchmarks/${newElementId()}.db")
        file.parentFile.mkdirs()
        driver = CountingSQLiteDriver(BundledSQLiteDriver())
        database = Room.databaseBuilder<DrawDatabase>(name = file.absolutePath)
            .addMigrations(*DRAW_DATABASE_MIGRATIONS)
            .setDriver(driver)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        store = RoomDocumentStore(database, InkStrokeModeller)
    }

    @AfterTest
    fun close() {
        database.close()
    }

    /** The statements run for each stroke written in a note, with the collectors of PencilNotes. */
    @Test
    fun writingStrokes() = runBlocking {
        repeat(LIBRARY_SIZE) { store.insertDocument(SyntheticDocuments.notebook(pages = 3, strokesPerPage = 20, title = "Note $it", seed = it)) }
        val notebook = SyntheticDocuments.notebook(pages = 10, strokesPerPage = 300, title = "Written")
        store.insertDocument(notebook)

        val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1))
        val open = assertNotNull(documents.open(notebook.id))
        // What PencilNotes follows while a note is written: the library (documents, trash, folders),
        // the indexer of the search (documents twice, folders), the editor (documents, folders) and
        // the application (documents, trash)
        val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        repeat(5) { collectors.launch { store.documents.collect {} } }
        repeat(3) { collectors.launch { store.folders.collect {} } }
        repeat(2) { collectors.launch { store.trash.collect {} } }
        settle()

        driver.reset()
        val page = open.edit { it.document.pages.last() }
        val elapsed = measureTime {
            repeat(STROKES) {
                open.edit { session -> session.submit(AddElements.of(page, listOf(stroke()))) }
                delay(STROKE_INTERVAL_MS)
            }
            open.flush()
            settle()
        }
        report("M1 statements per stroke", "%.1f (%.1f queries, %.1f others)".format(driver.statements / STROKES.toDouble(), driver.queries / STROKES.toDouble(), driver.others / STROKES.toDouble()))
        report("M1 time in the database per stroke", "%.2f ms of queries, %.2f ms of others (%d strokes in %s)".format(driver.queryMillis / STROKES, driver.otherMillis / STROKES, STROKES, elapsed))

        collectors.cancel()
        documents.close(open)
    }

    /** Opening a notebook of 50 pages of 600 strokes each, a tenth of them erased. */
    @Test
    fun openingANotebook() = runBlocking {
        val notebook = SyntheticDocuments.notebook(pages = 50, strokesPerPage = 600, textsPerPage = 2, removedFraction = 0.1f)
        store.insertDocument(notebook)

        // The first opening warms the code up
        assertNotNull(store.loadDocument(notebook.id))
        val runs = List(3) {
            driver.reset()
            val time = measureTime { assertNotNull(store.loadDocument(notebook.id)) }
            time to driver.queryMillis
        }
        val (time, queries) = runs.sortedBy { it.first }[runs.size / 2]
        report("M5 opening 50 pages x 600 strokes", "$time (of which %.0f ms in the queries)".format(queries))
    }

    /** Waits for the statements to stop for a second (the flows of the library caught up). */
    private suspend fun settle() {
        do {
            val before = driver.statements
            delay(1_000)
        } while (driver.statements != before)
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
        const val LIBRARY_SIZE = 200
        const val STROKES = 40
        const val STROKE_INTERVAL_MS = 250L
    }
}
