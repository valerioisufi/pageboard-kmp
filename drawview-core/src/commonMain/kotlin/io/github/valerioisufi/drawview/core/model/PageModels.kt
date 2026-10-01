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
import io.github.valerioisufi.drawview.core.geometry.Dimension
import io.github.valerioisufi.drawview.core.geometry.mm
import kotlin.concurrent.Volatile
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
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
 * A vector ink stroke placed on a page.
 *
 * The [mesh] and the [bounds] are expressed in page millimeters, so that the content is
 * independent from the window size, the zoom level and the screen density.
 */
class DrawStroke(
    val id: String = generateRandomId(),
    mesh: StrokeMesh,
    brushConfig: BrushConfig,
    bounds: Rect,
    var zIndex: Int = 0,
    var dbId: Int = 0
) {
    @Volatile
    var mesh: StrokeMesh = mesh

    @Volatile
    var brushConfig: BrushConfig = brushConfig

    @Volatile
    var bounds: Rect = bounds

    /** Hidden from the static (tile) layers while the stroke is selected or moved. */
    @Volatile
    var isDragging: Boolean = false
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
 */
class DrawText(var zIndex: Int = 0) : RotatableItem {
    val id: String = generateRandomId()
    var dbId: Int = 0

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
 */
class DrawImage(var zIndex: Int = 0) : RotatableItem {
    var resourceId: String = ""
    var dbId: Int = 0

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
 * A page of an imported PDF document rendered as the page background.
 */
class DrawPdf(var zIndex: Int = 0, var pdfPageIndex: Int = 0) {
    var resourceId: String = ""
    var dbId: Int = 0
}

/**
 * A file owned by a document (imported images and PDFs).
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
 */
class DrawPage(
    var index: Int = 0,
    widthMm: Float = 210f,
    heightMm: Float = 297f,
    background: PageBackground = PageBackground.Solid(),
    var dbId: Int = 0
) {
    /** Stable identity of the page (survives reordering), used to key the render caches. */
    val uid: Long = nextPageUid()

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

    /** Incremented on every content change (used by thumbnails and caches). */
    var contentVersion: Int by mutableIntStateOf(0)

    val dimension: Dimension
        get() = Dimension(widthMm.mm, heightMm.mm)

    fun rect(): Rect = Rect(0f, 0f, widthMm, heightMm)

    fun markContentChanged() {
        contentVersion++
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
            heightMm = heightMm
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
    val heightMm: Float
) {
    fun rect(): Rect = Rect(0f, 0f, widthMm, heightMm)
}

/**
 * Represents a multi-page vector document.
 */
class DrawDocument(
    val id: String = generateRandomId(),
    title: String = "Untitled",
    defaultWidthMm: Float = 210f,
    defaultHeightMm: Float = 297f,
    defaultBackground: PageBackground = PageBackground.Solid(),
    var createdAt: Long = 0L,
    modifiedAt: Long = 0L,
    var dbId: Int = 0,
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

    fun findResource(resourceId: String): DrawResource? = readSnapshot {
        resources.firstOrNull { it.id == resourceId }
    }

    fun pageByUid(uid: Long): DrawPage? = readSnapshot {
        pages.firstOrNull { it.uid == uid }
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

private val pageUidCounter = atomic(0L)

private fun nextPageUid(): Long = pageUidCounter.incrementAndGet()

internal fun generateRandomId(): String {
    return "item_" + Random.nextLong(100000000, 999999999).toString(16)
}
