package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.ElementOrder
import io.github.valerioisufi.pageboard.model.FractionalIndex
import io.github.valerioisufi.pageboard.model.KotlinStrokeModeller
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokeModeller
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.Versions
import io.github.valerioisufi.pageboard.model.change.Placement
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.placement
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.model.geometry.Dimension
import io.github.valerioisufi.pageboard.model.geometry.Point
import io.github.valerioisufi.pageboard.model.geometry.Rect
import io.github.valerioisufi.pageboard.model.geometry.mm
import io.github.valerioisufi.pageboard.model.text.TextBackground
import io.github.valerioisufi.pageboard.model.text.TextRoom
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
 * the stack of its page (see [Page.stack]), an order written before the one stack read in
 * the layer of its kind (see [ElementOrder]). Each piece of an element is changed by the most
 * recent change of it on every device (last-writer-wins), whatever the order the changes arrive
 * in:
 * - its presence: [added] and [removed] are the stamps of the last addition and of the last
 *   removal (removed when [removed] is set: kept hidden on its page, see
 *   [Page.removedElements]);
 * - its placement (page, position, size, rotation): [placed] is the stamp of its last change;
 * - its order: [ordered] is the stamp of its last change (apart from the placement, so that a
 *   move and a change of order made apart both stay);
 * - its content (a text and its format, the style of a mark): [updated] is the stamp of its last
 *   change.
 */
