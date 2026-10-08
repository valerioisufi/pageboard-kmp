package io.github.valerioisufi.drawview.core.controller

import io.github.valerioisufi.drawview.core.model.TextAlignment
import io.github.valerioisufi.drawview.core.text.RichTextEditing

/**
 * What the formatting of the texts applies to: the text being written (see [TextEditorManager]) or
 * the texts selected (see [SelectionManager.selectedTexts]). The commands and the bars of the
 * formatting work on one, whichever it is.
 */
interface TextFormatTarget {
    /** The size in points, the ARGB color and the alignment shown (those of the first text). */
    val fontSize: Float
    val color: Int
    val alignment: TextAlignment

    fun changeFontSize(size: Float)
    fun changeColor(argb: Int)
    fun changeAlignment(newAlignment: TextAlignment)

    /** Whether the formats of the characters apply (bold, a formula…): to the text being written. */
    val formatsCharacters: Boolean get() = false

    /** Adds or removes [format] around the selection of the text being written. */
    fun toggle(format: RichTextEditing.Format) {}

    /** Makes the line of the caret a heading, or plain text again. */
    fun toggleHeading() {}
}
