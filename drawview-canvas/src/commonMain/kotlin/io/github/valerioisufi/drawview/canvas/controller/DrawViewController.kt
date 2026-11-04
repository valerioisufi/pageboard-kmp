package io.github.valerioisufi.drawview.canvas.controller

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import io.github.valerioisufi.drawview.canvas.CanvasGestureHandler
import io.github.valerioisufi.drawview.canvas.platform.encodePng
import io.github.valerioisufi.drawview.canvas.DrawThemeColors
import io.github.valerioisufi.drawview.canvas.camera.Viewport
import io.github.valerioisufi.drawview.canvas.geometry.mapOffset
import io.github.valerioisufi.drawview.canvas.geometry.toCompose
import io.github.valerioisufi.drawview.canvas.geometry.toModel
import io.github.valerioisufi.drawview.canvas.history.HistoryManager
import io.github.valerioisufi.drawview.canvas.render.DrawViewEngines
import io.github.valerioisufi.drawview.canvas.render.HiddenElements
import io.github.valerioisufi.drawview.canvas.render.tile.TextBlockSize
import io.github.valerioisufi.drawview.canvas.render.tile.TextLineMetrics
import io.github.valerioisufi.drawview.canvas.render.tile.TextTileAdapter
import io.github.valerioisufi.drawview.canvas.render.tile.TileManager
import io.github.valerioisufi.drawview.canvas.tool.LassoMode
import io.github.valerioisufi.drawview.canvas.tool.Tool
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawCustomElement
import io.github.valerioisufi.drawview.model.DrawDocument
import io.github.valerioisufi.drawview.model.DrawElement
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawResource
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.SelectionActivity
import io.github.valerioisufi.drawview.model.StrokeMesh
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.ChangeObserver
import io.github.valerioisufi.drawview.model.change.ChangeOrigin
import io.github.valerioisufi.drawview.model.change.ChangePersister
import io.github.valerioisufi.drawview.model.change.CommittedChange
import io.github.valerioisufi.drawview.model.change.CompositeChange
import io.github.valerioisufi.drawview.model.change.DocumentChange
import io.github.valerioisufi.drawview.model.change.DocumentPersistence
import io.github.valerioisufi.drawview.model.change.DocumentSession
import io.github.valerioisufi.drawview.model.change.NoOpDocumentPersistence
import io.github.valerioisufi.drawview.model.change.PersistenceQueue
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
import io.github.valerioisufi.drawview.model.geometry.Matrix2D
import io.github.valerioisufi.drawview.model.geometry.Rect as MmRect
import io.github.valerioisufi.drawview.model.text.TextDefaults
import io.github.valerioisufi.drawview.model.text.TextSizes
import kotlin.math.ceil
import kotlin.math.hypot
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Central state holder of an editing session: document, tools, history, selection, text editing,
 * page management and persistence; what is shown of the document and the coordinate spaces are
 * in its [viewport], the other devices working on it in its [presence].
 *
 * Every piece of state observed by the UI is backed by Compose snapshot state.
 */
