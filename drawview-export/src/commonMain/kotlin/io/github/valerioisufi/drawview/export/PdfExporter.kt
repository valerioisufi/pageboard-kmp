package io.github.valerioisufi.drawview.export

import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.canvas.TextMarkupPainter
import io.github.valerioisufi.drawview.core.platform.decodeImageBitmap
import io.github.valerioisufi.drawview.core.platform.exifOrientation
import io.github.valerioisufi.drawview.core.platform.isJpeg
import io.github.valerioisufi.drawview.core.platform.jpegOrientation
import io.github.valerioisufi.drawview.core.render.tile.TextBlockLayout
import io.github.valerioisufi.drawview.core.render.tile.TextFace
import io.github.valerioisufi.drawview.core.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.core.render.tile.TextWord
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PageContentSnapshot
import io.github.valerioisufi.drawview.model.StrokeOutline
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.pdfPointsToPageMm
import io.github.valerioisufi.drawview.model.triangleOutline
import io.github.valerioisufi.drawview.pdf.PdfFont
import io.github.valerioisufi.drawview.pdf.PdfImportedPage
import io.github.valerioisufi.drawview.pdf.PdfInfo
import io.github.valerioisufi.drawview.pdf.PdfMarkupKind
import io.github.valerioisufi.drawview.pdf.PdfPath
import io.github.valerioisufi.drawview.pdf.PdfWriter
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.pdf.TrueTypeFont
import io.github.valerioisufi.drawview.platform.PlatformFiles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Writes the documents as PDF (see `docs/export.md`), the same way on every platform: every page
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
 * @param createWriter the PDF written.
 */
class PdfExporter(
    private val textAdapter: TextTileAdapter?,
    private val fontFiles: (suspend (TextFace) -> ByteArray?)? = null,
    private val creator: String? = null,
    private val createWriter: () -> PdfWriter = PdfiumEngine::create
) : DocumentExporter {
    override val extension: String get() = "pdf"
    override val mimeType: String get() = "application/pdf"

    override suspend fun export(content: DocumentContent, options: ExportOptions, path: String, progress: (Float) -> Unit): ExportResult =
        withContext(Dispatchers.Default) {
            // The texts laid out once, the characters of their words embedded once per face
            val layouts = HashMap<DrawText, TextBlockLayout?>()
            for (page in content.pages) for (text in page.texts) layouts[text] = textAdapter?.layout(text)
            val faces = loadFaces(layouts.values.filterNotNull())
            var skipped = 0
            createWriter().use { writer ->
                val texts = TextWriter(writer, layouts, embed(writer, faces, layouts.values.filterNotNull()), faces)
                val bases = importBases(writer, content)
                content.pages.forEachIndexed { index, page ->
                    ensureActive()
                    skipped += PageWriter(writer, content, page, texts, bases[index]).write(options)
                    progress((index + 1f) / content.pages.size)
                }
                writer.save(path, PdfInfo(title = content.title, creator = creator))
            }
            ExportResult(content.pages.size, skipped)
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
        private val texFonts = HashMap<String, TrueTypeFont?>()
        private val texPaths = TexPaths { font ->
            val file = font.file
            if (file == null) {
                faces[textFace(font.style)]
            } else {
                texFonts.getOrPut(file) {
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

        /** Writes [text] placed by [mmToPt] on its page; returns whether it could. */
        fun write(text: DrawText, mmToPt: Matrix2D): Boolean {
            val layout = layouts[text] ?: return writeImage(text, mmToPt)
            val toBlock = Matrix2D()
                .postRotate(text.rotation, text.width / 2f, layout.height / 2f)
                .postTranslate(text.x, text.y)
                .postConcat(mmToPt)
            val raster = BlockRaster(text, toBlock)
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
            }
            for (formula in layout.formulas) {
                val drawing = LatexEngine.drawing(formula.latex, FORMULA_PX, formula.color)
                if (drawing == null || drawing.width <= 0) {
                    raster.draw(Rect(formula.left, formula.top, formula.left + formula.width, formula.top + formula.height))
                    continue
                }
                val scale = formula.width / drawing.width
                val toPage = Matrix2D().postScale(scale, scale).postTranslate(formula.left, formula.top).postConcat(toBlock)
                for (shape in texPaths.shapes(drawing, toPage)) {
                    if (shape.strokeWidth > 0f) writer.strokePath(shape.path, shape.argb, shape.strokeWidth) else writer.fillPath(shape.path, shape.argb)
                }
            }
            return true
        }

        /** The line through [word], where its font strikes out. */
        private fun strike(word: TextWord, font: TrueTypeFont?, toBlock: Matrix2D) {
            val (position, thickness) = font?.strikeout()?.let { (p, t) ->
                p.toFloat() / font.unitsPerEm to t.toFloat() / font.unitsPerEm
            } ?: (0.3f to 0.05f)
            val y = word.baseline - position * word.fontSize
            val half = thickness * word.fontSize / 2f
            val corners = floatArrayOf(word.left, y - half, word.left + word.width, y - half, word.left + word.width, y + half, word.left, y + half)
            toBlock.mapPoints(corners)
            writer.fillPath(PdfPath().apply { addPolygon(corners) }, word.color)
        }

        /** [text] as an image, the way the editor draws it (without a layout). */
        private fun writeImage(text: DrawText, mmToPt: Matrix2D): Boolean {
            val adapter = textAdapter ?: return true
            if (text.width <= 0f || text.text.isBlank()) return true
            val toBlock = Matrix2D()
                .postRotate(text.rotation, text.width / 2f, text.height / 2f)
                .postTranslate(text.x, text.y)
                .postConcat(mmToPt)
            return BlockRaster(text, toBlock).draw(Rect(0f, 0f, text.width, text.height))
        }

        /**
         * The image of [text] without its rotation at 300 dpi, drawn by the adapter when a part of
         * it is first needed; [toBlock] places the block.
         */
        private inner class BlockRaster(private val text: DrawText, private val toBlock: Matrix2D) {
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
                val blockHeight = max(text.height, 0.1f)
                scale = minOf(TEXT_PX_PER_MM, MAX_TEXT_SIDE / max(text.width, blockHeight))
                width = ceil(text.width * scale).toInt().coerceAtLeast(1)
                height = ceil(blockHeight * scale).toInt().coerceAtLeast(1)
                val bitmap = ImageBitmap(width, height)
                val canvas = Canvas(bitmap)
                canvas.drawRect(ComposeRect(0f, 0f, width.toFloat(), height.toFloat()), Paint().apply { blendMode = BlendMode.Clear })
                val toBitmap = Matrix2D()
                    .postRotate(-text.rotation, text.x + text.width / 2f, text.y + text.height / 2f)
                    .postTranslate(-text.x, -text.y)
                    .postScale(scale, scale)
                adapter.drawText(canvas, text, toBitmap)
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

        private fun writeText(text: DrawText): Boolean = texts.write(text, mmToPt)

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
    }
}

/** The text of the word without the characters that are not written (controls). */
private val TextWord.printable: String get() = text.filter { it >= ' ' }

/**
 * Outline of [stroke] on its page: modeled again from its inputs (the outlines of Google Ink), or
 * the triangles of its mesh when the engine cannot give it.
 */
private fun outlineOf(stroke: DrawStroke): StrokeOutline {
    val base = stroke.baseMesh
    val modeled = InkStrokeEngine.buildOutline(base.points, stroke.baseBrush, base.inputToolType)
        ?: return stroke.mesh.triangleOutline()
    return if (stroke.transform.isIdentity) modeled else modeled.transformed(stroke.transform.toMatrix())
}
