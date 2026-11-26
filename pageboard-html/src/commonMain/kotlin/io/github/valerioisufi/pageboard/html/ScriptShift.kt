package io.github.valerioisufi.pageboard.html

/**
 * A subscript or a superscript of what is pasted or imported (`<sub>`, `<sup>`, the runs of Word
 * shifted), kept shifted in the notation, which has no notation of its own for it (see
 * `docs/plans/import-and-clipboard.md`): in the characters of Unicode when each one has its shifted
 * form (`H₂O`, `x²`, `1ˢᵗ`), else as a formula (`$x^{ab}$`), else as it is.
 */
internal object ScriptShift {

    private val SUPERSCRIPTS: Map<Char, Char> = pairs(
        "0123456789+-−=()n" + "abcdefghijklmoprstuvwxyz" + "ABDEGHIJKLMNOPRTUVW",
        "⁰¹²³⁴⁵⁶⁷⁸⁹⁺⁻⁻⁼⁽⁾ⁿ" + "ᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐᵒᵖʳˢᵗᵘᵛʷˣʸᶻ" + "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂ"
    )

    private val SUBSCRIPTS: Map<Char, Char> = pairs(
        "0123456789+-−=()aehijklmnoprstuvx",
        "₀₁₂₃₄₅₆₇₈₉₊₋₋₌₍₎ₐₑₕᵢⱼₖₗₘₙₒₚᵣₛₜᵤᵥₓ"
    )

    /** What LaTeX would read as a command or a group: kept as it is rather than in a formula. */
    private const val SPECIAL = "\\{}$%#&^_~"

    private fun pairs(from: String, to: String): Map<Char, Char> {
        check(from.length == to.length)
        return from.indices.associate { from[it] to to[it] }
    }

    /** [text] shifted up ([superscript]) or down; `null` for nothing to write. */
    fun of(text: String, superscript: Boolean, format: HtmlFormat): InlineRun? {
        val shifted = text.trim()
        if (shifted.isEmpty()) return null
        val map = if (superscript) SUPERSCRIPTS else SUBSCRIPTS
        if (shifted.all { it in map }) return InlineRun.Text(shifted.map { map.getValue(it) }.joinToString(""), format)
        if (shifted.any { it in SPECIAL || it == '\n' }) return InlineRun.Text(shifted, format)
        val content = if (shifted.all { it.isLetter() || it == ' ' }) "\\text{$shifted}" else shifted
        return InlineRun.Formula((if (superscript) "^{" else "_{") + content + "}", display = false)
    }
}
