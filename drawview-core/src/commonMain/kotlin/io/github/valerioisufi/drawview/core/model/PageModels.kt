package io.github.valerioisufi.drawview.core.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.geometry.Dimension
import io.github.valerioisufi.drawview.core.geometry.mm
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.atomic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Styling definition for page background rendering.
 */
@Serializable
sealed class PageBackground {
    abstract val backgroundColor: Int

    @Serializable
    @SerialName("Solid")
    data class Solid(
        override val backgroundColor: Int = 0xFFFFFFFF.toInt()
    ) : PageBackground()

    @Serializable
    @SerialName("Ruled")
    data class Ruled(
        override val backgroundColor: Int = 0xFFFFFFFF.toInt(),
        val lineColor: Int = 0x330000FF.toInt(),
        val spacingMm: Float = 8f,
        val thicknessMm: Float = 0.5f
    ) : PageBackground()

    @Serializable
    @SerialName("Grid")
    data class Grid(
        override val backgroundColor: Int = 0xFFFFFFFF.toInt(),
        val lineColor: Int = 0x330000FF.toInt(),
        val spacingMm: Float = 5f,
        val thicknessMm: Float = 0.5f
    ) : PageBackground()

    @Serializable
    @SerialName("Dotted")
    data class Dotted(
        override val backgroundColor: Int = 0xFFFFFFFF.toInt(),
        val dotColor: Int = 0x50000000.toInt(),
        val spacingMm: Float = 5f,
        val dotRadiusMm: Float = 0.5f
    ) : PageBackground()
}

/**
 * An element of a page: a stroke, a text block, an image, a PDF page or a mark of its text.
 *
 * [id] identifies the element on every device (see [newElementId]); [zIndex] is its stacking
 * order among the elements of its kind on its page.
 */
sealed interface DrawElement {
    val id: String
    var zIndex: Int
}

/**
 * A vector ink stroke placed on a page.
 *
 * The stroke keeps the geometry it was drawn with ([baseMesh], [baseBrush]) and its placement
 * on the page ([transform], absolute): [mesh], [brushConfig] and [bounds] are the transformed
 * geometry, in page millimeters, so that the content is independent from the window size, the
 * zoom level and the screen density.
 */
class DrawStroke(
    override val id: String = newElementId(),
    mesh: StrokeMesh,
    brushConfig: BrushConfig,
    transform: AffineTransform = AffineTransform.IDENTITY,
    override var zIndex: Int = 0
) : DrawElement {
    /** Geometry of the stroke as it was drawn. */
    val baseMesh: StrokeMesh = mesh

    /** Brush of the stroke as it was drawn. */
    val baseBrush: BrushConfig = brushConfig

    /** Placement of [baseMesh] on the page. */
    @Volatile
    var transform: AffineTransform = transform
        private set

    @Volatile
    var mesh: StrokeMesh = mesh
        private set

    @Volatile
    var brushConfig: BrushConfig = brushConfig
        private set

    @Volatile
    var bounds: Rect = mesh.computeBounds()
        private set

    /** Hidden from the static (tile) layers while the stroke is selected or moved. */
    @Volatile
    var isDragging: Boolean = false

    init {
        if (!transform.isIdentity) place(transform)
    }

    /** Places the stroke on its page with [transform] (absolute, applied to the [baseMesh]). */
    fun place(transform: AffineTransform) {
        val matrix = transform.toMatrix()
        val scale = matrix.scaleFactor
        val placed = if (transform.isIdentity) baseMesh else baseMesh.transformed(matrix)
        this.transform = transform
        mesh = placed
        brushConfig = if (transform.isIdentity) baseBrush else baseBrush.copy(size = baseBrush.size * scale, epsilon = baseBrush.epsilon * scale)
        bounds = placed.computeBounds()
    }
}

/**
 * An element of a page placed with a (possibly rotated) rectangle in page millimeters.
 */
interface RotatableItem {
    var x: Float
    var y: Float
    var width: Float
    var height: Float

    /** Rotation in degrees around the center of the item. */
    var rotation: Float

