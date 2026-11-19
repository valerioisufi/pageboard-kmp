package io.github.valerioisufi.pageboard.export

import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.pageboard.canvas.TextMarkupPainter
import io.github.valerioisufi.pageboard.canvas.platform.decodeImageBitmap
import io.github.valerioisufi.pageboard.canvas.platform.exifOrientation
import io.github.valerioisufi.pageboard.canvas.platform.isJpeg
import io.github.valerioisufi.pageboard.canvas.platform.jpegOrientation
import io.github.valerioisufi.pageboard.canvas.render.ElementRenderers
import io.github.valerioisufi.pageboard.canvas.render.tile.FormulaRenderer
import io.github.valerioisufi.pageboard.canvas.render.tile.TextBlockLayout
import io.github.valerioisufi.pageboard.canvas.render.tile.TextBox
import io.github.valerioisufi.pageboard.canvas.render.tile.TextFace
import io.github.valerioisufi.pageboard.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.pageboard.canvas.render.tile.TextWord
import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.DrawImage
import io.github.valerioisufi.pageboard.model.DrawPdf
import io.github.valerioisufi.pageboard.model.DrawResource
import io.github.valerioisufi.pageboard.model.DrawStroke
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.DrawTextMarkup
import io.github.valerioisufi.pageboard.model.PageContentSnapshot
import io.github.valerioisufi.pageboard.model.StrokeOutline
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.geometry.Matrix2D
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.geometry.VectorDrawing
import io.github.valerioisufi.pageboard.model.pdfPointsToPageMm
import io.github.valerioisufi.pageboard.model.text.codePoints
import io.github.valerioisufi.pageboard.model.triangleOutline
import io.github.valerioisufi.pageboard.pdf.PdfFont
import io.github.valerioisufi.pageboard.pdf.PdfImportedPage
import io.github.valerioisufi.pageboard.pdf.PdfInfo
import io.github.valerioisufi.pageboard.pdf.PdfMarkupKind
import io.github.valerioisufi.pageboard.pdf.PdfPath
import io.github.valerioisufi.pageboard.pdf.PdfWriter
import io.github.valerioisufi.pageboard.pdf.PdfiumEngine
import io.github.valerioisufi.pageboard.pdf.TrueTypeFont
import io.github.valerioisufi.pageboard.platform.PlatformFiles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Writes the documents as PDF (see `docs/plans/export.md`), the same way on every platform: every page
 * a page of the same size, with its background, the pages of the PDFs it shows (their vectors and
 * text kept; when they cover it, the page is theirs, imported once per file with their links), the
 * marks of their text as annotations, its images, its texts as real text in the
 * fonts of the notes (a subset of them embedded) with their formulas as vectors, and its strokes as
 * filled outlines.
 *
 * @param textAdapter lays out and draws the texts (the one of the editor), or `null` to leave the
 *   texts out.
 * @param fontFiles the TrueType files of the fonts the adapter draws the texts with
 *   (`NotesFontFiles`), or `null`: the texts are then images of them.
 * @param creator the name of the application making the PDF, written in it with the title of
 *   the document.
 * @param elements draws the elements of the types of the application, written as images at
 *   300 dpi (placeholders for the types it does not know).
 * @param formulas the formula renderer of the editors (`DrawViewEngines.formulas`): the formulas
 *   are written as the vectors it draws ([FormulaRenderer.drawing]), else as images of them.
 * @param createWriter the PDF written.
 */
