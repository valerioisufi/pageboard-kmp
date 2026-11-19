package io.nano.tex

import io.github.valerioisufi.pageboard.platform.JvmNativeLibraries

internal object LatexNativeLoader {
    /** Whether `libtex` has been loaded successfully. */
    val isAvailable: Boolean by lazy { JvmNativeLibraries.load(LatexNativeLoader::class.java, "tex") }

    fun load() {
        if (!isAvailable) throw UnsatisfiedLinkError("Could not load native TeX library (libtex)")
    }
}
