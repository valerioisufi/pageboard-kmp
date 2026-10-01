package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawPDFPage
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextScaleCTM
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextTranslateCTM
import platform.CoreGraphics.CGDataProviderCreateWithFilename
import platform.CoreGraphics.CGDataProviderRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGPDFDocumentCreateWithProvider
import platform.CoreGraphics.CGPDFDocumentGetNumberOfPages
import platform.CoreGraphics.CGPDFDocumentGetPage
import platform.CoreGraphics.CGPDFDocumentRef
import platform.CoreGraphics.CGPDFDocumentRelease
import platform.CoreGraphics.CGPDFPageGetBoxRect
import platform.CoreGraphics.CGPDFPageRef
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreGraphics.kCGPDFMediaBox
import platform.Foundation.NSFileManager

/**
 * PDF document rendered by CoreGraphics (iOS).
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPdfDocument(private var document: CGPDFDocumentRef?) : PdfDocument {

    override val pageCount: Int
        get() = document?.let { CGPDFDocumentGetNumberOfPages(it).toInt() } ?: 0

    private fun page(index: Int): CGPDFPageRef? {
        val doc = document ?: return null
        if (index !in 0 until pageCount) return null
        // CoreGraphics pages are 1-based
        return CGPDFDocumentGetPage(doc, (index + 1).toULong())
    }

    private fun mediaBox(page: CGPDFPageRef): CValue<CGRect> = CGPDFPageGetBoxRect(page, kCGPDFMediaBox)

    override fun getPageSize(pageIndex: Int): PdfSize {
        val page = page(pageIndex) ?: return PdfSize(0.0, 0.0)
        return mediaBox(page).useContents { PdfSize(size.width, size.height) }
    }

    override fun renderPageSlice(
        pageIndex: Int,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        widthPx: Int,
        heightPx: Int,
        rotate: Int
    ): ImageBitmap? {
        val page = page(pageIndex) ?: return null
        if (widthPx <= 0 || heightPx <= 0) return null
        val box = mediaBox(page)
        val boxX = box.useContents { origin.x }
        val boxY = box.useContents { origin.y }
        val boxW = box.useContents { size.width }
        val boxH = box.useContents { size.height }
        if (boxW <= 0.0 || boxH <= 0.0) return null

        val bytes = ByteArray(widthPx * heightPx * 4)
        val colorSpace = CGColorSpaceCreateDeviceRGB()
        try {
            bytes.usePinned { pinned ->
                val context = CGBitmapContextCreate(
                    pinned.addressOf(0),
                    widthPx.toULong(),
                    heightPx.toULong(),
                    8u,
                    (widthPx * 4).toULong(),
                    colorSpace,
                    CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big
                ) ?: return null
                try {
                    // White paper
                    CGContextSetRGBFillColor(context, 1.0, 1.0, 1.0, 1.0)
                    CGContextFillRect(context, CGRectMake(0.0, 0.0, widthPx.toDouble(), heightPx.toDouble()))
                    // CoreGraphics user space is y-up with the origin at the bottom-left of the
                    // bitmap, whose first row in memory is the top of the image.
                    CGContextTranslateCTM(context, startX.toDouble(), (heightPx - startY - sizeY).toDouble())
                    CGContextScaleCTM(context, sizeX / boxW, sizeY / boxH)
                    CGContextTranslateCTM(context, -boxX, -boxY)
                    CGContextDrawPDFPage(context, page)
                } finally {
                    CGContextRelease(context)
                }
            }
        } finally {
            CGColorSpaceRelease(colorSpace)
        }

        val info = ImageInfo(ColorInfo(ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB), widthPx, heightPx)
        val bitmap = Bitmap()
        bitmap.allocPixels(info)
        bitmap.installPixels(info, bytes, widthPx * 4)
        bitmap.setImmutable()
        return bitmap.asComposeImageBitmap()
    }

    override fun close() {
        document?.let { CGPDFDocumentRelease(it) }
        document = null
    }
}

@OptIn(ExperimentalForeignApi::class)
actual object PdfPlatform {
    actual fun openDocument(filePath: String, password: String?): PdfDocument {
        if (!NSFileManager.defaultManager.fileExistsAtPath(filePath)) {
            throw IllegalArgumentException("PDF file not found: $filePath")
        }
        val provider = CGDataProviderCreateWithFilename(filePath)
            ?: throw IllegalArgumentException("Cannot read PDF file: $filePath")
        try {
            val document = CGPDFDocumentCreateWithProvider(provider)
                ?: throw IllegalArgumentException("Invalid PDF document: $filePath")
            return IosPdfDocument(document)
        } finally {
            CGDataProviderRelease(provider)
        }
    }

    actual fun isAvailable(): Boolean = true
}
