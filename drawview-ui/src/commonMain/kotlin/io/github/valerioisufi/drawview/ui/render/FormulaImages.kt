package io.github.valerioisufi.drawview.ui.render

import io.github.valerioisufi.drawview.canvas.render.tile.FormulaImage
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.drawview.platform.SyncLock
import io.github.valerioisufi.drawview.platform.syncLock
import kotlin.math.roundToInt

/**
 * The formulas of the texts rendered by [renderer] as images, kept for the sizes and the colors
 * asked last (none without a renderer: the formulas stay as their source). Thread-safe: the tiles
 * render them on background threads.
 */
internal class FormulaImages(private val renderer: FormulaRenderer?, private val maxSize: Int = 128) {

    private data class Key(val latex: String, val color: Int, val sizePx: Int)

    private val lock = SyncLock()
    private val images = LinkedHashMap<Key, FormulaImage?>()

    /**
     * [latex] rendered [sizePx] high (rounded to a pixel) in [color], or `null` when it is invalid
     * or the engine is not ready yet.
     */
    fun render(latex: String, sizePx: Float, color: Int): FormulaImage? {
        val renderer = renderer ?: return null
        if (!renderer.isReady.value) return null
        val clean = renderer.sanitize(latex)
        if (clean.isBlank()) return null
        val key = Key(clean, color, sizePx.roundToInt().coerceAtLeast(1))
        return syncLock(lock) {
            if (images.containsKey(key)) {
                // The most recent last
                images.remove(key).also { images[key] = it }
            } else {
                val image = renderer.render(clean, key.sizePx.toFloat(), color)
                images[key] = image
                while (images.size > maxSize) images.remove(images.keys.first())
                image
            }
        }
    }

    /** Drops the images (e.g. when the engine becomes ready). */
    fun clear() = syncLock(lock) { images.clear() }
}
