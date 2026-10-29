package io.github.valerioisufi.drawview.canvas.render

import io.github.valerioisufi.drawview.canvas.controller.PageTopics
import io.github.valerioisufi.drawview.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.drawview.canvas.render.tile.PdfTileAdapter
import io.github.valerioisufi.drawview.model.text.code.BuiltInCodeHighlighter
import io.github.valerioisufi.drawview.model.text.code.CodeHighlighter

/**
 * What draws what the canvas does not draw by itself: the pages of the PDFs, the formulas of the
 * texts, the colors of their code and the elements of the types added by the application; and what
 * finds the pages a text is about. The application gives them (e.g. `PdfiumEngine::createTileAdapter`
 * of `drawview-pdf`, `LatexEngine.formulaRenderer` of `drawview-latex`, the renderers of its element
 * types, the search in the notes), so that
 * the modules drawing documents do not depend on an engine; without them a PDF page is drawn
 * blank, a formula as its source, code in one color and an element of an application as a
 * placeholder.
 *
 * @param pdfTiles creates the PDF adapter of a renderer (each one keeps the documents it opened
 *   and is closed with it), or returns `null` when the engine is not available.
 * @param formulas renders the formulas of the texts.
 * @param elements draws the elements of the types of the application.
 * @param code tells the tokens of the blocks of code of the texts, for their colors
 *   ([BuiltInCodeHighlighter] by default).
 * @param topics finds the pages a text is about (the search in the notes), to place a text next to
 *   them; without it a text goes where the student chooses.
 */
public class DrawViewEngines(
    public val pdfTiles: () -> PdfTileAdapter? = { null },
    public val formulas: FormulaRenderer? = null,
    public val elements: ElementRenderers = ElementRenderers.None,
    public val code: CodeHighlighter? = BuiltInCodeHighlighter,
    public val topics: PageTopics? = null
) {
    public companion object {
        /**
         * No engine: the PDF pages and the formulas are not drawn; the code has the colors of
         * [BuiltInCodeHighlighter], which needs none.
         */
        public val None: DrawViewEngines = DrawViewEngines()
    }
}
