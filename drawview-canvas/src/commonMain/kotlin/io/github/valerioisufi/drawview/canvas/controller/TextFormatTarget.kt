package io.github.valerioisufi.drawview.canvas.controller

import io.github.valerioisufi.drawview.canvas.text.RichTextEditing
import io.github.valerioisufi.drawview.model.TextAlignment

/**
 * What the formatting of the texts applies to: the text being written (see [TextEditorManager]) or
 * the texts selected (see [SelectionManager.selectedTexts]). The commands and the bars of the
 * formatting work on one, whichever it is.
 */
public interface TextFormatTarget {
    /** The size in points, the ARGB color and the alignment shown (those of the first text). */
    public val fontSize: Float
    public val color: Int
    public val alignment: TextAlignment

    public fun changeFontSize(size: Float)
    public fun changeColor(argb: Int)
    public fun changeAlignment(newAlignment: TextAlignment)

    /** Whether the formats of the characters apply (bold, a formula…): to the text being written. */
    public val formatsCharacters: Boolean get() = false

    /** Adds or removes [format] around the selection of the text being written. */
    public fun toggle(format: RichTextEditing.Format) {}

    /** Makes the line of the caret a heading, or plain text again. */
    public fun toggleHeading() {}
}
