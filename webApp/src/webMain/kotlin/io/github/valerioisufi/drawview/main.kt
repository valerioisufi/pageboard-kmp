package io.github.valerioisufi.drawview

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import io.github.valerioisufi.drawview.data.DrawViewStore
import io.github.valerioisufi.drawview.data.KeyValueDocumentStore

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    // Documents are kept in localStorage: the open document is written before the page is left
    onBeforeUnload {
        (DrawViewStore.default as? KeyValueDocumentStore)?.flushNow(allOpenDocuments = true)
    }
    ComposeViewport {
        App()
    }
}

/** Registers [action] for the `beforeunload` event of the window. */
internal expect fun onBeforeUnload(action: () -> Unit)
