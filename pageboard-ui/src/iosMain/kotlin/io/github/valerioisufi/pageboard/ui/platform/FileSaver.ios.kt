@file:OptIn(ExperimentalForeignApi::class)

package io.github.valerioisufi.pageboard.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject

/** Written in the temporary directory, then exported with the document picker (Files, iCloud...). */
@Composable
public actual fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit {
    val target by rememberUpdatedState(onTarget)
    val holder = remember { DelegateHolder() }
    return remember(holder) {
        { name ->
            val path = NSTemporaryDirectory().trimEnd('/') + "/" + name.replace('/', '-')
            target(FileTarget(path) { written ->
                withContext(Dispatchers.Main) {
                    val url = NSURL.fileURLWithPath(written)
                    val delete = {
                        holder.delegate = null
                        NSFileManager.defaultManager.removeItemAtPath(written, null)
                        Unit
                    }
                    val presenter = topViewController()
                    if (presenter == null) {
                        delete()
                    } else {
                        val picker = UIDocumentPickerViewController(forExportingURLs = listOf(url), asCopy = true)
                        val delegate = ExportPickerDelegate(onDone = delete)
                        holder.delegate = delegate
                        picker.delegate = delegate
                        presenter.presentViewController(picker, animated = true, completion = null)
                    }
                }
            })
        }
    }
}

private class ExportPickerDelegate(private val onDone: () -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        onDone()
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDone()
    }
}

/**
 * The share sheet of the system (mail, messages, AirDrop, print, Files): the file is written in
 * the temporary directory, deleted once the sheet is closed.
 */
@Composable
public actual fun rememberFileSharer(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): ((suggestedName: String) -> Unit)? {
    val target by rememberUpdatedState(onTarget)
    return remember {
        { name ->
            val path = NSTemporaryDirectory().trimEnd('/') + "/" + name.replace('/', '-')
            target(FileTarget(path) { written ->
                withContext(Dispatchers.Main) {
                    val delete = {
                        NSFileManager.defaultManager.removeItemAtPath(written, null)
                        Unit
                    }
                    val presenter = topViewController()
                    if (presenter == null) {
                        delete()
                    } else {
                        val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(written)), applicationActivities = null)
                        sheet.completionWithItemsHandler = { _, _, _, _ -> delete() }
                        // On the iPad the sheet is a popover: from the bottom center of the screen
                        sheet.popoverPresentationController?.let { popover ->
                            val view = presenter.view
                            popover.sourceView = view
                            view.bounds.useContents { popover.sourceRect = CGRectMake(size.width / 2, size.height - 80.0, 1.0, 1.0) }
                        }
                        presenter.presentViewController(sheet, animated = true, completion = null)
                    }
                }
            })
        }
    }
}

public actual fun fileReveal(): FileReveal? = null
