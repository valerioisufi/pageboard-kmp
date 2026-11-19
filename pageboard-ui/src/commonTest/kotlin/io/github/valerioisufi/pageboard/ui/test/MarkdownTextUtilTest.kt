package io.github.valerioisufi.pageboard.ui.test

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import io.github.valerioisufi.pageboard.ui.editor.MarkdownTextUtil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarkdownTextUtilTest {

    @Test
    fun liveStringKeepsTheOffsets() {
        val text = "This is **bold** and *italic* and `code`"
        val annotated = MarkdownTextUtil.toLiveAnnotatedString(text, TextRange(0), Color.Black)
        assertEquals(text, annotated.text)
        assertTrue(annotated.spanStyles.isNotEmpty())
    }
}
