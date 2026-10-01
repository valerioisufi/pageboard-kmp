package io.github.valerioisufi.drawview.core.controller

import androidx.compose.ui.geometry.Offset
import io.github.valerioisufi.drawview.core.history.AddImageAction
import io.github.valerioisufi.drawview.core.history.AddPageAction
import io.github.valerioisufi.drawview.core.history.BatchAction
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawPdf
import io.github.valerioisufi.drawview.core.model.DrawResource
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

            val resourceId = controller.persistence.addResource(document, DrawResource.ResourceType.IMAGE, path)
            document.resources.add(DrawResource(resourceId, DrawResource.ResourceType.IMAGE, path))

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
            page.images.add(image)
            page.markContentChanged()
            controller.historyManager.addHistoryAction(AddImageAction(page, image))
            controller.persist { saveImage(page, image) }
            controller.invalidatePageContent(page, image.getRotatedBounds())
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
            val resourceId = controller.persistence.addResource(document, DrawResource.ResourceType.PDF, path)
            document.resources.add(DrawResource(resourceId, DrawResource.ResourceType.PDF, path))

            val actions = mutableListOf<AddPageAction>()
            val newPages = mutableListOf<Pair<DrawPage, DrawPdf>>()
            sizes.forEachIndexed { pdfIndex, (widthMm, heightMm) ->
                val index = document.pages.size
                val page = DrawPage(
                    index = index,
                    widthMm = widthMm,
                    heightMm = heightMm,
                    background = document.defaultBackground
                )
                val pdf = DrawPdf(zIndex = 0, pdfPageIndex = pdfIndex).apply { this.resourceId = resourceId }
                page.pdfs.add(pdf)
                document.pages.add(page)
                actions.add(AddPageAction(page, index))
                newPages.add(page to pdf)
            }
            document.reindexPages()
            controller.historyManager.addHistoryAction(BatchAction(actions))
            controller.persist {
                for ((page, pdf) in newPages) {
                    insertPage(document, page)
                    savePdf(page, pdf)
                }
            }
            controller.markLayoutNeedsUpdate()
            controller.pageManager.jumpToPage(newPages.first().first.index)
        }
    }
}
