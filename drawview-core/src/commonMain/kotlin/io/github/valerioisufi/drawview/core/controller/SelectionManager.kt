package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.geometry.HitTesting
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.history.ChangeTextFormattingAction
import io.github.valerioisufi.drawview.core.history.DeleteElementsAction
import io.github.valerioisufi.drawview.core.history.ItemState
import io.github.valerioisufi.drawview.core.history.StrokeState
import io.github.valerioisufi.drawview.core.history.TransformSelectionAction
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.LassoMode
import io.github.valerioisufi.drawview.core.model.MeshPartition
import io.github.valerioisufi.drawview.core.model.StrokeMesh
import io.github.valerioisufi.drawview.core.model.StrokePoint
import kotlin.math.max
import kotlin.math.min

/**
 * A group of elements selected with the lasso on a single page.
 *
 * While the user drags the selection, [transform] accumulates the live transformation in the
 * millimeter space of the source page; it is applied to the elements by
 * [SelectionManager.applyTransformation].
 */
class SelectionGroup(
    val strokes: MutableList<DrawStroke> = mutableListOf(),
    val texts: MutableList<DrawText> = mutableListOf(),
    val images: MutableList<DrawImage> = mutableListOf(),
    var boundingBox: Rect = Rect.Zero,
    var pageIndex: Int = -1
) {
    /** Live transformation (page millimeters of the source page). */
    val transform = Matrix2D()

    /** Rotation of the selection frame (single rotated text/image). */
    var baseRotation: Float = 0f

    fun isEmpty(): Boolean = images.isEmpty() && strokes.isEmpty() && texts.isEmpty()

    val isSingleText: Boolean get() = texts.size == 1 && images.isEmpty() && strokes.isEmpty()
    val isSingleImage: Boolean get() = images.size == 1 && texts.isEmpty() && strokes.isEmpty()

    /**
     * AABB in millimeters of the whole selection, accounting for [baseRotation].
     */
    fun getEnclosingBoundingBoxMm(): Rect {
        if (baseRotation == 0f) return boundingBox
        val m = Matrix2D().postRotate(baseRotation, boundingBox.center.x, boundingBox.center.y)
        return m.mapRect(boundingBox)
    }

    internal var oldStrokeStates: List<StrokeState>? = null
    internal var oldTextStates: List<ItemState>? = null
    internal var oldImageStates: List<ItemState>? = null

    fun captureOriginalStates() {
        oldStrokeStates = strokes.map { StrokeState.of(it) }
        oldTextStates = texts.map { ItemState.of(it) }
        oldImageStates = images.map { ItemState.of(it) }
    }

    fun setDragging(dragging: Boolean) {
        strokes.forEach { it.isDragging = dragging }
        texts.forEach { it.isDragging = dragging }
        images.forEach { it.isDragging = dragging }
    }
}

/**
 * Lasso selection, clipboard and transformation of the selected elements.
 */
class SelectionManager(private val controller: DrawViewController) {

    var currentSelection: SelectionGroup? by mutableStateOf(null)
        private set

    /** Incremented on every live change of the selection (drives the overlay redraw). */
    var selectionVersion: Int by mutableIntStateOf(0)
        private set

    var clipboard: SelectionGroup? by mutableStateOf(null)
        private set

    var lassoMode: LassoMode by mutableStateOf(LassoMode.ALL)

    fun canPaste(): Boolean = clipboard?.isEmpty() == false

    internal fun notifyChanged() {
        selectionVersion++
        controller.requestRedraw()
    }

