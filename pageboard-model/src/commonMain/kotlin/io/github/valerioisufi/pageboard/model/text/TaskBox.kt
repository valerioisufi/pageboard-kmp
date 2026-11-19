package io.github.valerioisufi.pageboard.model.text

/**
 * The box of an item of a checklist, written after the marker of a bulleted item as in GitHub
 * Flavored Markdown: `- [ ] to do`, `- [x] done` (`X` read as `x`). The box is followed by a space,
 * a tab or the end of its line, so that `- [x]y` stays text. Only the bulleted items take a box: a
 * numbered item keeps `[ ]` as text.
 */
public object TaskBox {

    /** The length of a box: `[`, the mark, `]`. */
    public const val LENGTH: Int = 3

    /** The box not checked, as written. */
    public const val UNCHECKED: String = "[ ]"

    /** The box checked, as written. */
    public const val CHECKED: String = "[x]"

    /**
     * Whether a box starts at [offset] of [text], its line ending at [lineEnd]: `true` checked,
     * `false` not checked, `null` for no box.
     */
    public fun at(text: CharSequence, offset: Int, lineEnd: Int = text.length): Boolean? {
        if (offset + LENGTH > lineEnd || text[offset] != '[' || text[offset + 2] != ']') return null
        val after = offset + LENGTH
        if (after < lineEnd && text[after] != ' ' && text[after] != '\t') return null
        return when (text[offset + 1]) {
            ' ' -> false
            'x', 'X' -> true
            else -> null
        }
    }

    /** The box written checked or not. */
    public fun write(checked: Boolean): String = if (checked) CHECKED else UNCHECKED
}
