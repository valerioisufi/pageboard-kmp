package io.github.valerioisufi.pageboard.handwriting

/**
 * A reading of handwriting.
 *
 * @param text the most likely text.
 * @param alternatives the other readings of the whole ink, the likelier first: empty when the
 *   reader gives none.
 * @param searchText every candidate of the reader, for the search (a word read wrong is still
 *   found by the right one among them).
 */
public class InkReading(
    public val text: String,
    public val alternatives: List<String> = emptyList(),
    public val searchText: String = text
) {
    override fun toString(): String = "InkReading($text)"
}

/** Reads handwritten text from the samples of its strokes (ML Kit, PencilKit, the ink of Windows). */
public interface TextInkRecognizer {
    /** The reader and its version: what it read is read again when it changes. */
    public val version: String

    /** Whether it splits an ink into lines itself; otherwise it is given one line at a time. */
    public val readsBlocks: Boolean get() = false

    /** The languages it reads now, as BCP 47 tags (with their models on the device). */
    public suspend fun languages(): Set<String>

    /**
     * The reading of [ink] in [language], `null` when it cannot read it (no model for the language,
     * an error of the system).
     *
     * @param context the text written before [ink] (the line before), which helps some readers.
     */
    public suspend fun recognize(ink: InkSample, language: String, context: String = ""): InkReading?
}

/** Reads a handwritten formula from the samples of its strokes, as LaTeX. */
public interface MathInkRecognizer {
    /** The reader and its version. */
    public val version: String

    /** The formula of [ink] as LaTeX (without `$`), `null` when it cannot read it. */
    public suspend fun recognize(ink: InkSample): InkReading?
}

/**
 * The model of a language a reader downloads.
 *
 * @param sizeBytes its size, when known.
 */
public class InkLanguageModel(
    public val language: String,
    public val isDownloaded: Boolean,
    public val sizeBytes: Long? = null
)

/** The models of the languages of a reader that downloads them (ML Kit): on the device only when the user asks. */
public interface InkLanguageModels {
    /** The models of [languages] the reader offers, downloaded or not. */
    public suspend fun models(languages: Collection<String>): List<InkLanguageModel>

    /** Downloads the model of [language] (on Wi-Fi only when [wifiOnly]); throws when it fails. */
    public suspend fun download(language: String, wifiOnly: Boolean = true)

    /** Deletes the model of [language] from the device. */
    public suspend fun delete(language: String)
}

/**
 * The readers of handwriting of a device, given by the application.
 *
 * @param text the readers of text, the preferred first.
 * @param math the reader of formulas, if any.
 * @param languageModels the models of the languages to download, for a reader that needs them.
 */
public class HandwritingEngines(
    public val text: List<TextInkRecognizer> = emptyList(),
    public val math: MathInkRecognizer? = null,
    public val languageModels: InkLanguageModels? = null
) {
    /** The first reader of [language] (by its primary subtag: `it` reads `it-IT`), `null` when none reads it now. */
    public suspend fun textReader(language: String): TextInkRecognizer? =
        text.firstOrNull { reader -> reader.languages().any { sameLanguage(it, language) } }

    /** The languages read now by any reader. */
    public suspend fun languages(): Set<String> = text.flatMapTo(mutableSetOf()) { it.languages() }

    public companion object {
        /** No reader: nothing is read. */
        public val None: HandwritingEngines = HandwritingEngines()

        /** Whether two BCP 47 tags name the same language (`it`, `it-IT`, `it_CH`). */
        public fun sameLanguage(a: String, b: String): Boolean = primary(a) == primary(b)

        private fun primary(tag: String): String = tag.substringBefore('-').substringBefore('_').lowercase()
    }
}
