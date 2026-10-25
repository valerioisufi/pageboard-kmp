package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.drawview.model.change.CopiedContent

/**
 * The last content copied in the editors given this instance: one per application, shared by
 * the editors of all its documents and windows, so that what is copied in a document is pasted
 * in another one (see `docs/cross-document-paste.md`). It lives in memory, while the application
 * runs.
 */
@Stable
public class EditorClipboard {
    /** The elements or the pages copied last, `null` when nothing was copied. */
    public var content: CopiedContent? by mutableStateOf(null)

    /**
     * The text the clipboard of the system held right after [content] was copied (the text of
     * the texts copied, or what was there already): while the system holds it, a paste takes
     * [content]; another text came from another application, copied after.
     */
    public var systemText: String? by mutableStateOf(null)
}
