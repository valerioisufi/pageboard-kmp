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

    /**
     * Whether the selection of the text being written is in a block of code: its text is code,
     * the formats of the characters, the headings and the lists do not apply there.
     */
    public val inCodeBlock: Boolean get() = false

    /** Whether the block of code of the selection shows the numbers of its lines. */
    public val codeLineNumbers: Boolean get() = false

    /** Adds or removes [format] around the selection of the text being written. */
    public fun toggle(format: RichTextEditing.Format) {}

    /** Makes the line of the caret a heading, or plain text again. */
    public fun toggleHeading() {}

    /** Puts the lines of the selection in a block of code, or takes the block of the selection out of its fences. */
    public fun toggleCodeBlock() {}

    /** Asks for the numbers of the lines of the block of code of the selection, or not any more. */
    public fun toggleLineNumbers() {}

    /** Makes the lines of the selection bullet list items, or plain text again. */
    public fun toggleBulletList() {}

    /** Makes the lines of the selection numbered list items, or plain text again. */
    public fun toggleNumberedList() {}

    /** Adds 2 spaces of indentation to the lines of the selection. */
    public fun indent() {}

    /** Removes up to 2 spaces of indentation from the lines of the selection. */
    public fun outdent() {}
}
