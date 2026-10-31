package io.github.valerioisufi.drawview.search

import io.github.valerioisufi.drawview.model.text.RichTextParser
import io.github.valerioisufi.drawview.model.text.RichTextParser.Kind
import io.github.valerioisufi.drawview.model.text.TableShape

/**
 * Removes the marks of Markdown from [text] while keeping its LaTeX formulas (`$…$` and `$$…$$`)
 * and the content of its code blocks intact; a table keeps the words of its cells.
 */
public fun cleanMarkdownForSearch(text: String): String {
    if (text.isBlank()) return ""
    val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
    val spans = RichTextParser.parse(normalized)
    if (spans.isEmpty()) return normalized.trim()
    val hidden = BooleanArray(normalized.length)
    // The pipes between the cells of a table: a space, the words of two cells apart
    val spaced = BooleanArray(normalized.length)
    for (span in spans) {
        when (span.kind) {
            Kind.FORMULA -> Unit
            Kind.TABLE -> {
                val shape = TableShape.of(normalized, span)
                val inCells = BooleanArray(span.end - span.start)
                for (row in shape.rows) for (cell in row) for (i in cell) inCells[i - span.start] = true
                for ((index, line) in shape.lines.withIndex()) {
                    for (i in line) {
                        if (index == 1) hidden[i] = true else if (!inCells[i - span.start]) spaced[i] = true
                    }
                }
            }
            Kind.LIST_ITEM -> for (i in span.start until span.contentStart) {
                if (normalized[i] != '\n') hidden[i] = true
            }
            else -> for (range in span.delimiters) {
                for (i in range) {
                    if (normalized[i] != '\n') hidden[i] = true
                }
            }
        }
    }
    val out = StringBuilder(normalized.length)
    for (i in normalized.indices) {
        if (spaced[i]) out.append(' ') else if (!hidden[i]) out.append(normalized[i])
    }
    return out.toString()
        .lines()
        .joinToString("\n") { it.trimEnd() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
}

/**
 * Cuts [text] into pieces of at most about [maxChars] characters (~400 tokens at 1,600 characters),
 * splitting at Markdown headings and paragraph breaks first, then at line breaks or spaces.
 */
public fun splitTextChunks(text: String, maxChars: Int = DEFAULT_CHUNK_MAX_CHARS): List<String> {
    val cleaned = text.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (cleaned.isEmpty()) return emptyList()
    if (cleaned.length <= maxChars) return listOf(cleaned)

    // Split into sections at Markdown headings or blank lines
    val paragraphs = cleaned.split(Regex("(?=^#{1,6}\\s)|\\n\\s*\\n", RegexOption.MULTILINE))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

    val chunks = mutableListOf<String>()
    val current = StringBuilder()

    fun flush() {
        if (current.isNotEmpty()) {
            chunks.add(current.toString().trim())
            current.clear()
        }
    }

    for (paragraph in paragraphs) {
        if (paragraph.length > maxChars) {
            flush()
            var start = 0
            while (start < paragraph.length) {
                val end = (start + maxChars).coerceAtMost(paragraph.length)
                val cut = if (end == paragraph.length) {
                    end
                } else {
                    val newline = paragraph.lastIndexOf('\n', end - 1)
                    val space = paragraph.lastIndexOf(' ', end - 1)
                    when {
                        newline > start + maxChars / 2 -> newline + 1
                        space > start + maxChars / 2 -> space + 1
                        else -> end
                    }
                }
                val piece = paragraph.substring(start, cut).trim()
                if (piece.isNotEmpty()) chunks.add(piece)
                start = cut
            }
        } else if (current.isEmpty()) {
            current.append(paragraph)
        } else if (current.length + 1 + paragraph.length <= maxChars) {
            current.append('\n').append(paragraph)
        } else {
            flush()
            current.append(paragraph)
        }
    }
    flush()
    return chunks
}

/**
 * A short single-line excerpt of [text] (at most [maxChars] characters) around the first word of
 * [query] that appears in it, or from its start when none does.
 */
public fun snippetOf(text: String, query: String, maxChars: Int = DEFAULT_SNIPPET_CHARS): String {
    val singleLine = text.replace(Regex("\\s+"), " ").trim()
    if (singleLine.length <= maxChars) return singleLine
    val foldedText = foldDiacritics(singleLine)
    val queryWords = searchWordsOf(query)
    val matchIndex = queryWords.firstNotNullOfOrNull { word ->
        foldedText.indexOf(word).takeIf { it >= 0 }
    } ?: 0
    val start = (matchIndex - maxChars / 3).coerceAtLeast(0).let { raw ->
        if (raw == 0) 0 else singleLine.indexOf(' ', raw).takeIf { it in raw..(raw + 20) }?.plus(1) ?: raw
    }
    val end = (start + maxChars).coerceAtMost(singleLine.length).let { raw ->
        if (raw == singleLine.length) raw else singleLine.lastIndexOf(' ', raw).takeIf { it > start + maxChars / 2 } ?: raw
    }
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < singleLine.length) "…" else ""
    return prefix + singleLine.substring(start, end).trim() + suffix
}

/**
 * The words of [text], lower-case and without Latin diacritics, in their order.
 */
public fun searchWordsOf(text: String): List<String> {
    val folded = foldDiacritics(text)
    val words = mutableListOf<String>()
    val current = StringBuilder()
    for (ch in folded) {
        if (ch.isLetterOrDigit()) {
            current.append(ch)
        } else if (current.isNotEmpty()) {
            words.add(current.toString())
            current.clear()
        }
    }
    if (current.isNotEmpty()) words.add(current.toString())
    return words
}

/** Lower-case [text] with common accented Latin letters mapped to their base ASCII letter (same length). */
internal fun foldDiacritics(text: String): String {
    val out = StringBuilder(text.length)
    for (ch in text) {
        val lower = ch.lowercaseChar()
        out.append(
            when (lower) {
                'à', 'á', 'â', 'ã', 'ä', 'å', 'ā', 'ă', 'ą' -> 'a'
                'ç', 'ć', 'č' -> 'c'
                'ď', 'đ' -> 'd'
                'è', 'é', 'ê', 'ë', 'ē', 'ĕ', 'ė', 'ę', 'ě' -> 'e'
                'ì', 'í', 'î', 'ï', 'ī', 'ĭ', 'į' -> 'i'
                'ł' -> 'l'
                'ñ', 'ń', 'ň' -> 'n'
                'ò', 'ó', 'ô', 'õ', 'ö', 'ø', 'ō', 'ŏ', 'ő' -> 'o'
                'ř', 'ŕ' -> 'r'
                'ś', 'š', 'ş' -> 's'
                'ť', 'ţ' -> 't'
                'ù', 'ú', 'û', 'ü', 'ū', 'ŭ', 'ů', 'ű', 'ų' -> 'u'
                'ý', 'ÿ' -> 'y'
                'ź', 'ż', 'ž' -> 'z'
                else -> lower
            }
        )
    }
    return out.toString()
}

internal const val DEFAULT_CHUNK_MAX_CHARS: Int = 1600
internal const val DEFAULT_SNIPPET_CHARS: Int = 140
