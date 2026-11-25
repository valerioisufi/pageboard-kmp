package io.github.valerioisufi.pageboard.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import io.github.valerioisufi.pageboard.model.FractionalIndex
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.PageLayer
import io.github.valerioisufi.pageboard.model.PageLayerData
import io.github.valerioisufi.pageboard.model.change.AssignElementsLayer
import io.github.valerioisufi.pageboard.model.change.ChangeEffects
import io.github.valerioisufi.pageboard.model.change.RemovePageLayer
import io.github.valerioisufi.pageboard.model.change.UpsertPageLayer
import io.github.valerioisufi.pageboard.model.newElementId

/**
 * Manages per-page layers: creation, renaming, visibility, lock protection, reordering, active
 * drawing layer selection, and element assignment.
 */
public class LayerManager internal constructor(private val controller: PageboardController) {

    private val activeLayers = mutableStateMapOf<String, String?>()

    /** Observable version counter incremented whenever layers or active layer change. */
    public var version: Int by mutableIntStateOf(0)
        private set

    private fun bump() {
        version++
    }

    /** Returns the layers of [page] ordered from bottom to top; tracked by Compose snapshots. */
    public fun layersOf(page: Page): List<PageLayer> {
        version
        return page.layers
    }

    /** Returns the selected active layer ID for [page], or `null` if unlayered or no layers exist. */
    public fun activeLayerIdOf(page: Page): String? {
        version
        val stored = activeLayers[page.id] ?: return null
        return if (page.findLayer(stored) != null) stored else null
    }

    /** Sets the active drawing layer for [page] (`null` draws unlayered). */
    public fun setActiveLayer(page: Page, layerId: String?) {
        if (layerId != null && page.findLayer(layerId) == null) return
        activeLayers[page.id] = layerId
        bump()
    }

    /** Returns the active layer ID when it is visible and unlocked, or `null` for unlayered drawing. */
    internal fun writableActiveLayerId(page: Page): String? {
        val id = activeLayers[page.id] ?: return null
        val layer = page.findLayer(id) ?: return null
        return if (layer.visible && !layer.locked) id else null
    }

    /** Creates a new layer on top of [page] and selects it as active; `null` at limit or blank name. */
    public fun createLayer(page: Page, name: String): PageLayer? {
        if (page.layers.size >= PageLayer.MAX_LAYERS_PER_PAGE) return null
        val normalized = name.trim().take(PageLayer.MAX_NAME_LENGTH)
        if (normalized.isEmpty()) return null
        val data = PageLayerData(
            id = newElementId(),
            name = normalized,
            orderKey = page.layerOrderKeyAt(page.layers.size),
            visible = true,
            locked = false
        )
        controller.session.submit(UpsertPageLayer(page.id, data)) ?: return null
        val created = page.findLayer(data.id) ?: return null
        activeLayers[page.id] = created.id
        bump()
        return created
    }

    /** Renames [layerId] on [page]. */
    public fun renameLayer(page: Page, layerId: String, name: String): Boolean {
        val layer = page.findLayer(layerId) ?: return false
        val normalized = name.trim().take(PageLayer.MAX_NAME_LENGTH)
        if (normalized.isEmpty()) return false
        controller.session.submit(UpsertPageLayer(page.id, layer.toData().copy(name = normalized))) ?: return false
        bump()
        return true
    }

    /** Shows or hides [layerId] on [page]. Hiding drops any now-hidden active selection. */
    public fun setLayerVisible(page: Page, layerId: String, visible: Boolean): Boolean {
        val layer = page.findLayer(layerId) ?: return false
        if (layer.visible == visible) return false
        controller.session.submit(UpsertPageLayer(page.id, layer.toData().copy(visible = visible))) ?: return false
        controller.selectionManager.pruneUnavailable()
        bump()
        return true
    }

    /** Locks or unlocks [layerId] on [page]. Locking drops any now-locked active selection. */
    public fun setLayerLocked(page: Page, layerId: String, locked: Boolean): Boolean {
        val layer = page.findLayer(layerId) ?: return false
        if (layer.locked == locked) return false
        controller.session.submit(UpsertPageLayer(page.id, layer.toData().copy(locked = locked))) ?: return false
        controller.selectionManager.pruneUnavailable()
        bump()
        return true
    }

    /** Moves [layerId] on [page] to [toIndex] in bottom-to-top layer order. */
    public fun moveLayer(page: Page, layerId: String, toIndex: Int): Boolean {
        val current = page.layers
        val fromIndex = current.indexOfFirst { it.id == layerId }
        if (fromIndex < 0) return false
        val clamped = toIndex.coerceIn(0, current.lastIndex)
        if (fromIndex == clamped) return false
        val without = current.filter { it.id != layerId }
        val before = without.getOrNull(clamped - 1)?.orderKey
        val after = without.getOrNull(clamped)?.orderKey
        val newKey = FractionalIndex.between(before, after)
        controller.session.submit(UpsertPageLayer(page.id, current[fromIndex].toData().copy(orderKey = newKey))) ?: return false
        bump()
        return true
    }

    /** Deletes [layerId] on [page] (undoable via [RemovePageLayer]). */
    public fun deleteLayer(page: Page, layerId: String): Boolean {
        page.findLayer(layerId) ?: return false
        controller.session.submit(RemovePageLayer(page.id, layerId)) ?: return false
        if (activeLayers[page.id] == layerId) activeLayers[page.id] = page.layers.lastOrNull()?.id
        bump()
        return true
    }

    /** Assigns every currently selected element on its page to [layerId] (`null` = unlayered). */
    public fun assignSelectionToLayer(layerId: String?): Boolean {
        val selection = controller.selectionManager.currentSelection ?: return false
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return false
        if (layerId != null && page.findLayer(layerId) == null) return false
        controller.session.submit(AssignElementsLayer(page.id, selection.elements().map { it.id }, layerId)) ?: return false
        controller.selectionManager.pruneUnavailable()
        bump()
        return true
    }

    internal fun onChange(@Suppress("UNUSED_PARAMETER") effects: ChangeEffects) {
        bump()
    }
}
