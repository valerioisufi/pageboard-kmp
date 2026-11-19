package io.github.valerioisufi.pageboard.ui.extension

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What an extension suggests to write after the caret of the text box being written
 * (`docs/plans/text-box-hook.md`): asked by the editor when the user stops typing for a moment, its
 * answer shown after the caret until it is accepted (Tab) or dropped.
 */
public fun interface TextSuggester {
    /**
     * What may follow the caret of [writing], in the editor of [context]: `null` for nothing. Called
     * on the main thread; a slow answer runs on another one. An answer whose text changed meanwhile
     * is dropped by the editor.
     */
    public suspend fun suggest(context: EditorContext, writing: TextBeingWritten): TextSuggestion?
}

/**
 * The text box being written when a suggestion is asked.
 *
 * @property source the text, in the notation of the notes (`**bold**`, `$x^2$`, lists…).
 * @property caret where the caret is, an offset of [source].
 * @property pageIndex the page of the box.
 * @property textId the id of the text box, `null` for a text not in the document yet.
 */
public class TextBeingWritten(
    public val source: String,
    public val caret: Int,
    public val pageIndex: Int,
    public val textId: String?
)

/**
 * A suggestion: [text] inserted at the caret once accepted, in the notation of the notes (shown as
 * the page will show it).
 */
public class TextSuggestion(public val text: String)

/** The suggester of the editor shown, with its context: given by `EditorScreen` to its text box. */
internal class TextSuggestions(val suggester: TextSuggester, val context: EditorContext)

/** The suggestions of the extensions to the text box, `null` without a suggester. */
internal val LocalTextSuggestions = staticCompositionLocalOf<TextSuggestions?> { null }