public sealed interface PageElement {
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
public class StrokeElement(
    override val id: String = newElementId(),
    mesh: StrokeMesh,
    brushConfig: BrushConfig,
    transform: AffineTransform = AffineTransform.IDENTITY
) : PageElement {
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
public class TextElement(override val id: String = newElementId()) : RotatableItem, PageElement {
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

    /** The bounds of its box (see [boxWidth]) were it [height] high, its [background] included. */
    override fun getRotatedBounds(height: Float): Rect {
        val outset = backgroundOutset
        val left = boxLeft - outset
        val right = boxLeft + boxWidth + outset
        val top = -outset
        val bottom = height + outset
        if (rotation == 0f) return Rect(x + left, y + top, x + right, y + bottom)
        val corners = listOf(turned(left, top), turned(right, top), turned(left, bottom), turned(right, bottom))
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

    /** Whether the point is in its box, or on its [background]. */
    override fun containsPoint(px: Float, py: Float): Boolean {
        val local = toLocal(px, py)
        val outset = backgroundOutset
        return local.x >= -outset && local.x <= boxWidth + outset && local.y >= -outset && local.y <= height + outset
    }

    /** The point ([dx], [dy]) of the text before its rotation, turned around its corner. */
    private fun turned(dx: Float, dy: Float): Point = turn(dx, dy, rotation)

    /** ARGB color. */
    @Volatile public var color: Int = 0xFF000000.toInt()

    /** Font size in typographic points. */
    @Volatile public var fontSize: Float = 16f

    /** The ARGB color of the fill under its box, or `null` for none (see [TextBackground]). */
    @Volatile public var background: Int? = null

    /**
     * The identifier of the text this one goes on into, on a following page (a chain of boxes, see
     * `TextChains`), or `null`.
     */
    @Volatile public var next: String? = null

    /**
     * Whether its first line goes on the last line of the text before it in its chain (a paragraph
     * cut between two boxes): their texts are joined without a line break.
     */
    @Volatile public var continuesLine: Boolean = false

    /** How far its [background] goes out of its box, in millimeters (0 without one). */
    public val backgroundOutset: Float get() = if (background == null) 0f else TextBackground.outsetMm(fontSize)


    /** Incremented on every change, used to invalidate the render caches. */
    @Volatile public var version: Int = 0
        private set

    public fun markDirty() {
        version++
    }

    public companion object {
        /**
         * The corner of a text whose box [width] x [height] (at [boxLeft] along its lines, see
         * [TextElement.boxLeft]) is turned by [rotation] degrees around the middle ([centerX],
         * [centerY]): where its lines start (see [TextElement]).
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

    public fun copyFrom(other: TextElement) {
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
        background = other.background
        next = other.next
        continuesLine = other.continuesLine
        markDirty()
    }
}

/**
 * A raster image placed on a page. The pixels live in the document [DocumentResource] referenced
 * by [resourceId].
 *
 * [id] identifies the image on every device (see [newElementId]).
 */
public class ImageElement(override val id: String = newElementId()) : RotatableItem, PageElement {
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
public class CustomElement(
    /** The identifier of its type ([ElementType.id]). */
    public val type: String,
    override val id: String = newElementId()
) : RotatableItem, PageElement {
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
    public fun copy(): CustomElement = CustomElement(type, id).also {
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
 * see [TextMarkupElement]).
 *
 * [id] identifies it on every device (see [newElementId]).
 */
public class PdfElement(public var pdfPageIndex: Int = 0, override val id: String = newElementId()) : PageElement {
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
public class DocumentResource(
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
 * [Document.removedPages]), [orderVersion] and [formatVersion] of its position and of its
 * format: the most recent change wins on every device.
 */
public class Page(
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
     * every device. Put by [load] and by the changes of the document.
     */
    public val removedElements: List<PageElement> get() = hidden

    private val hidden = mutableListOf<PageElement>()

    // The elements by identifier, shown and removed: changed with them, on the thread of the
    // document (like add and remove)
    private val shownById = HashMap<String, PageElement>()
    private val hiddenById = HashMap<String, PageElement>()

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

    public val strokes: List<StrokeElement> get() = content.strokes
    public val texts: List<TextElement> get() = content.texts
    public val images: List<ImageElement> get() = content.images
    public val pdfs: List<PdfElement> get() = content.pdfs

    /** Highlights, underlines and strikeouts of the text of [pdfs]. */
    public val markups: List<TextMarkupElement> get() = content.markups

    /** The elements of the kinds added by the applications ([ElementType]). */
    public val customElements: List<CustomElement> get() = content.customs

    /**
     * The content of the page in its stack, from the bottom: the images, the elements of the
     * applications, the texts and the strokes, by their order (see [stackOrder]). The PDF pages and
     * the marks of their text are under it, apart (the paper).
     */
    public val stack: List<PageElement> get() = content.stack

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
    public fun elements(): List<PageElement> = content.let { it.pdfs + it.images + it.strokes + it.texts + it.markups + it.customs }

    /** The removed element [id] of this page (see [removedElements]), or `null`. */
    internal fun findRemoved(id: String): PageElement? = hiddenById[id]

    /** The element [id] shown on this page, or `null`: on the thread of the document, like [add]. */
    public fun find(id: String): PageElement? = shownById[id]

    /** Keeps the removed [element] on this page, hidden (see [removedElements]). */
    internal fun keepRemoved(element: PageElement) {
        hidden.add(element)
        hiddenById[element.id] = element
    }

    /** Forgets the removed [element] of this page (shown again, moved or purged); whether it was there. */
    internal fun forgetRemoved(element: PageElement): Boolean {
        if (!hidden.remove(element)) return false
        if (hiddenById[element.id] === element) hiddenById.remove(element.id)
        return true
    }

    /**
     * Puts [shown] and [removed] (hidden, see [removedElements]) on this page at once, each kind in
     * its stacking order: a page being built (the stores loading it), cheaper than [add] for each
     * (which copies the elements of its kind). An element shown without order gets the one [add]
     * would give it, in the order of [shown].
     */
    public fun load(shown: Collection<PageElement>, removed: Collection<PageElement> = emptyList()) {
        val c = content
        val strokes = ArrayList(c.strokes)
        val texts = ArrayList(c.texts)
        val images = ArrayList(c.images)
        val pdfs = ArrayList(c.pdfs)
        val markups = ArrayList(c.markups)
        val customs = ArrayList(c.customs)
        // The elements built so of each layer, as add counts them
        val built = HashMap<Char, Int>()
        fun <T : PageElement> MutableList<T>.put(element: T) {
            val layer = ElementOrder.layerOf(element)
            var count = built.getOrPut(layer) { count { ElementOrder.isBuilt(it.order, layer) } }
            if (element.order == ElementOrder.UNASSIGNED) element.order = ElementOrder.built(layer, count)
            if (ElementOrder.isBuilt(element.order, layer)) count++
            built[layer] = count
            add(element)
        }
        for (element in shown) {
            when (element) {
                is StrokeElement -> strokes.put(element)
                is TextElement -> texts.put(element)
                is ImageElement -> images.put(element)
                is PdfElement -> pdfs.put(element)
                is TextMarkupElement -> markups.put(element)
                is CustomElement -> customs.put(element)
            }
            shownById[element.id] = element
        }
        content = PageElements(
            strokes = strokes.sortedWith(stackOrder),
            texts = texts.sortedWith(stackOrder),
            images = images.sortedWith(stackOrder),
            pdfs = pdfs.sortedWith(stackOrder),
            markups = markups.sortedWith(stackOrder),
            customs = customs.sortedWith(stackOrder)
        )
        removed.forEach(::keepRemoved)
    }

    /**
     * Adds [element] among the elements of its kind, in its stacking order (see [stackOrder]). An
     * element without order (a page being built, outside of the changes) gets the next order of
     * the elements of its kind built so, below the ones of the changes.
     */
    public fun add(element: PageElement) {
        fun <T : PageElement> List<T>.inserted(item: T): List<T> {
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
            is StrokeElement -> c.copy(strokes = c.strokes.inserted(element))
            is TextElement -> c.copy(texts = c.texts.inserted(element))
            is ImageElement -> c.copy(images = c.images.inserted(element))
            is PdfElement -> c.copy(pdfs = c.pdfs.inserted(element))
            is TextMarkupElement -> c.copy(markups = c.markups.inserted(element))
            is CustomElement -> c.copy(customs = c.customs.inserted(element))
        }
        shownById[element.id] = element
    }

    /** Removes [element]; returns its position among the elements of its kind, or -1. */
    public fun remove(element: PageElement): Int {
        fun <T : PageElement> List<T>.without(item: T): List<T> = toMutableList().apply { remove(item) }
        val c = content
        val position = when (element) {
            is StrokeElement -> c.strokes.indexOf(element)
            is TextElement -> c.texts.indexOf(element)
            is ImageElement -> c.images.indexOf(element)
            is PdfElement -> c.pdfs.indexOf(element)
            is TextMarkupElement -> c.markups.indexOf(element)
            is CustomElement -> c.customs.indexOf(element)
        }
        if (position < 0) return -1
        content = when (element) {
            is StrokeElement -> c.copy(strokes = c.strokes.without(element))
            is TextElement -> c.copy(texts = c.texts.without(element))
            is ImageElement -> c.copy(images = c.images.without(element))
            is PdfElement -> c.copy(pdfs = c.pdfs.without(element))
            is TextMarkupElement -> c.copy(markups = c.markups.without(element))
            is CustomElement -> c.copy(customs = c.customs.without(element))
        }
        if (shownById[element.id] === element) shownById.remove(element.id)
        return position
    }

    /**
     * Area of [element] in page millimeters (what must be drawn again when it changes), or `null`
     * for the whole page.
     */
    public fun boundsOf(element: PageElement): Rect? = when (element) {
        is StrokeElement -> element.bounds
        is TextElement -> element.getRotatedBounds()
        is ImageElement -> element.getRotatedBounds()
        is PdfElement -> null
        is TextMarkupElement -> element.boundsMm(this)
        is CustomElement -> element.getRotatedBounds()
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
        public val stackOrder: Comparator<PageElement> = compareBy<PageElement>({ it.order }, { it.id })
    }
}

/** The elements shown on a page, by kind, each kind in its stacking order (see [Page]). */
private data class PageElements(
    val strokes: List<StrokeElement> = emptyList(),
    val texts: List<TextElement> = emptyList(),
    val images: List<ImageElement> = emptyList(),
    val pdfs: List<PdfElement> = emptyList(),
    val markups: List<TextMarkupElement> = emptyList(),
    val customs: List<CustomElement> = emptyList()
) {
    /** The content in its stack (see [Page.stack]), made once when it is read. */
    val stack: List<PageElement> by lazy { stackOf(images, customs, texts, strokes) }
}

/** [images], [customs], [texts] and [strokes] (each in its order) as one stack (see [Page.stack]). */
private fun stackOf(
    images: List<ImageElement>,
    customs: List<CustomElement>,
    texts: List<TextElement>,
    strokes: List<StrokeElement>
): List<PageElement> = (images + customs + texts + strokes).sortedWith(Page.stackOrder)

/**
 * Immutable view of the content of a [Page].
 */
public class PageContentSnapshot(
    public val strokes: List<StrokeElement>,
    public val texts: List<TextElement>,
    public val images: List<ImageElement>,
    public val pdfs: List<PdfElement>,
    public val background: PageBackground,
    public val widthMm: Float,
    public val heightMm: Float,
    public val markups: List<TextMarkupElement> = emptyList(),
    /** The elements of the kinds added by the applications. */
    public val customs: List<CustomElement> = emptyList(),
    /** The content in its stack (see [Page.stack]); `null`: made from the lists of the kinds. */
    stack: List<PageElement>? = null
) {
    public fun rect(): Rect = Rect(0f, 0f, widthMm, heightMm)

    /** The content in its stack, from the bottom (see [Page.stack]). */
    public val stack: List<PageElement> = stack ?: stackOf(images, customs, texts, strokes)

    /**
     * The elements in the order they are painted, from the bottom: the PDF pages, the marks of
     * their text (the paper), then the content in its [stack].
     */
    public val paintOrder: List<PageElement> get() = pdfs + markups + stack

    /**
     * A copy of this content whose elements are copies too: the elements of a page are changed in
     * place, this copy stays as it is (e.g. while a document is exported).
     */
    public fun detached(): PageContentSnapshot = PageContentSnapshot(
        strokes = strokes.map { stroke -> StrokeElement(stroke.id, stroke.baseMesh, stroke.baseBrush, stroke.transform).also { it.order = stroke.order } },
        texts = texts.map { text ->
            TextElement(text.id).also {
                it.copyFrom(text)
                it.order = text.order
            }
        },
        images = images.map { image ->
            ImageElement(image.id).also {
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
            PdfElement(pdf.pdfPageIndex, pdf.id).also {
                it.resourceId = pdf.resourceId
                it.order = pdf.order
            }
        },
        background = background,
        widthMm = widthMm,
        heightMm = heightMm,
        markups = markups.map { markup ->
            TextMarkupElement(
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
public class Document(
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

    @Volatile public var modifiedAt: Long = modifiedAt
    public var lastOpenedAt: Long? = null

    @Volatile public var defaultWidthMm: Float = defaultWidthMm
    @Volatile public var defaultHeightMm: Float = defaultHeightMm
    @Volatile public var defaultBackground: PageBackground = defaultBackground

    /**
     * The pages shown, ordered by [Page.orderKey] (then by [Page.id]): replaced as a whole
     * by every change, readable on any thread.
     */
    @Volatile public var pages: List<Page> = emptyList()
        private set

    /** The files of the document (imported images and PDFs), readable on any thread. */
    @Volatile public var resources: List<DocumentResource> = emptyList()
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
    public val removedPages: MutableMap<String, Page> = mutableMapOf()

    /**
     * Removes for good the pages and the elements hidden by a removal that [stable] says can be
     * forgotten (every device has it, and nothing made without seeing it is still on its way, see
     * `SyncEngine`): nothing can show them again. A page goes only once removed before
     * [pagesRemovedBefore] (milliseconds): until then it can be put back from the trash (see
     * `docs/plans/trash.md`).
     */
    public fun purgeHidden(pagesRemovedBefore: Long = Long.MAX_VALUE, stable: (Stamp) -> Boolean): PurgedContent {
        val pages = removedPages.values.filter { page -> page.removed?.let { it.time < pagesRemovedBefore && stable(it) } == true }
        pages.forEach { removedPages.remove(it.id) }
        val elements = mutableListOf<Pair<Page, PageElement>>()
        for (page in this.pages + removedPages.values) {
            val gone = page.removedElements.filter { element -> element.removed?.let(stable) == true }
            if (gone.isEmpty()) continue
            gone.forEach(page::forgetRemoved)
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
    ): Page {
        val newPage = Page(
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
    public fun appendPage(page: Page) {
        pages = pages + page
        reindexPages()
    }

    /** Inserts [page] at the position of its [Page.orderKey]. */
    public fun insertPage(page: Page) {
        val list = pages
        var position = list.size
        while (position > 0 && pageOrder.compare(list[position - 1], page) > 0) position--
        pages = list.toMutableList().apply { add(position, page) }
        reindexPages()
    }

    /** Takes [page] out of the pages shown; whether it was shown. */
    internal fun removeShownPage(page: Page): Boolean {
        if (page !in pages) return false
        pages = pages - page
        reindexPages()
        return true
    }

    /** Moves [page] to the position of its [Page.orderKey]. */
    internal fun repositionPage(page: Page) {
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
    public fun restorePageOrder(order: List<Page>) {
        pages = order.toList()
        reindexPages()
    }

    /** Adds the file [resource] to the document. */
    public fun addResource(resource: DocumentResource) {
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
                            ((it as? TextElement)?.contentVersions?.stamps?.values ?: emptyList())
                    }
            }
        return stamps.maxOrNull() ?: Stamp.ZERO
    }

    /** Re-assigns the [Page.index] of every page after a structural change. */
    public fun reindexPages() {
        pages.forEachIndexed { i, p -> p.index = i }
        markChanged()
    }

    public fun pageById(id: String): Page? = pages.firstOrNull { it.id == id }

    /**
     * The pages removed since [time] (milliseconds), the most recent removal first: the ones the
     * trash of the document shows (see `docs/plans/trash.md`).
     */
    public fun removedPagesSince(time: Long): List<Page> =
        removedPages.values.filter { page -> page.removed.let { it != null && it.time >= time } }.sortedByDescending { it.removed }

    /** Every page, shown or removed (see [removedPages]). */
    public fun allPages(): List<Page> = pages + removedPages.values

    /** The page [id], shown or removed (see [removedPages]), or `null`. */
    internal fun anyPage(id: String): Page? = pageById(id) ?: removedPages[id]

    /** Whether [page] is shown (not removed). */
    public fun isShown(page: Page): Boolean = page.id !in removedPages

    /**
     * The element [id] (not removed) and the page holding it, shown or removed, looked for in
     * [hint] first (an element can be moved to another page by another device), or `null`.
     */
    public fun findElement(id: String, hint: Page? = null): Pair<Page, PageElement>? {
        hint?.find(id)?.let { return hint to it }
        for (page in pages) if (page !== hint) page.find(id)?.let { return page to it }
        for (page in removedPages.values) if (page !== hint) page.find(id)?.let { return page to it }
        return null
    }

    /** The removed element [id] and the page holding it (see [Page.removedElements]), or `null`. */
    internal fun findRemovedElement(id: String): Pair<Page, PageElement>? {
        for (page in pages) page.findRemoved(id)?.let { return page to it }
        for (page in removedPages.values) page.findRemoved(id)?.let { return page to it }
        return null
    }

    public fun findResource(resourceId: String): DocumentResource? = resources.firstOrNull { it.id == resourceId }

    private companion object {
        val pageOrder: Comparator<Page> = compareBy<Page>({ it.orderKey }, { it.id })
    }
}

private val pageRenderKeys = atomic(0L)

private fun nextPageRenderKey(): Long = pageRenderKeys.incrementAndGet()

/**
 * A new identifier of a document, a page, an element or a resource: a random UUID, unique on
 * every device, so that the content created by several devices can be merged (see
 * `docs/plans/collaboration.md`).
 */
@OptIn(ExperimentalUuidApi::class)
public fun newElementId(): String = Uuid.random().toString()

/** The pages and the elements removed for good (see [Document.purgeHidden]), with the page of each element. */
public class PurgedContent(public val pages: List<Page>, public val elements: List<Pair<Page, PageElement>>) {
    public val isEmpty: Boolean get() = pages.isEmpty() && elements.isEmpty()
}
