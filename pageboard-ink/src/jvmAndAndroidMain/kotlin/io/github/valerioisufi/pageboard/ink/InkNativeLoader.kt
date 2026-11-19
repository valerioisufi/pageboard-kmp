package io.github.valerioisufi.pageboard.ink

/** Loads `libink_bridge`, as each platform ships it. */
internal expect object InkNativeLoader {
    /** Loads the library: `true` on success. */
    fun load(): Boolean
}
