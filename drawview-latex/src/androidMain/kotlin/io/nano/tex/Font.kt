package io.nano.tex

import android.graphics.Typeface

/**
 * Font used by the TeX engine (created and derived from native code through JNI).
 */
class Font private constructor(
    val typeface: Typeface,
    val size: Float
) {
    fun deriveFont(style: Int): Font {
        if (typeface.style == style) return this
        return Font(Typeface.create(typeface, style), size)
    }

    companion object {
        private val fileCache = mutableMapOf<String, Typeface>()

        @JvmStatic
        fun create(name: String, style: Int, size: Float): Font =
            Font(Typeface.create(name, style), size)

        @JvmStatic
        fun create(file: String, size: Float): Font {
            val typeface = synchronized(fileCache) {
                fileCache.getOrPut(file) {
                    try {
                        Typeface.createFromFile(file)
                    } catch (_: Throwable) {
                        Typeface.SERIF
                    }
                }
            }
            return Font(typeface, size)
        }
    }
}
