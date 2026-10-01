package io.nano.tex

/**
 * Entry point of the native MicroTeX engine (`libtex.so`, built by :drawview-native).
 * The native methods are registered by `JNI_OnLoad` (see native/tex/port/jni_latex.cpp).
 */
class LaTeX private constructor() {

    @Volatile
    var isInitialized = false
        private set

    @Synchronized
    fun init(resDir: String) {
        if (!nInit(resDir)) throw TeXException("Failed to initialize LaTeX engine with root directory: $resDir")
        isInitialized = true
    }

    @Synchronized
    fun release() {
        if (isInitialized) {
            nFree()
            isInitialized = false
        }
    }

    @Synchronized
    fun parse(ltx: String, textSize: Float, foreground: Int): TeXRender {
        check(isInitialized) { "LaTeX engine is not initialized" }
        val ptr = nParse(ltx, 0, textSize, 0f, foreground)
        if (ptr == 0L) throw TeXException("Failed to parse LaTeX: $ltx")
        return TeXRender(ptr)
    }

    companion object {
        private var instance: LaTeX? = null

        /** Whether `libtex.so` has been loaded. */
        val isLibraryAvailable: Boolean by lazy {
            try {
                System.loadLibrary("tex")
                true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("LaTeX", "Failed loading libtex.so", e)
                false
            }
        }

        @Synchronized
        fun instance(): LaTeX {
            if (!isLibraryAvailable) throw UnsatisfiedLinkError("libtex.so is not available")
            return instance ?: LaTeX().also { instance = it }
        }

        @JvmStatic
        private external fun nInit(resDir: String): Boolean

        @JvmStatic
        private external fun nFree()

        @JvmStatic
        private external fun nParse(ltx: String, width: Int, textSize: Float, lineSpace: Float, foreground: Int): Long

        @JvmStatic
        private external fun nSetDebug(debug: Boolean)
    }
}
