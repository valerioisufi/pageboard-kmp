package io.github.valerioisufi.drawview.canvas.render

import io.github.valerioisufi.drawview.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter

/**
 * The optional engines of the editors: what draws the pages of the PDFs and the formulas of the
 * texts. The application gives them (e.g. `PdfiumEngine::createTileAdapter` of `drawview-pdf`,
 * `LatexEngine.formulaRenderer` of `drawview-latex`), so that the modules drawing documents do not
 * depend on an engine; without them a PDF page is drawn blank and a formula as its source.
 *
 * @param pdfTiles creates the PDF adapter of a renderer (each one keeps the documents it opened
 *   and is closed with it), or returns `null` when the engine is not available.
 * @param formulas renders the formulas of the texts.
 */
class DrawViewEngines(
    val pdfTiles: () -> PdfTileAdapter? = { null },
    val formulas: FormulaRenderer? = null
) {
    companion object {
        /** No engine: the PDF pages and the formulas are not drawn. */
        val None: DrawViewEngines = DrawViewEngines()
    }
}
