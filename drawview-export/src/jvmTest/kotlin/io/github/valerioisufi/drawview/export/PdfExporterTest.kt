package io.github.valerioisufi.drawview.export

import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.platform.Font as PlatformFont
import io.github.valerioisufi.drawview.canvas.platform.decodeImageBitmap
import io.github.valerioisufi.drawview.canvas.render.tile.TextFace
import io.github.valerioisufi.drawview.canvas.render.tile.TileRenderer
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.latex.LatexEngine
import io.github.valerioisufi.drawview.latex.TexFont
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawImage
import io.github.valerioisufi.drawview.model.DrawPdf
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.DrawTextMarkup
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.pdf.PdfFont
import io.github.valerioisufi.drawview.pdf.PdfImportedPage
import io.github.valerioisufi.drawview.pdf.PdfInfo
import io.github.valerioisufi.drawview.pdf.PdfMarkupKind
import io.github.valerioisufi.drawview.pdf.PdfPath
import io.github.valerioisufi.drawview.pdf.PdfSize
import io.github.valerioisufi.drawview.pdf.PdfWriter
import io.github.valerioisufi.drawview.pdf.PdfiumEngine
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.ui.render.ComposeTextTileAdapter
import io.github.valerioisufi.drawview.ui.theme.NotesFonts
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

class PdfExporterTest {
    private val textAdapter = ComposeTextTileAdapter(createFontFamilyResolver(), NOTES_FONTS)
    private val fontFiles: suspend (TextFace) -> ByteArray? = { NOTES_FILES[it] }

    @Test
    fun aNotebookIsExportedAsItIsDrawn() = runBlocking {
        val document = notebook()
        val pdf = tempFile("notebook", ".pdf")
        val progress = mutableListOf<Float>()
        val result = PdfExporter(textAdapter, fontFiles, creator = "PencilNotes").export(DocumentContent.of(document), ExportOptions(), pdf.path) { progress += it }
        assertEquals(2, result.pages)
        assertEquals(0, result.skipped)
        assertEquals(listOf(0.5f, 1f), progress)

        PdfiumEngine.open(pdf.path).use { exported ->
            assertEquals(2, exported.pageCount)
            val a4 = exported.getPageSize(0)
            assertEquals(595.3, a4.widthPt, 0.5)
            assertEquals(841.9, a4.heightPt, 0.5)
            // The text written over the image of the block, without the Markdown marks
            assertTrue("Funzioni hash crittografiche" in exported.getPageText(0).orEmpty(), exported.getPageText(0))
            // The text of the PDF page
            assertEquals("Hash functions", exported.getPageText(1)?.trim())
        }
        val bytes = pdf.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertTrue("/Highlight" in bytes)
        assertTrue("/FontFile2" in bytes, "the text is written in an embedded font")
        assertTrue("/Title ${hex("Lezione 3: hash")}" in bytes && "/Creator ${hex("PencilNotes")}" in bytes, "its title and its application")

        val page = render(pdf, 0)
        // The stroke, the PNG, the JPEG, the text and the grid
        assertTrue(page.isDark(page.mm(20f + 15 * 3f, 40f + sin(3f) * 5f)), "the stroke is drawn")
        assertColor(0xFFFF0000.toInt(), page.at(page.mm(35f, 115f)))
        assertColor(0xFF0000FF.toInt(), page.at(page.mm(75f, 115f)), tolerance = 24)
        assertTrue(page.countDark(page.mm(20f, 60f), page.mm(140f, 72f)) > 50, "the text is drawn")
        assertTrue(page.at(page.mm(5f, 150f)) != WHITE, "the lines of the grid are drawn")

        // The highlight multiplies the colors of the PDF page
        val second = render(pdf, 1)
        val highlighted = second.at(second.pt(39f, 28f))
        assertTrue(highlighted != WHITE && (highlighted and 0xFF) < 0xC0, "highlighted: ${highlighted.toUInt().toString(16)}")
    }

