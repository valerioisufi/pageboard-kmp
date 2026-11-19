package io.github.valerioisufi.pageboard.ui.window

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Coordinates multi-window lifecycle on desktop platforms.
 * Manages the visibility of the primary library window and a set of open independent document windows.
 */
@Stable
public class DesktopWindowManager {
    public var isLibraryOpen: Boolean by mutableStateOf(true)
        internal set

    private val _openDocumentIds = mutableStateListOf<String>()
    public val openDocumentIds: List<String> get() = _openDocumentIds

    public fun openLibrary() {
        isLibraryOpen = true
    }

    public fun closeLibrary() {
        isLibraryOpen = false
    }

    public fun openDocument(documentId: String) {
        if (!_openDocumentIds.contains(documentId)) {
            _openDocumentIds.add(documentId)
        }
    }

    public fun closeDocument(documentId: String) {
        _openDocumentIds.remove(documentId)
    }

    /**
     * Whether all windows (both library and document windows) are closed.
     */
    public val shouldExit: Boolean
        get() = !isLibraryOpen && _openDocumentIds.isEmpty()
}

/**
 * Remembers a [DesktopWindowManager] for coordinating multiple application windows.
 */
@Composable
public fun rememberDesktopWindowManager(): DesktopWindowManager {
    return remember { DesktopWindowManager() }
}
