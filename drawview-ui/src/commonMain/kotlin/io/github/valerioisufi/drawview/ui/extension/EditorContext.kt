package io.github.valerioisufi.drawview.ui.extension

import io.github.valerioisufi.drawview.canvas.controller.DrawViewController

/**
 * What the editor gives to its extensions ([EditorExtension]): the editor of the document shown
 * ([controller]). The same instance as long as the document is shown, so that an extension can
 * remember what it makes of it.
 */
public class EditorContext(public val controller: DrawViewController) {
    /**
     * The document shown, by its identifier (the same on every device): what an extension keeps
     * beside the documents refers to it.
     */
    public val documentId: String get() = controller.document.id
}
