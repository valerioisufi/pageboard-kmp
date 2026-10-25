package io.github.valerioisufi.drawview.canvas.controller

import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.change.ChangeEffects
import io.github.valerioisufi.drawview.model.change.toData
import kotlin.math.abs
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The heights of the texts of the document of [controller] as this device lays them out
 * ([DrawText.height]): the box of a text is what its lines take, never what a version of the
 * application measured when it wrote it (docs/text-heights.md).
 *
 * Every text is laid out when the text adapter is set, off the main thread, and again when a
 * change reaches it, made here or on another device (the adapter keeps the sizes it laid out, so
 * a text not changed is not laid out again); the tiles of the box it had and of the box it has are
 * drawn again. The frame of the selection, the lasso, the taps and the snapping read its heights.
 */
internal class TextBoxes(private val controller: DrawViewController) {

    private var layingOut: Job? = null

    /** Where the texts are laid out when the adapter is set: off the main thread. */
    var dispatcher: CoroutineDispatcher = Dispatchers.Default

    /** Lays out every text of the document with [adapter] (none: the heights stay as they are). */
    fun adapterChanged(adapter: TextTileAdapter?) {
        layingOut?.cancel()
        if (adapter == null) return
        val texts = controller.document.pages.flatMap { page -> page.texts.map { page to it.toData() } }
        layingOut = controller.coroutineScope.launch {
            val heights = withContext(dispatcher) {
                texts.mapNotNull { (page, data) -> adapter.measure(data.toElement())?.let { Triple(page, data, it.height) } }
            }
            for ((page, data, height) in heights) {
                // A text changed meanwhile was laid out by its change
                val text = page.texts.firstOrNull { it.id == data.id } ?: continue
                if (text.toData() == data) apply(page, text, height)
            }
        }
    }

    /** Lays out again the texts [effects] changed, and the texts of the pages that came. */
    fun onChange(effects: ChangeEffects) {
        for ((element, page) in effects.savedElements) {
            if (element is DrawText && element.removed == null) layOut(page, element)
        }
        if (effects.layoutChanged) {
            for (page in controller.document.pages) for (text in page.texts) layOut(page, text)
        }
    }

    /** Stops laying the texts out. */
    fun dispose() {
        layingOut?.cancel()
    }

    private fun layOut(page: DrawPage, text: DrawText) {
        val height = controller.textAdapter?.measure(text)?.height ?: return
        apply(page, text, height)
    }

    private fun apply(page: DrawPage, text: DrawText, height: Float) {
        if (abs(height - text.height) <= HEIGHT_EPSILON_MM) return
        val before = text.getRotatedBounds()
        text.height = height
        controller.invalidatePageContent(page, before.union(text.getRotatedBounds()))
    }

    private companion object {
        /** A difference of height smaller than this is the rounding of the layout. */
        const val HEIGHT_EPSILON_MM = 0.01f
    }
}