    val centerX: Float get() = x + width / 2f
    val centerY: Float get() = y + height / 2f

    /**
     * Axis-aligned bounding box in page millimeters, taking the rotation into account.
     */
    fun getRotatedBounds(): Rect {
        if (rotation == 0f) return Rect(x, y, x + width, y + height)
        val rad = rotation * PI.toFloat() / 180f
        val c = abs(cos(rad))
        val s = abs(sin(rad))
        val halfW = (width / 2f) * c + (height / 2f) * s
        val halfH = (width / 2f) * s + (height / 2f) * c
        return Rect(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
    }

    /**
     * Whether the point ([px], [py]) in page millimeters falls inside the rotated rectangle.
     */
    fun containsPoint(px: Float, py: Float): Boolean {
        if (rotation == 0f) {
            return px >= x && px <= x + width && py >= y && py <= y + height
        }
        val rad = rotation * PI.toFloat() / 180f
        val c = cos(rad)
        val s = sin(rad)
        val dx = px - centerX
        val dy = py - centerY
        val localX = dx * c + dy * s
        val localY = -dx * s + dy * c
        return abs(localX) <= width / 2f && abs(localY) <= height / 2f
    }
}

/**
 * A text block (plain text, Markdown or a LaTeX formula) placed on a page.
 *
 * [id] identifies the block on every device (see [newElementId]).
 */
class DrawText(override var zIndex: Int = 0, override val id: String = newElementId()) : RotatableItem, DrawElement {

    @Volatile var text: String = ""
    @Volatile var isLatex: Boolean = false
    @Volatile var isMarkdownEnabled: Boolean = true

    /** 0 = start, 1 = center, 2 = end. */
    @Volatile var alignment: Int = 0

    @Volatile override var x: Float = 0f
    @Volatile override var y: Float = 0f
    @Volatile override var width: Float = 50f
    @Volatile override var height: Float = 20f
    @Volatile override var rotation: Float = 0f

    /** ARGB color. */
    @Volatile var color: Int = 0xFF000000.toInt()

    /** Font size in typographic points. */
    @Volatile var fontSize: Float = 16f

    /** Hidden from the static (tile) layers while selected or edited. */
    @Volatile var isDragging: Boolean = false

    /** Incremented on every change, used to invalidate the render caches. */
    @Volatile var version: Int = 0
        private set

    fun markDirty() {
        version++
    }

    fun copyFrom(other: DrawText) {
        text = other.text
        isLatex = other.isLatex
        isMarkdownEnabled = other.isMarkdownEnabled
        alignment = other.alignment
        x = other.x
        y = other.y
        width = other.width
        height = other.height
        rotation = other.rotation
        color = other.color
        fontSize = other.fontSize
        markDirty()
    }
}

/**
 * A raster image placed on a page. The pixels live in the document [DrawResource] referenced
 * by [resourceId].
 *
 * [id] identifies the image on every device (see [newElementId]).
 */
class DrawImage(override var zIndex: Int = 0, override val id: String = newElementId()) : RotatableItem, DrawElement {
    var resourceId: String = ""

    @Volatile override var x: Float = 0f
    @Volatile override var y: Float = 0f
    @Volatile override var width: Float = 0f
    @Volatile override var height: Float = 0f
    @Volatile override var rotation: Float = 0f

    /** Hidden from the static (tile) layers while selected or moved. */
    @Volatile var isDragging: Boolean = false

