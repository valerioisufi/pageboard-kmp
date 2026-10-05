package io.github.valerioisufi.drawview.pdf

import io.github.valerioisufi.drawview.core.platform.JvmNativeLibraries

internal object PdfiumNativeLoader {
    /** Loads PDFium and its JNI bridge (in dependency order). */
    val isAvailable: Boolean by lazy {
        JvmNativeLibraries.load(PdfiumNativeLoader::class.java, "pdfium", "pdfium_bridge")
    }

    fun load() {
        if (!isAvailable) throw UnsatisfiedLinkError("Could not load the native PDFium libraries")
    }
}

internal object PdfiumNative {
    init {
        PdfiumNativeLoader.load()
    }

    @JvmStatic
    external fun nInit()

    @JvmStatic
    external fun nDestroy()

    @JvmStatic
    external fun nOpenDocument(path: String, password: String?): Long

    @JvmStatic
    external fun nGetPageCount(docHandle: Long): Int

    @JvmStatic
    external fun nGetPageSize(docHandle: Long, pageIndex: Int, outSize: DoubleArray): Boolean

    @JvmStatic
    external fun nRenderPageBitmap(
        docHandle: Long,
        pageIndex: Int,
        startX: Int,
        startY: Int,
        sizeX: Int,
        sizeY: Int,
        widthPx: Int,
        heightPx: Int,
        rotate: Int,
        outPixels: IntArray
    ): Boolean

    /** Text of a page, or `null` when the page cannot be read. */
    @JvmStatic
    external fun nGetPageText(docHandle: Long, pageIndex: Int): String?

    /**
     * Characters of a page and their boxes into [outChars] and [outBoxes] (four values per
     * character), when they are large enough: returns the number of characters (call it first
     * with `null` arrays to size them), or -1 when the page cannot be read.
     */
    @JvmStatic
    external fun nGetPageTextLayout(docHandle: Long, pageIndex: Int, outChars: IntArray?, outBoxes: FloatArray?): Int

    @JvmStatic
    external fun nCloseDocument(docHandle: Long)
}