public class PdfExporter(
    private val textAdapter: TextTileAdapter?,
    private val fontFiles: (suspend (TextFace) -> ByteArray?)? = null,
    private val creator: String? = null,
    private val elements: ElementRenderers = ElementRenderers.None,
    private val formulas: FormulaRenderer? = null,
    private val createWriter: () -> PdfWriter = PdfiumEngine::create
) : DocumentExporter {
    override val extension: String get() = "pdf"
    override val mimeType: String get() = "application/pdf"

    override suspend fun export(content: DocumentContent, options: ExportOptions, path: String, progress: (Float) -> Unit): ExportResult =
        exportCombined(listOf(content), options, path, title = content.title, progress)

    /**
     * Writes [contents] sequentially into a single PDF document at [path], dated from the oldest
     * creation to the latest change of its contents.
     */
    public suspend fun exportCombined(
        contents: List<DocumentContent>,
        options: ExportOptions,
        path: String,
        title: String? = null,
        progress: (Float) -> Unit = {}
    ): ExportResult = withContext(Dispatchers.Default) {
        val totalPages = contents.sumOf { it.pages.size }
        val info = PdfInfo(
            title = title,
            creator = creator,
            createdAt = contents.mapNotNull { it.createdAt }.minOrNull(),
            modifiedAt = contents.mapNotNull { it.modifiedAt }.maxOrNull()
        )
        if (totalPages == 0) {
            createWriter().use { writer ->
                writer.save(path, info)
            }
            return@withContext ExportResult(0, 0)
        }
        // The texts laid out once, the characters of their words embedded once per face
        val layouts = HashMap<DrawText, TextBlockLayout?>()
        for (content in contents) {
            for (page in content.pages) for (text in page.texts) layouts[text] = textAdapter?.layout(text, page.background.backgroundColor)
        }
        val faces = loadFaces(layouts.values.filterNotNull())
        var skipped = 0
        var pagesWritten = 0
        createWriter().use { writer ->
            val texts = TextWriter(writer, layouts, embed(writer, faces, layouts.values.filterNotNull()), faces)
            for (content in contents) {
                val bases = importBases(writer, content)
                content.pages.forEachIndexed { index, page ->
                    ensureActive()
                    skipped += PageWriter(writer, content, page, texts, bases[index]).write(options)
                    pagesWritten++
                    progress(pagesWritten.toFloat() / totalPages)
                }
            }
            writer.save(path, info)
        }
        ExportResult(pagesWritten, skipped)
    }

    /** The fonts of the faces the texts use, and of the text of their formulas. */
    private suspend fun loadFaces(layouts: List<TextBlockLayout>): Map<TextFace, TrueTypeFont> {
        val files = fontFiles ?: return emptyMap()
        val used = layouts.flatMapTo(mutableSetOf()) { layout -> layout.words.map { it.face } }
        if (layouts.any { it.formulas.isNotEmpty() }) used += listOf(TextFace.REGULAR, TextFace.BOLD, TextFace.ITALIC, TextFace.BOLD_ITALIC)
        return used.mapNotNull { face ->
            val bytes = files(face) ?: return@mapNotNull null
            try {
                face to TrueTypeFont(bytes)
            } catch (_: IllegalArgumentException) {
                null
            }
        }.toMap()
    }

    /**
     * The pages of the PDFs the pages are written on, imported once per file (their fonts and
     * images written once): those a page shows under everything else and covering it at their
     * size (as the import of a PDF makes them), each once; `null` for the other pages.
     */
    private fun importBases(writer: PdfWriter, content: DocumentContent): List<BasePage?> {
        val shown = arrayOfNulls<DrawPdf>(content.pages.size)
        val pagesByFile = LinkedHashMap<String, MutableList<Int>>()
        val imported = HashSet<Pair<String, Int>>()
        content.pages.forEachIndexed { index, page ->
            val pdf = page.paintOrder.firstOrNull() as? DrawPdf ?: return@forEachIndexed
            val file = content.resourceFile(pdf.resourceId, DrawResource.ResourceType.PDF) ?: return@forEachIndexed
            val size = writer.pdfPageSize(file, pdf.pdfPageIndex) ?: return@forEachIndexed
            val covers = abs(size.widthPt.toFloat() - page.widthMm * PT_PER_MM) < SAME_SIZE_PT &&
                abs(size.heightPt.toFloat() - page.heightMm * PT_PER_MM) < SAME_SIZE_PT
            if (!covers || !imported.add(file to pdf.pdfPageIndex)) return@forEachIndexed
            shown[index] = pdf
            pagesByFile.getOrPut(file) { mutableListOf() } += index
        }
        val bases = arrayOfNulls<BasePage>(content.pages.size)
        for ((file, pages) in pagesByFile) {
            val base = writer.importPdfPages(file, pages.map { shown[it]!!.pdfPageIndex }) ?: continue
            pages.forEachIndexed { k, index -> bases[index] = BasePage(shown[index]!!, base[k]) }
        }
        return bases.toList()
    }

    /** A page written on [page], the page of a PDF [pdf] shows. */
    private class BasePage(val pdf: DrawPdf, val page: PdfImportedPage)

    /** Embeds in [writer] the subset of each face with the characters of the words it writes. */
    private fun embed(writer: PdfWriter, faces: Map<TextFace, TrueTypeFont>, layouts: List<TextBlockLayout>): Map<TextFace, PdfFont> {
        val characters = HashMap<TextFace, MutableSet<Int>>()
        for (layout in layouts) for (word in layout.words) {
            val font = faces[word.face] ?: continue
            val points = codePoints(word.printable)
            if (points.all(font::covers)) characters.getOrPut(word.face) { mutableSetOf() } += points
        }
        return characters.mapNotNull { (face, points) ->
            writer.loadFont(faces.getValue(face).subset(points))?.let { face to it }
        }.toMap()
    }

    /** Writes the texts, their words with [fonts], their formulas as vectors. */
    private inner class TextWriter(
        private val writer: PdfWriter,
        private val layouts: Map<DrawText, TextBlockLayout?>,
        private val fonts: Map<TextFace, PdfFont>,
        private val faces: Map<TextFace, TrueTypeFont>
    ) {
        private val formulaFonts = HashMap<String, TrueTypeFont?>()
        private val formulaPaths = FormulaPaths { font ->
            val file = font.file
            if (file == null) {
                faces[textFace(font.style)]
            } else {
                formulaFonts.getOrPut(file) {
                    PlatformFiles.readBytes(file)?.let { bytes ->
                        try {
                            TrueTypeFont(bytes)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    }
                }
            }
        }

        /** Writes [text] placed by [mmToPt] on its page of [paperColor]; returns whether it could. */
        fun write(text: DrawText, mmToPt: Matrix2D, paperColor: Int): Boolean {
            val layout = layouts[text] ?: return writeImage(text, mmToPt, paperColor)
            val toBlock = Matrix2D()
                .postRotate(text.rotation)
                .postTranslate(text.x, text.y)
                .postConcat(mmToPt)
            val raster = BlockRaster(text, toBlock, paperColor)
            // The backgrounds of the code, under its words
            for (box in layout.boxes) writer.fillPath(roundedRect(box, toBlock), box.color)
            for (word in layout.words) {
                val printable = word.printable
                if (printable.isBlank()) continue
                val font = fonts[word.face]
                val covered = font != null && codePoints(printable).all(font.metrics::covers)
                if (covered) {
                    val toWord = Matrix2D().postTranslate(word.left, word.baseline).postConcat(toBlock)
                    writer.addText(font!!, printable, word.fontSize, toWord, word.color, width = word.width)
                } else {
                    // The characters out of the font, as the editor draws them
                    raster.draw(Rect(word.left, word.lineTop, word.left + word.width, word.lineBottom))
                }
                if (word.strikethrough) strike(word, faces[word.face], toBlock)
                if (word.underline) underline(word, faces[word.face], toBlock)
            }
            for (formula in layout.formulas) {
                val drawing = formulas?.drawing(formula.latex, FORMULA_PX, formula.color)
                if (drawing == null || drawing.width <= 0) {
                    raster.draw(Rect(formula.left, formula.top, formula.left + formula.width, formula.top + formula.height))
                    continue
                }
                val scale = formula.width / drawing.width
                val toPage = Matrix2D().postScale(scale, scale).postTranslate(formula.left, formula.top).postConcat(toBlock)
                for (shape in formulaPaths.shapes(drawing, toPage)) {
                    if (shape.strokeWidth > 0f) writer.strokePath(shape.path, shape.argb, shape.strokeWidth) else writer.fillPath(shape.path, shape.argb)
                }
            }
            return true
        }

        /** The rectangle of [box] with its rounded corners, placed by [toBlock] (its curves are cubic). */
        private fun roundedRect(box: TextBox, toBlock: Matrix2D): PdfPath {
            val r = box.radius.coerceIn(0f, minOf(box.width, box.height) / 2f)
            val k = r * (1f - CIRCLE_KAPPA)
            val l = box.left
            val t = box.top
            val rt = box.left + box.width
            val b = box.top + box.height
            // From the top edge, clockwise: the points of the lines and of the curves at the corners
            val points = floatArrayOf(
                l + r, t, rt - r, t,
                rt - k, t, rt, t + k, rt, t + r,
                rt, b - r,
                rt, b - k, rt - k, b, rt - r, b,
                l + r, b,
                l + k, b, l, b - k, l, b - r,
                l, t + r,
                l, t + k, l + k, t, l + r, t
            )
            toBlock.mapPoints(points)
            fun x(i: Int) = points[2 * i]
            fun y(i: Int) = points[2 * i + 1]
            return PdfPath().apply {
                moveTo(x(0), y(0))
                lineTo(x(1), y(1))
                cubicTo(x(2), y(2), x(3), y(3), x(4), y(4))
                lineTo(x(5), y(5))
                cubicTo(x(6), y(6), x(7), y(7), x(8), y(8))
                lineTo(x(9), y(9))
                cubicTo(x(10), y(10), x(11), y(11), x(12), y(12))
                lineTo(x(13), y(13))
                cubicTo(x(14), y(14), x(15), y(15), x(16), y(16))
                close()
            }
        }

        /** The line through [word], where its font strikes out. */
        private fun strike(word: TextWord, font: TrueTypeFont?, toBlock: Matrix2D) {
            val (position, thickness) = font?.strikeout()?.let { (p, t) ->
                p.toFloat() / font.unitsPerEm to t.toFloat() / font.unitsPerEm
            } ?: (0.3f to 0.05f)
            lineOf(word, position, thickness, toBlock)
        }

        /** The line under [word], where its font underlines. */
        private fun underline(word: TextWord, font: TrueTypeFont?, toBlock: Matrix2D) {
            val (position, thickness) = font?.underline()?.takeIf { (_, t) -> t > 0 }?.let { (p, t) ->
                p.toFloat() / font.unitsPerEm to t.toFloat() / font.unitsPerEm
            } ?: (-0.1f to 0.05f)
            lineOf(word, position, thickness, toBlock)
        }

        private fun lineOf(word: TextWord, position: Float, thickness: Float, toBlock: Matrix2D) {
            val y = word.baseline - position * word.fontSize
            val half = thickness * word.fontSize / 2f
            val corners = floatArrayOf(word.left, y - half, word.left + word.width, y - half, word.left + word.width, y + half, word.left, y + half)
            toBlock.mapPoints(corners)
            writer.fillPath(PdfPath().apply { addPolygon(corners) }, word.color)
        }

        /** [text] as an image, the way the editor draws it (without a layout). */
        private fun writeImage(text: DrawText, mmToPt: Matrix2D, paperColor: Int): Boolean {
            val adapter = textAdapter ?: return true
            if (text.width <= 0f || text.text.isBlank()) return true
            val toBlock = Matrix2D()
                .postRotate(text.rotation)
                .postTranslate(text.x, text.y)
                .postConcat(mmToPt)
            val height = adapter.measure(text)?.height ?: text.height
            return BlockRaster(text, toBlock, paperColor).draw(Rect(0f, 0f, text.width, height))
        }

        /**
         * The image of [text] without its rotation at 300 dpi, drawn by the adapter when a part of
         * it is first needed, on the paper of [paperColor]; [toBlock] places the block.
         */
        private inner class BlockRaster(private val text: DrawText, private val toBlock: Matrix2D, private val paperColor: Int) {
            private var pixels: IntArray? = null
            private var width = 0
            private var height = 0
            private var scale = 1f

            /** Draws the part [area] of the block (millimeters of the block); returns whether it could. */
            fun draw(area: Rect): Boolean {
                val all = pixels ?: render() ?: return false
                val left = (area.left * scale).toInt().coerceIn(0, width)
                val top = (area.top * scale).toInt().coerceIn(0, height)
                val right = ceil(area.right * scale).toInt().coerceIn(left, width)
                val bottom = ceil(area.bottom * scale).toInt().coerceIn(top, height)
                val w = right - left
                val h = bottom - top
                if (w <= 0 || h <= 0) return true
                val part = IntArray(w * h) { all[(top + it / w) * width + left + it % w] }
                if (part.all { it ushr 24 == 0 }) return true
                val toPage = Matrix2D()
                    .postScale(w / scale, h / scale)
                    .postTranslate(left / scale, top / scale)
                    .postConcat(toBlock)
                return writer.drawImage(part, w, h, toPage)
            }

            private fun render(): IntArray? {
                val adapter = textAdapter ?: return null
                // As high as its lines are laid out
                val blockHeight = max(adapter.measure(text)?.height ?: text.height, 0.1f)
                scale = minOf(TEXT_PX_PER_MM, MAX_TEXT_SIDE / max(text.width, blockHeight))
                width = ceil(text.width * scale).toInt().coerceAtLeast(1)
                height = ceil(blockHeight * scale).toInt().coerceAtLeast(1)
                val bitmap = ImageBitmap(width, height)
                val canvas = Canvas(bitmap)
                canvas.drawRect(ComposeRect(0f, 0f, width.toFloat(), height.toFloat()), Paint().apply { blendMode = BlendMode.Clear })
                val toBitmap = Matrix2D()
                    .postTranslate(-text.x, -text.y)
                    .postRotate(-text.rotation)
                    .postScale(scale, scale)
                adapter.drawText(canvas, text, toBitmap, paperColor)
                return IntArray(width * height).also { bitmap.readPixels(it); pixels = it }
            }
        }
    }

    /** Writes [page] with [writer], on [base] when it is written on the page of a PDF. */
    private inner class PageWriter(
        private val writer: PdfWriter,
        private val content: DocumentContent,
        private val page: PageContentSnapshot,
        private val texts: TextWriter,
        private val base: BasePage?
    ) {
        private val mmToPt = Matrix2D().postScale(PT_PER_MM, PT_PER_MM)
        private var skipped = 0

        /** Whether something has been drawn the next PDF page covers (the editor draws them opaque, on white). */
        private var covering = false

        /** Returns the number of elements that could not be written. */
        fun write(options: ExportOptions): Int {
            if (base != null) {
                // The page of the PDF covers the page and its background
                writer.beginPage(base.page)
                covering = true
            } else {
                writer.beginPage(page.widthMm * PT_PER_MM, page.heightMm * PT_PER_MM)
                writeBackground(options.backgroundPattern)
            }
            for (element in page.paintOrder) {
                if (element === base?.pdf) continue
                val written = when (element) {
                    is DrawPdf -> writePdfPage(element)
                    is DrawTextMarkup -> writeMarkup(element)
                    is DrawImage -> writeImage(element)
                    is DrawCustomElement -> writeCustomElement(element)
                    is DrawText -> writeText(element)
                    is DrawStroke -> writeStroke(element)
                }
                if (!written) skipped++
            }
            return skipped
        }

        private fun writeBackground(withPattern: Boolean) {
            val background = page.background
            if (background.backgroundColor != WHITE) {
                writer.fillPath(PdfPath().apply { addPolygon(rect(0f, 0f, page.widthMm, page.heightMm)) }, background.backgroundColor)
                covering = true
            }
            if (!withPattern) return
            val pattern = background.pattern(page.widthMm, page.heightMm) ?: return
            covering = true
            val path = PdfPath()
            if (pattern.dots) {
                for (x in pattern.columns) for (y in pattern.rows) {
                    path.addCircle(x * PT_PER_MM, y * PT_PER_MM, pattern.sizeMm * PT_PER_MM)
                }
                writer.fillPath(path, pattern.color)
            } else {
                for (y in pattern.rows) {
                    path.moveTo(0f, y * PT_PER_MM)
                    path.lineTo(page.widthMm * PT_PER_MM, y * PT_PER_MM)
                }
                for (x in pattern.columns) {
                    path.moveTo(x * PT_PER_MM, 0f)
                    path.lineTo(x * PT_PER_MM, page.heightMm * PT_PER_MM)
                }
                writer.strokePath(path, pattern.color, pattern.sizeMm * PT_PER_MM)
            }
        }

        private fun writePdfPage(pdf: DrawPdf): Boolean {
            val file = content.resourceFile(pdf.resourceId, DrawResource.ResourceType.PDF) ?: return false
            val size = writer.pdfPageSize(file, pdf.pdfPageIndex) ?: return false
            val toPage = pdfPointsToPageMm(size.widthPt.toFloat(), size.heightPt.toFloat(), page.widthMm, page.heightMm)
            if (covering) {
                val area = toPage.mapRect(Rect(0f, 0f, size.widthPt.toFloat(), size.heightPt.toFloat()))
                writer.fillPath(PdfPath().apply { addPolygon(rect(area.left, area.top, area.right, area.bottom)) }, WHITE)
            }
            covering = true
            return writer.drawPdfPage(file, pdf.pdfPageIndex, toPage.postConcat(mmToPt))
        }

        private fun writeMarkup(markup: DrawTextMarkup): Boolean {
            val toPage = markup.pointsToPageMm(page.widthMm, page.heightMm).postConcat(mmToPt)
            val kind = when (markup.kind) {
                TextMarkupKind.HIGHLIGHT -> PdfMarkupKind.HIGHLIGHT
                TextMarkupKind.UNDERLINE -> PdfMarkupKind.UNDERLINE
                TextMarkupKind.STRIKEOUT -> PdfMarkupKind.STRIKEOUT
            }
            // A highlight covers its lines (as PDFium draws it), the other marks are drawn as by the editor
            val drawn = if (markup.kind == TextMarkupKind.HIGHLIGHT) {
                null
            } else {
                markup.rects.map { toPage.mapRect(TextMarkupPainter.markRect(it, markup.kind)) }
            }
            return writer.addMarkup(kind, markup.rects.map(toPage::mapRect), markup.color, drawn)
        }

        /**
         * [element] as the vectors its type gives, or drawn by its renderer at 300 dpi, without
         * its rotation, then placed.
         */
        private fun writeCustomElement(element: DrawCustomElement): Boolean {
            if (element.width <= 0f || element.height <= 0f) return true
            elements.vectorsOf(element)?.let { return writeVectors(it, element) }
            val scale = minOf(TEXT_PX_PER_MM, MAX_TEXT_SIDE / max(element.width, element.height))
            val width = ceil(element.width * scale).toInt().coerceAtLeast(1)
            val height = ceil(element.height * scale).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(width, height)
            val upright = element.copy().apply { x = 0f; y = 0f; rotation = 0f }
            elements.draw(Canvas(bitmap), upright, Matrix2D().postScale(scale, scale))
            val pixels = IntArray(width * height).also { bitmap.readPixels(it) }
            if (pixels.all { it ushr 24 == 0 }) return true
            return writer.drawImage(pixels, width, height, boxToPage(element.x, element.y, element.width, element.height, element.rotation))
        }

        /** [drawing] in the box of [element] (millimeters of the box), as paths of the PDF. */
        private fun writeVectors(drawing: VectorDrawing, element: DrawCustomElement): Boolean {
            val boxToPt = Matrix2D()
                .postRotate(element.rotation, element.width / 2f, element.height / 2f)
                .postTranslate(element.x, element.y)
                .postConcat(mmToPt)
            val scale = boxToPt.scaleFactor
            fun xy(points: List<Point>) = FloatArray(points.size * 2) { i -> if (i % 2 == 0) points[i / 2].x else points[i / 2].y }.also(boxToPt::mapPoints)
            var written = true
            for (fill in drawing.fills) {
                written = writer.fillPath(PdfPath().apply { addPolygon(xy(fill.points)) }, fill.argb) && written
            }
            for (line in drawing.lines) {
                val points = xy(line.points)
                if (points.size < 4) continue
                val path = PdfPath()
                path.moveTo(points[0], points[1])
                var i = 2
                while (i + 1 < points.size) {
                    path.lineTo(points[i], points[i + 1])
                    i += 2
                }
                if (line.closed) path.close()
                written = writer.strokePath(path, line.argb, line.widthMm * scale) && written
            }
            for (dot in drawing.dots) {
                val center = xy(listOf(dot.center))
                written = writer.fillPath(PdfPath().apply { addCircle(center[0], center[1], dot.radiusMm * scale) }, dot.argb) && written
            }
            return written
        }

        private fun writeImage(image: DrawImage): Boolean {
            if (image.width <= 0f || image.height <= 0f) return true
            val file = content.resourceFile(image.resourceId, DrawResource.ResourceType.IMAGE) ?: return false
            val bytes = PlatformFiles.readBytes(file) ?: return false
            val toPage = boxToPage(image.x, image.y, image.width, image.height, image.rotation)
            // A JPEG is embedded as it is, turned by its place as its Exif metadata say (as it is shown)
            if (isJpeg(bytes) && writer.drawJpeg(bytes, exifOrientation(jpegOrientation(bytes)).postConcat(toPage))) return true
            val bitmap = decodeImageBitmap(bytes, MAX_IMAGE_SIDE) ?: return false
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.readPixels(pixels)
            return writer.drawImage(pixels, bitmap.width, bitmap.height, toPage)
        }

        private fun writeText(text: DrawText): Boolean = texts.write(text, mmToPt, page.background.backgroundColor)

        private fun writeStroke(stroke: DrawStroke): Boolean {
            val outline = outlineOf(stroke)
            val path = PdfPath()
            for (polygon in outline.polygons) {
                path.addPolygon(polygon.copyOf().also(mmToPt::mapPoints))
            }
            return path.isEmpty || writer.fillPath(path, stroke.mesh.color)
        }

        /**
         * From the unit square to the box ([x], [y], [width], [height]) of the page, turned by
         * [rotation] degrees around the center of the box of [boxWidth] x [boxHeight].
         */
        private fun boxToPage(
            x: Float,
            y: Float,
            width: Float,
            height: Float,
            rotation: Float,
            boxWidth: Float = width,
            boxHeight: Float = height
        ): Matrix2D = Matrix2D()
            .postScale(width, height)
            .postRotate(rotation, boxWidth / 2f, boxHeight / 2f)
            .postTranslate(x, y)
            .postConcat(mmToPt)
    }

    /** The file of the resource [id] of [type], or `null`. */
    private fun DocumentContent.resourceFile(id: String, type: DrawResource.ResourceType): String? =
        resources[id]?.takeIf { it.type == type && PlatformFiles.exists(it.path) }?.path

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        floatArrayOf(left, top, right, top, right, bottom, left, bottom).also { Matrix2D().postScale(PT_PER_MM, PT_PER_MM).mapPoints(it) }

    private companion object {
        const val PT_PER_MM = 72f / 25.4f

        /** Size the formulas are laid out at before being fitted in their box (any: they are vectors). */
        const val FORMULA_PX = 100f

        /** The face of the text of the formulas of [style] (bold 1, italic 2). */
        fun textFace(style: Int): TextFace = when (style and 3) {
            1 -> TextFace.BOLD
            2 -> TextFace.ITALIC
            3 -> TextFace.BOLD_ITALIC
            else -> TextFace.REGULAR
        }
        const val WHITE = 0xFFFFFFFF.toInt()

        /** Largest difference of the sizes of a page and of the PDF page covering it, in points. */
        const val SAME_SIZE_PT = 0.05f

        /** Resolution of the texts: 300 dpi. */
        const val TEXT_PX_PER_MM = 300f / 25.4f

        /** Largest side of the image of a text, in pixels. */
        const val MAX_TEXT_SIDE = 8192f

        /** Largest side of an image that is not a JPEG (written as pixels), in pixels. */
        const val MAX_IMAGE_SIDE = 4096

        /** Where the control points of a cubic curve drawing a quarter of a circle are on its tangents. */
        const val CIRCLE_KAPPA = 0.5522848f
    }
}

/** The text of the word without the characters that are not written (controls). */
private val TextWord.printable: String get() = text.filter { it >= ' ' }

/**
 * Outline of [stroke] on its page: the one of its mesh (from Google Ink), or the triangles of its
 * mesh when the modeller could not give it.
 */
private fun outlineOf(stroke: DrawStroke): StrokeOutline = stroke.mesh.outline ?: stroke.mesh.triangleOutline()
