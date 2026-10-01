package io.nano.tex

/**
 * A parsed formula (native object released by [release] or when garbage collected).
 */
class TeXRender internal constructor(private var nativePtr: Long) {

    private var recorder: ActionRecorder? = null

    val width: Int get() = nGetWidth(nativePtr)
    val height: Int get() = nGetHeight(nativePtr)
    val depth: Int get() = nGetDepth(nativePtr)
    val baseline: Float get() = nGetBaseline(nativePtr)
    val textSize: Float get() = nGetTextSize(nativePtr)

    fun draw(g2: Graphics2D, x: Int, y: Int) {
        val r = recorder ?: ActionRecorder().also {
            nDraw(nativePtr, it, 0, 0)
            recorder = it
        }
        r.setPosition(x.toFloat(), y.toFloat())
        r.play(g2)
    }

    @Synchronized
    fun release() {
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
