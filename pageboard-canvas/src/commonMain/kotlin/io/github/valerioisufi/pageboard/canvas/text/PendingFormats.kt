package io.github.valerioisufi.pageboard.canvas.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.math.max

/**
 * The formats chosen at a caret before typing (see `docs/plans/text-fidelity-more.md`): nothing is
 * written for them, the characters typed next at that caret are written in them. Moving the caret,
 * or any other change of the text, drops them; a text composed by an input method goes in them once
 * committed.
 */
internal class PendingFormats {

    /** The text and the caret the formats were chosen at, `null` for none. */
    private var base: TextFieldValue? = null

    /** The formats waiting, in the order they were chosen: state, so that the buttons show them. */
    private var formats: Set<RichTextEditing.Format> by mutableStateOf(emptySet())

    /** Whether [format] waits at the caret of [value]. */
    fun has(value: TextFieldValue, format: RichTextEditing.Format): Boolean {
        val at = base ?: return false
        return format in formats && value.text == at.text && value.selection == at.selection
    }

    /** [format] waiting at the caret of [value], or not any more when it was. */
    fun toggle(value: TextFieldValue, format: RichTextEditing.Format) {
        val kept = if (base?.text == value.text && base?.selection == value.selection) formats else emptySet()
        formats = if (format in kept) kept - format else kept + format
        base = if (formats.isEmpty()) null else TextFieldValue(value.text, value.selection)
    }

    /** Nothing waiting any more. */
    fun clear() {
        base = null
        if (formats.isNotEmpty()) formats = emptySet()
    }

    /**
     * [newValue] with the characters typed at the caret of the formats written in them, the caret
     * after the characters, in the formats; `null` when it types nothing there (the formats are
     * dropped unless it only composes there, or changes nothing).
     */
    fun typed(newValue: TextFieldValue): TextFieldValue? {
        val at = base ?: return null
        val caret = at.selection.start
        val text = at.text
        if (newValue.text == text) {
            if (newValue.selection != at.selection) clear()
            return null
        }
        val inserted = newValue.text.length - text.length
        val insertion = inserted > 0 && newValue.text.startsWith(text.substring(0, caret)) && newValue.text.endsWith(text.substring(caret))
        val typed = if (insertion) newValue.text.substring(caret, caret + inserted) else ""
        if (!insertion || '\n' in typed) {
            clear()
            return null
        }
        // Composing at the caret: the formats wait for the commit
        if (newValue.composition != null) return null
        if (newValue.selection != TextRange(caret + inserted)) {
            clear()
            return null
        }
        var written = TextFieldValue(newValue.text, TextRange(caret, caret + inserted))
        for (format in formats) written = RichTextEditing.toggle(written, format)
        clear()
        return written.copy(selection = TextRange(max(written.selection.start, written.selection.end)))
    }
}
