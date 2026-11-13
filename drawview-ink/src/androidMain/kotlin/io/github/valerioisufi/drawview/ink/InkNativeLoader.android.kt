package io.github.valerioisufi.drawview.ink

import android.util.Log

internal actual object InkNativeLoader {
    private const val TAG = "InkNativeLoader"

    /**
     * Loads `libink_bridge.so` (packaged by the :drawview-native-ink module).
     * @return `true` on success.
     */
    actual fun load(): Boolean = try {
        System.loadLibrary("ink_bridge")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "Failed loading libink_bridge.so, falling back to the Kotlin tessellator", e)
        false
    }
}
