package io.github.valerioisufi.drawview.canvas.controller

import io.github.valerioisufi.drawview.canvas.text.RichTextEditing
import io.github.valerioisufi.drawview.canvas.text.TableEditing
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.text.NumberingStyle

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

    /** Whether the caret of the text being written is in a table (see [TableEditing]). */
    public val inTable: Boolean get() = false

    /** Inserts a table of [rows] rows (its header included) and [columns] columns at the caret. */
    public fun insertTable(rows: Int, columns: Int) {}

    /** [action] on the table of the caret. */
    public fun applyTableAction(action: TableEditing.Action) {}

    /** Whether the boxes take a fill (see `DrawText.background`): the texts, not the style of a new one. */
    public val takesBackground: Boolean get() = false

    /** The ARGB color of the fill of the box shown (that of the first text), `null` for none. */
    public val background: Int? get() = null

    /** Gives the boxes the fill [argb], or takes it off (`null`). */
    public fun changeBackground(argb: Int?) {}

    /** Whether the formats of the characters apply (bold, a formula…): to the text being written. */
    public val formatsCharacters: Boolean get() = false

    /**
     * Whether the selection of the text being written is in a block of code: its text is code,
     * the formats of the characters, the headings and the lists do not apply there.
     */
    public val inCodeBlock: Boolean get() = false

    /** Whether the block of code of the selection shows the numbers of its lines. */
    public val codeLineNumbers: Boolean get() = false

    /** Whether the selection of the text being written has [format]. */
    public fun isActive(format: RichTextEditing.Format): Boolean = false

    /** Whether the line of the caret (of the start of the selection) is a heading. */
    public val inHeading: Boolean get() = false

    /** Whether the lines of the selection are bullet list items. */
    public val inBulletList: Boolean get() = false

    /** The level of the heading of the line of the caret (1 to 6), 0 for a line that is not one. */
    public val headingLevel: Int get() = 0

    /** Whether the lines of the selection are items of a checklist (`- [ ] `). */
    public val inChecklist: Boolean get() = false

    /** Whether the lines of the selection are numbered list items. */
    public val inNumberedList: Boolean get() = false

    /** Whether the lines of the selection are lines of a block quote. */
    public val inQuote: Boolean get() = false

    /** The style of the numbered list of the selection (`null` for none, or a number by its level). */
    public val numberingStyle: NumberingStyle? get() = null

    /** The number the numbered list of the selection starts from. */
    public val numberingStart: Int get() = 1

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

    /** Makes the line of the caret a heading of [level] (1 to 6), or plain text for 0. */
    public fun setHeading(level: Int) {}

    /** Makes the lines of the selection items of a checklist, or plain text again. */
    public fun toggleChecklist() {}

    /** Checks the items of a checklist of the selection, or unchecks them when they are all checked. */
    public fun toggleChecked() {}

    /** Makes the lines of the selection lines of a block quote, or takes them out of it. */
    public fun toggleQuote() {}

    /** The list of the selection in [style] from [start], made a numbered list if it is not one. */
    public fun setNumbering(style: NumberingStyle?, start: Int) {}

    /** Adds 2 spaces of indentation to the lines of the selection. */
    public fun indent() {}

    /** Removes up to 2 spaces of indentation from the lines of the selection. */
    public fun outdent() {}
}
