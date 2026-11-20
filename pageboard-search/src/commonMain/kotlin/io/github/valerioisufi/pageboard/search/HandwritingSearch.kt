package io.github.valerioisufi.pageboard.search

import io.github.valerioisufi.pageboard.handwriting.HandwritingReading
import io.github.valerioisufi.pageboard.store.DocumentStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop

/**
 * The reading of the handwriting for the search, as the settings choose it (Controller): the
 * readers of the device ([reading]), the language the notes are read in, and whether they are read
 * at all. Its [source] is the [HandwritingSearchSource] of a search; a change here reads the
 * library again (the blocks read already are kept).
 */
public class HandwritingSearch(
    public val reading: HandwritingReading,
    language: String,
    enabled: Boolean = true
) {
    private val languageState = MutableStateFlow(language)
    private val enabledState = MutableStateFlow(enabled)
    private val readersState = MutableStateFlow(0)

    /** The language the handwriting is read in, as a BCP 47 tag. */
    public val language: StateFlow<String> = languageState.asStateFlow()

    /** Whether the handwriting is read. */
    public val isEnabled: StateFlow<Boolean> = enabledState.asStateFlow()

    public fun setEnabled(enabled: Boolean) {
        enabledState.value = enabled
    }

    public fun setLanguage(language: String) {
        languageState.value = language
    }

    /** Says that the readers changed (a model of a language downloaded or deleted). */
    public fun readersChanged() {
        readersState.value++
    }

    /** Emits at every change of the settings or of the readers. */
    public val changes: Flow<Unit> = combine(languageState, enabledState, readersState) { _, _, _ -> }.drop(1)

    private var lastSource: HandwritingSearchSource? = null

    /** The source of a search of [store] keeping its readings in [index]. */
    public fun source(store: DocumentStore, index: SearchIndex): HandwritingSearchSource = HandwritingSearchSource(
        store = store,
        index = index,
        reading = reading,
        language = { languageState.value },
        isEnabled = { enabledState.value },
        changes = changes
    ).also { lastSource = it }

    /** The handwriting of a page, through the last [source] made (see [HandwritingSearchSource.pageText]). */
    public suspend fun pageText(documentId: String, pageId: String): String? = lastSource?.pageText(documentId, pageId)
}
