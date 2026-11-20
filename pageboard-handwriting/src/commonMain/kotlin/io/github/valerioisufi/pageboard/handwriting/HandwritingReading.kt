package io.github.valerioisufi.pageboard.handwriting

import kotlin.coroutines.cancellation.CancellationException

/**
 * Reads blocks of handwriting with the readers of the device: the lines of a block ([InkLines]),
 * each given to the reader of the language with the line before as context (or the whole block to
 * a reader that splits it itself), the readings put together, a line under the other.
 *
 * Its readings are derived data: the callers keep them by the key of the block and the
 * [version] of the reader, and read a block again when either changes.
 */
public class HandwritingReading(
    public val engines: HandwritingEngines,
    private val lines: InkLines = InkLines()
) {
    /** Whether a reader of the device reads [language] now. */
    public suspend fun readsText(language: String): Boolean = engines.textReader(language) != null

    /** Whether a reader of formulas is there. */
    public val readsFormulas: Boolean get() = engines.math != null

    /** The version of the reader of [language], `null` when none reads it. */
    public suspend fun version(language: String): String? = engines.textReader(language)?.version

    /**
     * The text of [ink] in [language], `null` when no reader reads the language or nothing was read.
     *
     * @param context the text written before the block, if known.
     */
    public suspend fun readText(ink: InkSample, language: String, context: String = ""): InkReading? {
        val reader = engines.textReader(language) ?: return null
        val layout = lines.split(ink)
        if (layout.lines.isEmpty()) return null
        if (reader.readsBlocks) {
            val writing = InkSample(layout.lines.flatMap { it.strokes })
            return safely { reader.recognize(writing.retimed(), language, context) }?.takeIf { it.text.isNotBlank() }
        }
        val read = mutableListOf<InkReading>()
        var before = context
        for (line in layout.lines) {
            val reading = safely { reader.recognize(line.retimed(), language, before) } ?: continue
            if (reading.text.isBlank()) continue
            read.add(reading)
            before = reading.text
        }
        return when (read.size) {
            0 -> null
            1 -> read.single()
            else -> InkReading(
                text = read.joinToString("\n") { it.text },
                searchText = read.joinToString("\n") { it.searchText }
            )
        }
    }

    /** The formula of [ink] as LaTeX, `null` without a reader of formulas or when nothing was read. */
    public suspend fun readFormula(ink: InkSample): InkReading? {
        val reader = engines.math ?: return null
        if (ink.isEmpty) return null
        return safely { reader.recognize(ink.retimed()) }?.takeIf { it.text.isNotBlank() }
    }

    // A reader failing reads nothing: the caller falls back to what it has
    private inline fun safely(read: () -> InkReading?): InkReading? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
