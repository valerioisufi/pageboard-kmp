package io.github.valerioisufi.pageboard.model.text

/** The code point of [text] at [index] (a surrogate pair read as one). */
public fun codePointAt(text: String, index: Int): Int {
    val high = text[index]
    if (high.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) {
        return 0x10000 + ((high.code - 0xD800) shl 10) + (text[index + 1].code - 0xDC00)
    }
    return high.code
}

/** The code points of [text]. */
public fun codePoints(text: String): List<Int> {
    val points = mutableListOf<Int>()
    var i = 0
    while (i < text.length) {
        val codePoint = codePointAt(text, i)
        points += codePoint
        i += if (codePoint > 0xFFFF) 2 else 1
    }
    return points
}