    @Test
    fun thePatternOfTheBackgroundCanBeLeftOut() = runBlocking {
        val pdf = tempFile("plain", ".pdf")
        PdfExporter(textAdapter, fontFiles).export(DocumentContent.of(notebook()), ExportOptions(backgroundPattern = false), pdf.path)
        val page = render(pdf, 0)
        assertEquals(WHITE, page.at(page.mm(5f, 150f)))
    }

    @Test
    fun aPageIsExportedAsAnImageAsItIsDrawn() = runBlocking {
        val document = notebook()
        val png = tempFile("page", ".png")
        val content = DocumentContent.of(document, setOf(document.pages[0].id))
        val result = PageImageExporter(textAdapter, pixelsPerMm = 4f).export(content, ExportOptions(), png.path)
        assertEquals(1, result.pages)
        val image = Image.makeFromEncoded(png.readBytes())
        assertEquals(840, image.width)
        assertEquals(1188, image.height)
        val bitmap = Bitmap.makeFromImage(image)
        fun at(xMm: Float, yMm: Float) = bitmap.getColor((xMm * 4).roundToInt(), (yMm * 4).roundToInt())
        assertColor(0xFFFF0000.toInt(), at(35f, 115f))
        assertColor(0xFF0000FF.toInt(), at(75f, 115f), tolerance = 24)
        assertTrue(at(5f, 150f) != WHITE, "the lines of the grid are drawn")
    }

    @Test
    fun theTextIsTheEditorsAndItsFormulasAreVectors() = runBlocking {
        val document = DrawDocument(title = "Testo")
        val page = document.pages[0]
        page.add(DrawText().apply {
            text = "Hash αβγ **grassetto** _corsivo_ `codice` ~~barrato~~ [sito](https://example.com) e \$x^2+1\$ fine"
            x = 15f; y = 20f; width = 170f; height = 40f; fontSize = 24f
        })
        page.add(DrawText().apply {
            text = "\$\$\\int_0^1 x^2\\,dx = \\frac{1}{3}\$\$"
            x = 15f; y = 90f; width = 120f; height = 30f; fontSize = 24f
        })
        val pdf = tempFile("text", ".pdf")
        PdfExporter(textAdapter, fontFiles).export(DocumentContent.of(document), ExportOptions(), pdf.path)

        val bytes = pdf.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertTrue(!Regex("/Subtype\\s*/Image").containsMatchIn(bytes), "no picture: real text and vectors")
        PdfiumEngine.open(pdf.path).use { exported ->
            val read = exported.getPageText(0).orEmpty().replace(Regex("\\s+"), " ")
            assertTrue("Hash αβγ grassetto corsivo codice barrato sito" in read && "example.com" !in read, read)
        }

        // Drawn by the PDF as by the editor
        val exported = render(pdf, 0)
        val editor = renderEditor(document, exported.width, exported.height)
        for (area in listOf(Rect(15f, 20f, 185f, 60f), Rect(15f, 90f, 135f, 125f))) {
            val similarity = inkOverlap(exported, editor, area)
            assertTrue(similarity > 0.75f, "$area: overlap of the ink $similarity")
        }
    }

