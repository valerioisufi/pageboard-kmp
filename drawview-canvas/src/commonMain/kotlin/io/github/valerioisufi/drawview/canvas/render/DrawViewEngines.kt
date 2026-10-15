package io.github.valerioisufi.drawview.canvas.render

import io.github.valerioisufi.drawview.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter

/**
 * What draws what the canvas does not draw by itself: the pages of the PDFs, the formulas of the
 * texts and the elements of the types added by the application. The application gives them (e.g.
 * `PdfiumEngine::createTileAdapter` of `drawview-pdf`, `LatexEngine.formulaRenderer` of
 * `drawview-latex`, the renderers of its element types), so that the modules drawing documents do
 * not depend on an engine; without them a PDF page is drawn blank, a formula as its source and an
 * element of an application as a placeholder.
 *
 * @param pdfTiles creates the PDF adapter of a renderer (each one keeps the documents it opened
 *   and is closed with it), or returns `null` when the engine is not available.
 * @param formulas renders the formulas of the texts.
 * @param elements draws the elements of the types of the application.
 */
public class DrawViewEngines(
    public val pdfTiles: () -> PdfTileAdapter? = { null },
    public val formulas: FormulaRenderer? = null,
    public val elements: ElementRenderers = ElementRenderers.None
) {
    public companion object {
        /** No engine: the PDF pages and the formulas are not drawn. */
        public val None: DrawViewEngines = DrawViewEngines()
    }
}
