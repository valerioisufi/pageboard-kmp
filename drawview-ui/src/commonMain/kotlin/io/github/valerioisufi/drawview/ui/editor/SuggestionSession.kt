package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.ui.extension.TextBeingWritten
import io.github.valerioisufi.drawview.ui.extension.TextSuggestion
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The suggestion of a text box (`docs/text-box-hook.md`): asked [pauseMs] after the last change of
 * the text, kept only for the text and the caret it was asked for, dropped by any change.
 *
 * @param ask the suggester of the editor; what it throws is no suggestion.
 */
internal class SuggestionSession(
    private val scope: CoroutineScope,
    private val pauseMs: Long = SUGGESTION_PAUSE_MS,
    private val ask: suspend (TextBeingWritten) -> TextSuggestion?
) {
    /** A suggestion for [source] with the caret at [caret]. */
    private class Shown(val source: String, val caret: Int, val text: String)

    /** The suggestion shown, `null` without one. */
    private var shown: Shown? by mutableStateOf(null)

    /** Whether a suggestion is shown. */
    val isShown: Boolean get() = shown != null

    private var asked: TextBeingWritten? = null
    private var request: Job? = null

    /**
     * The text being written changed (or not): a request for [writing] after the pause, the one
     * before forgotten; `null` when nothing is to be asked (a selection, a table).
     */
    fun changed(writing: TextBeingWritten?) {
        val before = asked
        if (writing != null && before != null && writing.source == before.source && writing.caret == before.caret && writing.textId == before.textId) return
        drop()
        if (writing == null) return
        asked = writing
        request = scope.launch {
            delay(pauseMs)
            val answer = try {
                ask(writing)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // An extension that fails suggests nothing
                null
            }
            val text = answer?.text
            if (!text.isNullOrEmpty() && asked === writing) shown = Shown(writing.source, writing.caret, text)
        }
    }

    /** The suggestion for [source] with the caret at [caret], `null` when the one shown is of another text. */
    fun suggestionFor(source: String, caret: Int): String? = shown?.takeIf { it.source == source && it.caret == caret }?.text

    /** Forgets the suggestion shown and the request going on. */
    fun drop() {
        request?.cancel()
        request = null
        asked = null
        shown = null
    }

    companion object {
        /** The pause after the last change of the text before a suggestion is asked. */
        const val SUGGESTION_PAUSE_MS: Long = 400
    }
}
