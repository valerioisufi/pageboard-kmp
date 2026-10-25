package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.ElementOrder
import io.github.valerioisufi.drawview.model.FractionalIndex
import io.github.valerioisufi.drawview.model.KotlinStrokeModeller
import io.github.valerioisufi.drawview.model.PageBackground
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.StrokeModeller
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.Versions
import io.github.valerioisufi.drawview.model.change.Placement
import io.github.valerioisufi.drawview.model.change.RenameDocument
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.model.change.placement
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
import io.github.valerioisufi.drawview.model.geometry.Dimension
import io.github.valerioisufi.drawview.model.geometry.Point
import io.github.valerioisufi.drawview.model.geometry.Rect
import io.github.valerioisufi.drawview.model.geometry.mm
import io.github.valerioisufi.drawview.model.text.TextRoom
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An element of a page: a stroke, a text block, an image, a PDF page or a mark of its text.
 *
 * [id] identifies the element on every device (see [newElementId]); [order] is its position in
 * the stack of its page (see [DrawPage.stack]), an order written before the one stack read in
 * the layer of its kind (see [ElementOrder]). Each piece of an element is changed by the most
 * recent change of it on every device (last-writer-wins), whatever the order the changes arrive
 * in:
 * - its presence: [added] and [removed] are the stamps of the last addition and of the last
 *   removal (removed when [removed] is set: kept hidden on its page, see
 *   [DrawPage.removedElements]);
 * - its placement (page, position, size, rotation): [placed] is the stamp of its last change;
 * - its order: [ordered] is the stamp of its last change (apart from the placement, so that a
 *   move and a change of order made apart both stay);
 * - its content (a text and its format, the style of a mark): [updated] is the stamp of its last
 *   change.
 */
public sealed interface DrawElement {
    public val id: String
    public var order: ElementOrder
    public var added: Stamp
    public var removed: Stamp?
    public var placed: Stamp
    public var ordered: Stamp
    public var updated: Stamp
}

/**
 * A vector ink stroke placed on a page.
 *
 * The stroke keeps the geometry it was drawn with ([baseMesh], [baseBrush]) and its placement
 * on the page ([transform], absolute): [mesh], [brushConfig] and [bounds] are the transformed
 * geometry, in page millimeters, so that the content is independent from the window size, the
 * zoom level and the screen density.
 */
