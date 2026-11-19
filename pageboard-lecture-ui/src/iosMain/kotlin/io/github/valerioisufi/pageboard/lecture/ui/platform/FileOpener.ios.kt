@file:OptIn(ExperimentalForeignApi::class)

package io.github.valerioisufi.pageboard.lecture.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import io.github.valerioisufi.pageboard.lecture.platform.ChosenFile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTTypeAudio
import platform.UniformTypeIdentifiers.UTTypeMPEG4Movie
import platform.darwin.NSObject

/** Keeps the delegate of the picker alive: UIKit references delegates weakly. */
private class DelegateHolder {
    var delegate: NSObject? = null
}

/** The document picker: a copy of the file, in the temporary directory of the application. */
@Composable
internal actual fun rememberFileOpener(title: String, extensions: Set<String>, onChosen: (ChosenFile) -> Unit): () -> Unit {
    val chosen by rememberUpdatedState(onChosen)
    val holder = remember { DelegateHolder() }
    return remember(holder) {
        {
            topViewController()?.let { presenter ->
                val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeAudio, UTTypeMPEG4Movie), asCopy = true)
                val delegate = PickerDelegate(
                    onPicked = { url -> url.path?.let { path -> chosen(ChosenFile(path, url.lastPathComponent ?: "audio", modifiedAt(path), temporary = true)) } },
                    onDone = { holder.delegate = null }
                )
                holder.delegate = delegate
                picker.delegate = delegate
                picker.allowsMultipleSelection = false
                presenter.presentViewController(picker, animated = true, completion = null)
            }
        }
    }
}

private class PickerDelegate(private val onPicked: (NSURL) -> Unit, private val onDone: () -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        (didPickDocumentsAtURLs.firstOrNull() as? NSURL)?.let(onPicked)
        onDone()
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        onDone()
    }
}

private fun modifiedAt(path: String): Long? =
    (NSFileManager.defaultManager.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate)
        ?.let { (it.timeIntervalSince1970 * 1000).toLong() }

@Suppress("DEPRECATION")
private fun topViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    val window = application.keyWindow ?: application.windows.firstOrNull() as? UIWindow
    var controller = window?.rootViewController
    while (controller?.presentedViewController != null) controller = controller.presentedViewController
    return controller
}
