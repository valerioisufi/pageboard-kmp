package io.github.valerioisufi.drawview.pdf

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeImageBitmap
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.measureTime
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/**
 * The cost of a tile of a PDF page (see `docs/plans/performance.md`, phase 8): the tile drawn by the
 * adapter of the tiles, which loads the page for every tile; the same with the pixel at its
 * center only (the page loaded and what is under the pixel prepared, little drawn); the copies of
 * its pixels on the desktop. The tiles measured are those whose center is on the content of the
 * slide.
 *
 * Three slides of 16:9, written by [PdfWriter]: text (a title and bullets in an embedded font),
 * a photograph (a JPEG of 2000 x 1500), a dense diagram (20,000 segments and 500 circles).
 */
class PdfTileBenchmark {

    @Test
    fun aTileOfAPdfPage() {
        val adapter = assertNotNull(PdfiumEngine.createTileAdapter())
        // The slides, and where their content is (points)
        val slides = mapOf(
            "text" to (textSlide() to Rect(48f, 40f, 900f, 500f)),
            "photograph" to (photoSlide() to Rect(200f, 100f, 760f, 520f)),
            "diagram" to (diagramSlide() to Rect(40f, 40f, 920f, 500f))
        )
        val bitmap = ImageBitmap(TILE, TILE)
        val canvas = Canvas(bitmap)
        for ((name, slide) in slides) {
            val (file, content) = slide
            for (lod in 0..2) {
                // The page as wide as the screen at level 0 (a slide in the editor), twice at each level
                val width = PAGE_WIDTH_PX * (1 shl lod)
                val page = Rect(0f, 0f, width, width * SLIDE_HEIGHT / SLIDE_WIDTH)
                val scale = width / SLIDE_WIDTH
                val tiles = tilesOf(page).filter { content.contains(Offset(it.center.x / scale, it.center.y / scale)) }
                fun draw(clip: (Rect) -> Rect) = tiles.map { tile ->
                    measureTime {
                        canvas.save()
                        canvas.translate(-tile.left, -tile.top)
                        adapter.renderPdfPage(canvas, file.absolutePath, 0, page, clip(tile))
                        canvas.restore()
                    }
                }
                repeat(2) { draw { it } }
                val whole = median((1..3).flatMap { draw { it } })
                val loaded = median((1..3).flatMap { draw { Rect(it.center.x, it.center.y, it.center.x + 1f, it.center.y + 1f) } })
                report("PDF a tile of the $name slide at level $lod (${tiles.size} tiles)", "$whole, its pixel at the center $loaded")
            }
        }
        report("PDF the copies of the pixels of a tile on the desktop", "${median(List(50) { measureTime { copyPixels() } })}")
        adapter.close()
    }

    /** The tiles covering [page], at the origin of the canvas each. */
    private fun tilesOf(page: Rect): List<Rect> {
        val columns = ceil(page.width / TILE).toInt()
        val rows = ceil(page.height / TILE).toInt()
        return (0 until rows).flatMap { row ->
            (0 until columns).map { column -> Rect(column * TILE.toFloat(), row * TILE.toFloat(), (column + 1) * TILE.toFloat(), (row + 1) * TILE.toFloat()) }
        }
    }

    /** What the bindings of the desktop do with the pixels of a tile: an `IntArray`, a `ByteBuffer`, a Skia bitmap, drawn. */
    private fun copyPixels() {
        val pixels = IntArray(TILE * TILE) { it }
        val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.nativeOrder())
        bytes.asIntBuffer().put(pixels)
        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo(TILE, TILE, ColorType.BGRA_8888, ColorAlphaType.PREMUL))
        bitmap.installPixels(bitmap.imageInfo, bytes.array(), TILE * 4)
        Canvas(ImageBitmap(TILE, TILE)).drawImage(bitmap.asComposeImageBitmap(), Offset.Zero, Paint())
    }

    /** A title and twelve bullets, in an embedded font. */
    private fun textSlide(): File = slide("text") { writer ->
        val font = assertNotNull(writer.loadFont(File(FONT).readBytes()))
        writer.addText(font, "Hash functions and their collisions", 36f, Matrix2D().postTranslate(48f, 80f))
        for (line in 0 until 12) {
            writer.addText(font, "• A point of the lecture, written long enough to fill the line $line", 20f, Matrix2D().postTranslate(64f, 140f + line * 30f))
        }
    }

    /** A title and a photograph over most of the slide. */
    private fun photoSlide(): File = slide("photograph") { writer ->
        val font = assertNotNull(writer.loadFont(File(FONT).readBytes()))
        writer.addText(font, "The experiment", 36f, Matrix2D().postTranslate(48f, 70f))
        writer.drawJpeg(photograph(2000, 1500), Matrix2D().postScale(560f, 420f).postTranslate(200f, 100f))
    }

    /** A chart of 200 lines of 100 points and 500 circles. */
    private fun diagramSlide(): File = slide("diagram") { writer ->
        val random = Random(8)
        for (line in 0 until 200) {
            val path = PdfPath()
            for (i in 0 until 100) {
                val x = 40f + i * 8.8f
                val y = 270f + 200f * sin(i / 9f + line) * cos(line / 7f) + random.nextFloat() * 10f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            writer.strokePath(path, 0xFF000000.toInt() or random.nextInt(0xFFFFFF), 0.8f)
        }
        repeat(500) {
            val circle = PdfPath().apply { addCircle(40f + random.nextFloat() * 880f, 40f + random.nextFloat() * 460f, 3f) }
            writer.fillPath(circle, 0xFF3366CC.toInt())
        }
    }

    private fun slide(name: String, draw: (PdfWriter) -> Unit): File {
        val file = File.createTempFile("slide-$name", ".pdf").apply { deleteOnExit() }
        PdfiumEngine.create().use { writer ->
            writer.beginPage(SLIDE_WIDTH, SLIDE_HEIGHT)
            draw(writer)
            writer.save(file.absolutePath)
        }
        return file
    }

    /** A JPEG of gradients and noise, which compresses as a photograph does. */
    private fun photograph(width: Int, height: Int): ByteArray {
        val random = Random(3)
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until height) for (x in 0 until width) {
            val r = (x * 255 / width + random.nextInt(40)).coerceAtMost(255)
            val g = (y * 255 / height + random.nextInt(40)).coerceAtMost(255)
            val b = (128 + 100 * sin(x / 40.0) * cos(y / 60.0)).toInt().coerceIn(0, 255)
            image.setRGB(x, y, Color(r, g, b).rgb)
        }
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun median(times: List<Duration>): Duration = times.sorted()[times.size / 2]

    private fun report(measure: String, value: String) = println("BENCHMARK $measure: $value")

    private companion object {
        const val TILE = 512
        const val SLIDE_WIDTH = 960f
        const val SLIDE_HEIGHT = 540f
        const val PAGE_WIDTH_PX = 1500f
        const val FONT = "../drawview-ui/src/commonMain/composeResources/font/noto_sans_regular.ttf"
    }
}
