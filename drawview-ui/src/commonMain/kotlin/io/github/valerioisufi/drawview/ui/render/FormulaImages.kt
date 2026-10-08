package io.github.valerioisufi.drawview.ui.render

import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.util.SyncLock
import io.github.valerioisufi.drawview.core.util.syncLock
import io.github.valerioisufi.drawview.latex.LatexEngine
import kotlin.math.roundToInt

/**
 * The formulas of the texts rendered by MicroTeX as images, kept for the sizes and the colors
 * asked last. Thread-safe: the tiles render them on background threads.
 */
class FormulaImages(private val maxSize: Int = 128) {

    /** A formula rendered at a size: its [bitmap] and the place of its baseline (0 top, 1 bottom). */
    class Image(val bitmap: ImageBitmap, val baselineRatio: Float)

    private data class Key(val latex: String, val color: Int, val sizePx: Int)

    private val lock = SyncLock()
    private val images = LinkedHashMap<Key, Image?>()

    /**
     * [latex] rendered [sizePx] high (rounded to a pixel) in [color], or `null` when it is invalid
     * or the engine is not ready yet.
     */
    fun render(latex: String, sizePx: Float, color: Int): Image? {
        if (!LatexEngine.isNativeReady()) return null
        val clean = LatexEngine.sanitizeLatexString(latex)
        if (clean.isBlank()) return null
        val key = Key(clean, color, sizePx.roundToInt().coerceAtLeast(1))
        return syncLock(lock) {
            if (images.containsKey(key)) {
                // The most recent last
                images.remove(key).also { images[key] = it }
            } else {
                val image = LatexEngine.renderFormula(clean, key.sizePx.toFloat(), color, paddingPx = 0f)?.let { Image(it.bitmap, it.baselineRatio) }
                images[key] = image
                while (images.size > maxSize) images.remove(images.keys.first())
                image
            }
        }
    }

    /** Drops the images (e.g. when the engine becomes ready). */
    fun clear() = syncLock(lock) { images.clear() }
}
