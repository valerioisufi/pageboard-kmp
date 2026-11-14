package io.github.valerioisufi.drawview

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.ui.extension.EditorContext
import io.github.valerioisufi.drawview.ui.extension.TextBeingWritten
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** The end of a word suggested by the sample from the words of the document. */
class WordSuggestionsTest {

    @Test
    fun theMostFrequentWordOfTheDocumentEndsTheOneWritten() = runBlocking {
        val document = DrawDocument()
        val controller = DrawViewController(document = document, coroutineScope = CoroutineScope(Dispatchers.Unconfined))
        val texts = listOf("The flux through a surface", "surface integrals", "a surplus").map { DrawText().apply { text = it; x = 10f; y = 10f; width = 80f } }
        controller.session.submit(AddElements.of(document.pages[0], texts))
        val context = EditorContext(controller)
        val suggester = checkNotNull(wordSuggestionsExtension().textSuggestions)

        assertEquals("face", suggester.suggest(context, TextBeingWritten("on the sur", 10, 0, null))?.text)
        assertEquals("ough", suggester.suggest(context, TextBeingWritten("thr", 3, 0, null))?.text)
        assertNull(suggester.suggest(context, TextBeingWritten("a s", 3, 0, null)), "one letter is not enough")
        assertNull(suggester.suggest(context, TextBeingWritten("the zeta", 8, 0, null)))
    }
}