    /**
     * Selects the elements of [page] intersecting the closed [lasso] polygon (page millimeters).
     */
    fun selectWithLasso(pageIndex: Int, lasso: FloatArray) {
        clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        if (lasso.size < 6) return
        val lassoBox = HitTesting.polygonBounds(lasso)

        val selection = SelectionGroup(pageIndex = pageIndex)
        var left = Float.POSITIVE_INFINITY
        var top = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        var bottom = Float.NEGATIVE_INFINITY
        fun include(r: Rect) {
            left = min(left, r.left); top = min(top, r.top)
            right = max(right, r.right); bottom = max(bottom, r.bottom)
        }

        if (lassoMode != LassoMode.IMAGES_ONLY) {
            for (stroke in page.strokes) {
                val b = stroke.bounds
                if (b.left >= lassoBox.right || lassoBox.left >= b.right || b.top >= lassoBox.bottom || lassoBox.top >= b.bottom) continue
                if (HitTesting.meshIntersectsPolygon(stroke.mesh, lasso, lassoBox)) {
                    selection.strokes.add(stroke)
                    include(b)
                }
            }
            for (text in page.texts) {
                if (isItemInsideLasso(text.centerX, text.centerY, text.getRotatedBounds(), lasso, lassoBox)) {
                    selection.texts.add(text)
                    include(text.getRotatedBounds())
                }
            }
        }
        for (image in page.images) {
            if (isItemInsideLasso(image.centerX, image.centerY, image.getRotatedBounds(), lasso, lassoBox)) {
                selection.images.add(image)
                include(image.getRotatedBounds())
            }
        }

        if (selection.isEmpty()) return
        when {
            selection.isSingleText -> {
                val t = selection.texts[0]
                selection.boundingBox = Rect(t.x, t.y, t.x + t.width, t.y + t.height)
                selection.baseRotation = t.rotation
            }
            selection.isSingleImage -> {
                val i = selection.images[0]
                selection.boundingBox = Rect(i.x, i.y, i.x + i.width, i.y + i.height)
                selection.baseRotation = i.rotation
            }
            else -> selection.boundingBox = Rect(left, top, right, bottom)
        }
        setSelection(selection, page)
    }

    private fun isItemInsideLasso(cx: Float, cy: Float, bounds: Rect, lasso: FloatArray, lassoBox: Rect): Boolean {
        if (HitTesting.pointInPolygon(cx, cy, lasso)) return true
        return bounds.left >= lassoBox.left && bounds.right <= lassoBox.right &&
                bounds.top >= lassoBox.top && bounds.bottom <= lassoBox.bottom
    }

    /**
     * Selects a single image (long press on an image).
     */
    fun selectImage(pageIndex: Int, image: DrawImage) {
        clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        val selection = SelectionGroup(
            images = mutableListOf(image),
            boundingBox = Rect(image.x, image.y, image.x + image.width, image.y + image.height),
            pageIndex = pageIndex
        ).apply { baseRotation = image.rotation }
        setSelection(selection, page)
    }

    /**
     * Selects a single text block (e.g. a text added by the application, so that it can be moved).
     */
    fun selectText(pageIndex: Int, text: DrawText) {
        clearSelection()
        val page = controller.document.pages.getOrNull(pageIndex) ?: return
        val selection = SelectionGroup(
            texts = mutableListOf(text),
            boundingBox = Rect(text.x, text.y, text.x + text.width, text.y + text.height),
            pageIndex = pageIndex
        ).apply { baseRotation = text.rotation }
        setSelection(selection, page)
    }

    private fun setSelection(selection: SelectionGroup, page: DrawPage) {
        selection.setDragging(true)
        currentSelection = selection
        // The selected elements are drawn live on top: rasterize the tiles without them
        controller.invalidatePageContent(page, selection.getEnclosingBoundingBoxMm())
        notifyChanged()
    }

    /**
     * Drops the selection, baking its elements back into the static layers.
     */
    fun clearSelection() {
        val selection = currentSelection ?: return
        currentSelection = null
        val page = controller.document.pages.getOrNull(selection.pageIndex)
        selection.setDragging(false)
        if (page != null) {
            controller.bakeElementsSync(page, selection.strokes, selection.texts, selection.images)
            controller.invalidatePageContent(page, selection.getEnclosingBoundingBoxMm())
        }
        notifyChanged()
    }