public class DrawStroke(
    override val id: String = newElementId(),
    mesh: StrokeMesh,
    brushConfig: BrushConfig,
    transform: AffineTransform = AffineTransform.IDENTITY
) : DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
        set(value) {
            field = value.inLayer(ElementOrder.STROKE_LAYER)
        }
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var ordered: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO

    /** Geometry of the stroke as it was drawn. */
    public val baseMesh: StrokeMesh = mesh

    /** Brush of the stroke as it was drawn. */
    public val baseBrush: BrushConfig = brushConfig

    /** Placement of [baseMesh] on the page. */
    @Volatile
    public var transform: AffineTransform = transform
        private set

    @Volatile
    public var mesh: StrokeMesh = mesh
        private set

    @Volatile
    public var brushConfig: BrushConfig = brushConfig
        private set

    @Volatile
    public var bounds: Rect = mesh.computeBounds()
        private set

    init {
        if (!transform.isIdentity) place(transform)
    }

    /** Places the stroke on its page with [transform] (absolute, applied to the [baseMesh]). */
    public fun place(transform: AffineTransform) {
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
public interface RotatableItem {
    public var x: Float
    public var y: Float
    public var width: Float
    public var height: Float

    /** Rotation in degrees around the center of the item. */
    public var rotation: Float

    public val centerX: Float get() = x + width / 2f
    public val centerY: Float get() = y + height / 2f

    /**
     * Axis-aligned bounding box in page millimeters, taking the rotation into account.
     */
    public fun getRotatedBounds(): Rect = getRotatedBounds(height)

    /** [getRotatedBounds] of the item were it [height] high. */
    public fun getRotatedBounds(height: Float): Rect {
        if (rotation == 0f) return Rect(x, y, x + width, y + height)
        val rad = rotation * PI.toFloat() / 180f
        val c = abs(cos(rad))
        val s = abs(sin(rad))
        val halfW = (width / 2f) * c + (height / 2f) * s
        val halfH = (width / 2f) * s + (height / 2f) * c
        val middleX = x + width / 2f
        val middleY = y + height / 2f
        return Rect(middleX - halfW, middleY - halfH, middleX + halfW, middleY + halfH)
    }

    /**
     * Whether the point ([px], [py]) in page millimeters falls inside the rotated rectangle.
     */
    public fun containsPoint(px: Float, py: Float): Boolean {
        val local = toLocal(px, py)
        return local.x >= 0f && local.x <= width && local.y >= 0f && local.y <= height
    }

    /**
     * The point ([px], [py]) of the page in millimeters from the top-left corner of the item
     * before its rotation (inside the item from (0, 0) to ([width], [height])).
     */
    public fun toLocal(px: Float, py: Float): Point {
        if (rotation == 0f) return Point(px - x, py - y)
        val rad = rotation * PI.toFloat() / 180f
        val c = cos(rad)
        val s = sin(rad)
        val dx = px - centerX
        val dy = py - centerY
        return Point(dx * c + dy * s + width / 2f, -dx * s + dy * c + height / 2f)
    }
}

/**
 * A text block placed on a page: Markdown with formulas (see `RichTextParser`).
 *
 * Its document keeps where its lines may go: its top-left corner ([x], [y]), the width they wrap
 * on, its rotation around that corner (its lines go along its turned axes from there, whatever
 * their height). Its [height] is what its lines take.
 *
 * [id] identifies the block on every device (see [newElementId]).
 */
public class DrawText(override val id: String = newElementId()) : RotatableItem, DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
        set(value) {
            field = value.inLayer(ElementOrder.TEXT_LAYER)
        }
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var ordered: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO

    /**
     * The changes of the content its content includes: two contents made apart are told apart
     * (the one lost becomes a copy, see `UpdateTexts`).
     */
    public var contentVersions: Versions = Versions.NONE

    @Volatile public var text: String = ""
    @Volatile public var alignment: TextAlignment = TextAlignment.START

    /**
     * Whether its box follows the text (a text started by a tap: [width] is the room its lines
     * may take, up to the margin of the page, and its box the part they take, see [TextRoom]), or
     * was set (drawn, resized: its box is [width] wide).
     */
    @Volatile public var autoWidth: Boolean = false

    @Volatile override var x: Float = 0f
    @Volatile override var y: Float = 0f

    /** The width its lines wrap on (the room of a text whose box follows it, see [autoWidth]). */
    @Volatile override var width: Float = 50f

    /**
     * The width of its longest line as this device lays it out (kept by the editor, see
     * `TextBoxes`), not stored; `NaN` until it is laid out.
     */
    @Volatile public var naturalWidth: Float = Float.NaN

    /** The width of its box: [width], or the part its lines take when it follows them. */
    public val boxWidth: Float
        get() = if (!autoWidth || naturalWidth.isNaN()) width else TextRoom.boxWidth(naturalWidth, width)

    /** Where its box starts along its lines, from its corner. */
    public val boxLeft: Float get() = if (!autoWidth) 0f else TextRoom.boxLeft(boxWidth, width, alignment)

    /** The top-left corner of its box (its corner moved by [boxLeft] along its lines). */
    public val boxX: Float get() = x + turned(boxLeft, 0f).x

    /** See [boxX]. */
    public val boxY: Float get() = y + turned(boxLeft, 0f).y

    /**
     * The height its lines take as this device lays them out (kept by the editor, see
     * `TextBoxes`), not what the device that wrote it measured: the height it was stored with
     * until it is laid out.
     */
    @Volatile override var height: Float = 20f

    /** Rotation in degrees around its top-left corner ([x], [y]). */
    @Volatile override var rotation: Float = 0f

    override val centerX: Float get() = x + turned(boxLeft + boxWidth / 2f, height / 2f).x
    override val centerY: Float get() = y + turned(boxLeft + boxWidth / 2f, height / 2f).y

    /** The bounds of its box (see [boxWidth]) were it [height] high. */
    override fun getRotatedBounds(height: Float): Rect {
        val left = boxLeft
        val right = left + boxWidth
        if (rotation == 0f) return Rect(x + left, y, x + right, y + height)
        val corners = listOf(turned(left, 0f), turned(right, 0f), turned(left, height), turned(right, height))
        return Rect(x + corners.minOf { it.x }, y + corners.minOf { it.y }, x + corners.maxOf { it.x }, y + corners.maxOf { it.y })
    }

    /** The point ([px], [py]) of the page from the top-left corner of its box, before its rotation. */
    override fun toLocal(px: Float, py: Float): Point {
        val dx = px - x
        val dy = py - y
        if (rotation == 0f) return Point(dx - boxLeft, dy)
        val rad = rotation * PI.toFloat() / 180f
        val c = cos(rad)
        val s = sin(rad)
        return Point(dx * c + dy * s - boxLeft, -dx * s + dy * c)
    }

    override fun containsPoint(px: Float, py: Float): Boolean {
        val local = toLocal(px, py)
        return local.x >= 0f && local.x <= boxWidth && local.y >= 0f && local.y <= height
    }

    /** The point ([dx], [dy]) of the text before its rotation, turned around its corner. */
    private fun turned(dx: Float, dy: Float): Point = turn(dx, dy, rotation)

    /** ARGB color. */
    @Volatile public var color: Int = 0xFF000000.toInt()

    /** Font size in typographic points. */
    @Volatile public var fontSize: Float = 16f


    /** Incremented on every change, used to invalidate the render caches. */
    @Volatile public var version: Int = 0
        private set

    public fun markDirty() {
        version++
    }

    public companion object {
        /**
         * The corner of a text whose box [width] x [height] (at [boxLeft] along its lines, see
         * [DrawText.boxLeft]) is turned by [rotation] degrees around the middle ([centerX],
         * [centerY]): where its lines start (see [DrawText]).
         */
        public fun cornerFor(centerX: Float, centerY: Float, width: Float, height: Float, rotation: Float, boxLeft: Float = 0f): Point {
            val half = turn(boxLeft + width / 2f, height / 2f, rotation)
            return Point(centerX - half.x, centerY - half.y)
        }

        private fun turn(dx: Float, dy: Float, rotation: Float): Point {
            if (rotation == 0f) return Point(dx, dy)
            val rad = rotation * PI.toFloat() / 180f
            val c = cos(rad)
            val s = sin(rad)
            return Point(dx * c - dy * s, dx * s + dy * c)
        }
    }

    public fun copyFrom(other: DrawText) {
        text = other.text
        alignment = other.alignment
        autoWidth = other.autoWidth
        x = other.x
        y = other.y
        width = other.width
        height = other.height
        naturalWidth = other.naturalWidth
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
public class DrawImage(override val id: String = newElementId()) : RotatableItem, DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
        set(value) {
            field = value.inLayer(ElementOrder.IMAGE_LAYER)
        }
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var ordered: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO
    public var resourceId: String = ""

    @Volatile override var x: Float = 0f
    @Volatile override var y: Float = 0f
    @Volatile override var width: Float = 0f
    @Volatile override var height: Float = 0f
    @Volatile override var rotation: Float = 0f

}

/**
 * An element of a kind added by an application or a library ([ElementType]: a table, a shape, a
 * recording...). Its envelope is the model's: its identifier, its order, its stamps, its presence
 * and its (rotated) box in page millimeters, so that it is moved, rotated, resized, selected,
 * undone, stored and synchronized like the other elements. Its content is its type's: [payload],
 * the value of its type in CBOR, of format [version].
 *
 * The model never reads the payload: a device without the type [type] keeps the element as it
 * is, synchronizes it and draws a placeholder in its box.
 */
public class DrawCustomElement(
    /** The identifier of its type ([ElementType.id]). */
    public val type: String,
    override val id: String = newElementId()
) : RotatableItem, DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
        set(value) {
            field = value.inLayer(ElementOrder.CUSTOM_LAYER)
        }
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var ordered: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO

    /** The format of [payload] ([ElementType.version] when it was written). */
    @Volatile public var version: Int = 1

    /** The value of the element: CBOR of its type, replaced as a whole by every change of it. */
    @Volatile public var payload: ByteArray = ByteArray(0)

    @Volatile override var x: Float = 0f
    @Volatile override var y: Float = 0f
    @Volatile override var width: Float = 0f
    @Volatile override var height: Float = 0f
    @Volatile override var rotation: Float = 0f

    /** A copy of this element, the same on every device (e.g. a page copied while it is exported). */
    public fun copy(): DrawCustomElement = DrawCustomElement(type, id).also {
        it.order = order
        it.version = version
        it.payload = payload
        it.x = x
        it.y = y
        it.width = width
        it.height = height
        it.rotation = rotation
    }
}

