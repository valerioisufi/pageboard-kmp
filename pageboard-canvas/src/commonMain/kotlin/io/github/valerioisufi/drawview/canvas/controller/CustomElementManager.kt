package io.github.valerioisufi.drawview.canvas.controller

import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.ElementType
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.UpdateElementContent
import io.github.valerioisufi.drawview.model.encode
import io.github.valerioisufi.drawview.model.geometry.Rect

/**
 * The elements of the types added by the application ([ElementType]), as changes of the
 * document: added, then their value replaced (each one undone by undo, stored and sent to the
 * other devices). The editor places, selects, moves, rotates, resizes, copies and removes them
 * like the images, through their box; they are drawn by the renderers of
 * `DrawViewEngines.elements`.
 */
public class CustomElementManager internal constructor(private val controller: DrawViewController) {

    /**
     * Adds an element of [type] of value [value] to the page [pageIndex], in [box] (page
     * millimeters), on top of the others, and selects it; `null` when the page does not exist or
     * the document may only be read.
     */
    public fun <T : Any> add(type: ElementType<T>, value: T, pageIndex: Int, box: Rect, select: Boolean = true): DrawCustomElement? =
        add(type, value, pageIndex, box, rotation = 0f, select = select)

    /**
     * Adds an element of [type] of value [value] to the page [pageIndex], in [box] (page
     * millimeters) turned by [rotation] degrees around its center, on top of the others, and
     * selects it; `null` when the page does not exist or the document may only be read.
     */
    public fun <T : Any> add(type: ElementType<T>, value: T, pageIndex: Int, box: Rect, rotation: Float, select: Boolean = true): DrawCustomElement? {
        val page = controller.document.pages.getOrNull(pageIndex) ?: return null
        if (box.isEmpty) return null
        val element = DrawCustomElement(type.id).apply {
            version = type.version
            payload = type.encode(value)
            x = box.left
            y = box.top
            width = box.width
            height = box.height
            this.rotation = rotation
        }
        controller.session.submit(AddElements.of(page, listOf(element))) ?: return null
        if (select) controller.selectionManager.selectCustomElement(pageIndex, element)
        return element
    }

    /**
     * Replaces the value of [element] (of [type]) with [value]; `false` when the element is no
     * longer in the document or the document may only be read.
     */
    public fun <T : Any> update(element: DrawCustomElement, type: ElementType<T>, value: T): Boolean {
        require(element.type == type.id) { "${element.type} is not of the type ${type.id}" }
        val (page, _) = controller.document.findElement(element.id) ?: return false
        return controller.session.submit(UpdateElementContent(page.id, element.id, type.version, type.encode(value))) != null
    }
}
