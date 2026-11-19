package io.nano.tex

/** A formula laid out by MicroTeX (native), drawn on a [Graphics2D] at its size. */
public class TeXRender(private var nativePtr: Long) {

    private var recorder: ActionRecorder? = null

    public val textSize: Float
        get() = nGetTextSize(nativePtr)

    public val height: Int
        get() = nGetHeight(nativePtr)

    public val depth: Int
        get() = nGetDepth(nativePtr)

    public val width: Int
        get() = nGetWidth(nativePtr)

    public val baseline: Float
        get() = nGetBaseline(nativePtr)

    public fun setTextSize(size: Float) {
        nSetTextSize(nativePtr, size)
    }

    public fun setForeground(color: Int) {
        recorder = null
        nSetForeground(nativePtr, color)
    }

    public fun setWidth(width: Int, align: Int) {
        nSetWidth(nativePtr, width, align)
    }

    public fun setHeight(height: Int, align: Int) {
        nSetHeight(nativePtr, height, align)
    }

    /** The operations drawing the formula from its top-left corner (see [ActionRecorder.toOps]). */
    public fun ops(): List<io.github.valerioisufi.pageboard.canvas.render.tile.FormulaOp> {
        val r = ActionRecorder()
        nDraw(nativePtr, r, 0, 0)
        return r.toOps()
    }

    public fun draw(g2: Graphics2D, x: Int, y: Int) {
        if (recorder == null) {
            val r = ActionRecorder()
            nDraw(nativePtr, r, 0, 0)
            recorder = r
        }
        recorder?.setPosition(x.toFloat(), y.toFloat())
        recorder?.play(g2)
    }

    public fun invalidateDrawingCache() {
        recorder = null
    }

    protected fun finalize() {
        if (nativePtr != 0L) {
            nFinalize(nativePtr)
            nativePtr = 0L
        }
    }

    public companion object {
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
