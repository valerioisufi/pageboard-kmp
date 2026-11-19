import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.RenameDocument

/** A document changed and undone through its session, without any UI. */
fun main() {
    val document = DrawDocument(title = "Notes")
    val session = DocumentSession(document, "consumer")
    val text = DrawText().apply {
        text = "Hello"
        width = 40f
        height = 10f
    }
    val undo = checkNotNull(session.submit(AddElements.of(document.pages[0], listOf(text))))
    session.submit(RenameDocument("Lecture 1"))
    check(document.pages[0].texts.single().text == "Hello")
    session.submit(undo)
    check(document.pages[0].texts.isEmpty())
    println("${document.title}: ${document.pages.size} page, the text added and removed")
}
