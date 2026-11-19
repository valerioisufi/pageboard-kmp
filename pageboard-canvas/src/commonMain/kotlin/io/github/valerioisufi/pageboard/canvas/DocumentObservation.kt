package io.github.valerioisufi.pageboard.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.valerioisufi.pageboard.model.DrawDocument

/**
 * Recomposes the caller with every change of this document ([DrawDocument.revision]): the model
 * is not Compose state, so what shows the document calls it before reading the document.
 *
 * @return the revision of the document shown.
 */
@Composable
public fun DrawDocument.observeChanges(): Long = revision.collectAsState().value

/**
 * What [read] reads of this document, as it changes: a state whose readers are composed again
 * when what [read] gives changes, not at every change of the document as with [observeChanges]
 * (whether the document holds PDF pages, which a stroke does not change).
 *
 * [read] runs at every change of the document while the state is read, on the thread of the
 * composition: it reads little.
 */
@Composable
public fun <T> DrawDocument.observe(read: DrawDocument.() -> T): State<T> {
    val revision = revision.collectAsState()
    val currentRead by rememberUpdatedState(read)
    return remember(this) {
        derivedStateOf {
            revision.value
            currentRead()
        }
    }
}
