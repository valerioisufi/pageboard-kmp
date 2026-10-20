package io.github.valerioisufi.pencilnotes

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = MainViewController(onOpenDocumentInNewWindow = null)

fun MainViewController(
    onOpenDocumentInNewWindow: ((String) -> Unit)?
): UIViewController = ComposeUIViewController {
    PencilNotesApp(onOpenDocumentInNewWindow = onOpenDocumentInNewWindow)
}

fun NoteDocumentViewController(
    documentId: String,
    onClose: () -> Unit = {}
): UIViewController = ComposeUIViewController {
    PencilNotesDocumentWindow(
        documentId = documentId,
        onClose = onClose
    )
}