    // ==========================================================
    // Live transformation (driven by the canvas gestures)
    // ==========================================================

    fun beginTransformation() {
        currentSelection?.captureOriginalStates()
    }

    /** Translates the selection by ([dxMm], [dyMm]) in the source page space. */
    fun translateBy(dxMm: Float, dyMm: Float) {
        val selection = currentSelection ?: return
        selection.transform.postTranslate(dxMm, dyMm)
        notifyChanged()
    }

    /** Scales the selection by [factor] around its (transformed) center. */
    fun scaleBy(factor: Float) {
        val selection = currentSelection ?: return
        if (!(factor > 0f) || !factor.isFinite()) return
        val c = selection.transform.mapPoint(selection.boundingBox.center.x, selection.boundingBox.center.y)
        selection.transform.postScale(factor, factor, c.x, c.y)
        notifyChanged()
    }

    /** Rotates the selection by [degrees] around its (transformed) center. */
    fun rotateBy(degrees: Float) {
        val selection = currentSelection ?: return
        val c = selection.transform.mapPoint(selection.boundingBox.center.x, selection.boundingBox.center.y)
        selection.transform.postRotate(degrees, c.x, c.y)
        notifyChanged()
    }

    /**
     * Resizes the wrapping width of a single selected text by dragging one of its side handles.
     *
     * @param touchMm finger position in the source page space.
     */
    fun resizeText(touchMm: Offset, isRightEdge: Boolean) {
        val selection = currentSelection ?: return
        val text = selection.texts.singleOrNull() ?: return
        val inverse = Matrix2D()
        if (!selection.transform.invert(inverse)) return
        val local = inverse.mapPoint(touchMm.x, touchMm.y)
        // Undo the base rotation around the text center
        val x = if (selection.baseRotation != 0f) {
            Matrix2D().postRotate(-selection.baseRotation, text.centerX, text.centerY).mapPoint(local.x, local.y).x
        } else local.x
        val padding = 4f / max(controller.pixelsPerMm(selection.pageIndex), 0.01f) / max(selection.transform.scaleFactor, 0.01f)
        val minWidth = 20f
        if (isRightEdge) {
            text.width = max(minWidth, x - padding - text.x)
        } else {
            val rightEdge = text.x + text.width
            val newLeft = x + padding
            if (rightEdge - newLeft >= minWidth) {
                text.x = newLeft
                text.width = rightEdge - newLeft
            }
        }
        text.markDirty()
        selection.boundingBox = Rect(text.x, text.y, text.x + text.width, text.y + text.height)
        notifyChanged()
    }

