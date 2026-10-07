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
import io.github.valerioisufi.drawview.core.util.FractionalIndex
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
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

    /**
     * The lines or the dots drawn over [backgroundColor] on a page of [widthMm] x [heightMm], those
     * of [area] only (page millimeters; the whole page by default), or `null` for a solid color.
     */
    fun pattern(widthMm: Float, heightMm: Float, area: Rect = Rect(0f, 0f, widthMm, heightMm)): BackgroundPattern? {
        fun positions(spacingMm: Float, lengthMm: Float, from: Float, to: Float): FloatArray {
            if (spacingMm <= 0f) return FloatArray(0)
            val end = minOf(to, lengthMm)
            val positions = ArrayList<Float>()
            // Every spacing after the edge, from the one before the area (a line crossing its edge)
            var k = maxOf(floor(from / spacingMm).toInt(), 1)
            while (k * spacingMm < end) positions.add(k++ * spacingMm)
            return positions.toFloatArray()
        }
        val rows = { spacing: Float -> positions(spacing, heightMm, area.top, area.bottom) }
        val columns = { spacing: Float -> positions(spacing, widthMm, area.left, area.right) }
        return when (this) {
            is Solid -> null
            is Ruled -> BackgroundPattern(lineColor, spacingMm, thicknessMm, dots = false, rows(spacingMm), FloatArray(0), widthMm, heightMm)
            is Grid -> BackgroundPattern(lineColor, spacingMm, thicknessMm, dots = false, rows(spacingMm), columns(spacingMm), widthMm, heightMm)
            is Dotted -> BackgroundPattern(dotColor, spacingMm, dotRadiusMm, dots = true, rows(spacingMm), columns(spacingMm), widthMm, heightMm)
        }
    }

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
 * The pattern of a [PageBackground] on a page of [widthMm] x [heightMm], in page millimeters:
 * horizontal lines at [rows] and vertical lines at [columns] across the page, or ([dots]) a dot at
 * every row and column.
 *
 * @param spacingMm distance between two lines or two dots.
 * @param sizeMm thickness of the lines, or radius of the dots.
 */
class BackgroundPattern(
    val color: Int,
    val spacingMm: Float,
    val sizeMm: Float,
    val dots: Boolean,
    val rows: FloatArray,
    val columns: FloatArray,
    val widthMm: Float,
    val heightMm: Float
)

/**
 * An element of a page: a stroke, a text block, an image, a PDF page or a mark of its text.
 *
 * [id] identifies the element on every device (see [newElementId]); [order] is its stacking
 * order among the elements of its kind on its page. Each piece of an element is changed by the
 * most recent change of it on every device (last-writer-wins), whatever the order the changes
 * arrive in:
 * - its presence: [added] and [removed] are the stamps of the last addition and of the last
 *   removal (removed when [removed] is set: kept hidden on its page, see
 *   [DrawPage.removedElements]);
 * - its placement (page, order, position, size, rotation): [placed] is the stamp of its last
 *   change;
 * - its content (a text and its format, the style of a mark): [updated] is the stamp of its last
 *   change.
 */
sealed interface DrawElement {
    val id: String
    var order: ElementOrder
    var added: Stamp
    var removed: Stamp?
    var placed: Stamp
    var updated: Stamp
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
    transform: AffineTransform = AffineTransform.IDENTITY
) : DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO

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

    /**
     * Hidden from the static layers while another device edits or moves it: drawn over them
     * instead (see `DrawViewController.collaborators`).
     */
    @Volatile var isShownByCollaborator: Boolean = false

    /** Hidden from the static (tile) layers: selected, moved or edited here or on another device. */
    val isHidden: Boolean get() = isDragging || isShownByCollaborator

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
class DrawText(override val id: String = newElementId()) : RotatableItem, DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO

    /**
     * The changes of the content its content includes: two contents made apart are told apart
     * (the one lost becomes a copy, see `UpdateTexts`).
     */
    var contentVersions: Versions = Versions.NONE

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

    /**
     * Hidden from the static layers while another device edits or moves it: drawn over them
     * instead (see `DrawViewController.collaborators`).
     */
    @Volatile var isShownByCollaborator: Boolean = false

    /** Hidden from the static (tile) layers: selected, moved or edited here or on another device. */
    val isHidden: Boolean get() = isDragging || isShownByCollaborator

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
class DrawImage(override val id: String = newElementId()) : RotatableItem, DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO
    var resourceId: String = ""

    @Volatile override var x: Float = 0f
    @Volatile override var y: Float = 0f
    @Volatile override var width: Float = 0f
    @Volatile override var height: Float = 0f
    @Volatile override var rotation: Float = 0f

    /** Hidden from the static (tile) layers while selected or moved. */
    @Volatile var isDragging: Boolean = false

    /**
     * Hidden from the static layers while another device edits or moves it: drawn over them
     * instead (see `DrawViewController.collaborators`).
     */
    @Volatile var isShownByCollaborator: Boolean = false

    /** Hidden from the static (tile) layers: selected, moved or edited here or on another device. */
    val isHidden: Boolean get() = isDragging || isShownByCollaborator

    /** Decoded pixels (lazily loaded from the resource file). */
    @Volatile var bitmap: ImageBitmap? = null
}

