import io.github.valerioisufi.pageboard.handwriting.pencilkit.PencilKitTextRecognizer
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.pdf.PdfiumEngine
import io.github.valerioisufi.pageboard.store.room.db.createPageboardDatabase

/** The engines of the libraries with native code, so that the framework links all of them. */
fun engines(): List<Any> = listOf(InkStrokeModeller, PdfiumEngine::class, PencilKitTextRecognizer::class, ::createPageboardDatabase)
