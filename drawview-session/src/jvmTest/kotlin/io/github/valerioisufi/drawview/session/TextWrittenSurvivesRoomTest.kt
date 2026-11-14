package io.github.valerioisufi.drawview.session

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.room.Room
import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.newElementId
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.room.RoomDocumentStore
import io.github.valerioisufi.drawview.store.room.db.DrawDatabase
import io.github.valerioisufi.drawview.store.room.db.createDrawDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * The text of a box still open in the database of the applications when the process is killed: read
 * by another connection to the file, the first one never closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextWrittenSurvivesRoomTest {
    private val main = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(main)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** The database of the file [file], as the applications open theirs. */
    private fun database(file: File): DrawDatabase = createDrawDatabase(Room.databaseBuilder<DrawDatabase>(name = file.absolutePath))

    @Test
    fun aTextBeingWrittenIsInTheDatabaseWhenTheProcessIsKilled() = runTest(main) {
        val file = File(checkNotNull(System.getProperty("drawview.dataDir")), "survives/${newElementId()}.db")
        file.parentFile.mkdirs()
        // What dies with the process: its connection, the writes of the documents open
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = RoomDocumentStore(database(file), InkStrokeModeller)
        val documentId = store.createDocument("Notes", 210f, 297f, PageBackground.Solid(), null).id
        val documents = OpenDocuments(store, main, process)
        val viewModel = DocumentEditorViewModel(documents)
        viewModel.openDocument(documentId)
        advanceUntilIdle()
        // Loaded on the threads of the database
        val controller = assertNotNull(awaitReal { viewModel.session?.controller })
        controller.viewport.setSize(800f, 1200f)

        val editor = controller.textEditorManager
        editor.startDrawnText(0, Rect(20f, 30f, 120f, 40f))
        val text = "a line typed in a box still open"
        editor.updateValue(TextFieldValue(text, TextRange(text.length)))
        // A pause in the writing
        advanceTimeBy(2_100)
        runCurrent()
        assertTrue(editor.isEditing, "the box is still open")
        // The writes already queued reach the database (nothing more is asked of them)
        assertNotNull(documents.open(documentId)).flush()
        process.cancel()

        // Another process, on the same file
        val again = RoomDocumentStore(database(file), InkStrokeModeller)
        val document = assertNotNull(again.loadDocument(documentId))
        assertEquals(listOf(text), document.pages.single().texts.map { it.text })
    }

    /** [value] once it is not `null`, the test's time running and the threads of the database working. */
    private fun <T : Any> kotlinx.coroutines.test.TestScope.awaitReal(value: () -> T?): T? {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            value()?.let { return it }
            Thread.sleep(20)
            advanceUntilIdle()
        }
        return null
    }
}
