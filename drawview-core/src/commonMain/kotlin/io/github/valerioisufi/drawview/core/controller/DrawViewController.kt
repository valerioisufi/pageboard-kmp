package io.github.valerioisufi.drawview.core.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.valerioisufi.drawview.core.camera.CalcPage
import io.github.valerioisufi.drawview.core.camera.CameraPhysicsEngine
import io.github.valerioisufi.drawview.core.change.AddElements
import io.github.valerioisufi.drawview.core.change.ChangeObserver
import io.github.valerioisufi.drawview.core.change.ChangeOrigin
import io.github.valerioisufi.drawview.core.change.CommittedChange
import io.github.valerioisufi.drawview.core.change.CompositeChange
import io.github.valerioisufi.drawview.core.change.DocumentChange
import io.github.valerioisufi.drawview.core.change.DocumentSession
import io.github.valerioisufi.drawview.core.change.TextData
import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.geometry.Matrix2D
import io.github.valerioisufi.drawview.core.history.HistoryManager
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.DrawThemeColors
import io.github.valerioisufi.drawview.core.model.LassoMode
import io.github.valerioisufi.drawview.core.model.StrokeMesh
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.core.render.tile.TileManager
import kotlin.math.hypot
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Central state holder of an editing session: document, camera, page layout, tools, history,
 * selection, text editing, page management and persistence.
 *
 * Every piece of state observed by the UI is backed by Compose snapshot state.
 *
 * Coordinate spaces:
 * - **page millimeters**: content of a page (strokes, texts, images), origin at the top-left
 *   corner of the page;
 * - **world**: pages laid out vertically by [CalcPage] to fit the viewport width;
 * - **screen**: world transformed by the camera ([getRenderMatrix]).
 */
