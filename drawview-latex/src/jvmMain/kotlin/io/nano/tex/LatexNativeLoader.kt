package io.nano.tex

import io.github.valerioisufi.drawview.core.platform.JvmNativeLibraries

object LatexNativeLoader {
    /** Whether `libtex` has been loaded successfully. */
    val isAvailable: Boolean by lazy { JvmNativeLibraries.load(LatexNativeLoader::class.java, "tex") }

    fun load() {
        if (!isAvailable) throw UnsatisfiedLinkError("Could not load native TeX library (libtex)")
    }
}