    /**
     * Applies the live transformation to the selected elements, moving them to the page under
     * the selection center when needed, and records the operation in the history.
     */
    fun applyTransformation() {
        val selection = currentSelection ?: return
        val doc = controller.document
        val oldPage = doc.pages.getOrNull(selection.pageIndex) ?: return
        val oldStrokes = selection.oldStrokeStates
        val oldTexts = selection.oldTextStates
        val oldImages = selection.oldImageStates

        val transform = Matrix2D(selection.transform)
        val textResized = selection.isSingleText && oldTexts != null &&
                !oldTexts[0].values.contentEquals(ItemState.of(selection.texts[0]).values)
        if (transform.isIdentity && !textResized) {
            selection.oldStrokeStates = null
            return
        }

        // Target page: the one under the transformed selection center
        val center = transform.mapPoint(selection.boundingBox.center.x, selection.boundingBox.center.y)
        val oldMmToWorld = controller.pageMmToWorld(selection.pageIndex) ?: return
        val worldCenter = oldMmToWorld.mapPoint(center.x, center.y)
        val targetIndex = controller.findPageIndexAtWorld(worldCenter, nearest = true)
        val newPage = doc.pages.getOrNull(targetIndex) ?: oldPage

        val finalTransform = Matrix2D(transform)
        if (newPage !== oldPage) {
            val newMmToWorld = controller.pageMmToWorld(targetIndex) ?: return
            val worldToNewMm = Matrix2D()
            if (!newMmToWorld.invert(worldToNewMm)) return
            finalTransform.postConcat(oldMmToWorld).postConcat(worldToNewMm)
        }
        val dirtyOld = selection.getEnclosingBoundingBoxMm()

        // --- Apply to the elements ---
        val scale = finalTransform.scaleFactor
        val angle = finalTransform.rotationDegrees
        for (stroke in selection.strokes) {
            transformStroke(stroke, finalTransform)
        }
        for (text in selection.texts) {
            val c = finalTransform.mapPoint(text.centerX, text.centerY)
            text.width *= scale; text.height *= scale; text.fontSize *= scale
            text.rotation = (text.rotation + angle) % 360f
            text.x = c.x - text.width / 2f; text.y = c.y - text.height / 2f
            text.markDirty()
        }
        for (image in selection.images) {
            val c = finalTransform.mapPoint(image.centerX, image.centerY)
            image.width *= scale; image.height *= scale
            image.rotation = (image.rotation + angle) % 360f
            image.x = c.x - image.width / 2f; image.y = c.y - image.height / 2f
        }

        if (newPage !== oldPage) {
            oldPage.strokes.removeAll(selection.strokes)
            oldPage.texts.removeAll(selection.texts)
            oldPage.images.removeAll(selection.images)
            newPage.strokes.addAll(selection.strokes)
            newPage.texts.addAll(selection.texts)
            newPage.images.addAll(selection.images)
            selection.pageIndex = targetIndex
        }
        oldPage.markContentChanged()
        newPage.markContentChanged()

        // --- New frame ---
        when {
            selection.isSingleText -> {
                val t = selection.texts[0]
                selection.boundingBox = Rect(t.x, t.y, t.x + t.width, t.y + t.height)
                selection.baseRotation = t.rotation
            }
            selection.isSingleImage -> {
                val i = selection.images[0]
                selection.boundingBox = Rect(i.x, i.y, i.x + i.width, i.y + i.height)
                selection.baseRotation = i.rotation
            }
            else -> {
                selection.baseRotation = 0f
                val rects = selection.images.map { it.getRotatedBounds() } +
                        selection.texts.map { it.getRotatedBounds() } +
                        selection.strokes.map { it.bounds }
                selection.boundingBox = Rect(
                    rects.minOf { it.left }, rects.minOf { it.top },
                    rects.maxOf { it.right }, rects.maxOf { it.bottom }
                )
            }
        }
        selection.transform.reset()

        if (oldStrokes != null && oldTexts != null && oldImages != null) {
            controller.historyManager.addHistoryAction(
                TransformSelectionAction(
                    oldPage = oldPage,
                    newPage = newPage,
                    strokes = selection.strokes.toList(),
                    texts = selection.texts.toList(),
                    images = selection.images.toList(),
                    oldStrokeStates = oldStrokes,
                    newStrokeStates = selection.strokes.map { StrokeState.of(it) },
                    oldTextStates = oldTexts,
                    newTextStates = selection.texts.map { ItemState.of(it) },
                    oldImageStates = oldImages,
                    newImageStates = selection.images.map { ItemState.of(it) }
                )
            )
        }
        selection.oldStrokeStates = null

        val strokes = selection.strokes.toList()
        val texts = selection.texts.toList()
        val images = selection.images.toList()
        val pageChanged = newPage !== oldPage
        controller.persist {
            if (pageChanged) {
                deleteStrokes(strokes)
                saveNewStrokes(newPage, strokes)
            } else {
                updateStrokes(newPage, strokes)
            }
            texts.forEach { saveText(newPage, it) }
            images.forEach { saveImage(newPage, it) }
        }

        controller.invalidatePageContent(oldPage, dirtyOld)
        if (pageChanged) controller.invalidatePageContent(newPage, selection.getEnclosingBoundingBoxMm())
        notifyChanged()
    }

