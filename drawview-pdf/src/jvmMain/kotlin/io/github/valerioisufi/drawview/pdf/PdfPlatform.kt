package io.github.valerioisufi.drawview.pdf

import java.io.File
import java.io.FileNotFoundException

actual object PdfPlatform {

    actual fun openDocument(filePath: String, password: String?): PdfDocument {
        val file = File(filePath)
        if (!file.exists()) {
            throw FileNotFoundException("PDF file not found: $filePath")
        }

        PdfiumNativeLoader.load()
        val handle = PdfiumNative.nOpenDocument(file.absolutePath, password)
        if (handle == 0L) {
            throw IllegalArgumentException("Failed to open PDF document at: $filePath")
        }

        return JvmPdfDocument(handle)
    }

    actual fun isAvailable(): Boolean = PdfiumNativeLoader.isAvailable
}
