@file:OptIn(ExperimentalForeignApi::class)

package io.github.valerioisufi.pageboard.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.coroutines.resume
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.popoverPresentationController
import platform.darwin.NSObject

/** Serializes presenting modal export pickers and share sheets on iOS. */
private val iosPresentationLock = Mutex()

/** Keeps active weak UIKit picker delegates alive on the main thread until dismissed. */
private val activeDelegates = mutableSetOf<Any>()

/** Written in a unique temporary directory, then exported with the document picker (Files, iCloud...). */
@Composable
public actual fun rememberFileSaver(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): (suggestedName: String) -> Unit {
    val target by rememberUpdatedState(onTarget)
    return remember {
        { name ->
            val directory = NSTemporaryDirectory().trimEnd('/') + "/pageboard-export-" + NSUUID().UUIDString
            NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
            val path = "$directory/${name.replace('/', '-')}"
            target(FileTarget(path) { written ->
                iosPresentationLock.withLock {
                    withContext(Dispatchers.Main) {
                        var activeDelegate: ExportPickerDelegate? = null
                        var activePicker: UIDocumentPickerViewController? = null
                        try {
                            val presenter = topViewController() ?: return@withContext
                            suspendCancellableCoroutine { continuation ->
                                var resumed = false
                                fun finish() {
                                    if (!resumed) {
                                        resumed = true
                                        continuation.resume(Unit)
                                    }
                                }
                                val delegate = ExportPickerDelegate(onDone = ::finish)
                                activeDelegate = delegate
                                activeDelegates.add(delegate)
                                val picker = UIDocumentPickerViewController(forExportingURLs = listOf(NSURL.fileURLWithPath(written)), asCopy = true)
                                activePicker = picker
                                picker.delegate = delegate
                                presenter.presentViewController(picker, animated = true, completion = null)
                            }
                        } finally {
                            activePicker?.delegate = null
                            activeDelegate?.let { activeDelegates.remove(it) }
                            NSFileManager.defaultManager.removeItemAtPath(directory, null)
                        }
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
 * The share sheet of the system (mail, messages, AirDrop, print, Files): the file is written in a
 * unique temporary directory, deleted once the sheet is closed.
 */
@Composable
public actual fun rememberFileSharer(title: String, mimeType: String, onTarget: (FileTarget) -> Unit): ((suggestedName: String) -> Unit)? {
    val target by rememberUpdatedState(onTarget)
    return remember {
        { name ->
            val directory = NSTemporaryDirectory().trimEnd('/') + "/pageboard-share-" + NSUUID().UUIDString
            NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
            val path = "$directory/${name.replace('/', '-')}"
            target(FileTarget(path) { written ->
                iosPresentationLock.withLock {
                    withContext(Dispatchers.Main) {
                        try {
                            val presenter = topViewController() ?: return@withContext
                            suspendCancellableCoroutine { continuation ->
                                var resumed = false
                                fun finish() {
                                    if (!resumed) {
                                        resumed = true
                                        continuation.resume(Unit)
                                    }
                                }
                                val sheet = UIActivityViewController(activityItems = listOf(NSURL.fileURLWithPath(written)), applicationActivities = null)
                                sheet.completionWithItemsHandler = { _, _, _, _ -> finish() }
                                // On the iPad the sheet is a popover: from the bottom center of the screen
                                sheet.popoverPresentationController?.let { popover ->
                                    val view = presenter.view
                                    popover.sourceView = view
                                    view.bounds.useContents { popover.sourceRect = CGRectMake(size.width / 2, size.height - 80.0, 1.0, 1.0) }
                                }
                                presenter.presentViewController(sheet, animated = true, completion = null)
                            }
                        } finally {
                            NSFileManager.defaultManager.removeItemAtPath(directory, null)
                        }
                    }
                }
            })
        }
    }
}

public actual fun fileReveal(): FileReveal? = null