    /**
     * Applies an affine [transform] to the geometry of [stroke]: the mesh vertices are mapped
     * directly (what the user sees is exactly what is committed) and so are the recorded inputs,
     * with the brush size scaled accordingly.
     */
    internal fun transformStroke(stroke: DrawStroke, transform: Matrix2D) {
        val scale = transform.scaleFactor
        val mesh = stroke.mesh
        val partitions = mesh.partitions.map { partition ->
            val positions = partition.positions.copyOf()
            transform.mapPoints(positions)
            MeshPartition(positions, partition.indices)
        }
        val points = mesh.points.map { p ->
            val mapped = transform.mapPoint(p.x, p.y)
            StrokePoint(mapped.x, mapped.y, p.pressure, p.timestampMs)
        }
        val brush = stroke.brushConfig.copy(
            size = stroke.brushConfig.size * scale,
            epsilon = stroke.brushConfig.epsilon * scale
        )
        val newMesh = StrokeMesh(
            positions = partitions.first().positions,
            indices = partitions.first().indices,
            color = mesh.color,
            tool = mesh.tool,
            size = mesh.size * scale,
            points = points,
            additionalPartitions = partitions.drop(1),
            inputToolType = mesh.inputToolType
        )
        stroke.mesh = newMesh
        stroke.brushConfig = brush
        stroke.bounds = newMesh.computeBounds()
    }

    // ==========================================================
    // Clipboard
    // ==========================================================

    fun deleteSelection() {
        val selection = currentSelection ?: return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        currentSelection = null
        val strokes = selection.strokes.toList()
        val texts = selection.texts.toList()
        val images = selection.images.toList()
        page.strokes.removeAll(strokes)
        page.texts.removeAll(texts)
        page.images.removeAll(images)
        selection.setDragging(false)
        page.markContentChanged()
        controller.historyManager.addHistoryAction(DeleteElementsAction(page, strokes, texts, images))
        controller.persist {
            deleteStrokes(strokes)
            texts.forEach { deleteText(it) }
            images.forEach { deleteImage(it) }
        }
        controller.invalidatePageContent(page, selection.getEnclosingBoundingBoxMm())
        notifyChanged()
    }

    fun copySelection() {
        val selection = currentSelection ?: return
        clipboard = SelectionGroup(
            strokes = selection.strokes.toMutableList(),
            texts = selection.texts.toMutableList(),
            images = selection.images.toMutableList(),
            boundingBox = selection.boundingBox,
            pageIndex = selection.pageIndex
        ).apply { baseRotation = selection.baseRotation }
        clearSelection()
    }

    fun cutSelection() {
        val selection = currentSelection ?: return
        clipboard = SelectionGroup(
            strokes = selection.strokes.toMutableList(),
            texts = selection.texts.toMutableList(),
            images = selection.images.toMutableList(),
            boundingBox = selection.boundingBox,
            pageIndex = selection.pageIndex
        ).apply { baseRotation = selection.baseRotation }
        deleteSelection()
    }

