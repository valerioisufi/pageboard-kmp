package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.pageboard.canvas.platform.decodeImageBitmap
import io.github.valerioisufi.pageboard.model.DrawImage
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.DrawPdf
import io.github.valerioisufi.pageboard.model.DrawResource
import io.github.valerioisufi.pageboard.model.FractionalIndex
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.AddResource
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import io.github.valerioisufi.pageboard.platform.Sha256
import io.github.valerioisufi.pageboard.platform.newResourcePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imports images and PDF documents into the document resources and pages. A file already
 * imported (in any document) is kept once: its content is found by its hash.
 */
public class MediaManager internal constructor(private val controller: DrawViewController) {

    /** The file of [bytes] (of hash [hash]): the one already kept, or a new one. */
    private suspend fun storedFile(bytes: ByteArray, hash: String, prefix: String, extension: String): String? {
        controller.persistence.fileWithContent(hash)?.takeIf { PlatformFiles.exists(it) }?.let { return it }
        val path = newResourcePath(prefix, extension)
        return path.takeIf { PlatformFiles.writeBytes(it, bytes) }
    }

    /**
     * Imports an encoded image, placing it on the page under [targetScreen] (or at the center of
     * the viewport), and selects it.
     *
     * @param extension file extension of the encoded image (png, jpg, webp, ...).
     */
    public fun importImage(bytes: ByteArray, extension: String = "png", targetScreen: Offset? = null) {
        controller.coroutineScope.launch {
            val hash = withContext(Dispatchers.Default) { Sha256.of(bytes) }
            val prepared = withContext(Dispatchers.Default) {
                val bitmap = decodeImageBitmap(bytes) ?: return@withContext null
                val path = storedFile(bytes, hash, "img", extension.ifBlank { "png" }.lowercase()) ?: return@withContext null
                path to bitmap
            } ?: run {
                controller.emitMessage(DrawViewController.Message.IMPORT_IMAGE_FAILED)
                return@launch
            }
            val (path, bitmap) = prepared

            val document = controller.document
            val screenCenter = targetScreen ?: Offset(controller.viewport.width / 2f, controller.viewport.height / 2f)
            val pageIndex = controller.viewport.findPageIndexAtScreen(screenCenter)
                ?: controller.viewport.nearestVisiblePageIndex(screenCenter)
                ?: 0
            val page = document.pages.getOrNull(pageIndex) ?: return@launch

            val resourceId = newElementId()

            val widthMm = 100f.coerceAtMost(page.widthMm * 0.8f)
            val heightMm = if (bitmap.width > 0) widthMm * bitmap.height / bitmap.width else widthMm
            val centerMm = controller.viewport.screenToPageMm(pageIndex, screenCenter)
                ?: Offset(page.widthMm / 2f, page.heightMm / 2f)

            val image = DrawImage().apply {
                this.resourceId = resourceId
                x = (centerMm.x - widthMm / 2f).coerceIn(0f, (page.widthMm - widthMm).coerceAtLeast(0f))
                y = (centerMm.y - heightMm / 2f).coerceIn(0f, (page.heightMm - heightMm).coerceAtLeast(0f))
                width = widthMm
                height = heightMm
            }
            // Decoded already: the renderer does not decode it again
            controller.tileManager.tileRenderer.images.put(path, bitmap)
            controller.session.submit(
                CompositeChange(
                    listOf(
                        AddResource.of(resourceId, DrawResource.ResourceType.IMAGE, path, hash),
                        AddElements.of(page, listOf(image))
                    )
                )
            )
            controller.selectionManager.selectImage(pageIndex, image)
        }
    }

    /**
     * Imports a file by its [extension] (a PDF, an image: [IMPORTED_EXTENSIONS]), an image placed
     * on the page under [targetScreen]; whether it is a kind of file the editor imports.
     */
    public fun importFile(bytes: ByteArray, extension: String, targetScreen: Offset? = null): Boolean {
        val kind = extension.lowercase()
        when (kind) {
            "pdf" -> importPdf(bytes)
            in IMAGE_EXTENSIONS -> importImage(bytes, kind, targetScreen)
            else -> return false
        }
        return true
    }

    /**
     * Imports a PDF document, appending one page per PDF page at the end of the document; a
     * document of one empty page (a note just made) becomes the PDF, without that page.
     */
    public fun importPdf(bytes: ByteArray) {
        val adapter = controller.tileManager.tileRenderer.pdfAdapter
        if (adapter == null) {
            controller.emitMessage(DrawViewController.Message.PDF_NOT_SUPPORTED)
            return
        }
        controller.coroutineScope.launch {
            val hash = withContext(Dispatchers.Default) { Sha256.of(bytes) }
            val prepared = withContext(Dispatchers.Default) {
                val path = storedFile(bytes, hash, "pdf", "pdf") ?: return@withContext null
                val sizes = try {
                    adapter.getPageSizesMm(path)
                } catch (_: Throwable) {
                    emptyList()
                }
                if (sizes.isEmpty()) {
                    // Not a PDF: the file is not kept (unless another resource has it)
                    if (controller.persistence.fileWithContent(hash) != path) PlatformFiles.delete(path)
                    return@withContext null
                }
                path to sizes
            } ?: run {
                controller.emitMessage(DrawViewController.Message.IMPORT_PDF_FAILED)
                return@launch
            }
            val (path, sizes) = prepared

            val document = controller.document
            controller.selectionManager.clearSelection()
            val resourceId = newElementId()
            // The empty page of a note just made gives way to the PDF
            val emptyPage = document.pages.singleOrNull()?.takeIf { it.elements().isEmpty() }
            val firstIndex = if (emptyPage != null) 0 else document.pages.size
            // At the end of the document
            val keys = FractionalIndex.after(document.pages.lastOrNull()?.orderKey, sizes.size)
            val pages = sizes.mapIndexed { pdfIndex, (widthMm, heightMm) ->
                DrawPage(widthMm = widthMm, heightMm = heightMm, background = document.defaultBackground, orderKey = keys[pdfIndex]).apply {
                    add(DrawPdf(pdfPageIndex = pdfIndex).apply { this.resourceId = resourceId })
                }
            }
            controller.session.submit(
                CompositeChange(
                    listOf(AddResource.of(resourceId, DrawResource.ResourceType.PDF, path, hash)) + pages.map { AddPage.of(it) } +
                        listOfNotNull(emptyPage?.let { RemovePage(it.id) })
                )
            )
            controller.pageManager.jumpToPage(firstIndex)
        }
    }

    public companion object {
        /** The extensions of the images the editor imports. */
        public val IMAGE_EXTENSIONS: Set<String> = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

        /** The extensions of the files the editor imports: the PDFs and the images. */
        public val IMPORTED_EXTENSIONS: Set<String> = IMAGE_EXTENSIONS + "pdf"
    }
}