class DrawViewController(
    val document: DrawDocument = DrawDocument(),
    val coroutineScope: CoroutineScope,
    density: Float = 1f,
    persistence: DrawPersistence = NoOpDrawPersistence,
    persistenceScope: CoroutineScope = coroutineScope,
    /**
     * Every change of the document goes through the session (the managers create the changes):
     * the controller, the history and the persistence observe it. A session given here is shared
     * with others (e.g. the synchronization, see `OpenDocuments`), whose owner stores its changes;
     * without one the controller creates it and stores its changes through [persistence].
     */
    sharedSession: DocumentSession? = null
) {
    val session: DocumentSession = sharedSession ?: DocumentSession(document)
    private val storesChanges = sharedSession == null

    /** Persistence used by this session (Room in the apps, no-op for in-memory documents). */
    val persistence: DrawPersistence = persistence

    /**
     * Ordered queue of the persistence operations. It may run on its own [persistenceScope] (e.g.
     * a background scope that outlives the UI), so that pending changes can be flushed while the
     * editor is being closed.
     */
    private val persistenceQueue = PersistenceQueue(persistenceScope, persistence)

    var density: Float = density
        set(value) {
            if (field == value) return
            field = value
            calcPage.density = value
            cameraPhysics.density = value
            calcPage.needToBeUpdated = true
            updatePageLayout()
        }

    val calcPage = CalcPage(density = density)

    val cameraPhysics = CameraPhysicsEngine(
        density = density,
        getContentRect = { calcPage.contentRect }
    )

    val historyManager = HistoryManager(session)

    val tileManager = TileManager(onInvalidate = { requestRedraw() })

    val toolManager = ToolManager(
        onPresetsChanged = { tool, presets -> persist { savePresets(tool, presets) } },
        onToolSelected = { tool -> persist { saveLastSelectedTool(tool) } }
    )

    val selectionManager = SelectionManager(this)
    val eraserManager = EraserManager(this)
    val textEditorManager = TextEditorManager(this)
    val pageManager = PageManager(this)
    val mediaManager = MediaManager(this)
    val placementManager = PlacementManager(this)
    val textMarkupManager = TextMarkupManager(this)

    // ==========================================================
    // Observable state
    // ==========================================================

    /** Redraw counter: incremented whenever the canvas must be repainted. */
    var version by mutableIntStateOf(0)
        private set

    private val redrawPending = atomic(false)

    var viewportWidth by mutableFloatStateOf(0f)
        private set
    var viewportHeight by mutableFloatStateOf(0f)
        private set

    var themeColors by mutableStateOf(DrawThemeColors())

    var isDrawingMode by mutableStateOf(true)
        private set

    var isStylusOnlyMode by mutableStateOf(false)
        private set

    var isPageGridVisible by mutableStateOf(false)

    /** Screen position of the long-press context menu, or `null` when hidden. */
    var contextMenuPosition by mutableStateOf<Offset?>(null)

    var defaultTextFontSize by mutableFloatStateOf(16f)
        private set
    var defaultTextColor by mutableIntStateOf(0xFF000000.toInt())
        private set

    val selectedTool: Tool get() = toolManager.selectedTool

    var lassoMode: LassoMode
        get() = selectionManager.lassoMode
        set(value) {
            selectionManager.lassoMode = value
        }

    val isPanMode: Boolean get() = toolManager.isPanTool

    val canUndo: Boolean get() = historyManager.canUndo
    val canRedo: Boolean get() = historyManager.canRedo

    /** One-shot messages for the UI (snackbars/toasts). */
    enum class Message { STYLUS_MODE_ACTIVATED, IMPORT_IMAGE_FAILED, IMPORT_PDF_FAILED, PDF_NOT_SUPPORTED, PDF_PAGE_WITHOUT_TEXT }

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<Message> = _messages.asSharedFlow()

    internal fun emitMessage(message: Message) {
        _messages.tryEmit(message)
    }

    private val cachedRenderMatrix = Matrix2D()
    private val cachedInverseMatrix = Matrix2D()

    /** Set while a change whose elements are already drawn in the tiles is submitted. */
    private var changeAlreadyDrawn = false
    private val changeObserver = ChangeObserver(::onChangeCommitted)

    init {
        require(session.document === document) { "The session of another document" }
        if (storesChanges) session.addObserver(ChangePersister(::persist))
        session.addObserver(changeObserver)
        updatePageLayout()
        // A document always shows a page: added by a change, so that the other devices get it too
        if (document.pages.isEmpty()) pageManager.addNewPageAtBottom()
    }

    // ==========================================================
    // Modes and preferences
    // ==========================================================

    fun selectTool(tool: Tool, presetIndex: Int? = null) {
        if (tool != Tool.LAZO) selectionManager.clearSelection()
        if (tool != Tool.TEXT && textEditorManager.isEditing) textEditorManager.cancelTextEditing()
        textMarkupManager.clearSelection()
        toolManager.selectTool(tool, presetIndex)
        if (tool == Tool.TEXT_MARKUP) textMarkupManager.prefetchVisiblePages()
        requestRedraw()
    }

    fun toggleDrawingMode() {
        isDrawingMode = !isDrawingMode
        textMarkupManager.clearSelection()
        if (!isDrawingMode) {
            selectionManager.clearSelection()
            textEditorManager.cancelTextEditing()
            contextMenuPosition = null
        }
        requestRedraw()
    }

    fun togglePageGrid() {
        isPageGridVisible = !isPageGridVisible
        if (isPageGridVisible) {
            selectionManager.clearSelection()
            textMarkupManager.clearSelection()
            textEditorManager.cancelTextEditing()
            contextMenuPosition = null
        }
    }

    fun updateStylusOnlyMode(enabled: Boolean) {
        if (isStylusOnlyMode == enabled) return
        isStylusOnlyMode = enabled
        persist { saveStylusOnlyMode(enabled) }
    }

    /**
     * Called the first time a stylus touches the canvas: like the original app, the editor
     * switches to stylus-only mode (fingers pan and zoom).
     */
    fun onFirstStylusDetected() {
        if (!isStylusOnlyMode) {
            updateStylusOnlyMode(true)
            emitMessage(Message.STYLUS_MODE_ACTIVATED)
        }
    }

    fun updateTextDefaults(fontSize: Float, color: Int) {
        if (defaultTextFontSize == fontSize && defaultTextColor == color) return
        defaultTextFontSize = fontSize
        defaultTextColor = color
        persist { saveTextDefaults(fontSize, color) }
    }

    /**
     * Applies the global preferences loaded from the persistence layer.
     */
    fun applyPreferences(preferences: DrawPreferences) {
        isStylusOnlyMode = preferences.isStylusOnlyMode
        defaultTextFontSize = preferences.defaultTextFontSize
        defaultTextColor = preferences.defaultTextColor
        toolManager.applyPreferences(preferences)
    }

    // ==========================================================
    // Rendering requests
    // ==========================================================

    /**
     * Requests a canvas redraw. Safe to call from any thread: requests are coalesced and applied
     * on the session scope.
     */
    fun requestRedraw() {
        if (redrawPending.compareAndSet(expect = false, update = true)) {
            coroutineScope.launch {
                redrawPending.value = false
                version++
            }
        }
    }

    fun persist(operation: suspend DrawPersistence.() -> Unit) {
        persistenceQueue.enqueue(operation)
    }

    /** Suspends until every pending persistence operation has been executed. */
    suspend fun flushPersistence() = persistenceQueue.flush()

    fun invalidatePageContent(page: DrawPage, mmRect: Rect?) {
        val pageWorldRect = calcPage.pagesRectOnWindow.getOrNull(document.pages.indexOf(page))
        if (mmRect == null) tileManager.invalidatePage(page.renderKey)
        else tileManager.invalidatePageRegion(page, mmRect, pageWorldRect)
        requestRedraw()
    }

    /**
     * The file of [resource] arrived (from another device): the images and the PDF pages showing
     * it are drawn again.
     */
    fun onResourceAvailable(resource: DrawResource) {
        for (page in document.pages) {
            if (page.images.any { it.resourceId == resource.id } || page.pdfs.any { it.resourceId == resource.id }) {
                invalidatePageContent(page, null)
            }
        }
    }

    // ==========================================================
    // Activity (for the others working on the document)
    // ==========================================================

    private val currentActivity = MutableStateFlow<EditorActivity?>(null)
    private var pointerActivity: EditorActivity? = null
    private var textDraft: TextData? = null
    private var textDraftPageId: String? = null
    private var selectionActivity: SelectionActivity? = null

    /**
     * What the user is doing on the document now (the pointer, the stroke being drawn, the text
     * being written, the elements selected), for the others working on it; `null` when nothing.
     */
    val activity: StateFlow<EditorActivity?> = currentActivity.asStateFlow()

    /** Where the pointer is and the stroke being drawn (from the gestures). */
    internal fun reportActivity(activity: EditorActivity?) {
        pointerActivity = activity
        publishActivity()
    }

    /** The text being written on the page [pageId] as it is now (`null`: none). */
    internal fun reportTextDraft(pageId: String?, text: TextData?) {
        textDraftPageId = pageId
        textDraft = text
        publishActivity()
    }

    /** The elements selected (`null`: none). */
    internal fun reportSelection(selection: SelectionActivity?) {
        selectionActivity = selection
        publishActivity()
    }

    private fun publishActivity() {
        val activity = (pointerActivity ?: EditorActivity()).copy(text = textDraft, textPageId = textDraftPageId, selection = selectionActivity)
        currentActivity.value = activity.takeUnless { it.isIdle }
    }

    private var shownCollaborators by mutableStateOf(emptyList<Collaborator>())

    /**
     * The other devices working on the document, drawn over it: where they are, the strokes they
     * are drawing, the texts they are writing and the elements they select (kept by the
     * synchronization, see `drawview-sync-ui`). The texts they write and the elements they move
     * are hidden from the content meanwhile (see [DrawText.isShownByCollaborator]).
     */
    var collaborators: List<Collaborator>
        get() = shownCollaborators
        set(value) {
            showByCollaborators(shownCollaborators.hiddenElementIds(), value.hiddenElementIds())
            shownCollaborators = value
        }

    /** The elements the collaborators draw themselves: the texts written, the elements moved. */
    private fun List<Collaborator>.hiddenElementIds(): Set<String> = buildSet {
        for (collaborator in this@hiddenElementIds) {
            collaborator.text?.let { add(it.id) }
            if (collaborator.selectionTransform != null) collaborator.selection.forEach { add(it.id) }
        }
    }

    private fun showByCollaborators(before: Set<String>, after: Set<String>) {
        if (before == after) return
        for (id in before - after) setShownByCollaborator(id, false)
        for (id in after - before) setShownByCollaborator(id, true)
    }

    private fun setShownByCollaborator(id: String, shown: Boolean) {
        val (page, element) = document.findElement(id) ?: return
        when (element) {
            is DrawStroke -> element.isShownByCollaborator = shown
            is DrawText -> element.isShownByCollaborator = shown
            is DrawImage -> element.isShownByCollaborator = shown
            else -> return
        }
        invalidatePageContent(page, page.boundsOf(element))
    }

    fun clearSelection() {
        selectionManager.clearSelection()
        textMarkupManager.clearSelection()
    }

    fun markLayoutNeedsUpdate() {
        calcPage.needToBeUpdated = true
        updatePageLayout()
        tileManager.retainDocumentPages(document)
        cameraPhysics.restoreToBounds(animated = false)
        requestRedraw()
    }

    /**
     * Submits [change], made on this device, whose elements the caller has already drawn into
     * the tiles (the atomic hand-off of a live layer): its areas are not drawn again.
     */
    internal fun submitAlreadyDrawn(change: DocumentChange) {
        changeAlreadyDrawn = true
        try {
            session.submit(change)
        } finally {
            changeAlreadyDrawn = false
        }
    }

    /** Draws again what a change of the document touched, and leaves the states it made stale. */
    private fun onChangeCommitted(committed: CommittedChange) {
        val effects = committed.effects
        if (committed.origin == ChangeOrigin.REMOTE) {
            textEditorManager.onChange(effects)
            selectionManager.onChange(effects)
            textMarkupManager.onChange(effects)
            // The last pages removed on two devices at once: a document always has a page
            if (document.pages.isEmpty()) {
                coroutineScope.launch { if (document.pages.isEmpty()) pageManager.addNewPageAtBottom() }
            }
        }
        if (effects.layoutChanged) {
            markLayoutNeedsUpdate()
        }
        if (!changeAlreadyDrawn) {
            for ((page, rect) in effects.dirtyRegions) invalidatePageContent(page, rect)
        }
        requestRedraw()
    }

    /**
     * Draws [strokes], [texts] and [images] of [page] straight into the cached tiles (used when
     * the elements leave the live selection layer, to avoid a frame without them).
     */
    fun bakeElementsSync(page: DrawPage, strokes: List<DrawStroke>, texts: List<DrawText>, images: List<DrawImage>) {
        val index = document.pages.indexOf(page)
        if (index < 0) return
        if (strokes.isNotEmpty()) {
            tileManager.bakeStrokesDirectlySync(
                strokesByPage = mapOf(index to strokes),
                document = document,
                pageRects = calcPage.pagesRectOnWindow,
                cameraScale = cameraPhysics.getCurrentScale()
            )
        }
    }

    // ==========================================================
    // Layout and coordinates
    // ==========================================================

    /**
     * Updates viewport dimensions, keeping the content under the center of the viewport stable.
     */
    fun setViewport(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        val sizeChanged = viewportWidth != width || viewportHeight != height
        if (!sizeChanged && !calcPage.needToBeUpdated) return

        // Remember which point of which page is at the center of the viewport
        var anchor: Triple<Int, Float, Float>? = null
        val previousScale = cameraPhysics.getCurrentScale()
        val hadLayout = viewportWidth > 0f && calcPage.pagesRectOnWindow.isNotEmpty()
        if (hadLayout) {
            val center = screenToWorld(Offset(viewportWidth / 2f, viewportHeight / 2f))
            val rects = calcPage.pagesRectOnWindow
            var target = rects.lastIndex
            for (i in rects.indices) {
                val zoneBottom = if (i < rects.lastIndex) rects[i + 1].top else calcPage.contentRect.bottom
                if (center.y <= zoneBottom) {
                    target = i
                    break
                }
            }
            val r = rects[target]
            anchor = Triple(
                target,
                ((center.x - r.left) / r.width).coerceIn(0f, 1f),
                ((center.y - r.top) / r.height).coerceIn(0f, 1f)
            )
        }

        viewportWidth = width
        viewportHeight = height
        cameraPhysics.stopAllAnimations()
        cameraPhysics.setViewport(width, height)
        calcPage.needToBeUpdated = true
        updatePageLayout()

        if (anchor != null && anchor.first in calcPage.pagesRectOnWindow.indices) {
            val r = calcPage.pagesRectOnWindow[anchor.first]
            cameraPhysics.centerOnWorldPoint(
                worldX = r.left + r.width * anchor.second,
                worldY = r.top + r.height * anchor.third,
                scale = previousScale,
                screenWidth = width,
                screenHeight = height
            )
        } else {
            // First layout: show the top of the document at 1:1 (pages fit the viewport width)
            cameraPhysics.centerOnWorldPoint(
                worldX = width / 2f,
                worldY = height / 2f,
                scale = 1f,
                screenWidth = width,
                screenHeight = height
            )
        }
        cameraPhysics.restoreToBounds(animated = false)
        requestRedraw()
    }

    fun updatePageLayout() {
        if (viewportWidth > 0f && viewportHeight > 0f) {
            calcPage.calcPagesRectOnWindow(
                pages = document.pages,
                windowRect = Rect(0f, 0f, viewportWidth, viewportHeight)
            )
            // The pages laid out at another scale (window resized, widest page changed) are rasterized again
            tileManager.updateLayout(document.pages, calcPage.pagesRectOnWindow)
        }
    }

    /**
     * Retrieves the current camera matrix (transforms World space -> Screen space).
     */
    fun getRenderMatrix(outMatrix: Matrix2D = cachedRenderMatrix): Matrix2D {
        return cameraPhysics.getRenderMatrix(outMatrix)
    }

    /**
     * Retrieves the inverse camera matrix (transforms Screen space -> World space).
     */
    fun getInverseMatrix(outMatrix: Matrix2D = cachedInverseMatrix): Matrix2D {
        val render = cameraPhysics.getRenderMatrix(Matrix2D())
        render.invert(outMatrix)
        return outMatrix
    }

    fun screenToWorld(screenPoint: Offset): Offset {
        val inverse = getInverseMatrix(Matrix2D())
        return inverse.mapPoint(screenPoint.x, screenPoint.y)
    }

    fun worldToScreen(worldPoint: Offset): Offset {
        val render = cameraPhysics.getRenderMatrix(Matrix2D())
        return render.mapPoint(worldPoint.x, worldPoint.y)
    }

    /** Matrix mapping the millimeters of page [pageIndex] to world space. */
    fun pageMmToWorld(pageIndex: Int): Matrix2D? {
        val page = document.pages.getOrNull(pageIndex) ?: return null
        val worldRect = calcPage.pagesRectOnWindow.getOrNull(pageIndex) ?: return null
        return Matrix2D().setRectToRect(page.rect(), worldRect)
    }

    /** Matrix mapping the millimeters of page [pageIndex] to screen pixels. */
    fun pageMmToScreen(pageIndex: Int): Matrix2D? {
        val mmToWorld = pageMmToWorld(pageIndex) ?: return null
        return mmToWorld.postConcat(cameraPhysics.getRenderMatrix(Matrix2D()))
    }

    /** Converts a screen point to the millimeters of page [pageIndex]. */
    fun screenToPageMm(pageIndex: Int, screenPoint: Offset): Offset? {
        val mmToScreen = pageMmToScreen(pageIndex) ?: return null
        val inverse = Matrix2D()
        if (!mmToScreen.invert(inverse)) return null
        return inverse.mapPoint(screenPoint.x, screenPoint.y)
    }

    /** Current screen pixels per millimeter of page [pageIndex]. */
    fun pixelsPerMm(pageIndex: Int): Float {
        val page = document.pages.getOrNull(pageIndex) ?: return 1f
        val worldRect = calcPage.pagesRectOnWindow.getOrNull(pageIndex) ?: return 1f
        return worldRect.width / page.widthMm.coerceAtLeast(0.01f) * cameraPhysics.getCurrentScale()
    }

    /** Pages intersecting the viewport, with their screen rectangles. */
    fun visiblePages(): List<CalcPage.PageRectWithIndex> {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return emptyList()
        return calcPage.getPagesRectOnWindowTransformation(
            Rect(0f, 0f, viewportWidth, viewportHeight),
            cameraPhysics.getRenderMatrix(Matrix2D())
        )
    }

    /** Index of the page under [screenPoint], or `null`. */
    fun findPageIndexAtScreen(screenPoint: Offset): Int? =
        visiblePages().firstOrNull { it.rect.contains(screenPoint) }?.index

    /** Index of the visible page nearest to [screenPoint]. */
    fun nearestVisiblePageIndex(screenPoint: Offset): Int? =
        visiblePages().minByOrNull { hypot(it.rect.center.x - screenPoint.x, it.rect.center.y - screenPoint.y) }?.index

    fun firstVisiblePageIndex(): Int? = visiblePages().firstOrNull()?.index

    /**
     * Index of the page containing [worldPoint] (or the nearest one when [nearest] is `true`).
     */
    fun findPageIndexAtWorld(worldPoint: Offset, nearest: Boolean = false): Int {
        val rects = calcPage.pagesRectOnWindow
        for (i in rects.indices) {
            if (rects[i].contains(worldPoint)) return i
        }
        if (!nearest || rects.isEmpty()) return -1
        return rects.indices.minBy { i ->
            val r = rects[i]
            hypot(r.center.x - worldPoint.x, r.center.y - worldPoint.y)
        }
    }

    // ==========================================================
    // Ink
    // ==========================================================

    /**
     * Brush of the active tool at the zoom level of page [pageIndex].
     */
    fun brushForActiveTool(pageIndex: Int): BrushConfig? =
        toolManager.brushFor(toolManager.selectedTool, pixelsPerMm(pageIndex))

    /**
     * Commits a completed ink stroke drawn on [originPageIndex] (mesh in the millimeters of that
     * page). Strokes crossing several pages are duplicated on every intersected page, each copy
     * expressed in the millimeters of its page, as in the original implementation.
     */
    fun commitInkStroke(mesh: StrokeMesh, originPageIndex: Int, brush: BrushConfig) {
        val originPage = document.pages.getOrNull(originPageIndex) ?: return
        if (mesh.isEmpty) return
        val bounds = mesh.computeBounds()

        val strokesByPage = LinkedHashMap<Int, MutableList<DrawStroke>>()
        val originToWorld = pageMmToWorld(originPageIndex)
        val worldBounds = originToWorld?.mapRect(bounds)

        val targetIndexes = if (worldBounds != null) {
            calcPage.pagesRectOnWindow.indices.filter { calcPage.pagesRectOnWindow[it].overlaps(worldBounds) }
                .ifEmpty { listOf(originPageIndex) }
        } else listOf(originPageIndex)

        for (index in targetIndexes) {
            val page = document.pages.getOrNull(index) ?: continue
            // The copy on another page is the same stroke, placed in the millimeters of that page
            val transform = if (index != originPageIndex && originToWorld != null) {
                val worldToTarget = Matrix2D()
                if (pageMmToWorld(index)?.invert(worldToTarget) != true) continue
                AffineTransform.of(Matrix2D(originToWorld).postConcat(worldToTarget))
            } else {
                AffineTransform.IDENTITY
            }
            val stroke = DrawStroke(mesh = mesh, brushConfig = brush, transform = transform)
            strokesByPage.getOrPut(index) { mutableListOf() }.add(stroke)
        }
        if (strokesByPage.isEmpty()) return

        val changes = strokesByPage.map { (index, strokes) -> AddElements.of(document.pages[index], strokes) }
        // Atomic hand-off from the live layer to the tiles (no missing or doubled frame)
        tileManager.bakeStrokesDirectlySync(
            strokesByPage = strokesByPage,
            document = document,
            pageRects = calcPage.pagesRectOnWindow,
            cameraScale = cameraPhysics.getCurrentScale()
        ) {
            submitAlreadyDrawn(changes.singleOrNull() ?: CompositeChange(changes))
        }
    }

    // ==========================================================
    // History
    // ==========================================================

    fun undo() {
        clearSelection()
        historyManager.undo()
    }

    fun redo() {
        clearSelection()
        historyManager.redo()
    }

    // ==========================================================
    // Camera
    // ==========================================================

    fun zoomBy(factor: Float, focus: Offset = Offset(viewportWidth / 2f, viewportHeight / 2f)) {
        cameraPhysics.onDrag(0f, 0f, factor, focus.x, focus.y)
        cameraPhysics.restoreToBounds()
        requestRedraw()
    }

    fun zoomIn() = zoomBy(1.25f)

    fun zoomOut() = zoomBy(0.8f)

    /**
     * Pans the camera by ([dx], [dy]) screen pixels (e.g. to keep the edited text above the
     * software keyboard).
     */
    fun panCanvasBy(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        cameraPhysics.stopAllAnimations()
        cameraPhysics.onDrag(dx, dy, 1f, viewportWidth / 2f, viewportHeight / 2f)
        requestRedraw()
    }

    fun centerDocument() {
        if (viewportWidth <= 0f || viewportHeight <= 0f) return
        val content = calcPage.contentRect
        if (content.isEmpty) return
        cameraPhysics.centerOnWorldPoint(
            worldX = content.center.x,
            worldY = content.top + (viewportHeight / 2f),
            scale = 1f,
            screenWidth = viewportWidth,
            screenHeight = viewportHeight
        )
        cameraPhysics.restoreToBounds(animated = false)
        requestRedraw()
    }

    /**
     * Releases the background resources of the session.
     */
    fun dispose() {
        // The session may go on without the editor (e.g. synchronized with other devices)
        session.removeObserver(changeObserver)
        historyManager.dispose()
        tileManager.cleanup()
    }
}
