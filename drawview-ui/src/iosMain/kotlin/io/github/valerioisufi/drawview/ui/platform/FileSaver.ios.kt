@file:OptIn(ExperimentalForeignApi::class)

package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.darwin.NSObject

/** Written in the temporary directory, then exported with the document picker (Files, iCloud...). */
@Composable
actual fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit {
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
