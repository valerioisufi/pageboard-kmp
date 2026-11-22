import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.github.valerioisufi.pageboard.explorer.DocumentLibrary
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.platform.PageboardDesktop
import io.github.valerioisufi.pageboard.session.EditorSessions
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.room.RoomDocumentStore
import io.github.valerioisufi.pageboard.store.room.db.createPageboardDatabase
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

// The minimal application of the README, as it is written there
object MyNotes {
    val documents by lazy { OpenDocuments(RoomDocumentStore(createPageboardDatabase(), InkStrokeModeller)) }
    val sessions = EditorSessions()
}

@Composable
fun App() {
    PageboardTheme {
        DocumentLibrary(title = { Text("My Notes") }, documents = MyNotes.documents, sessions = MyNotes.sessions, modifier = Modifier.fillMaxSize())
    }
}

fun main(args: Array<String>) {
    if (args.size == 2 && args[0] == "--store-in") {
        storeIn(File(args[1]))
        return
    }
    PageboardDesktop.init(PageboardDesktop.applicationDataDirectory("My Notes"))
    application { Window(onCloseRequest = ::exitApplication, title = "My Notes") { App() } }
}

/** The check: the store of the application in [directory], a note written in it, the database closed. */
private fun storeIn(directory: File) {
    PageboardDesktop.init(directory)
    val database = createPageboardDatabase()
    val store = RoomDocumentStore(database, InkStrokeModeller)
    val names = runBlocking {
        store.createDocument("A note")
        store.documents.first().map { it.name }
    }
    database.close()
    check("A note" in names) { "The note written is not in the store: $names" }
    println("The store of the minimal application in $directory: $names")
}
