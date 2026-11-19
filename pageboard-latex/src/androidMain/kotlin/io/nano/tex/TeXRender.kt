package io.nano.tex

/**
 * A formula laid out by MicroTeX (native), drawn on a [Graphics2D] at its size; its native object
 * is released by [release], or when it is garbage collected.
 */
public class TeXRender internal constructor(private var nativePtr: Long) {

    private var recorder: ActionRecorder? = null

    public val width: Int get() = nGetWidth(nativePtr)
    public val height: Int get() = nGetHeight(nativePtr)
    public val depth: Int get() = nGetDepth(nativePtr)
    public val baseline: Float get() = nGetBaseline(nativePtr)
    public val textSize: Float get() = nGetTextSize(nativePtr)

    /** The operations drawing the formula from its top-left corner (see [ActionRecorder.toOps]). */
    public fun ops(): List<io.github.valerioisufi.pageboard.canvas.render.tile.FormulaOp> {
        val r = ActionRecorder()
        nDraw(nativePtr, r, 0, 0)
        return r.toOps()
    }

    public fun draw(g2: Graphics2D, x: Int, y: Int) {
        val r = recorder ?: ActionRecorder().also {
            nDraw(nativePtr, it, 0, 0)
            recorder = it
        }
        r.setPosition(x.toFloat(), y.toFloat())
        r.play(g2)
    }

    @Synchronized
    public fun release() {
        if (nativePtr != 0L) {
            nFinalize(nativePtr)
            nativePtr = 0L
        }
    }

    @Suppress("deprecation")
    protected fun finalize() {
        release()
    }

    private companion object {
        @JvmStatic private external fun nDraw(ptr: Long, recorder: ActionRecorder, x: Int, y: Int)
        @JvmStatic private external fun nGetTextSize(ptr: Long): Float
        @JvmStatic private external fun nGetHeight(ptr: Long): Int
        @JvmStatic private external fun nGetDepth(ptr: Long): Int
        @JvmStatic private external fun nGetWidth(ptr: Long): Int
        @JvmStatic private external fun nGetBaseline(ptr: Long): Float
        @JvmStatic private external fun nSetTextSize(ptr: Long, size: Float)
        @JvmStatic private external fun nSetForeground(ptr: Long, color: Int)
        @JvmStatic private external fun nSetWidth(ptr: Long, width: Int, align: Int)
        @JvmStatic private external fun nSetHeight(ptr: Long, height: Int, align: Int)
        @JvmStatic private external fun nFinalize(ptr: Long)
    }
}