    /** Decoded pixels (lazily loaded from the resource file). */
    @Volatile var bitmap: ImageBitmap? = null
}

/**
 * A page of an imported PDF document rendered as the page background (its text can be marked,
 * see [DrawTextMarkup]).
 *
 * [id] identifies it on every device (see [newElementId]).
 */
class DrawPdf(override var zIndex: Int = 0, var pdfPageIndex: Int = 0, override val id: String = newElementId()) : DrawElement {
    var resourceId: String = ""
}

/**
 * A file owned by a document (imported images and PDFs). [id] identifies the resource on every
 * device (see [newElementId]), [path] is the file on this device.
 */
class DrawResource(
    val id: String,
    val type: ResourceType,
    var path: String
) {
    enum class ResourceType { PDF, IMAGE, COLOR }
}

/**
 * Represents a single document page with physical dimensions and vector content.
 *
 * All the content coordinates are expressed in page millimeters, with the origin at the
 * top-left corner of the page.
 *
 * [id] identifies the page on every device (see [newElementId]).
 */
class DrawPage(
    var index: Int = 0,
    widthMm: Float = 210f,
    heightMm: Float = 297f,
    background: PageBackground = PageBackground.Solid(),
    val id: String = newElementId()
) {
    /** Key of the page in the render caches of this process (a number, cheaper than [id]). */
    val renderKey: Long = nextPageRenderKey()

    var widthMm: Float by mutableFloatStateOf(widthMm)
    var heightMm: Float by mutableFloatStateOf(heightMm)
    var background: PageBackground by mutableStateOf(background)

    /**
     * Whether [background] has been customized for this page. Pages without a custom background
     * follow the default background of the document.
     */
    var hasCustomBackground: Boolean = false

    val strokes: SnapshotStateList<DrawStroke> = mutableStateListOf()
    val texts: SnapshotStateList<DrawText> = mutableStateListOf()
    val images: SnapshotStateList<DrawImage> = mutableStateListOf()
    val pdfs: SnapshotStateList<DrawPdf> = mutableStateListOf()

    /** Highlights, underlines and strikeouts of the text of [pdfs]. */
    val markups: SnapshotStateList<DrawTextMarkup> = mutableStateListOf()

    /** Incremented on every content change (used by thumbnails and caches). */
    var contentVersion: Int by mutableIntStateOf(0)

    val dimension: Dimension
        get() = Dimension(widthMm.mm, heightMm.mm)

    fun rect(): Rect = Rect(0f, 0f, widthMm, heightMm)

    fun markContentChanged() {
        contentVersion++
    }

    // ==========================================================
    // Elements (the changes of the document go through these)
    // ==========================================================

    /** Every element of the page: PDF pages, images, strokes, texts and marks, each kind in order. */
    fun elements(): List<DrawElement> = readSnapshot { pdfs + images + strokes + texts + markups }

    /** The element [id] of this page, or `null`. */
    fun find(id: String): DrawElement? = readSnapshot {
        strokes.firstOrNull { it.id == id } ?: texts.firstOrNull { it.id == id } ?: images.firstOrNull { it.id == id }
            ?: markups.firstOrNull { it.id == id } ?: pdfs.firstOrNull { it.id == id }
    }

    /** Adds [element] at [position] of the elements of its kind (at the end when out of range). */
    fun add(element: DrawElement, position: Int = -1) {
        fun <T : DrawElement> SnapshotStateList<T>.insert(item: T) =
            if (position in 0..size) add(position, item) else add(item)
        when (element) {
            is DrawStroke -> strokes.insert(element)
            is DrawText -> texts.insert(element)
            is DrawImage -> images.insert(element)
            is DrawPdf -> pdfs.insert(element)
            is DrawTextMarkup -> markups.insert(element)
        }
    }

    /** Removes [element]; returns its position among the elements of its kind, or -1. */
    fun remove(element: DrawElement): Int {
        fun <T : DrawElement> SnapshotStateList<T>.delete(item: T): Int = indexOf(item).also { if (it >= 0) removeAt(it) }
        return when (element) {
            is DrawStroke -> strokes.delete(element)
            is DrawText -> texts.delete(element)
            is DrawImage -> images.delete(element)
            is DrawPdf -> pdfs.delete(element)
            is DrawTextMarkup -> markups.delete(element)
        }
    }

    /**
     * Area of [element] in page millimeters (what must be drawn again when it changes), or `null`
     * for the whole page.
     */
    fun boundsOf(element: DrawElement): Rect? = when (element) {
        is DrawStroke -> element.bounds
        is DrawText -> element.getRotatedBounds()
        is DrawImage -> element.getRotatedBounds()
        is DrawPdf -> null
        is DrawTextMarkup -> element.boundsMm(this)
    }

    /**
     * Captures a consistent, immutable copy of the page content. Safe to call from background
     * threads (tile rasterization) while the UI thread keeps editing the page.
     */
    fun snapshotContent(): PageContentSnapshot = readSnapshot {
        PageContentSnapshot(
            strokes = strokes.toList(),
            texts = texts.toList(),
            images = images.toList(),
            pdfs = pdfs.toList(),
            background = background,
            widthMm = widthMm,
            heightMm = heightMm,
            markups = markups.toList()
        )
    }
}

/**
 * Immutable view of the content of a [DrawPage].
 */
class PageContentSnapshot(
    val strokes: List<DrawStroke>,
    val texts: List<DrawText>,
    val images: List<DrawImage>,
    val pdfs: List<DrawPdf>,
    val background: PageBackground,
    val widthMm: Float,
    val heightMm: Float,
    val markups: List<DrawTextMarkup> = emptyList()
) {
    fun rect(): Rect = Rect(0f, 0f, widthMm, heightMm)
}

/**
 * Represents a multi-page vector document.
 *
 * [id] identifies the document on every device (see [newElementId]).
 */
class DrawDocument(
    val id: String = newElementId(),
    title: String = "Untitled",
    defaultWidthMm: Float = 210f,
    defaultHeightMm: Float = 297f,
    defaultBackground: PageBackground = PageBackground.Solid(),
    var createdAt: Long = 0L,
    modifiedAt: Long = 0L,
    addInitialPage: Boolean = true
) {
    var title: String by mutableStateOf(title)

    var name: String
        get() = title
        set(value) {
            title = value
        }

    var modifiedAt: Long by mutableLongStateOf(modifiedAt)
    var lastOpenedAt: Long? = null

    var defaultWidthMm: Float by mutableStateOf(defaultWidthMm)
    var defaultHeightMm: Float by mutableStateOf(defaultHeightMm)
    var defaultBackground: PageBackground by mutableStateOf(defaultBackground)

    val pages: SnapshotStateList<DrawPage> = mutableStateListOf()
    val resources: SnapshotStateList<DrawResource> = mutableStateListOf()

    init {
        if (addInitialPage) {
            pages.add(
                DrawPage(
                    index = 0,
                    widthMm = defaultWidthMm,
                    heightMm = defaultHeightMm,
                    background = defaultBackground
                )
            )
        }
    }

    fun addPage(
        width: Float = defaultWidthMm,
        height: Float = defaultHeightMm,
        bg: PageBackground = defaultBackground
    ): DrawPage {
        val newPage = DrawPage(
            index = pages.size,
            widthMm = width,
            heightMm = height,
            background = bg
        )
        pages.add(newPage)
        return newPage
    }

    /** Re-assigns the [DrawPage.index] of every page after a structural change. */
    fun reindexPages() {
        pages.forEachIndexed { i, p -> p.index = i }
    }

    fun pageById(id: String): DrawPage? = readSnapshot {
        pages.firstOrNull { it.id == id }
    }

    fun findResource(resourceId: String): DrawResource? = readSnapshot {
        resources.firstOrNull { it.id == resourceId }
    }

    fun pageByRenderKey(renderKey: Long): DrawPage? = readSnapshot {
        pages.firstOrNull { it.renderKey == renderKey }
    }
}

/**
 * Runs [block] in a read-only snapshot, giving a consistent view of snapshot state objects even
 * when they are concurrently modified by the UI thread.
 */
inline fun <T> readSnapshot(block: () -> T): T {
    val snapshot = Snapshot.takeSnapshot()
    try {
        return snapshot.enter(block)
    } finally {
        snapshot.dispose()
    }
}

private val pageRenderKeys = atomic(0L)

private fun nextPageRenderKey(): Long = pageRenderKeys.incrementAndGet()

/**
 * A new identifier of a document, a page, an element or a resource: a random UUID, unique on
 * every device, so that the content created by several devices can be merged (see
 * `docs/collaboration.md`).
 */
@OptIn(ExperimentalUuidApi::class)
fun newElementId(): String = Uuid.random().toString()
