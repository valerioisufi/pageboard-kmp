package io.github.valerioisufi.drawview.pdf

/**
 * JNI entry points of libpdfium_bridge.so (native/bridge/pdfium_bridge_jni.cpp), built by
 * :drawview-native together with the prebuilt libpdfium.so.
 */
internal object PdfiumNative {
    /** Loads PDFium and its JNI bridge (in dependency order). */
    val isAvailable: Boolean by lazy {
        try {
            System.loadLibrary("pdfium")
            System.loadLibrary("pdfium_bridge")
            true
        } catch (e: UnsatisfiedLinkError) {
            System.err.println("DrawView: native PDFium libraries not available: ${e.message}")
            false
        }
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
