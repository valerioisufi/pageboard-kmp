package io.github.valerioisufi.drawview.core.controller

import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.drawview.core.change.AddElements
import io.github.valerioisufi.drawview.core.change.AddPage
import io.github.valerioisufi.drawview.core.change.AddResource
import io.github.valerioisufi.drawview.core.change.CompositeChange
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.newElementId
import io.github.valerioisufi.drawview.core.platform.PlatformFiles
import io.github.valerioisufi.drawview.core.platform.decodeImageBitmap
import io.github.valerioisufi.drawview.core.platform.newResourcePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Imports images and PDF documents into the document resources and pages.
 */
class MediaManager(private val controller: DrawViewController) {

    /**
     * Imports an encoded image, placing it on the page under [targetScreen] (or at the center of
     * the viewport), and selects it.
     *
     * @param extension file extension of the encoded image (png, jpg, webp, ...).
     */
    fun importImage(bytes: ByteArray, extension: String = "png", targetScreen: Offset? = null) {
        controller.coroutineScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val bitmap = decodeImageBitmap(bytes) ?: return@withContext null
                val path = newResourcePath("img", extension.ifBlank { "png" }.lowercase())
                if (!PlatformFiles.writeBytes(path, bytes)) return@withContext null
                path to bitmap
            } ?: run {
                controller.emitMessage(DrawViewController.Message.IMPORT_IMAGE_FAILED)
                return@launch
            }
            val (path, bitmap) = prepared

            val document = controller.document
            val screenCenter = targetScreen ?: Offset(controller.viewportWidth / 2f, controller.viewportHeight / 2f)
            val pageIndex = controller.findPageIndexAtScreen(screenCenter)
                ?: controller.nearestVisiblePageIndex(screenCenter)
                ?: 0
            val page = document.pages.getOrNull(pageIndex) ?: return@launch

            val resourceId = newElementId()

            val widthMm = 100f.coerceAtMost(page.widthMm * 0.8f)
            val heightMm = if (bitmap.width > 0) widthMm * bitmap.height / bitmap.width else widthMm
            val centerMm = controller.screenToPageMm(pageIndex, screenCenter)
                ?: Offset(page.widthMm / 2f, page.heightMm / 2f)

            val image = DrawImage(zIndex = page.images.size).apply {
                this.resourceId = resourceId
                x = (centerMm.x - widthMm / 2f).coerceIn(0f, (page.widthMm - widthMm).coerceAtLeast(0f))
                y = (centerMm.y - heightMm / 2f).coerceIn(0f, (page.heightMm - heightMm).coerceAtLeast(0f))
                width = widthMm
                height = heightMm
                this.bitmap = bitmap
            }
            controller.session.submit(
                CompositeChange(
                    listOf(
                        AddResource.of(resourceId, DrawResource.ResourceType.IMAGE, path),
                        AddElements.of(page, listOf(image))
                    )
                )
            )
            controller.selectionManager.selectImage(pageIndex, image)
        }
    }

    /**
     * Imports a PDF document, appending one page per PDF page at the end of the document.
     */
    fun importPdf(bytes: ByteArray) {
        val adapter = controller.tileManager.tileRenderer.pdfAdapter
        if (adapter == null) {
            controller.emitMessage(DrawViewController.Message.PDF_NOT_SUPPORTED)
            return
        }
        controller.coroutineScope.launch {
            val prepared = withContext(Dispatchers.Default) {
                val path = newResourcePath("pdf", "pdf")
                if (!PlatformFiles.writeBytes(path, bytes)) return@withContext null
                val sizes = try {
                    adapter.getPageSizesMm(path)
                } catch (_: Throwable) {
                    emptyList()
                }
                if (sizes.isEmpty()) {
                    PlatformFiles.delete(path)
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
            val firstIndex = document.pages.size
            val pages = sizes.mapIndexed { pdfIndex, (widthMm, heightMm) ->
                DrawPage(widthMm = widthMm, heightMm = heightMm, background = document.defaultBackground).apply {
                    add(DrawPdf(zIndex = 0, pdfPageIndex = pdfIndex).apply { this.resourceId = resourceId })
                }
            }
            controller.session.submit(
                CompositeChange(
                    listOf(AddResource.of(resourceId, DrawResource.ResourceType.PDF, path)) +
                        pages.mapIndexed { i, page -> AddPage.of(page, firstIndex + i) }
                )
            )
            controller.pageManager.jumpToPage(firstIndex)
        }
    }
}
