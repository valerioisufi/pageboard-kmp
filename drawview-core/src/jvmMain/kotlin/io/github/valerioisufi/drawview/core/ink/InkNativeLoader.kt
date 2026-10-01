package io.github.valerioisufi.drawview.core.ink

import io.github.valerioisufi.drawview.core.platform.JvmNativeLibraries

internal object InkNativeLoader {
    /**
     * Loads `libink_bridge` bundled in the jvmMain resources.
     * @return `true` on success.
     */
    fun load(): Boolean = JvmNativeLibraries.load(InkNativeLoader::class.java, "ink_bridge")
}
