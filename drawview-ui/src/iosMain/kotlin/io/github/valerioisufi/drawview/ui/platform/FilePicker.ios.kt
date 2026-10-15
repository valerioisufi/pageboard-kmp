package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.PhotosUI.PHPickerConfiguration
import platform.PhotosUI.PHPickerFilter
import platform.PhotosUI.PHPickerResult
import platform.PhotosUI.PHPickerViewController
import platform.PhotosUI.PHPickerViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UniformTypeIdentifiers.UTTypePDF
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/** Keeps the picker delegate alive: UIKit references delegates weakly. */
internal class DelegateHolder {
    var delegate: NSObject? = null
}

@Composable
public actual fun rememberFilePicker(type: PickerType, onPicked: (PickedFile) -> Unit): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)
    val holder = remember { DelegateHolder() }
    return remember(type, holder) {
        {
            val presenter = topViewController()
            if (presenter != null) {
                val deliver: (PickedFile) -> Unit = { file -> currentOnPicked(file) }
                when (type) {
                    PickerType.IMAGE -> {
                        val configuration = PHPickerConfiguration()
                        configuration.filter = PHPickerFilter.imagesFilter
                        configuration.selectionLimit = 1
                        val picker = PHPickerViewController(configuration = configuration)
                        val delegate = ImagePickerDelegate(deliver)
                        holder.delegate = delegate
                        picker.delegate = delegate
                        presenter.presentViewController(picker, animated = true, completion = null)
                    }
                    PickerType.PDF -> {
                        val picker = UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypePDF), asCopy = true)
                        val delegate = DocumentPickerDelegate(deliver)
                        holder.delegate = delegate
                        picker.delegate = delegate
                        picker.allowsMultipleSelection = false
                        presenter.presentViewController(picker, animated = true, completion = null)
                    }
                }
            }
        }
    }
}

private class ImagePickerDelegate(private val onPicked: (PickedFile) -> Unit) : NSObject(), PHPickerViewControllerDelegateProtocol {
    override fun picker(picker: PHPickerViewController, didFinishPicking: List<*>) {
        picker.dismissViewControllerAnimated(true, completion = null)
        val result = didFinishPicking.firstOrNull() as? PHPickerResult ?: return
        val provider = result.itemProvider
        // PNG and JPEG files are imported as they are, the other formats (HEIC, ...) as JPEG
        val (typeIdentifier, keepOriginal) = when {
            provider.hasItemConformingToTypeIdentifier("public.png") -> "public.png" to true
            provider.hasItemConformingToTypeIdentifier("public.jpeg") -> "public.jpeg" to true
            else -> "public.image" to false
        }
        provider.loadDataRepresentationForTypeIdentifier(typeIdentifier) { data, _ ->
            val file = data?.let { imageData ->
                if (keepOriginal) {
                    PickedFile(if (typeIdentifier == "public.png") "image.png" else "image.jpg", imageData.toByteArray())
                } else {
                    UIImage.imageWithData(imageData)
                        ?.let { UIImageJPEGRepresentation(it, 0.92) }
                        ?.let { PickedFile("image.jpg", it.toByteArray()) }
                }
            }
            if (file != null) {
                dispatch_async(dispatch_get_main_queue()) { onPicked(file) }
            }
        }
    }
}

private class DocumentPickerDelegate(private val onPicked: (PickedFile) -> Unit) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
        val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return
        val accessing = url.startAccessingSecurityScopedResource()
        val data = NSData.dataWithContentsOfURL(url)
        if (accessing) url.stopAccessingSecurityScopedResource()
        val bytes = data?.toByteArray() ?: return
        onPicked(PickedFile(url.lastPathComponent ?: "document.pdf", bytes))
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
    }
}

@Suppress("DEPRECATION")
internal fun topViewController(): UIViewController? {
    val application = UIApplication.sharedApplication
    val window = application.keyWindow ?: application.windows.firstOrNull() as? UIWindow
    var controller = window?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    val result = ByteArray(size)
    if (size > 0) {
        result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
    return result
}

@Composable
internal actual fun HideSystemBarsEffect() {
}
