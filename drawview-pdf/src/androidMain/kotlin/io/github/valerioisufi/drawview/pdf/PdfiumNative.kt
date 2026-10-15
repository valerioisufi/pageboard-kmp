package io.github.valerioisufi.drawview.pdf

/**
 * JNI entry points of libpdfium_bridge.so (native/bridge/pdfium_bridge_jni.cpp), built by
 * :drawview-native-pdfium together with the prebuilt libpdfium.so.
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

    // Writing (see pdfium_bridge.h)

    @JvmStatic
    external fun nNewDocument(): Long

    @JvmStatic
    external fun nNewPage(docHandle: Long, index: Int, widthPt: Double, heightPt: Double): Long

    @JvmStatic
    external fun nImportPages(docHandle: Long, sourceHandle: Long, pageIndices: IntArray): Boolean

    @JvmStatic
    external fun nPlacePage(docHandle: Long, fromIndex: Int, toIndex: Int, matrix: DoubleArray): Long

    @JvmStatic
    external fun nDeletePage(docHandle: Long, index: Int): Boolean

    @JvmStatic
    external fun nPageAddPath(
        pageHandle: Long,
        verbs: ByteArray,
        verbCount: Int,
        points: FloatArray,
        fillArgb: Int,
        strokeArgb: Int,
        strokeWidth: Float
    ): Boolean

    @JvmStatic
    external fun nPageAddJpeg(docHandle: Long, pageHandle: Long, data: ByteArray, matrix: DoubleArray): Boolean

    @JvmStatic
    external fun nPageAddBitmap(docHandle: Long, pageHandle: Long, pixels: IntArray, width: Int, height: Int, matrix: DoubleArray): Boolean

    @JvmStatic
    external fun nPageAddPdfPage(docHandle: Long, pageHandle: Long, sourceHandle: Long, pageIndex: Int, matrix: DoubleArray): Boolean

    @JvmStatic
    external fun nPageAddMarkup(pageHandle: Long, kind: Int, quads: FloatArray, argb: Int, appearance: String?): Boolean

    @JvmStatic
    external fun nLoadFont(docHandle: Long, data: ByteArray): Long

    @JvmStatic
    external fun nCloseFont(fontHandle: Long)

    @JvmStatic
    external fun nPageAddText(
        docHandle: Long,
        pageHandle: Long,
        fontHandle: Long,
        text: String,
        fontSize: Float,
        argb: Int,
        invisible: Boolean,
        matrix: DoubleArray
    ): Boolean

    @JvmStatic
    external fun nEndPage(pageHandle: Long): Boolean

    @JvmStatic
    external fun nSaveDocument(docHandle: Long, path: String, info: String?): Boolean
}
