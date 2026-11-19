package io.github.valerioisufi.pageboard

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

fun MainViewController(): UIViewController = MainViewController(onOpenDocumentInNewWindow = null)

fun MainViewController(
    onOpenDocumentInNewWindow: ((String) -> Unit)?
): UIViewController = ComposeUIViewController {
    App(onOpenDocumentInNewWindow = onOpenDocumentInNewWindow)
}

fun SampleDocumentViewController(
    documentId: String,
    onClose: () -> Unit = {}
): UIViewController = ComposeUIViewController {
    SampleDocumentWindow(
        documentId = documentId,
        onClose = onClose
    )
}