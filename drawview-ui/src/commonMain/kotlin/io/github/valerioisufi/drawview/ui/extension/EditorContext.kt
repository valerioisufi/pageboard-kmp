package io.github.valerioisufi.drawview.ui.extension

import io.github.valerioisufi.drawview.canvas.controller.CollaboratorPresence
import io.github.valerioisufi.drawview.canvas.controller.DrawViewController
import io.github.valerioisufi.drawview.canvas.port.EditorDocument
import io.github.valerioisufi.drawview.canvas.port.EditorEdits
import io.github.valerioisufi.drawview.canvas.port.EditorPages
import io.github.valerioisufi.drawview.canvas.port.EditorPlacement
import io.github.valerioisufi.drawview.canvas.port.EditorRendering

/**
 * What the editor of the document shown gives to its extensions ([EditorExtension]): its ports
 * (`docs/editor-ports.md`). The same instance as long as the document is shown, so that an
 * extension can remember what it makes of it. Built by the host of the editor from its
 * controller.
 */
public class EditorContext(controller: DrawViewController) {
    /** The editor itself, for the editor of this module only: the extensions go through the ports. */
    internal val controller: DrawViewController = controller

    /**
     * The document shown, by its identifier (the same on every device): what an extension keeps
     * beside the documents refers to it.
     */
    public val documentId: String get() = controller.document.id

    /** The document and its changes. */
    public val document: EditorDocument get() = controller.ports.document

    /** The pages as they are shown. */
    public val pages: EditorPages get() = controller.ports.pages

    /** What the user does, done by code. */
    public val edits: EditorEdits get() = controller.ports.edits

    /** Elements and texts placed in the free space of the pages. */
    public val placement: EditorPlacement get() = controller.ports.placement

    /** The engines, the measures and the pictures of the pages. */
    public val rendering: EditorRendering get() = controller.ports.rendering

    /** The others in the document. */
    public val presence: CollaboratorPresence get() = controller.ports.presence
}
