package io.nano.tex

class TeXRender(private var nativePtr: Long) {

    private var recorder: ActionRecorder? = null

    val textSize: Float
        get() = nGetTextSize(nativePtr)

    val height: Int
        get() = nGetHeight(nativePtr)

    val depth: Int
        get() = nGetDepth(nativePtr)

    val width: Int
        get() = nGetWidth(nativePtr)

    val baseline: Float
        get() = nGetBaseline(nativePtr)

    fun setTextSize(size: Float) {
        nSetTextSize(nativePtr, size)
    }

    fun setForeground(color: Int) {
        recorder = null
        nSetForeground(nativePtr, color)
    }

    fun setWidth(width: Int, align: Int) {
        nSetWidth(nativePtr, width, align)
    }

    fun setHeight(height: Int, align: Int) {
        nSetHeight(nativePtr, height, align)
    }

    /** The operations drawing the formula from its top-left corner (see [ActionRecorder.toOps]). */
    fun ops(): List<io.github.valerioisufi.drawview.latex.TexOp> {
        val r = ActionRecorder()
        nDraw(nativePtr, r, 0, 0)
        return r.toOps()
    }

    fun draw(g2: Graphics2D, x: Int, y: Int) {
        if (recorder == null) {
            val r = ActionRecorder()
            nDraw(nativePtr, r, 0, 0)
            recorder = r
        }
        recorder?.setPosition(x.toFloat(), y.toFloat())
        recorder?.play(g2)
    }

    fun invalidateDrawingCache() {
        recorder = null
    }

    protected fun finalize() {
        if (nativePtr != 0L) {
            nFinalize(nativePtr)
            nativePtr = 0L
        }
    }

    companion object {
        @JvmStatic
        private external fun nDraw(ptr: Long, recorder: ActionRecorder, x: Int, y: Int)

        @JvmStatic
        private external fun nGetTextSize(ptr: Long): Float

        @JvmStatic
        private external fun nGetHeight(ptr: Long): Int

        @JvmStatic
        private external fun nGetDepth(ptr: Long): Int

        @JvmStatic
        private external fun nGetWidth(ptr: Long): Int

        @JvmStatic
        private external fun nGetBaseline(ptr: Long): Float

        @JvmStatic
        private external fun nSetTextSize(ptr: Long, size: Float)

        @JvmStatic
        private external fun nSetForeground(ptr: Long, color: Int)

        @JvmStatic
        private external fun nSetWidth(ptr: Long, width: Int, align: Int)

        @JvmStatic
        private external fun nSetHeight(ptr: Long, height: Int, align: Int)

        @JvmStatic
        private external fun nFinalize(ptr: Long)
    }
}
