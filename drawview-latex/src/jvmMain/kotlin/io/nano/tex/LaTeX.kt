package io.nano.tex

class LaTeX private constructor() {

    @Volatile
    var isInitialized = false
        private set

    @Synchronized
    fun init(resDir: String) {
        val success = nInit(resDir)
        if (!success) {
            throw TeXException("Failed to initialize LaTeX engine with root directory: $resDir")
        }
        isInitialized = true
    }

    @Synchronized
    fun release() {
        if (isInitialized) {
            nFree()
            isInitialized = false
        }
    }

    private fun check() {
        if (!isInitialized) {
            throw IllegalStateException("LaTeX engine is not initialized. Call init(resDir) first.")
        }
    }

    @Synchronized
    fun parse(ltx: String, textSize: Float, foreground: Int): TeXRender {
        check()
        val ptr = nParse(ltx, 0, textSize, 0f, foreground)
        if (ptr == 0L) throw TeXException("Failed to parse LaTeX: $ltx")
        return TeXRender(ptr)
    }

    @Synchronized
    fun parse(
        ltx: String,
        width: Int,
        textSize: Float,
        lineSpace: Float,
        foreground: Int
    ): TeXRender {
        check()
        val ptr = nParse(ltx, width, textSize, lineSpace, foreground)
        if (ptr == 0L) throw TeXException("Failed to parse LaTeX: $ltx")
        val r = TeXRender(ptr)
        if (width > 0 && r.width > width) {
            val scale = width.toFloat() / r.width.toFloat()
            r.setTextSize(scale * textSize)
        }
        return r
    }

    fun setDebug(debug: Boolean) {
        nSetDebug(debug)
    }

    companion object {
        private var instance: LaTeX? = null

        @Synchronized
        fun instance(): LaTeX {
            LatexNativeLoader.load()
            if (instance == null) {
                instance = LaTeX()
            }
            return instance!!
        }

        @JvmStatic
        private external fun nInit(resDir: String): Boolean

        @JvmStatic
        private external fun nFree()

        @JvmStatic
        private external fun nParse(
            ltx: String,
            width: Int,
            textSize: Float,
            lineSpace: Float,
            foreground: Int
        ): Long

        @JvmStatic
        private external fun nSetDebug(debug: Boolean)
    }
}
