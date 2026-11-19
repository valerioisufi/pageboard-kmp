package io.github.valerioisufi.drawview.canvas.tool

/**
 * Enumeration of primary user-facing interactive tools.
 */
public enum class Tool {
    INK_PEN,
    INK_HIGHLIGHTER,

    /** Draws lines, arrows and shapes of a box by a drag (see `ShapeInserter`). */
    SHAPES,

    /** Highlights, underlines or strikes out the text of the PDFs. */
    TEXT_MARKUP,
    ERASER,
    TEXT,
    LAZO,
    PAN
}

/**
 * Selection filtering mode for the Lasso tool.
 */
public enum class LassoMode {
    ALL,
    IMAGES_ONLY
}
