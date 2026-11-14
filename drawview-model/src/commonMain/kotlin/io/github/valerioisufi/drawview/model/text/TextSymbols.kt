package io.github.valerioisufi.drawview.model.text

/**
 * The characters the text of the notes takes from the symbols font (`notes_symbols.ttf`, see
 * `tools/fonts/make_symbols.py`), which its text font does not have: arrows, mathematical operators,
 * geometric shapes, stars, check marks and the boxes of the checklists. Without it they would come from a font of each system,
 * with other shapes and widths, as an emoji for some.
 */
public object TextSymbols {

    /** The ranges the font covers, from the first to the last character, both included, sorted. */
    public val RANGES: List<CharRange> = listOf(
        '←'..'⋿',
        '■'..'◿',
        '★'..'☆',
        '☐'..'☑',
        '✓'..'✘'
    )

    /** Asks for the emoji of the character before it. */
    private const val EMOJI_SELECTOR = '️'

    /** Whether the character at [index] of [text] is a symbol of the font: not one asked as an emoji. */
    public fun isAt(text: CharSequence, index: Int): Boolean {
        val c = text[index]
        if (c < RANGES.first().first || c > RANGES.last().last) return false
        if (RANGES.none { c in it }) return false
        return index + 1 >= text.length || text[index + 1] != EMOJI_SELECTOR
    }
}
