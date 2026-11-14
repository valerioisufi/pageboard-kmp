package io.github.valerioisufi.drawview.latex

import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock

/** The TrueType files of the text of the formulas, by style (see [LatexEngine.prepare]). */
internal object TexTextFonts {
    private val lock = SyncLock()
    private val fonts = mutableMapOf<Int, ByteArray>()

    fun set(byStyle: Map<Int, ByteArray>) = syncLock(lock) {
        fonts.clear()
        fonts.putAll(byStyle)
    }

    /** The font of [style] (bold and italic bits), or `null`. */
    fun get(style: Int): ByteArray? = syncLock(lock) { fonts[style] }
}