    @Test
    fun theCodeIsTextOnItsBackgroundAsTheEditorDrawsIt() = runBlocking {
        val document = DrawDocument(title = "Codice")
        document.pages[0].add(DrawText().apply {
            text = "```kotlin\nfun squares(n: Int) = (1..n).map { it * it }\n```\nand `inline` code"
            x = 15f; y = 20f; width = 170f; height = 40f; fontSize = 24f
        })
        val pdf = tempFile("code", ".pdf")
        PdfExporter(textAdapter, fontFiles).export(DocumentContent.of(document), ExportOptions(), pdf.path)

        val bytes = pdf.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertTrue(!Regex("/Subtype\\s*/Image").containsMatchIn(bytes), "no picture: real text and vectors")
        PdfiumEngine.open(pdf.path).use { exported ->
            val read = exported.getPageText(0).orEmpty().replace(Regex("\\s+"), " ")
            assertTrue("fun squares(n: Int)" in read && "inline" in read, read)
        }

        val exported = render(pdf, 0)
        val editor = renderEditor(document, exported.width, exported.height)
        // The top of the block, away from its words: its faint background
        val background = exported.mm(25f, 20.6f)
        assertColor(editor.at(background), exported.at(background), tolerance = 6)
        assertTrue((exported.at(background) and 0xFF) in 200..250, "a faint background, not the paper: ${(exported.at(background) and 0xFF)}")
        val similarity = inkOverlap(exported, editor, Rect(15f, 20f, 185f, 50f))
        assertTrue(similarity > 0.75f, "overlap of the ink $similarity")
    }

    @Test
    fun theCharactersOutOfTheFontAreImagesOfTheirWords() = runBlocking {
        val document = DrawDocument(title = "Emoji")
        document.pages[0].add(DrawText().apply { text = "ciao \uD83D\uDE00 mondo"; x = 15f; y = 20f; width = 170f; height = 20f; fontSize = 24f })
        val pdf = tempFile("emoji", ".pdf")
        PdfExporter(textAdapter, fontFiles).export(DocumentContent.of(document), ExportOptions(), pdf.path)
        val bytes = pdf.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertTrue(Regex("/Subtype\\s*/Image").containsMatchIn(bytes), "the word with the emoji is a picture")
        PdfiumEngine.open(pdf.path).use { exported ->
            val read = exported.getPageText(0).orEmpty()
            assertTrue("ciao" in read && "mondo" in read, read)
        }
    }

    @Test
    fun thePagesOfAPdfAreImportedOncePerFileWithTheirLinks() = runBlocking {
        // The pages of a PDF imported on a grid, a page showing the first one again
        val document = DrawDocument(title = "Slides", defaultBackground = PageBackground.Grid(lineColor = 0xFF0000FF.toInt()), addInitialPage = false)
        val pdf = resource(document, "pdf", DrawResource.ResourceType.PDF, slides())
        for (index in listOf(0, 1, 2, 0)) {
            document.addPage(width = 300f / PT_PER_MM, height = 200f / PT_PER_MM).add(DrawPdf(index).apply { resourceId = pdf })
        }
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 1.5f, color = Color.Black.toArgb(), epsilon = 0.05f)
        val points = (0..30).map { StrokePoint(10f + it * 2f, 10f, 0.6f, 16L * (it + 1)) }
        document.pages[1].add(DrawStroke(mesh = InkStrokeEngine.buildMesh(points, brush)!!.withPoints(points), brushConfig = brush))

        val exported = tempFile("slides", ".pdf")
        val result = PdfExporter(textAdapter, fontFiles).export(DocumentContent.of(document), ExportOptions(), exported.path)
        assertEquals(0, result.skipped)
        // The picture of the slides written twice: once for the pages imported, once for the page
        // showing the first one again (a copy of it)
        assertTrue(exported.length() < SLIDE_PICTURE_BYTES * 5 / 2, "${exported.length()} bytes")
        PdfiumEngine.open(exported.path).use { read ->
            assertEquals(4, read.pageCount)
            assertEquals(listOf("Slide 0", "Slide 1", "Slide 2", "Slide 0"), (0 until 4).map { read.getPageText(it)?.trim() })
        }
        val bytes = exported.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertEquals(1, Regex(Regex.escape(SLIDE_LINK)).findAll(bytes).count(), "the link of the first page")

