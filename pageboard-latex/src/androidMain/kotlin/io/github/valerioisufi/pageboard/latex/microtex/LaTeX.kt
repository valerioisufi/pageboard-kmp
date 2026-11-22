package io.github.valerioisufi.pageboard.latex.microtex

/**
 * Entry point of the native MicroTeX engine (`libtex.so`, built by :pageboard-native-tex).
 * The native methods are registered by `JNI_OnLoad` (see native/tex/port/jni_latex.cpp).
 */
internal class LaTeX private constructor() {

    @Volatile
    public var isInitialized: Boolean = false
        private set

    @Synchronized
    public fun init(resDir: String) {
        if (!nInit(resDir)) throw TeXException("Failed to initialize LaTeX engine with root directory: $resDir")
        isInitialized = true
    }

    @Synchronized
    public fun release() {
        if (isInitialized) {
            nFree()
            isInitialized = false
        }
    }

    @Synchronized
    public fun parse(ltx: String, textSize: Float, foreground: Int): TeXRender {
        check(isInitialized) { "LaTeX engine is not initialized" }
        val ptr = nParse(ltx, 0, textSize, 0f, foreground)
        if (ptr == 0L) throw TeXException("Failed to parse LaTeX: $ltx")
        return TeXRender(ptr)
    }

    public companion object {
        private var instance: LaTeX? = null

        /** Whether `libtex.so` has been loaded. */
        public val isLibraryAvailable: Boolean by lazy {
            try {
                System.loadLibrary("tex")
                true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("LaTeX", "Failed loading libtex.so", e)
                false
            }
        }

        @Synchronized
        public fun instance(): LaTeX {
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