/**
 * A page of an imported PDF document rendered as the page background (its text can be marked,
 * see [DrawTextMarkup]).
 *
 * [id] identifies it on every device (see [newElementId]).
 */
class DrawPdf(var pdfPageIndex: Int = 0, override val id: String = newElementId()) : DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO
    var resourceId: String = ""
}

/**
 * A file owned by a document (imported images and PDFs). [id] identifies the resource on every
 * device (see [newElementId]), [path] is the file on this device.
 */
class DrawResource(
    val id: String,
    val type: ResourceType,
    var path: String,
    /** The SHA-256 of the content of the file (see `Sha256`): the same content is kept once. */
    val hash: String = ""
) {
    enum class ResourceType { PDF, IMAGE, COLOR }
}

/**
 * Represents a single document page with physical dimensions and vector content.
 *
 * All the content coordinates are expressed in page millimeters, with the origin at the
 * top-left corner of the page.
 *
 * [id] identifies the page on every device (see [newElementId]); the pages of a document are
 * ordered by [orderKey] (see [FractionalIndex]), then by [id]. [added] and [removed] are the
 * stamps of the last changes of its presence (removed when [removed] is set, see
 * [DrawDocument.removedPages]), [orderVersion] and [formatVersion] of its position and of its
 * format: the most recent change wins on every device.
 */