        for (index in 0 until 4) {
            val page = render(exported, index)
            // The grid is under the page of the PDF, drawn opaque as in the editor
            val blank = (page.pt(0f, 60f).second until page.pt(0f, 140f).second).all { y ->
                (0 until page.pt(40f, 0f).first).all { x -> page.at(Pair(x, y)) == WHITE }
            }
            assertTrue(blank, "page $index: the grid is not seen")
        }
        assertTrue(render(exported, 1).isDark(render(exported, 1).mm(30f, 10f)), "the stroke is drawn over the page of the PDF")
    }

    @Test
    fun theMarksAreDrawnAsByTheEditor() = runBlocking {
        val document = DrawDocument(title = "Marks", addInitialPage = false)
        val pdf = resource(document, "pdf", DrawResource.ResourceType.PDF, SOURCE_PDF.encodeToByteArray())
        val page = document.addPage(width = 300f / PT_PER_MM, height = 200f / PT_PER_MM)
        page.add(DrawPdf(0).apply { resourceId = pdf })
        page.add(
            DrawTextMarkup(
                resourceId = pdf, pdfPageIndex = 0, pdfWidthPt = 300f, pdfHeightPt = 200f, startIndex = 0, endIndex = 4,
                text = "Hash", rects = listOf(Rect(150f, 100f, 250f, 130f)), kind = TextMarkupKind.STRIKEOUT, color = 0xFFFF0000.toInt()
            )
        )
        val exported = tempFile("marks", ".pdf")
        PdfExporter(textAdapter, fontFiles).export(DocumentContent.of(document), ExportOptions(), exported.path)
        val drawn = render(exported, 0)
        // Through the lowercase letters (58 % of the line), not through its middle
        assertColor(0xFFFF0000.toInt(), drawn.at(drawn.pt(200f, 117.4f)), tolerance = 8)
        assertEquals(WHITE, drawn.at(drawn.pt(200f, 115f)))
    }

    @Test
    fun aJpegTurnedByItsExifIsEmbeddedAsItIsAndTurnedByItsPlace() = runBlocking {
        // Stored 32 x 16: red, blue above green, yellow; shown turned a quarter clockwise
        val stored = Bitmap().apply {
            allocPixels(ImageInfo.makeN32(32, 16, ColorAlphaType.OPAQUE))
            for (y in 0 until 16) for (x in 0 until 32) {
                erase(QUADRANTS[(if (y < 8) 0 else 2) + if (x < 16) 0 else 1], IRect.makeXYWH(x, y, 1, 1))
            }
        }
        val jpeg = withOrientation(Image.makeFromBitmap(stored).encodeToData(EncodedImageFormat.JPEG, 100)!!.bytes, 6)
        // As the editor shows it
        val shown = decodeImageBitmap(jpeg, 64)!!
        assertEquals(16 to 32, shown.width to shown.height)
        val shownPixels = IntArray(16 * 32).also { shown.readPixels(it) }

        val document = DrawDocument(title = "Photo")
        val image = resource(document, "jpg", DrawResource.ResourceType.IMAGE, jpeg)
        document.pages[0].add(DrawImage().apply { resourceId = image; x = 20f; y = 20f; width = 20f; height = 40f })
        val exported = tempFile("photo", ".pdf")
        PdfExporter(textAdapter, fontFiles).export(DocumentContent.of(document), ExportOptions(), exported.path)

        val bytes = exported.readBytes().decodeToString(throwOnInvalidSequence = false)
        assertEquals(1, Regex("/Subtype\\s*/Image").findAll(bytes).count())
        assertTrue("/DCTDecode" in bytes, "the JPEG as it is")
        val page = render(exported, 0)
        for ((x, y) in listOf(4 to 8, 12 to 8, 4 to 24, 12 to 24)) {
            val expected = shownPixels[y * 16 + x] or (0xFF shl 24)
            assertColor(expected, page.at(page.mm(20f + x * 20f / 16f, 20f + y * 40f / 32f)), tolerance = 24)
        }
        // The stored top-left corner, red, is at the top-right corner
        assertColor(0xFFFF0000.toInt(), page.at(page.mm(35f, 30f)), tolerance = 24)
    }

    @Test
    fun theElementsAreWrittenInTheOrderTheyArePaintedAndTheMissingFilesAreSkipped() = runBlocking {
        val document = notebook()
        document.resources.first { it.type == DrawResource.ResourceType.IMAGE }.path = "/missing.png"
        val writer = RecordingWriter()
        val result = PdfExporter(textAdapter, fontFiles) { writer }.export(DocumentContent.of(document), ExportOptions(), "unused.pdf")
        assertEquals(1, result.skipped)
        // The fonts of the faces used (not loaded: the words are images of them), the PDF page
        // covering the second page; the images, the words, the strokes; the marks of the PDF page
        assertEquals(
            listOf(
                "font", "font", "import", "page", "pattern", "image", "text-image", "text-image", "text-image", "stroke-path",
                "page-on-pdf", "markup:HIGHLIGHT", "save"
            ),
            writer.calls
        )
    }

    @Test
    fun theExportOfADocumentCopiesItsPagesFromItsSession() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val documents = OpenDocuments(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope), Dispatchers.Default, scope)
        val document = notebook()
        documents.close(documents.insert(document, folderId = null))
        val export = DocumentExport(documents)

        val pdf = tempFile("second", ".pdf")
        val result = export.export(
            document.id,
            PdfExporter(textAdapter, fontFiles),
            ExportOptions(pageIds = setOf(document.pages[1].id)),
            pdf.path
        )
        assertEquals(1, result.pages)
        PdfiumEngine.open(pdf.path).use { assertEquals("Hash functions", it.getPageText(0)?.trim()) }
        assertEquals("Lezione 3_ hash.pdf", DocumentExport.fileName(document.title, PdfExporter(null)))
    }

    // ==========================================================

    /** A grid page with a stroke, a text, a PNG and a JPEG; a page showing a PDF with a highlight. */
    private fun notebook(): DrawDocument {
        val document = DrawDocument(title = "Lezione 3: hash", defaultBackground = PageBackground.Grid(lineColor = 0x660000FF))
        val page = document.pages[0]
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 1.5f, color = Color.Black.toArgb(), epsilon = 0.05f)
        val points = (0..30).map { StrokePoint(20f + it * 3f, 40f + sin(it / 5f) * 5f, 0.6f, 16L * (it + 1)) }
        page.add(DrawStroke(mesh = InkStrokeEngine.buildMesh(points, brush)!!.withPoints(points), brushConfig = brush))
        page.add(DrawText().apply {
            text = "Funzioni **hash** crittografiche"
            x = 20f; y = 60f; width = 120f; height = 12f; fontSize = 14f
        })
        val png = resource(document, "png", DrawResource.ResourceType.IMAGE, encoded(0xFFFF0000.toInt(), EncodedImageFormat.PNG))
        val jpeg = resource(document, "jpg", DrawResource.ResourceType.IMAGE, encoded(0xFF0000FF.toInt(), EncodedImageFormat.JPEG))
        page.add(DrawImage().apply { resourceId = png; x = 20f; y = 100f; width = 30f; height = 30f })
        page.add(DrawImage().apply { resourceId = jpeg; x = 60f; y = 100f; width = 30f; height = 30f })

        val second = document.addPage(width = 300f / PT_PER_MM, height = 200f / PT_PER_MM, bg = PageBackground.Solid())
        val pdf = resource(document, "pdf", DrawResource.ResourceType.PDF, SOURCE_PDF.encodeToByteArray())
        second.add(DrawPdf(0).apply { resourceId = pdf })
        second.add(
            DrawTextMarkup(
                resourceId = pdf, pdfPageIndex = 0, pdfWidthPt = 300f, pdfHeightPt = 200f, startIndex = 0, endIndex = 4,
                text = "Hash", rects = listOf(Rect(38f, 26f, 100f, 56f)), kind = TextMarkupKind.HIGHLIGHT, color = 0xFFFFF176.toInt()
            )
        )
        return document
    }

    /** A PDF of three 300 x 200 pages showing the same picture ([SLIDE_PICTURE_BYTES]), "Slide <n>"; the first with a link to [SLIDE_LINK]. */
    private fun slides(): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun write(text: String) = out.write(text.toByteArray(Charsets.ISO_8859_1))
        fun obj(body: String) {
            offsets += out.size()
            write("${offsets.size} 0 obj $body endobj\n")
        }
        write("%PDF-1.4\n")
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [6 0 R 8 0 R 10 0 R] /Count 3 >>")
        // Not compressible: written as it is
        val pixels = Random(7).nextBytes(SLIDE_PICTURE_BYTES)
        offsets += out.size()
        write("3 0 obj << /Type /XObject /Subtype /Image /Width 260 /Height 260 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Length ${pixels.size} >>\nstream\n")
        out.write(pixels)
        write("\nendstream endobj\n")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
        obj("<< /Type /Annot /Subtype /Link /Rect [40 140 200 170] /Border [0 0 0] /A << /S /URI /URI ($SLIDE_LINK) >> >>")
        for (index in 0 until 3) {
            val content = "q 100 0 0 100 150 40 cm /Im Do Q BT /F1 24 Tf 40 150 Td (Slide $index) Tj ET"
            val annots = if (index == 0) " /Annots [5 0 R]" else ""
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents ${7 + index * 2} 0 R /Resources << /XObject << /Im 3 0 R >> /Font << /F1 4 0 R >> >>$annots >>")
            obj("<< /Length ${content.length} >>\nstream\n$content\nendstream")
        }
        val xref = out.size()
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) write("${offset.toString().padStart(10, '0')} 00000 n \n")
        write("trailer << /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toByteArray()
    }

    /** [jpeg] with Exif metadata turning it by [orientation], after its JFIF header. */
    private fun withOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(), 0, 42, 0, 0, 0, 8,
            // One entry: the orientation, a short
            0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0,
            0, 0, 0, 0
        )
        val payload = "Exif".encodeToByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        // After the start of the image and its JFIF segment
        val at = if (jpeg[3] == 0xE0.toByte()) 4 + ((jpeg[4].toInt() and 0xFF) shl 8 or (jpeg[5].toInt() and 0xFF)) else 2
        return jpeg.copyOfRange(0, at) + segment + jpeg.copyOfRange(at, jpeg.size)
    }

    /** [text] as a string of PDF (UTF-16BE in hexadecimal). */
    private fun hex(text: String) = "<FEFF" + text.map { it.code.toString(16).uppercase().padStart(4, '0') }.joinToString("") + ">"

    private fun resource(document: DrawDocument, extension: String, type: DrawResource.ResourceType, bytes: ByteArray): String {
        val file = tempFile("resource", ".$extension").apply { writeBytes(bytes) }
        val resource = DrawResource(id = "$extension-${document.resources.size}", type = type, path = file.path)
        document.addResource(resource)
        return resource.id
    }

    private fun encoded(color: Int, format: EncodedImageFormat): ByteArray = Bitmap().run {
        allocPixels(ImageInfo.makeN32(16, 16, ColorAlphaType.OPAQUE))
        erase(color)
        Image.makeFromBitmap(this).encodeToData(format, 95)!!.bytes
    }

    private fun tempFile(prefix: String, suffix: String) = File.createTempFile(prefix, suffix).apply { deleteOnExit() }

    /** The first page of [document] as the editor draws it, [width] x [height] pixels. */
    private fun renderEditor(document: DrawDocument, width: Int, height: Int): Page {
        val content = document.pages[0].snapshotContent()
        val bitmap = ImageBitmap(width, height)
        val scale = width / content.widthMm
        TileRenderer(null, textAdapter).renderPageContent(
            ComposeCanvas(bitmap), document, content, Matrix2D().postScale(scale, scale), ComposeRect(0f, 0f, width.toFloat(), height.toFloat())
        )
        return Page(width, height, IntArray(width * height).also { bitmap.readPixels(it) })
    }

    /** How much the ink of [a] and [b] overlaps in [area] (millimeters): intersection over union. */
    private fun inkOverlap(a: Page, b: Page, area: Rect): Float {
        var both = 0
        var either = 0
        val (left, top) = a.mm(area.left, area.top)
        val (right, bottom) = a.mm(area.right, area.bottom)
        for (y in top until bottom) for (x in left until right) {
            // Dilated by a pixel: the edges of the glyphs are drawn a little differently
            val inkA = (-1..1).any { dy -> (-1..1).any { dx -> a.isInk(x + dx, y + dy) } }
            val inkB = (-1..1).any { dy -> (-1..1).any { dx -> b.isInk(x + dx, y + dy) } }
            if (a.isInk(x, y) || b.isInk(x, y)) either++
            if ((a.isInk(x, y) && inkB) || (b.isInk(x, y) && inkA)) both++
        }
        return if (either == 0) 0f else both.toFloat() / either
    }

    /** Page [index] of [pdf] at two pixels per point. */
    private fun render(pdf: File, index: Int): Page = PdfiumEngine.open(pdf.path).use { document ->
        val size = document.getPageSize(index)
        val width = (size.widthPt * 2).roundToInt()
        val height = (size.heightPt * 2).roundToInt()
        Page(width, height, IntArray(width * height).also { document.renderPage(index, width, height)!!.readPixels(it) })
    }

    private class Page(val width: Int, val height: Int, val pixels: IntArray) {
        fun mm(x: Float, y: Float) = Pair((x * PT_PER_MM * 2).roundToInt(), (y * PT_PER_MM * 2).roundToInt())
        fun pt(x: Float, y: Float) = Pair((x * 2).roundToInt(), (y * 2).roundToInt())
        fun at(p: Pair<Int, Int>) = pixels[p.second * width + p.first] or (0xFF shl 24)
        fun isInk(x: Int, y: Int) = x in 0 until width && y in 0 until height && brightness(pixels[y * width + x] or (0xFF shl 24)) < 140
        fun isDark(p: Pair<Int, Int>) = (-2..2).any { dy -> (-2..2).any { dx -> brightness(at(Pair(p.first + dx, p.second + dy))) < 200 } }
        fun countDark(from: Pair<Int, Int>, to: Pair<Int, Int>) =
            (from.second until to.second).sumOf { y -> (from.first until to.first).count { x -> brightness(at(Pair(x, y))) < 128 } }

        private fun brightness(color: Int) = ((color shr 16 and 0xFF) + (color shr 8 and 0xFF) + (color and 0xFF)) / 3
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 2) {
        val same = (0..2).all { abs((expected shr (it * 8) and 0xFF) - (actual shr (it * 8) and 0xFF)) <= tolerance }
        assertTrue(same, "expected ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}")
    }

    /** Records what is written. */
    private class RecordingWriter : PdfWriter {
        val calls = mutableListOf<String>()
        override fun beginPage(widthPt: Float, heightPt: Float) { calls += "page" }
        override fun beginPage(base: PdfImportedPage) { calls += "page-on-pdf" }
        override fun importPdfPages(sourcePath: String, pageIndices: List<Int>) =
            pageIndices.map { PdfImportedPage(300f, 200f) }.also { calls += "import" }
        override fun fillPath(path: PdfPath, argb: Int) = true.also { calls += if (argb == 0xFF000000.toInt()) "stroke-path" else "fill" }
        override fun strokePath(path: PdfPath, argb: Int, widthPt: Float) = true.also { calls += "pattern" }
        override fun drawPdfPage(sourcePath: String, pageIndex: Int, transform: Matrix2D) = true.also { calls += "pdf" }
        override fun pdfPageSize(sourcePath: String, pageIndex: Int) = PdfSize(300.0, 200.0)
        override fun drawJpeg(data: ByteArray, transform: Matrix2D) = true.also { calls += "image" }
        override fun drawImage(pixels: IntArray, width: Int, height: Int, transform: Matrix2D) =
            true.also { calls += if (width == 16) "image" else "text-image" }
        override fun addMarkup(kind: PdfMarkupKind, lines: List<Rect>, argb: Int, appearance: List<Rect>?) = true.also { calls += "markup:$kind" }
        override fun loadFont(data: ByteArray): PdfFont? = null.also { calls += "font" }
        override fun addText(font: PdfFont, text: String, fontSize: Float, transform: Matrix2D, argb: Int, width: Float, invisible: Boolean) =
            true.also { calls += "text" }
        override fun save(path: String, info: PdfInfo) { calls += "save" }
        override fun close() {}
    }

    private companion object {
        val NOTES_FILES = mapOf(
            TextFace.REGULAR to "noto_sans_regular",
            TextFace.ITALIC to "noto_sans_italic",
            TextFace.BOLD to "noto_sans_bold",
            TextFace.BOLD_ITALIC to "noto_sans_bold_italic",
            TextFace.MONO to "noto_sans_mono_regular",
            TextFace.MONO_BOLD to "noto_sans_mono_bold"
        ).mapValues { (_, name) -> File("../drawview-ui/src/commonMain/composeResources/font/$name.ttf").readBytes() }

        /** The fonts of the notes, as the editor draws them. */
        val NOTES_FONTS = NotesFonts(
            FontFamily(
                PlatformFont("noto-regular", NOTES_FILES.getValue(TextFace.REGULAR), FontWeight.Normal, FontStyle.Normal),
                PlatformFont("noto-italic", NOTES_FILES.getValue(TextFace.ITALIC), FontWeight.Normal, FontStyle.Italic),
                PlatformFont("noto-bold", NOTES_FILES.getValue(TextFace.BOLD), FontWeight.Bold, FontStyle.Normal),
                PlatformFont("noto-bold-italic", NOTES_FILES.getValue(TextFace.BOLD_ITALIC), FontWeight.Bold, FontStyle.Italic)
            ),
            FontFamily(
                PlatformFont("noto-mono", NOTES_FILES.getValue(TextFace.MONO), FontWeight.Normal),
                PlatformFont("noto-mono-bold", NOTES_FILES.getValue(TextFace.MONO_BOLD), FontWeight.Bold)
            )
        )

        init {
            runBlocking {
                LatexEngine.setTextFonts(
                    mapOf(
                        0 to NOTES_FILES.getValue(TextFace.REGULAR),
                        TexFont.BOLD to NOTES_FILES.getValue(TextFace.BOLD),
                        TexFont.ITALIC to NOTES_FILES.getValue(TextFace.ITALIC),
                        TexFont.BOLD or TexFont.ITALIC to NOTES_FILES.getValue(TextFace.BOLD_ITALIC)
                    )
                )
                LatexEngine.initialize()
            }
        }

        const val PT_PER_MM = 72f / 25.4f
        const val WHITE = 0xFFFFFFFF.toInt()

        /** A 300 x 200 page with a black square in its bottom-left corner and "Hash functions". */
        /** Red, blue, green, yellow. */
        val QUADRANTS = intArrayOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt())

        /** The picture of the [slides]: 260 x 260 RGB pixels. */
        const val SLIDE_PICTURE_BYTES = 260 * 260 * 3
        const val SLIDE_LINK = "https://example.com/slides"

        val SOURCE_PDF = """
            %PDF-1.4
            1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj
            2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj
            3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj
            4 0 obj << /Length 80 >>
            stream
            30 20 60 40 re f BT /F1 24 Tf 40 150 Td (Hash functions) Tj ET
            endstream
            endobj
            5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj
            trailer << /Root 1 0 R >>
            %%EOF
        """.trimIndent()
    }
}