    /**
     * Pastes the clipboard centered at [targetScreen] (or with a small offset from the copied
     * position when `null`), selecting the pasted elements.
     */
    fun pasteSelection(targetScreen: Offset? = null) {
        val copied = clipboard ?: return
        val doc = controller.document
        val targetIndex = targetScreen?.let { controller.findPageIndexAtScreen(it) }
            ?: controller.firstVisiblePageIndex()
            ?: copied.pageIndex
        val targetPage = doc.pages.getOrNull(targetIndex) ?: return
        clearSelection()

        var offsetX = 10f
        var offsetY = 10f
        if (targetScreen != null) {
            val mm = controller.screenToPageMm(targetIndex, targetScreen)
            if (mm != null) {
                offsetX = mm.x - copied.boundingBox.center.x
                offsetY = mm.y - copied.boundingBox.center.y
            }
        }
        val offset = Matrix2D().postTranslate(offsetX, offsetY)

        val pastedStrokes = copied.strokes.map { original ->
            DrawStroke(
                mesh = original.mesh,
                brushConfig = original.brushConfig,
                bounds = original.bounds,
                zIndex = targetPage.strokes.size
            ).also { transformStroke(it, offset) }
        }
        val pastedTexts = copied.texts.map { original ->
            DrawText(zIndex = targetPage.texts.size).apply {
                copyFrom(original)
                x = original.x + offsetX
                y = original.y + offsetY
            }
        }
        val pastedImages = copied.images.map { original ->
            DrawImage(zIndex = targetPage.images.size).apply {
                resourceId = original.resourceId
                x = original.x + offsetX
                y = original.y + offsetY
                width = original.width
                height = original.height
                rotation = original.rotation
                bitmap = original.bitmap
            }
        }

        targetPage.strokes.addAll(pastedStrokes)
        targetPage.texts.addAll(pastedTexts)
        targetPage.images.addAll(pastedImages)
        targetPage.markContentChanged()

        controller.historyManager.addHistoryAction(
            io.github.valerioisufi.drawview.core.history.BatchAction(
                listOfNotNull(
                    pastedStrokes.takeIf { it.isNotEmpty() }?.let {
                        io.github.valerioisufi.drawview.core.history.AddStrokesAction(targetPage, it)
                    },
                ) + pastedTexts.map { io.github.valerioisufi.drawview.core.history.AddTextAction(targetPage, it) } +
                        pastedImages.map { io.github.valerioisufi.drawview.core.history.AddImageAction(targetPage, it) }
            )
        )
        controller.persist {
            saveNewStrokes(targetPage, pastedStrokes)
            pastedTexts.forEach { saveText(targetPage, it) }
            pastedImages.forEach { saveImage(targetPage, it) }
        }

        val selection = SelectionGroup(
            strokes = pastedStrokes.toMutableList(),
            texts = pastedTexts.toMutableList(),
            images = pastedImages.toMutableList(),
            boundingBox = copied.boundingBox.translate(offsetX, offsetY),
            pageIndex = targetIndex
        ).apply { baseRotation = copied.baseRotation }
        setSelection(selection, targetPage)
    }

    // ==========================================================
    // Text formatting of the selection
    // ==========================================================

    fun updateSelectedTextsColor(color: Int) {
        val selection = currentSelection ?: return
        if (selection.texts.isEmpty()) return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        val texts = selection.texts.toList()
        val oldColors = texts.map { it.color }
        val sizes = texts.map { it.fontSize }
        texts.forEach { it.color = color; it.markDirty() }
        controller.historyManager.addHistoryAction(
            ChangeTextFormattingAction(page, texts, oldColors, texts.map { color }, sizes, sizes)
        )
        controller.persist { texts.forEach { saveText(page, it) } }
        notifyChanged()
    }

    fun updateSelectedTextsFontSize(fontSize: Float) {
        val selection = currentSelection ?: return
        if (selection.texts.isEmpty()) return
        val page = controller.document.pages.getOrNull(selection.pageIndex) ?: return
        val clamped = fontSize.coerceIn(8f, 72f)
        val texts = selection.texts.toList()
        val colors = texts.map { it.color }
        val oldSizes = texts.map { it.fontSize }
        texts.forEach {
            val ratio = clamped / it.fontSize.coerceAtLeast(0.1f)
            it.fontSize = clamped
            // The text box grows with the font (the wrapping width is preserved)
            it.height *= ratio
            it.markDirty()
        }
        if (selection.isSingleText) {
            val t = texts[0]
            selection.boundingBox = Rect(t.x, t.y, t.x + t.width, t.y + t.height)
        }
        controller.historyManager.addHistoryAction(
            ChangeTextFormattingAction(page, texts, colors, colors, oldSizes, texts.map { clamped })
        )
        controller.persist { texts.forEach { saveText(page, it) } }
        notifyChanged()
    }
}