public class DrawViewController(
    public val document: DrawDocument = DrawDocument(),
    public val coroutineScope: CoroutineScope,
    density: Float = 1f,
    persistence: DocumentPersistence = NoOpDocumentPersistence,
    persistenceScope: CoroutineScope = coroutineScope,
    /**
     * Every change of the document goes through the session (the managers create the changes):
     * the controller, the history and the persistence observe it. A session given here is shared
     * with others (e.g. the synchronization, see `OpenDocuments`), whose owner stores its changes;
     * without one the controller creates it and stores its changes through [persistence].
     */
    sharedSession: DocumentSession? = null,
    /** The engines drawing the PDF pages and the formulas (none: drawn blank and as their source). */
    public val engines: DrawViewEngines = DrawViewEngines.None,
    /**
     * What is copied and pasted: the one of the application, shared by the editors of all its
     * documents (by default this editor's own).
     */
    public val clipboard: EditorClipboard = EditorClipboard()
) {
    public val session: DocumentSession = sharedSession ?: DocumentSession(document)
    private val storesChanges = sharedSession == null

    /** Persistence used by this session (Room in the apps, no-op for in-memory documents). */
    public val persistence: DocumentPersistence = persistence

    /**
     * Ordered queue of the persistence operations. It may run on its own [persistenceScope] (e.g.
     * a background scope that outlives the UI), so that pending changes can be flushed while the
     * editor is being closed.
     */
    private val persistenceQueue = PersistenceQueue(persistenceScope, persistence)

    public val historyManager: HistoryManager = HistoryManager(session)

    public val tileManager: TileManager = TileManager(onInvalidate = { requestRedraw() })

    /** What the editor shows of the document: the layout of the pages, the camera, the conversions. */
    public val viewport: Viewport = Viewport(document, density, tileManager, ::requestRedraw)

    /** The elements drawn over the tiles: selected, moved or written here or on another device. */
    internal val hiddenElements = HiddenElements()

    public val toolManager: ToolManager = ToolManager(
        onPresetsChanged = { _, _ -> preferencesChanged() },
        onToolSelected = { preferencesChanged() }
    )

    public val selectionManager: SelectionManager = SelectionManager(this)
    internal val eraserManager = EraserManager(this)
    public val textEditorManager: TextEditorManager = TextEditorManager(this)
    public val pageManager: PageManager = PageManager(this)
    public val mediaManager: MediaManager = MediaManager(this)
    public val placementManager: PlacementManager = PlacementManager(this)

    /** Places long texts in the notebook through the free space of the pages, by topic when it can. */
    public val textPlacer: TextPlacer = TextPlacer(this)

    /** A text brought into the notebook waiting for the student to say where it goes (the editor shows its sheet). */
    public var pendingTextImport: TextImport? by mutableStateOf(null)
    public val textMarkupManager: TextMarkupManager = TextMarkupManager(this)
    public val snapManager: SnapManager = SnapManager(this)

    /** Turns the strokes held still at their end into their shapes. */
    public val shapeSnapper: ShapeSnapper = ShapeSnapper(this)

    /** The elements of the types added by the application. */
    public val customElementManager: CustomElementManager = CustomElementManager(this)

    // ==========================================================
    // Observable state
    // ==========================================================

    /** Redraw counter: incremented whenever the canvas must be repainted. */
    public var version: Int by mutableIntStateOf(0)
        private set

    private val redrawPending = atomic(false)

    public var themeColors: DrawThemeColors by mutableStateOf(DrawThemeColors())

    private var drawingModeChosen by mutableStateOf(true)

    /** Drawing (the tools change the document) or reading; never drawing on a document only read here. */
    public val isDrawingMode: Boolean get() = drawingModeChosen && !isReadOnly

    /** The document may only be read here (see [DocumentSession.isReadOnly]), as Compose state. */
    public var isReadOnly: Boolean by mutableStateOf(session.isReadOnly)
        private set

    private val readOnlyWatch: Job = coroutineScope.launch { session.readOnly.collect { isReadOnly = it } }

    public var isStylusOnlyMode: Boolean by mutableStateOf(false)
        private set

    public var isPageGridVisible: Boolean by mutableStateOf(false)

    /** Screen position of the long-press context menu, or `null` when hidden. */
    public var contextMenuPosition: Offset? by mutableStateOf<Offset?>(null)

    /** The style of a new text: the one of the text written last. */
    public var textDefaults: TextDefaults by mutableStateOf(TextDefaults())
        private set

    public val selectedTool: Tool get() = toolManager.selectedTool

    public var lassoMode: LassoMode
        get() = selectionManager.lassoMode
        set(value) {
            selectionManager.lassoMode = value
        }

    internal val isPanMode: Boolean get() = toolManager.isPanTool

    public val canUndo: Boolean get() = historyManager.canUndo
    public val canRedo: Boolean get() = historyManager.canRedo

    /**
     * One-shot messages for the UI (snackbars/toasts); [PASTED_WITHOUT_MISSING_FILES]: elements of
     * an image or a PDF not on this device were left out of a paste.
     */
    public enum class Message { STYLUS_MODE_ACTIVATED, IMPORT_IMAGE_FAILED, IMPORT_PDF_FAILED, PDF_NOT_SUPPORTED, PDF_PAGE_WITHOUT_TEXT, PASTED_WITHOUT_MISSING_FILES }

    private val _messages = MutableSharedFlow<Message>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    public val messages: SharedFlow<Message> = _messages.asSharedFlow()

    internal fun emitMessage(message: Message) {
        _messages.tryEmit(message)
    }

    /** Measures the latency of the strokes drawn while [isInkLatencyMeasured]. */
    internal val inkLatencyMeter = InkLatencyMeter()

    private val inkLatencyState = MutableStateFlow<InkLatencyReport?>(null)
    private var measuringInkLatency by mutableStateOf(false)

    /**
     * Whether the editor measures the latency of the ink, reported by [inkLatency] at the end of
     * each stroke; off by default (the measure costs a little at every input and frame).
     */
    public var isInkLatencyMeasured: Boolean
        get() = measuringInkLatency
        set(value) {
            measuringInkLatency = value
            inkLatencyMeter.isEnabled = value
            if (!value) inkLatencyState.value = null
        }

    /** The latency of the ink over the last stroke measured (see [isInkLatencyMeasured]); `null` before. */
    public val inkLatency: StateFlow<InkLatencyReport?> = inkLatencyState.asStateFlow()

    /** The stroke measured ended (drawn, or given up). */
    internal fun inkStrokeEnded() {
        inkLatencyMeter.strokeEnded()?.let { inkLatencyState.value = it }
    }

    /** Set while a change whose elements are already drawn in the tiles is submitted. */
    private var changeAlreadyDrawn = false
    private val changeObserver = ChangeObserver(::onChangeCommitted)

    /** The heights of the texts as this device lays them out (before any change is observed). */
    internal val textBoxes: TextBoxes = TextBoxes(this)

    /** Draws the texts again when the formula renderer becomes ready (see [textRenderingChanged]). */
    private val formulasWatch: Job? = engines.formulas?.let { formulas ->
        // Ready already: nothing was drawn without the formulas
        val readyAtStart = formulas.isReady.value
        coroutineScope.launch {
            formulas.isReady.filter { it }.drop(if (readyAtStart) 1 else 0).collect { textRenderingChanged() }
        }
    }

    init {
        require(session.document === document) { "The session of another document" }
        tileManager.tileRenderer.pdfAdapter = engines.pdfTiles()
        tileManager.tileRenderer.isHidden = hiddenElements::isHidden
        tileManager.tileRenderer.elements = engines.elements
        if (storesChanges) session.addObserver(ChangePersister(::persist))
        session.addObserver(changeObserver)
        viewport.updatePageLayout()
        // A document always shows a page: added by a change, so that the other devices get it too
        if (document.pages.isEmpty()) pageManager.addNewPageAtBottom()
    }

    // ==========================================================
    // Modes and preferences
    // ==========================================================

    public fun selectTool(tool: Tool, presetIndex: Int? = null) {
        if (tool != Tool.LAZO) selectionManager.clearSelection()
        if (tool != Tool.TEXT && textEditorManager.isEditing) textEditorManager.finishEditing()
        textMarkupManager.clearSelection()
        toolManager.selectTool(tool, presetIndex)
        if (tool == Tool.TEXT_MARKUP) textMarkupManager.prefetchVisiblePages()
        requestRedraw()
    }

    public fun toggleDrawingMode() {
        if (session.isReadOnly) return
        drawingModeChosen = !drawingModeChosen
        textMarkupManager.clearSelection()
        if (!isDrawingMode) {
            selectionManager.clearSelection()
            textEditorManager.finishEditing()
            contextMenuPosition = null
        }
        requestRedraw()
    }

    public fun togglePageGrid() {
        isPageGridVisible = !isPageGridVisible
        // The selection of the pages lives in the grid
        pageManager.selection.clear()
        if (isPageGridVisible) {
            selectionManager.clearSelection()
            textMarkupManager.clearSelection()
            textEditorManager.finishEditing()
            contextMenuPosition = null
        }
    }

    public fun updateStylusOnlyMode(enabled: Boolean) {
        if (isStylusOnlyMode == enabled) return
        isStylusOnlyMode = enabled
        preferencesChanged()
    }

    /**
     * Called the first time a stylus touches the canvas: like the original app, the editor
     * switches to stylus-only mode (fingers pan and zoom).
     */
    internal fun onFirstStylusDetected() {
        if (!isStylusOnlyMode) {
            updateStylusOnlyMode(true)
            emitMessage(Message.STYLUS_MODE_ACTIVATED)
        }
    }

    /**
     * What the formatting of the texts applies to now: the text being written, the texts
     * selected, or `null`.
     */
    public val textFormatTarget: TextFormatTarget?
        get() = if (textEditorManager.isEditing) textEditorManager else selectionManager.selectedTexts

    public fun updateTextDefaults(defaults: TextDefaults) {
        val clamped = defaults.copy(fontSize = TextSizes.clamp(defaults.fontSize))
        if (textDefaults == clamped) return
        textDefaults = clamped
        preferencesChanged()
    }

    private val preferencesState = MutableStateFlow(DrawPreferences())

    /**
     * The preferences of the user, as they change (the presets of the tools, the stylus only
     * mode, the last tool, the defaults of the texts, the snapping, the shapes): whoever keeps them collects
     * them and gives them back with [applyPreferences].
     */
    public val preferences: StateFlow<DrawPreferences> = preferencesState.asStateFlow()

    /** Applies the preferences of the user kept (e.g. when the editor opens). */
    public fun applyPreferences(preferences: DrawPreferences) {
        isStylusOnlyMode = preferences.isStylusOnlyMode
        textDefaults = preferences.textDefaults
        snapManager.applyPreferences(preferences)
        shapeSnapper.applyPreferences(preferences)
        toolManager.applyPreferences(preferences)
        preferencesState.value = currentPreferences()
    }

    /** A preference of the user changed: [preferences] tells it. */
    internal fun preferencesChanged() {
        preferencesState.value = currentPreferences()
    }

    private fun currentPreferences() = DrawPreferences(
        isStylusOnlyMode = isStylusOnlyMode,
        lastSelectedTool = toolManager.selectedTool,
        textDefaults = textDefaults,
        isSnapEnabled = snapManager.isEnabled,
        presets = ToolManager.PRESET_TOOLS.associateWith { toolManager.presetsOf(it).toList() },
        isShapeRecognitionEnabled = shapeSnapper.isEnabled
    )

    // ==========================================================
    // Rendering requests
    // ==========================================================

    /** The gestures of the canvas shown, for what moves the camera over the elements above it (see `canvasGesturesOverField`). */
    internal var canvasGestures: CanvasGestureHandler? = null

    /**
     * Requests a canvas redraw. Safe to call from any thread: requests are coalesced and applied
     * on the session scope.
     */
    public fun requestRedraw() {
        if (redrawPending.compareAndSet(expect = false, update = true)) {
            coroutineScope.launch {
                redrawPending.value = false
                version++
            }
        }
    }

    public fun persist(operation: suspend DocumentPersistence.() -> Unit) {
        persistenceQueue.enqueue(operation)
    }

    /** Suspends until every pending persistence operation has been executed. */
    public suspend fun flushPersistence(): Unit = persistenceQueue.flush()

    /** The size of [text] as the renderer lays it out, or `null` without a text adapter. */
    public fun measureText(text: DrawText): TextBlockSize? = tileManager.tileRenderer.textAdapter?.measure(text)

    /**
     * The page [pageId] as a PNG, drawn as this editor draws it, at most [maxSidePx] on its longest
     * side; `null` when there is no such page.
     */
    public suspend fun renderPageImage(pageId: String, maxSidePx: Int): ByteArray? {
        val page = document.pages.firstOrNull { it.id == pageId } ?: return null
        val content = page.snapshotContent()
        return withContext(Dispatchers.Default) {
            val scale = maxSidePx / maxOf(content.widthMm, content.heightMm)
            val width = ceil(content.widthMm * scale).toInt().coerceAtLeast(1)
            val height = ceil(content.heightMm * scale).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(width, height)
            tileManager.tileRenderer.renderPageContent(
                Canvas(bitmap), document, content, Matrix2D().postScale(scale, scale), Rect(0f, 0f, width.toFloat(), height.toFloat())
            )
            encodePng(bitmap)
        }
    }

    /** The color of the paper of the page [pageIndex] (the backgrounds of the code of its texts are opaque on it). */
    internal fun paperOf(pageIndex: Int): Int = document.pages.getOrNull(pageIndex)?.background?.backgroundColor ?: TextTileAdapter.NO_PAPER

    /** A line of a text of [fontSize] points as the renderer lays it out, or `null` without a text adapter. */
    internal fun textLineMetrics(fontSize: Float): TextLineMetrics? = tileManager.tileRenderer.textAdapter?.lineMetrics(fontSize)

    /**
     * What draws the texts (Markdown and formulas) in the tiles, the thumbnails and the overlays:
     * set by the UI, which owns the fonts. The tiles are drawn again when it changes, and the
     * texts are laid out again (see [TextBoxes]).
     */
    public var textAdapter: TextTileAdapter?
        get() = tileManager.tileRenderer.textAdapter
        set(value) {
            if (tileManager.tileRenderer.textAdapter === value) return
            tileManager.tileRenderer.textAdapter = value
            textRenderingChanged()
        }

    /**
     * The texts are drawn differently (e.g. the formula renderer became ready): what the text
     * adapter kept of them is dropped, they are laid out again and the tiles are drawn again.
     */
    internal fun textRenderingChanged() {
        tileManager.tileRenderer.textAdapter?.clearCaches()
        textBoxes.adapterChanged(textAdapter)
        tileManager.invalidateAll()
        requestRedraw()
    }

    internal fun invalidatePageContent(page: DrawPage, mmRect: MmRect?) {
        val pageWorldRect = viewport.layout.pagesRectOnWindow.getOrNull(document.pages.indexOf(page))
        if (mmRect == null) tileManager.invalidatePage(page.renderKey)
        else tileManager.invalidatePageRegion(page, mmRect.toCompose(), pageWorldRect)
        requestRedraw()
    }

    /**
     * The file of [resource] arrived (from another device): the images and the PDF pages showing
     * it are drawn again.
     */
    public fun onResourceAvailable(resource: DrawResource) {
        for (page in document.pages) {
            if (page.images.any { it.resourceId == resource.id } || page.pdfs.any { it.resourceId == resource.id }) {
                invalidatePageContent(page, null)
            }
        }
    }

    /** The presence of the editor among the other devices working on the document. */
    public val presence: CollaboratorPresence = CollaboratorPresence(document, hiddenElements, ::invalidatePageContent) {
        // A text selected here that another device starts writing: left to it
        if (selectionManager.currentSelection?.texts?.any { presence.writerOf(it) != null } == true) selectionManager.clearSelection()
    }

    public fun clearSelection() {
        selectionManager.clearSelection()
        textMarkupManager.clearSelection()
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
        // Before the frames and the snapping read the boxes of the texts
        textBoxes.onChange(effects)
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
            viewport.markLayoutNeedsUpdate()
        }
        if (!changeAlreadyDrawn) {
            for ((page, rect) in effects.dirtyRegions) invalidatePageContent(page, rect)
        }
        requestRedraw()
    }

    // ==========================================================
    // Ink
    // ==========================================================

    /**
     * Brush of the active tool at the zoom level of page [pageIndex].
     */
    internal fun brushForActiveTool(pageIndex: Int): BrushConfig? =
        toolManager.brushFor(toolManager.selectedTool, viewport.pixelsPerMm(pageIndex))

    /**
     * Commits a completed ink stroke drawn on [originPageIndex] (mesh in the millimeters of that
     * page). Strokes crossing several pages are duplicated on every intersected page, each copy
     * expressed in the millimeters of its page, as in the original implementation.
     */
    internal fun commitInkStroke(mesh: StrokeMesh, originPageIndex: Int, brush: BrushConfig) {
        val originPage = document.pages.getOrNull(originPageIndex) ?: return
        if (mesh.isEmpty) return
        val bounds = mesh.computeBounds()

        val strokesByPage = LinkedHashMap<Int, MutableList<DrawStroke>>()
        val originToWorld = viewport.pageMmToWorld(originPageIndex)
        val worldBounds = originToWorld?.mapRect(bounds)?.toCompose()

        val targetIndexes = if (worldBounds != null) {
            viewport.layout.pagesRectOnWindow.indices.filter { viewport.layout.pagesRectOnWindow[it].overlaps(worldBounds) }
                .ifEmpty { listOf(originPageIndex) }
        } else listOf(originPageIndex)

        for (index in targetIndexes) {
            val page = document.pages.getOrNull(index) ?: continue
            // The copy on another page is the same stroke, placed in the millimeters of that page
            val transform = if (index != originPageIndex && originToWorld != null) {
                val worldToTarget = Matrix2D()
                if (viewport.pageMmToWorld(index)?.invert(worldToTarget) != true) continue
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
            pageRects = viewport.layout.pagesRectOnWindow,
            cameraScale = viewport.camera.getCurrentScale()
        ) {
            submitAlreadyDrawn(changes.singleOrNull() ?: CompositeChange(changes))
        }
    }

    // ==========================================================
    // History
    // ==========================================================

    public fun undo() {
        clearSelection()
        historyManager.undo()
    }

    public fun redo() {
        clearSelection()
        historyManager.redo()
    }

    /**
     * Releases the background resources of the session.
     */
    public fun dispose() {
        formulasWatch?.cancel()
        readOnlyWatch.cancel()
        textBoxes.dispose()
        // The session may go on without the editor (e.g. synchronized with other devices)
        session.removeObserver(changeObserver)
        historyManager.dispose()
        tileManager.cleanup()
    }
}
