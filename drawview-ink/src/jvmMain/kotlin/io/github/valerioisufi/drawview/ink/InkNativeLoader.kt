package io.github.valerioisufi.drawview.ink

import io.github.valerioisufi.drawview.platform.JvmNativeLibraries

internal object InkNativeLoader {
    /**
     * Loads `libink_bridge` bundled in the jvmMain resources.
     * @return `true` on success.
     */
    fun load(): Boolean = JvmNativeLibraries.load(InkNativeLoader::class.java, "ink_bridge")
}