/**
 * A page of an imported PDF document rendered as the page background (its text can be marked,
 * see [DrawTextMarkup]).
 *
 * [id] identifies it on every device (see [newElementId]).
 */
public class DrawPdf(public var pdfPageIndex: Int = 0, override val id: String = newElementId()) : DrawElement {
    override var order: ElementOrder = ElementOrder.UNASSIGNED
        set(value) {
            field = value.inLayer(ElementOrder.PDF_LAYER)
        }
    override var added: Stamp = Stamp.ZERO
    override var removed: Stamp? = null
    override var placed: Stamp = Stamp.ZERO
    override var ordered: Stamp = Stamp.ZERO
    override var updated: Stamp = Stamp.ZERO
    public var resourceId: String = ""
}

/**
 * A file owned by a document (imported images and PDFs). [id] identifies the resource on every
 * device (see [newElementId]), [path] is the file on this device.
 */
public class DrawResource(
    public val id: String,
    public val type: ResourceType,
    public var path: String,
    /** The SHA-256 of the content of the file (see `Sha256`): the same content is kept once. */
    public val hash: String = ""
) {
    public enum class ResourceType { PDF, IMAGE, COLOR }
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
public class DrawPage(
    public var index: Int = 0,
    widthMm: Float = 210f,
    heightMm: Float = 297f,
    background: PageBackground = PageBackground.Solid(),
    public val id: String = newElementId(),
    public var orderKey: String = ""
) {
    /** Key of the page in the render caches of this process (a number, cheaper than [id]). */
    public val renderKey: Long = nextPageRenderKey()

    public var added: Stamp = Stamp.ZERO
    public var removed: Stamp? = null
    public var orderVersion: Stamp = Stamp.ZERO
    public var formatVersion: Stamp = Stamp.ZERO

    /** The changes of its content (elements added, changed or moved onto it; not removed). */
    public var contentVersions: Versions = Versions.NONE

    /**
     * What of its content the removal [removed] had seen (`null`: all of it): the page stays when
     * something was added to it meanwhile on another device (see [isHidden]).
     */
    public var removalSaw: Versions? = null

    /** Whether it is removed: by a removal that saw all of its content. */
    public val isHidden: Boolean get() = removed != null && removalSaw?.covers(contentVersions) != false

    /**
     * The elements removed from the page: hidden, they keep receiving the changes (the changes of
     * another device made before it saw the removal), so that an element put back is the same on
     * every device.
     */
    public val removedElements: MutableList<DrawElement> = mutableListOf()

    @Volatile public var widthMm: Float = widthMm
    @Volatile public var heightMm: Float = heightMm
    @Volatile public var background: PageBackground = background

    /**
     * Whether [background] has been customized for this page. Pages without a custom background
     * follow the default background of the document.
     */
    public var hasCustomBackground: Boolean = false

    /**
     * The elements shown, replaced as a whole by every change: read on any thread (e.g. by the
     * workers drawing the tiles) while the thread of the document changes them, a page is always
     * read whole.
     */
    @Volatile
    private var content: PageElements = PageElements()

    public val strokes: List<DrawStroke> get() = content.strokes
    public val texts: List<DrawText> get() = content.texts
    public val images: List<DrawImage> get() = content.images
    public val pdfs: List<DrawPdf> get() = content.pdfs

    /** Highlights, underlines and strikeouts of the text of [pdfs]. */
    public val markups: List<DrawTextMarkup> get() = content.markups

    /** The elements of the kinds added by the applications ([ElementType]). */
    public val customElements: List<DrawCustomElement> get() = content.customs

    /**
     * The content of the page in its stack, from the bottom: the images, the elements of the
     * applications, the texts and the strokes, by their order (see [stackOrder]). The PDF pages and
     * the marks of their text are under it, apart (the paper).
     */
    public val stack: List<DrawElement> get() = content.stack

    /** Incremented on every content change (used by thumbnails and caches). */
    @Volatile public var contentVersion: Int = 0
        private set

    public val dimension: Dimension
        get() = Dimension(widthMm.mm, heightMm.mm)

    public fun rect(): Rect = Rect(0f, 0f, widthMm, heightMm)

    internal fun markContentChanged() {
        contentVersion++
    }

    // ==========================================================
    // Elements (the changes of the document go through these)
    // ==========================================================

    /**
     * Every element of the page: PDF pages, images, strokes, texts, marks and the elements of the
     * applications, each kind in order.
     */
    public fun elements(): List<DrawElement> = content.let { it.pdfs + it.images + it.strokes + it.texts + it.markups + it.customs }

    /** The removed element [id] of this page (see [removedElements]), or `null`. */
    internal fun findRemoved(id: String): DrawElement? = removedElements.firstOrNull { it.id == id }

    /** The element [id] of this page, or `null`. */
    public fun find(id: String): DrawElement? = content.let { c ->
        c.strokes.firstOrNull { it.id == id } ?: c.texts.firstOrNull { it.id == id } ?: c.images.firstOrNull { it.id == id }
            ?: c.markups.firstOrNull { it.id == id } ?: c.pdfs.firstOrNull { it.id == id } ?: c.customs.firstOrNull { it.id == id }
    }

    /**
     * Adds [element] among the elements of its kind, in its stacking order (see [stackOrder]). An
     * element without order (a page being built, outside of the changes) gets the next order of
     * the elements of its kind built so, below the ones of the changes.
     */
    public fun add(element: DrawElement) {
        fun <T : DrawElement> List<T>.inserted(item: T): List<T> {
            if (item.order == ElementOrder.UNASSIGNED) {
                val layer = ElementOrder.layerOf(item)
                item.order = ElementOrder.built(layer, count { ElementOrder.isBuilt(it.order, layer) })
            }
            var position = size
            while (position > 0 && stackOrder.compare(this[position - 1], item) > 0) position--
            return toMutableList().apply { add(position, item) }
        }
        val c = content
        content = when (element) {
            is DrawStroke -> c.copy(strokes = c.strokes.inserted(element))
            is DrawText -> c.copy(texts = c.texts.inserted(element))
            is DrawImage -> c.copy(images = c.images.inserted(element))
            is DrawPdf -> c.copy(pdfs = c.pdfs.inserted(element))
            is DrawTextMarkup -> c.copy(markups = c.markups.inserted(element))
            is DrawCustomElement -> c.copy(customs = c.customs.inserted(element))
        }
    }

    /** Removes [element]; returns its position among the elements of its kind, or -1. */
    public fun remove(element: DrawElement): Int {
        fun <T : DrawElement> List<T>.without(item: T): List<T> = toMutableList().apply { remove(item) }
        val c = content
        val position = when (element) {
            is DrawStroke -> c.strokes.indexOf(element)
            is DrawText -> c.texts.indexOf(element)
            is DrawImage -> c.images.indexOf(element)
            is DrawPdf -> c.pdfs.indexOf(element)
            is DrawTextMarkup -> c.markups.indexOf(element)
            is DrawCustomElement -> c.customs.indexOf(element)
        }
        if (position < 0) return -1
        content = when (element) {
            is DrawStroke -> c.copy(strokes = c.strokes.without(element))
            is DrawText -> c.copy(texts = c.texts.without(element))
            is DrawImage -> c.copy(images = c.images.without(element))
            is DrawPdf -> c.copy(pdfs = c.pdfs.without(element))
            is DrawTextMarkup -> c.copy(markups = c.markups.without(element))
            is DrawCustomElement -> c.copy(customs = c.customs.without(element))
        }
        return position
    }

    /**
     * Area of [element] in page millimeters (what must be drawn again when it changes), or `null`
     * for the whole page.
     */
    public fun boundsOf(element: DrawElement): Rect? = when (element) {
        is DrawStroke -> element.bounds
        is DrawText -> element.getRotatedBounds()
        is DrawImage -> element.getRotatedBounds()
        is DrawPdf -> null
        is DrawTextMarkup -> element.boundsMm(this)
        is DrawCustomElement -> element.getRotatedBounds()
    }

    /**
     * The content of the page as it is now, which stays as it is. Safe to call from background
     * threads (tile rasterization) while the thread of the document keeps changing the page.
     */
    public fun snapshotContent(): PageContentSnapshot {
        val c = content
        return PageContentSnapshot(
            strokes = c.strokes,
            texts = c.texts,
            images = c.images,
            pdfs = c.pdfs,
            background = background,
            widthMm = widthMm,
            heightMm = heightMm,
            markups = c.markups,
            customs = c.customs,
            stack = c.stack
        )
    }

    public companion object {
        /** The order of the stack of a page: by order, then by identifier (the same orders made apart). */
        public val stackOrder: Comparator<DrawElement> = compareBy<DrawElement>({ it.order }, { it.id })
    }
}

/** The elements shown on a page, by kind, each kind in its stacking order (see [DrawPage]). */
private data class PageElements(
    val strokes: List<DrawStroke> = emptyList(),
    val texts: List<DrawText> = emptyList(),
    val images: List<DrawImage> = emptyList(),
    val pdfs: List<DrawPdf> = emptyList(),
    val markups: List<DrawTextMarkup> = emptyList(),
    val customs: List<DrawCustomElement> = emptyList()
) {
    /** The content in its stack (see [DrawPage.stack]), made once when it is read. */
    val stack: List<DrawElement> by lazy { stackOf(images, customs, texts, strokes) }
}

/** [images], [customs], [texts] and [strokes] (each in its order) as one stack (see [DrawPage.stack]). */
private fun stackOf(
    images: List<DrawImage>,
    customs: List<DrawCustomElement>,
    texts: List<DrawText>,
    strokes: List<DrawStroke>
): List<DrawElement> = (images + customs + texts + strokes).sortedWith(DrawPage.stackOrder)

/**
 * Immutable view of the content of a [DrawPage].
 */
public class PageContentSnapshot(
    public val strokes: List<DrawStroke>,
    public val texts: List<DrawText>,
    public val images: List<DrawImage>,
    public val pdfs: List<DrawPdf>,
    public val background: PageBackground,
    public val widthMm: Float,
    public val heightMm: Float,
    public val markups: List<DrawTextMarkup> = emptyList(),
    /** The elements of the kinds added by the applications. */
    public val customs: List<DrawCustomElement> = emptyList(),
    /** The content in its stack (see [DrawPage.stack]); `null`: made from the lists of the kinds. */
    stack: List<DrawElement>? = null
) {
    public fun rect(): Rect = Rect(0f, 0f, widthMm, heightMm)

    /** The content in its stack, from the bottom (see [DrawPage.stack]). */
    public val stack: List<DrawElement> = stack ?: stackOf(images, customs, texts, strokes)

    /**
     * The elements in the order they are painted, from the bottom: the PDF pages, the marks of
     * their text (the paper), then the content in its [stack].
     */
    public val paintOrder: List<DrawElement> get() = pdfs + markups + stack

    /**
     * A copy of this content whose elements are copies too: the elements of a page are changed in
     * place, this copy stays as it is (e.g. while a document is exported).
     */
    public fun detached(): PageContentSnapshot = PageContentSnapshot(
        strokes = strokes.map { stroke -> DrawStroke(stroke.id, stroke.baseMesh, stroke.baseBrush, stroke.transform).also { it.order = stroke.order } },
        texts = texts.map { text ->
            DrawText(text.id).also {
                it.copyFrom(text)
                it.order = text.order
            }
        },
        images = images.map { image ->
            DrawImage(image.id).also {
                it.order = image.order
                it.resourceId = image.resourceId
                it.x = image.x
                it.y = image.y
                it.width = image.width
                it.height = image.height
                it.rotation = image.rotation
            }
        },
        pdfs = pdfs.map { pdf ->
            DrawPdf(pdf.pdfPageIndex, pdf.id).also {
                it.resourceId = pdf.resourceId
                it.order = pdf.order
            }
        },
        background = background,
        widthMm = widthMm,
        heightMm = heightMm,
        markups = markups.map { markup ->
            DrawTextMarkup(
                markup.id, markup.resourceId, markup.pdfPageIndex, markup.pdfWidthPt, markup.pdfHeightPt, markup.startIndex, markup.endIndex,
                markup.text, markup.rects, markup.kind, markup.color
            ).also { it.order = markup.order }
        },
        customs = customs.map { it.copy() }
    )
}

/**
 * Represents a multi-page vector document.
 *
 * [id] identifies the document on every device (see [newElementId]); [strokeModeller] models
 * the strokes it receives as data (loaded, from another device, put back by undo).
 */
public class DrawDocument(
    public val id: String = newElementId(),
    title: String = "Untitled",
    defaultWidthMm: Float = 210f,
    defaultHeightMm: Float = 297f,
    defaultBackground: PageBackground = PageBackground.Solid(),
    public var createdAt: Long = 0L,
    modifiedAt: Long = 0L,
    addInitialPage: Boolean = true,
    public val strokeModeller: StrokeModeller = KotlinStrokeModeller
) {
    private val revisionState = MutableStateFlow(0L)

    /**
     * Increased by every change of the document: its pages (added, removed, moved, formatted,
     * their content), its title, its defaults, its files. What shows the document follows it (in
     * Compose: collected as state) and reads the document again.
     */
    public val revision: StateFlow<Long> = revisionState.asStateFlow()

    /** Tells the followers of [revision] that the document changed. */
    internal fun markChanged() {
        revisionState.update { it + 1 }
    }

    @Volatile public var title: String = title
        set(value) {
            field = value
            markChanged()
        }

    public var name: String
        get() = title
        set(value) {
            title = value
        }

    @Volatile public var modifiedAt: Long = modifiedAt
    public var lastOpenedAt: Long? = null

    @Volatile public var defaultWidthMm: Float = defaultWidthMm
    @Volatile public var defaultHeightMm: Float = defaultHeightMm
    @Volatile public var defaultBackground: PageBackground = defaultBackground

    /**
     * The pages shown, ordered by [DrawPage.orderKey] (then by [DrawPage.id]): replaced as a whole
     * by every change, readable on any thread.
     */
    @Volatile public var pages: List<DrawPage> = emptyList()
        private set

    /** The files of the document (imported images and PDFs), readable on any thread. */
    @Volatile public var resources: List<DrawResource> = emptyList()
        private set

    /** Stamp of the last change of the default format and background. */
    public var defaultsVersion: Stamp = Stamp.ZERO

    /** Stamp of the last change of the [title] (see `RenameDocument`). */
    public var titleVersion: Stamp = Stamp.ZERO

    /**
     * The pages removed: hidden, they keep receiving the changes like the other pages (the
     * changes of another device made before it saw the removal), so that a page put back is the
     * same on every device.
     */
    public val removedPages: MutableMap<String, DrawPage> = mutableMapOf()

    /**
     * Removes for good the pages and the elements hidden by a removal that [stable] says can be
     * forgotten (every device has it, and nothing made without seeing it is still on its way, see
     * `SyncEngine`): nothing can show them again.
     */
    public fun purgeHidden(stable: (Stamp) -> Boolean): PurgedContent {
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
    public fun addPage(
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
        appendPage(newPage)
        return newPage
    }

    /** Appends [page] after the others (building a document whose pages are in their order). */
    public fun appendPage(page: DrawPage) {
        pages = pages + page
        reindexPages()
    }

    /** Inserts [page] at the position of its [DrawPage.orderKey]. */
    public fun insertPage(page: DrawPage) {
        val list = pages
        var position = list.size
        while (position > 0 && pageOrder.compare(list[position - 1], page) > 0) position--
        pages = list.toMutableList().apply { add(position, page) }
        reindexPages()
    }

    /** Takes [page] out of the pages shown; whether it was shown. */
    internal fun removeShownPage(page: DrawPage): Boolean {
        if (page !in pages) return false
        pages = pages - page
        reindexPages()
        return true
    }

    /** Moves [page] to the position of its [DrawPage.orderKey]. */
    internal fun repositionPage(page: DrawPage) {
        if (!removeShownPage(page)) return
        insertPage(page)
    }

    /**
     * Moves the page at [from] to [to] without a change of the document (the pages being
     * reordered by hand, recorded as one change at the end, see [restorePageOrder]).
     */
    public fun movePage(from: Int, to: Int) {
        val list = pages.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        pages = list
        reindexPages()
    }

    /** Shows [order] as the pages again (the order before a [movePage]). */
    public fun restorePageOrder(order: List<DrawPage>) {
        pages = order.toList()
        reindexPages()
    }

    /** Adds the file [resource] to the document. */
    public fun addResource(resource: DrawResource) {
        resources = resources + resource
        markChanged()
    }

    /** A key between the pages at [index] - 1 and [index] (inserting a page at [index]). */
    public fun orderKeyAt(index: Int): String {
        val before = pages.getOrNull(index - 1)?.orderKey
        // Pages of equal keys (inserted at the same place on two devices): after all of them
        val after = pages.drop(index.coerceAtLeast(0)).firstOrNull { before == null || it.orderKey > before }?.orderKey
        return FractionalIndex.between(before, after)
    }

    /** The most recent stamp of the document (what its clock must stay after). */
    internal fun latestStamp(): Stamp {
        val stamps = sequenceOf(defaultsVersion, titleVersion) +
            allPages().asSequence().flatMap { page ->
                sequenceOf(page.added, page.removed ?: Stamp.ZERO, page.orderVersion, page.formatVersion) +
                    // The changes that changed nothing are among the versions only
                    page.contentVersions.stamps.values + (page.elements() + page.removedElements).asSequence().flatMap {
                        sequenceOf(it.added, it.removed ?: Stamp.ZERO, it.placed, it.ordered, it.updated) +
                            ((it as? DrawText)?.contentVersions?.stamps?.values ?: emptyList())
                    }
            }
        return stamps.maxOrNull() ?: Stamp.ZERO
    }

    /** Re-assigns the [DrawPage.index] of every page after a structural change. */
    public fun reindexPages() {
        pages.forEachIndexed { i, p -> p.index = i }
        markChanged()
    }

    public fun pageById(id: String): DrawPage? = pages.firstOrNull { it.id == id }

    /** Every page, shown or removed (see [removedPages]). */
    public fun allPages(): List<DrawPage> = pages + removedPages.values

    /** The page [id], shown or removed (see [removedPages]), or `null`. */
    internal fun anyPage(id: String): DrawPage? = pageById(id) ?: removedPages[id]

    /** Whether [page] is shown (not removed). */
    public fun isShown(page: DrawPage): Boolean = page.id !in removedPages

    /**
     * The element [id] (not removed) and the page holding it, shown or removed, looked for in
     * [hint] first (an element can be moved to another page by another device), or `null`.
     */
    public fun findElement(id: String, hint: DrawPage? = null): Pair<DrawPage, DrawElement>? {
        hint?.find(id)?.let { return hint to it }
        for (page in allPages()) {
            if (page === hint) continue
            page.find(id)?.let { return page to it }
        }
        return null
    }

    /** The removed element [id] and the page holding it (see [DrawPage.removedElements]), or `null`. */
    internal fun findRemovedElement(id: String): Pair<DrawPage, DrawElement>? {
        for (page in allPages()) page.findRemoved(id)?.let { return page to it }
        return null
    }

    public fun findResource(resourceId: String): DrawResource? = resources.firstOrNull { it.id == resourceId }

    internal fun pageByRenderKey(renderKey: Long): DrawPage? = pages.firstOrNull { it.renderKey == renderKey }

    private companion object {
        val pageOrder: Comparator<DrawPage> = compareBy<DrawPage>({ it.orderKey }, { it.id })
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
public fun newElementId(): String = Uuid.random().toString()

/** The pages and the elements removed for good (see [DrawDocument.purgeHidden]), with the page of each element. */
public class PurgedContent(public val pages: List<DrawPage>, public val elements: List<Pair<DrawPage, DrawElement>>) {
    public val isEmpty: Boolean get() = pages.isEmpty() && elements.isEmpty()
}