class DrawPage(
    var index: Int = 0,
    widthMm: Float = 210f,
    heightMm: Float = 297f,
    background: PageBackground = PageBackground.Solid(),
    val id: String = newElementId(),
    var orderKey: String = ""
) {
    /** Key of the page in the render caches of this process (a number, cheaper than [id]). */
    val renderKey: Long = nextPageRenderKey()

    var added: Stamp = Stamp.ZERO
    var removed: Stamp? = null
    var orderVersion: Stamp = Stamp.ZERO
    var formatVersion: Stamp = Stamp.ZERO

    /** The changes of its content (elements added, changed or moved onto it; not removed). */
    var contentVersions: Versions = Versions.NONE

    /**
     * What of its content the removal [removed] had seen (`null`: all of it): the page stays when
     * something was added to it meanwhile on another device (see [isHidden]).
     */
    var removalSaw: Versions? = null

    /** Whether it is removed: by a removal that saw all of its content. */
    val isHidden: Boolean get() = removed != null && removalSaw?.covers(contentVersions) != false

    /**
     * The elements removed from the page: hidden, they keep receiving the changes (the changes of
     * another device made before it saw the removal), so that an element put back is the same on
     * every device.
     */
    val removedElements: MutableList<DrawElement> = mutableListOf()

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
    fun elements(): List<DrawElement> = readSnapshot { pdfs.toList() + images.toList() + strokes.toList() + texts.toList() + markups.toList() }

    /** The removed element [id] of this page (see [removedElements]), or `null`. */
    fun findRemoved(id: String): DrawElement? = removedElements.firstOrNull { it.id == id }

    /** The element [id] of this page, or `null`. */
    fun find(id: String): DrawElement? = readSnapshot {
        // Each list read once (its elements read one at a time through the snapshot cost much more)
        strokes.toList().firstOrNull { it.id == id } ?: texts.toList().firstOrNull { it.id == id }
            ?: images.toList().firstOrNull { it.id == id } ?: markups.toList().firstOrNull { it.id == id }
            ?: pdfs.toList().firstOrNull { it.id == id }
    }

    /**
     * Adds [element] among the elements of its kind, in its stacking order (after the elements
     * of the same order). An element without order (a page being built, outside of the changes)
     * gets the next order of the elements built so, below the ones of the changes.
     */
    fun add(element: DrawElement) {
        fun <T : DrawElement> SnapshotStateList<T>.insert(item: T) {
            if (item.order == ElementOrder.UNASSIGNED) {
                item.order = ElementOrder(Stamp.ZERO, count { it.order.stamp == Stamp.ZERO })
            }
            var position = size
            while (position > 0 && this[position - 1].order > item.order) position--
            add(position, item)
        }
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

    /**
     * The elements in the order they are painted, from the bottom: the PDF pages, the marks of
     * their text, the images, the texts and the strokes, each kind in its stacking order.
     */
    val paintOrder: List<DrawElement> get() = pdfs + markups + images + texts + strokes

    /**
     * A copy of this content whose elements are copies too: the elements of a page are changed in
     * place, this copy stays as it is (e.g. while a document is exported). The copies are shown,
     * none of them is selected or moved.
     */
    fun detached(): PageContentSnapshot = PageContentSnapshot(
        strokes = strokes.map { DrawStroke(it.id, it.baseMesh, it.baseBrush, it.transform) },
        texts = texts.map { text -> DrawText(text.id).also { it.copyFrom(text) } },
        images = images.map { image ->
            DrawImage(image.id).also {
                it.resourceId = image.resourceId
                it.x = image.x
                it.y = image.y
                it.width = image.width
                it.height = image.height
                it.rotation = image.rotation
                it.bitmap = image.bitmap
            }
        },
        pdfs = pdfs.map { pdf -> DrawPdf(pdf.pdfPageIndex, pdf.id).also { it.resourceId = pdf.resourceId } },
        background = background,
        widthMm = widthMm,
        heightMm = heightMm,
        markups = markups.map {
            DrawTextMarkup(it.id, it.resourceId, it.pdfPageIndex, it.pdfWidthPt, it.pdfHeightPt, it.startIndex, it.endIndex, it.text, it.rects, it.kind, it.color)
        }
    )
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

    /** The pages, ordered by [DrawPage.orderKey] (then by [DrawPage.id]). */
    val pages: SnapshotStateList<DrawPage> = mutableStateListOf()
    val resources: SnapshotStateList<DrawResource> = mutableStateListOf()

    /** Stamp of the last change of the default format and background. */
    var defaultsVersion: Stamp = Stamp.ZERO

    /** Stamp of the last change of the [title] (see `RenameDocument`). */
    var titleVersion: Stamp = Stamp.ZERO

    /**
     * The pages removed: hidden, they keep receiving the changes like the other pages (the
     * changes of another device made before it saw the removal), so that a page put back is the
     * same on every device.
     */
    val removedPages: MutableMap<String, DrawPage> = mutableMapOf()

    /**
     * Removes for good the pages and the elements hidden by a removal that [stable] says can be
     * forgotten (every device has it, and nothing made without seeing it is still on its way, see
     * `SyncEngine`): nothing can show them again.
     */
    fun purgeHidden(stable: (Stamp) -> Boolean): PurgedContent {
        val pages = removedPages.values.filter { page -> page.removed?.let(stable) == true }
        pages.forEach { removedPages.remove(it.id) }
        val elements = mutableListOf<Pair<DrawPage, DrawElement>>()
        for (page in this.pages + removedPages.values) {
            val gone = page.removedElements.filter { element -> element.removed?.let(stable) == true }
            if (gone.isEmpty()) continue
            page.removedElements.removeAll(gone)
            gone.forEach { elements.add(page to it) }
        }
        return PurgedContent(pages, elements)
    }

    init {
        if (addInitialPage) addPage()
    }

    /** Appends a new page of the given format (outside of the changes: building a document). */
    fun addPage(
        width: Float = defaultWidthMm,
        height: Float = defaultHeightMm,
        bg: PageBackground = defaultBackground
    ): DrawPage {
        val newPage = DrawPage(
            index = pages.size,
            widthMm = width,
            heightMm = height,
            background = bg,
            orderKey = FractionalIndex.between(pages.lastOrNull()?.orderKey, null)
        )
        pages.add(newPage)
        return newPage
    }

    /** Inserts [page] at the position of its [DrawPage.orderKey]. */
    fun insertPage(page: DrawPage) {
        var position = pages.size
        while (position > 0 && pageOrder.compare(pages[position - 1], page) > 0) position--
        pages.add(position, page)
        reindexPages()
    }

    /** Moves [page] to the position of its [DrawPage.orderKey]. */
    fun repositionPage(page: DrawPage) {
        if (!pages.remove(page)) return
        insertPage(page)
    }

    /** A key between the pages at [index] - 1 and [index] (inserting a page at [index]). */
    fun orderKeyAt(index: Int): String {
        val before = pages.getOrNull(index - 1)?.orderKey
        // Pages of equal keys (inserted at the same place on two devices): after all of them
        val after = pages.drop(index.coerceAtLeast(0)).firstOrNull { before == null || it.orderKey > before }?.orderKey
        return FractionalIndex.between(before, after)
    }

    /** The most recent stamp of the document (what its clock must stay after). */
    fun latestStamp(): Stamp = readSnapshot {
        val stamps = sequenceOf(defaultsVersion) +
            allPages().asSequence().flatMap { page ->
                sequenceOf(page.added, page.removed ?: Stamp.ZERO, page.orderVersion, page.formatVersion) +
                    // The changes that changed nothing are among the versions only
                    page.contentVersions.stamps.values + (page.elements() + page.removedElements).asSequence().flatMap {
                        sequenceOf(it.added, it.removed ?: Stamp.ZERO, it.placed, it.updated, it.order.stamp) +
                            ((it as? DrawText)?.contentVersions?.stamps?.values ?: emptyList())
                    }
            }
        stamps.maxOrNull() ?: Stamp.ZERO
    }

    /** Re-assigns the [DrawPage.index] of every page after a structural change. */
    fun reindexPages() {
        pages.forEachIndexed { i, p -> p.index = i }
    }

    fun pageById(id: String): DrawPage? = readSnapshot {
        pages.firstOrNull { it.id == id }
    }

    /** Every page, shown or removed (see [removedPages]). */
    fun allPages(): List<DrawPage> = readSnapshot { pages.toList() } + removedPages.values

    /** The page [id], shown or removed (see [removedPages]), or `null`. */
    fun anyPage(id: String): DrawPage? = pageById(id) ?: removedPages[id]

    /** Whether [page] is shown (not removed). */
    fun isShown(page: DrawPage): Boolean = page.id !in removedPages

    /**
     * The element [id] (not removed) and the page holding it, shown or removed, looked for in
     * [hint] first (an element can be moved to another page by another device), or `null`.
     */
    fun findElement(id: String, hint: DrawPage? = null): Pair<DrawPage, DrawElement>? {
        hint?.find(id)?.let { return hint to it }
        for (page in allPages()) {
            if (page === hint) continue
            page.find(id)?.let { return page to it }
        }
        return null
    }

    /** The removed element [id] and the page holding it (see [DrawPage.removedElements]), or `null`. */
    fun findRemovedElement(id: String): Pair<DrawPage, DrawElement>? {
        for (page in allPages()) page.findRemoved(id)?.let { return page to it }
        return null
    }

    fun findResource(resourceId: String): DrawResource? = readSnapshot {
        resources.firstOrNull { it.id == resourceId }
    }

    fun pageByRenderKey(renderKey: Long): DrawPage? = readSnapshot {
        pages.firstOrNull { it.renderKey == renderKey }
    }

    private companion object {
        val pageOrder: Comparator<DrawPage> = compareBy<DrawPage>({ it.orderKey }, { it.id })
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

/** The pages and the elements removed for good (see [DrawDocument.purgeHidden]), with the page of each element. */
class PurgedContent(val pages: List<DrawPage>, val elements: List<Pair<DrawPage, DrawElement>>) {
    val isEmpty: Boolean get() = pages.isEmpty() && elements.isEmpty()
}
