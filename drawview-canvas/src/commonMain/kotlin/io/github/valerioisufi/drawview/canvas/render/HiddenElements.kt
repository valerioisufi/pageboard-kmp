package io.github.valerioisufi.drawview.canvas.render

import kotlin.concurrent.Volatile

/**
 * The elements the static (tile) layers leave out because the editor draws them over the tiles:
 * selected, moved or written here (dragged) or on another device (shown by a collaborator).
 *
 * The state of the editor, not of the document: the tile workers read it while the editor
 * changes it, so each set is replaced as a whole, never changed in place.
 */
class HiddenElements {
    @Volatile
    private var dragged: Set<String> = emptySet()

    @Volatile
    private var shownByCollaborators: Set<String> = emptySet()

    /** Whether the element [id] is left out of the tiles. */
    fun isHidden(id: String): Boolean = id in dragged || id in shownByCollaborators

    /** Whether the element [id] is selected, moved or written here. */
    fun isDragged(id: String): Boolean = id in dragged

    /** Whether another device writes or moves the element [id] (and draws it). */
    fun isShownByCollaborator(id: String): Boolean = id in shownByCollaborators

    /** Leaves the elements [ids] out of the tiles while [dragged] (selected, moved, written here). */
    fun setDragged(ids: Collection<String>, dragged: Boolean) {
        this.dragged = if (dragged) this.dragged + ids else this.dragged - ids.toSet()
    }

    /** Leaves the element [id] out of the tiles while another device draws it ([shown]). */
    fun setShownByCollaborator(id: String, shown: Boolean) {
        shownByCollaborators = if (shown) shownByCollaborators + id else shownByCollaborators - id
    }
}
