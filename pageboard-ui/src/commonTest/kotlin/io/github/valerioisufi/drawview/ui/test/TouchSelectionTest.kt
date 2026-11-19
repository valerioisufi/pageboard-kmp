package io.github.valerioisufi.drawview.ui.test

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.valerioisufi.drawview.ui.editor.TouchSelection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The handles and the toolbar of a selection made by a finger stay while only the composition of the
 * keyboard changes: a keyboard of Android composes the word of the caret again when the caret is put
 * in it (docs/plans/text-fidelity.md).
 */
class TouchSelectionTest {

    private val text = "Seconda riga del testo"

    @Test
    fun theHandlesStayWhenTheKeyboardComposesTheWordSelected() {
        val selection = TouchSelection()
        selection.touch(TextFieldValue(text, TextRange(0, 7)), TouchSelection.Handles.SELECTION, toolbar = true)
        selection.valueChanged(TextFieldValue(text, TextRange(0, 7), composition = TextRange(0, 7)))
        selection.valueChanged(TextFieldValue(text, TextRange(0, 7)))
        assertEquals(TouchSelection.Handles.SELECTION, selection.handles)
        assertTrue(selection.toolbar)
    }

    @Test
    fun theHandlesGoWhenTheSelectionChangesByAKey() {
        val selection = TouchSelection()
        selection.touch(TextFieldValue(text, TextRange(0, 7)), TouchSelection.Handles.SELECTION, toolbar = true)
        selection.valueChanged(TextFieldValue(text, TextRange(0, 8)))
        assertEquals(TouchSelection.Handles.NONE, selection.handles)
        assertTrue(!selection.toolbar)
    }
}
