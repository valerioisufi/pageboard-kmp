package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.ui.text.font.FontFamily
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The whole source of a text shown on demand, as it is written. */
class SourceViewTest {

    @Test
    fun theSourceIsShownAsItIsWrittenInTheMonospace() {
        val text = "# Title\n**bold** and \$x^2\$\n```kotlin\nval a = 1\n```"
        val shown = RichTextPresenter.source<Unit>(text, FontFamily.Monospace)
        assertEquals(text, shown.text.text)
        assertTrue(shown.text.spanStyles.single().item.fontFamily == FontFamily.Monospace)
        for (offset in 0..text.length) {
            assertEquals(offset, shown.mapping.originalToTransformed(offset))
            assertEquals(offset, shown.mapping.transformedToOriginal(offset))
        }
        assertTrue(shown.formulas.isEmpty() && shown.codeBlocks.isEmpty(), "nothing drawn over it")
        assertNull(shown.hiddenAtCaret, "nothing hidden: no strip")
    }
}
