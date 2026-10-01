package io.github.valerioisufi.drawview.pdf

/**
 * No PDF backend is available in the browser: importing PDFs is disabled.
 */
actual object PdfPlatform {
    actual fun openDocument(filePath: String, password: String?): PdfDocument =
        throw UnsupportedOperationException("PDF documents are not supported in the browser")

    actual fun isAvailable(): Boolean = false
}
