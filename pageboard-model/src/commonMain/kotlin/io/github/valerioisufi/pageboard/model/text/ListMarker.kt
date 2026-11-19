package io.github.valerioisufi.pageboard.model.text

import io.github.valerioisufi.pageboard.model.text.ListNumbering.Numbering

/**
 * The style chosen for a numbered list: how its numbers are written, the character after them.
 * A list with no style (`null`) is numbered by its level: `1.`, `a.`, `i.`.
 */
public data class NumberingStyle(val numbering: Numbering, val delimiter: Char = '.') {
    /**
     * Whether a marker read in [other] can be an item of a list in this style: the same delimiter
     * and the same kind of characters. The letters and the Roman numerals are told apart by the first
     * item only: `i.` is the ninth item of a list in letters.
     */
    internal fun admits(other: NumberingStyle): Boolean =
        delimiter == other.delimiter && numbering.family == other.numbering.family

    private val Numbering.family: Int
        get() = when (this) {
            Numbering.DECIMAL -> 0
            Numbering.LOWER_ALPHA, Numbering.LOWER_ROMAN -> 1
            Numbering.UPPER_ALPHA, Numbering.UPPER_ROMAN -> 2
        }

    public companion object {
        /** The delimiters a marker may have. */
        public const val DELIMITERS: String = ".)"
    }
}

/**
 * The marker of a numbered list item, the only place knowing how it is written, after the fancy
 * lists of Pandoc:
 *
 * | Written | Meaning |
 * | :--- | :--- |
 * | `1. text` | no style: numbered by level |
 * | `1.  text` | decimal at any level |
 * | `a.  text`, `A.  text` | letters |
 * | `i.  text`, `I.  text` | Roman numerals |
 * | `1)  text`, `a)  text`, … | the same with a parenthesis |
 *
 * **Two spaces make a marker of any other form than `1. `**: `A. Rossi`, `a. uno` and `1) tre`
 * stay text, as in the notes written before the styles. The editor writes the markers, and hides them.
 *
 * Only the marker of the first item of a list gives its style and start; a marker that another
 * style cannot write starts another list, as in CommonMark. One letter other than
 * `i`, `v` and `x` is a letter; more letters are a Roman numeral when they are one.
 */
public object ListMarker {

    /** A marker read: its [number], its [style], and its [length] with the spaces after it. */
    public data class Read(val number: Int, val style: NumberingStyle?, val length: Int)

    /** The most characters of the number of a marker in letters. */
    private const val MOST_LETTERS = 7

    /** The marker at the start of [text] from [start], or `null`. */
    public fun at(text: CharSequence, start: Int = 0): Read? {
        var end = start
        while (end < text.length && text[end].isLetterOrDigit() && text[end].code < 128) end++
        if (end == start || end >= text.length) return null
        val delimiter = text[end]
        if (delimiter !in NumberingStyle.DELIMITERS) return null
        var after = end + 1
        while (after < text.length && (text[after] == ' ' || text[after] == '\t')) after++
        if (after == end + 1) return null
        val twoSpaces = text.getOrNull(end + 1) == ' ' && text.getOrNull(end + 2) == ' '
        val word = text.substring(start, end)
        val length = after - start
        if (word.all { it.isDigit() }) {
            val number = word.toIntOrNull() ?: 1
            return when {
                twoSpaces -> Read(number, NumberingStyle(Numbering.DECIMAL, delimiter), length)
                delimiter == '.' -> Read(number, null, length)
                else -> null
            }
        }
        if (!twoSpaces || word.length > MOST_LETTERS) return null
        val (number, numbering) = readLetters(word) ?: return null
        return Read(number, NumberingStyle(numbering, delimiter), length)
    }

    /** The marker of [number] in [style] with the spaces after it: the one and only form written. */
    public fun write(style: NumberingStyle?, number: Int): String = when (style) {
        null -> "$number. "
        else -> ListNumbering.format(number, style.numbering) + style.delimiter + "  "
    }

    /** Whether a list in [style] can start from [number]: whether its first marker reads back so. */
    public fun canStart(style: NumberingStyle?, number: Int): Boolean {
        if (number < 1) return false
        val read = at(write(style, number) + "x") ?: return false
        return read.number == number && read.style == style
    }

    private fun readLetters(word: String): Pair<Int, Numbering>? {
        val lower = word.all { it in 'a'..'z' }
        val upper = word.all { it in 'A'..'Z' }
        if (!lower && !upper) return null
        val letters = word.lowercase()
        val single = letters.length == 1
        val roman = romanValue(letters)
        if (roman != null && (!single || letters[0] in SINGLE_ROMAN)) {
            return roman to if (lower) Numbering.LOWER_ROMAN else Numbering.UPPER_ROMAN
        }
        if (letters.length > MOST_ALPHA) return null
        var value = 0
        for (letter in letters) value = value * 26 + (letter - 'a' + 1)
        return value to if (lower) Numbering.LOWER_ALPHA else Numbering.UPPER_ALPHA
    }

    /** The value of a Roman numeral written as [ListNumbering.format] writes it, or `null`. */
    private fun romanValue(letters: String): Int? {
        var value = 0
        var k = 0
        while (k < letters.length) {
            val current = ROMAN_VALUES[letters[k]] ?: return null
            val next = letters.getOrNull(k + 1)?.let { ROMAN_VALUES[it] ?: return null } ?: 0
            value += if (current < next) -current else current
            k++
        }
        return value.takeIf { it > 0 && ListNumbering.format(it, Numbering.LOWER_ROMAN) == letters }
    }

    /** The single letters read as Roman numerals. */
    private const val SINGLE_ROMAN = "ivx"

    /** The most letters of a number in letters: `zzz` is far beyond any list. */
    private const val MOST_ALPHA = 3

    private val ROMAN_VALUES = mapOf('i' to 1, 'v' to 5, 'x' to 10, 'l' to 50, 'c' to 100, 'd' to 500, 'm' to 1000)
}
