package io.github.valerioisufi.pageboard.ui.screen

import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.name_error_empty
import io.github.valerioisufi.pageboard.ui.generated.resources.name_error_invalid_chars
import org.jetbrains.compose.resources.StringResource

/**
 * Renames the document of an editor, given by whoever keeps it ([EditorScreen]): the editor knows
 * the document, not the folder it is in, where no two documents have the same name.
 */
public fun interface DocumentRenamer {
    /**
     * Renames the document [name] (already checked by [DocumentNames.syntaxError]); `false` when
     * another document of its folder has that name, and nothing changes.
     */
    public suspend fun rename(name: String): Boolean
}

/** The rules of the names of the documents and of the folders, the same in the library and the editor. */
public object DocumentNames {
    /**
     * The error of [name] as the name of a document or a folder, `null` when it is valid: not
     * blank, without slashes. Whether another item of the folder has it is for the folder to say.
     */
    public fun syntaxError(name: String): StringResource? = when {
        name.isBlank() -> Res.string.name_error_empty
        name.contains('/') || name.contains('\\') -> Res.string.name_error_invalid_chars
        else -> null
    }
}
