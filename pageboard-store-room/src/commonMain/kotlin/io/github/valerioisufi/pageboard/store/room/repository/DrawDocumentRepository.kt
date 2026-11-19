package io.github.valerioisufi.pageboard.store.room.repository

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import io.github.valerioisufi.pageboard.model.DrawCustomElement
import io.github.valerioisufi.pageboard.model.DrawDocument
import io.github.valerioisufi.pageboard.model.DrawElement
import io.github.valerioisufi.pageboard.model.DrawImage
import io.github.valerioisufi.pageboard.model.DrawPage
import io.github.valerioisufi.pageboard.model.DrawPdf
import io.github.valerioisufi.pageboard.model.DrawResource
import io.github.valerioisufi.pageboard.model.DrawStroke
import io.github.valerioisufi.pageboard.model.DrawText
import io.github.valerioisufi.pageboard.model.DrawTextMarkup
import io.github.valerioisufi.pageboard.model.ElementOrder
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.PurgedContent
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokeModeller
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.TextAlignment
import io.github.valerioisufi.pageboard.model.TextMarkupKind
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.DocumentPersistence
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.change.StrokeData
import io.github.valerioisufi.pageboard.model.change.TextMarkupData
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.model.text.TextSizes
import io.github.valerioisufi.pageboard.platform.Sha256
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.store.DocumentOutline
import io.github.valerioisufi.pageboard.store.LoadedPage
import io.github.valerioisufi.pageboard.store.PageOutline
import io.github.valerioisufi.pageboard.store.room.db.CustomElementEntity
import io.github.valerioisufi.pageboard.store.room.db.DocumentEntity
import io.github.valerioisufi.pageboard.store.room.db.DrawDatabase
import io.github.valerioisufi.pageboard.store.room.db.ImageEntity
import io.github.valerioisufi.pageboard.store.room.db.OperationBaseEntity
import io.github.valerioisufi.pageboard.store.room.db.OperationEntity
import io.github.valerioisufi.pageboard.store.room.db.OperationProofEntity
import io.github.valerioisufi.pageboard.store.room.db.PageEntity
import io.github.valerioisufi.pageboard.store.room.db.PageWithContent
import io.github.valerioisufi.pageboard.store.room.db.PdfEntity
import io.github.valerioisufi.pageboard.store.room.db.PreferenceEntity
import io.github.valerioisufi.pageboard.store.room.db.ReplicaEntity
import io.github.valerioisufi.pageboard.store.room.db.ResourceEntity
import io.github.valerioisufi.pageboard.store.room.db.StrokeEntity
import io.github.valerioisufi.pageboard.store.room.db.StrokeInputCodec
import io.github.valerioisufi.pageboard.store.room.db.TextEntity
import io.github.valerioisufi.pageboard.store.room.db.TextMarkupEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Single source of truth of the documents: maps the Room entities to the in-memory model of
 * `:pageboard-canvas` and implements the [DocumentPersistence] port used by the editor.
 *
 * Every row has the identifier of its element in the model, with its order and the stamps of its
 * last changes. All the content coordinates are stored in page millimeters.
 */
internal class DrawDocumentRepository(
    val database: DrawDatabase,
    /** Models the strokes loaded from their inputs. */
    private val strokeModeller: StrokeModeller
) : DocumentPersistence {

    private val documentDao = database.documentDao()
    private val pageDao = database.pageDao()
    private val strokeDao = database.strokeDao()
    private val textDao = database.textDao()
    private val imageDao = database.imageDao()
    private val customElementDao = database.customElementDao()
    private val pdfDao = database.pdfDao()
    private val textMarkupDao = database.textMarkupDao()
    private val resourceDao = database.resourceDao()
    private val operationDao = database.operationDao()
    private val purgeDao = database.purgeDao()
    private val replicaDao = database.replicaDao()
    private val preferencesDao = database.preferencesDao()
    private val json = Json


    // ==========================================================
    // Loading
    // ==========================================================

    suspend fun loadDocument(documentId: String): DrawDocument? {
        val docWithPages = withContext(Dispatchers.IO) { documentDao.getFullDocumentWithPages(documentId) } ?: return null
        val resources = withContext(Dispatchers.IO) { resourceDao.getResourcesForDocument(documentId) }

        // Re-modeling the strokes is CPU bound
        return withContext(Dispatchers.Default) {
            val docEntity = docWithPages.document
            val doc = DrawDocument(
                id = docEntity.id,
                title = docEntity.name,
                defaultWidthMm = docEntity.defaultWidth,
                defaultHeightMm = docEntity.defaultHeight,
                defaultBackground = docEntity.defaultBackground,
                createdAt = docEntity.createdAt,
                modifiedAt = docEntity.modifiedAt,
                addInitialPage = false,
                strokeModeller = strokeModeller
            )
            doc.lastOpenedAt = docEntity.lastOpenedAt
            doc.defaultsVersion = docEntity.defaultsVersion
            doc.titleVersion = docEntity.titleVersion

            for (resource in resources) doc.addResource(mapResource(resource))

            val sortedPages = docWithPages.pages.sortedWith(compareBy({ it.page.orderKey }, { it.page.id }))
            // The strokes of all the pages modeled on every core, in the order of the pages
            val strokes = modelStrokes(sortedPages.flatMap { it.strokes })
            var nextStroke = 0
            for (pageWithContent in sortedPages) {
                val page = mapPage(pageWithContent.page, docEntity)

                val pageStrokes = strokes.subList(nextStroke, nextStroke + pageWithContent.strokes.size)
                nextStroke += pageStrokes.size
                val (removed, shown) = (pageStrokes + elementsBesideStrokes(pageWithContent) { true }).sortedBy { it.order }.partition { it.removed != null }
                // The removed elements are kept, hidden
                page.load(shown, removed)
                if (page.isHidden) doc.removedPages[page.id] = page else doc.appendPage(page)
            }
            doc.reindexPages()
            doc
        }
    }

    /**
     * The page at [index] of the document [documentId] among its pages shown, alone: its elements
     * shown (the ones removed are not read further, nor modeled) and the resources of the document.
     */
    suspend fun loadPage(documentId: String, index: Int): LoadedPage? {
        val (docEntity, pageEntity) = shownPage(documentId, index) ?: return null
        val rows = withContext(Dispatchers.IO) { pageDao.getPageWithContent(pageEntity.id) } ?: return null
        val resources = withContext(Dispatchers.IO) { resourceDao.getResourcesForDocument(documentId) }
        return withContext(Dispatchers.Default) {
            val page = mapPage(rows.page, docEntity)
            page.load((modelStrokes(rows.strokes.filter { it.removed == null }) + elementsBesideStrokes(rows) { it == null }).sortedBy { it.order })
            LoadedPage(page.id, page.snapshotContent(), resources.map(::mapResource))
        }
    }

    /**
     * The fingerprint of the page at [index] of the document [documentId] among its pages shown
     * (see `DocumentStore.pageFingerprint`): read from the stamps of its elements shown as they
     * are stored, nothing modeled.
     */
    suspend fun pageFingerprint(documentId: String, index: Int): String? {
        val (docEntity, page) = shownPage(documentId, index) ?: return null
        val elements = withContext(Dispatchers.IO) { pageDao.getShownElementStamps(page.id) }
        val resourceIds = elements.mapNotNullTo(mutableSetOf()) { it.resourceId }
        val resources = if (resourceIds.isEmpty()) emptyList() else withContext(Dispatchers.IO) { resourceDao.getResourcesForDocument(documentId) }
        val text = StringBuilder()
        text.append(page.id).append('|').append(page.width).append('|').append(page.height).append('|')
            .append(page.background ?: docEntity.defaultBackground).append('\n')
        for (element in elements.sortedBy { it.id }) {
            text.append(element.id).append('|').append(element.added).append('|').append(element.placed).append('|')
                .append(element.ordered).append('|').append(element.updated)
            element.contentVersions?.let { text.append('|').append(it) }
            text.append('\n')
        }
        for (id in resourceIds.sorted()) {
            val resource = resources.firstOrNull { it.id == id }
            text.append(id).append('|').append(resource?.uri).append('|').append(resource?.contentHash).append('\n')
        }
        return Sha256.of(text.toString().encodeToByteArray())
    }

    /** The document [documentId] and the page at [index] among its pages shown, or `null`. */
    private suspend fun shownPage(documentId: String, index: Int): Pair<DocumentEntity, PageEntity>? {
        val docEntity = withContext(Dispatchers.IO) { documentDao.getDocumentById(documentId) } ?: return null
        val pages = withContext(Dispatchers.IO) { pageDao.getPagesForDocument(documentId) }
        val page = pages.sortedWith(compareBy({ it.orderKey }, { it.id }))
            .filterNot { mapPage(it, docEntity).isHidden }
            .getOrNull(index) ?: return null
        return docEntity to page
    }

    /** The strokes of [entities] modeled on every core, in their order, with their stamps. */
    private suspend fun modelStrokes(entities: List<StrokeEntity>): List<DrawStroke> = coroutineScope {
        entities.chunked(STROKES_PER_TASK).map { chunk ->
            async { chunk.map { e -> mapStroke(e).stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) } }
        }.awaitAll().flatten()
    }

    /** The elements of [rows] but its strokes whose removal is [read], with their order and stamps. */
    private fun elementsBesideStrokes(rows: PageWithContent, read: (removed: Stamp?) -> Boolean): List<DrawElement> =
        rows.texts.filter { read(it.removed) }.map { e -> mapText(e).stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) } +
            rows.images.filter { read(it.removed) }.map { e -> mapImage(e).stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) } +
            rows.pdfs.filter { read(it.removed) }.map { e -> mapPdf(e).stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) } +
            rows.markups.filter { read(it.removed) }.mapNotNull { e -> mapTextMarkup(e)?.stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) } +
            rows.customs.filter { read(it.removed) }.map { e -> mapCustomElement(e).stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) }

    /**
     * What can be read of a document without its strokes: its pages shown with their texts, PDF
     * pages and elements of the applications, its resources.
     */
    suspend fun loadOutline(documentId: String): DocumentOutline? {
        val docEntity = withContext(Dispatchers.IO) { documentDao.getDocumentById(documentId) } ?: return null
        val pages = withContext(Dispatchers.IO) { pageDao.getPageOutlines(documentId) }
        val resources = withContext(Dispatchers.IO) { resourceDao.getResourcesForDocument(documentId) }
        val shown = pages.sortedWith(compareBy({ it.page.orderKey }, { it.page.id }))
            .filterNot { mapPage(it.page, docEntity).isHidden }
            .map { rows ->
                PageOutline(
                    id = rows.page.id,
                    texts = rows.texts.filter { it.removed == null }
                        .map { e -> mapText(e).stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) }
                        .sortedBy { it.order },
                    customElements = rows.customs.filter { it.removed == null }
                        .map { e -> mapCustomElement(e).stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) }
                        .sortedBy { it.order },
                    pdfs = rows.pdfs.filter { it.removed == null }
                        .map { e -> mapPdf(e).stored(e.orderKey, e.added, e.removed, e.placed, e.ordered, e.updated) }
                        .sortedBy { it.order }
                )
            }
        return DocumentOutline(documentId, shown, resources.map(::mapResource))
    }

    private fun mapPage(entity: PageEntity, document: DocumentEntity): DrawPage = DrawPage(
        widthMm = entity.width,
        heightMm = entity.height,
        background = entity.background ?: document.defaultBackground,
        id = entity.id,
        orderKey = entity.orderKey
    ).apply {
        hasCustomBackground = entity.background != null
        added = entity.added
        removed = entity.removed
        orderVersion = entity.orderVersion
        formatVersion = entity.formatVersion
        contentVersions = entity.contentVersions
        removalSaw = entity.removalSaw
    }

    private fun mapResource(entity: ResourceEntity): DrawResource {
        val type = DrawResource.ResourceType.entries.firstOrNull { it.name.equals(entity.type, ignoreCase = true) }
            ?: DrawResource.ResourceType.COLOR
        return DrawResource(entity.id, type, entity.uri, entity.contentHash)
    }

    private fun <T : DrawElement> T.stored(order: ElementOrder, added: Stamp, removed: Stamp?, placed: Stamp, ordered: Stamp, updated: Stamp): T =
        apply {
            this.order = order
            this.added = added
            this.removed = removed
            this.placed = placed
            this.ordered = ordered
            this.updated = updated
        }

    private fun mapStroke(entity: StrokeEntity): DrawStroke = StrokeData(
        id = entity.id,
        points = StrokeInputCodec.decode(entity.inputs),
        tool = StrokeTool.fromName(entity.brushFamily) ?: StrokeTool.PRESSURE_PEN,
        size = entity.size,
        color = entity.color,
        inputToolType = InputToolType.fromName(entity.toolType),
        transform = entity.transform
    ).toElement(strokeModeller)

    private fun mapTextMarkup(entity: TextMarkupEntity): DrawTextMarkup? {
        val rects = try {
            json.decodeFromString<List<Float>>(entity.rects)
        } catch (e: Exception) {
            // Not shown, but kept in the database as it is
            println("DrawView: the text markup ${entity.id} cannot be read: ${e.message}")
            return null
        }
        return TextMarkupData(
            id = entity.id,
            resourceId = entity.resourceId,
            pdfPageIndex = entity.pdfPageIndex,
            pdfWidthPt = entity.pdfWidth,
            pdfHeightPt = entity.pdfHeight,
            startIndex = entity.startIndex,
            endIndex = entity.endIndex,
            text = entity.text,
            rects = rects,
            kind = TextMarkupKind.fromName(entity.kind) ?: TextMarkupKind.HIGHLIGHT,
            color = entity.color
        ).toElement()
    }

    private fun mapText(entity: TextEntity) = DrawText(entity.id).apply {
        text = entity.text
        alignment = TextAlignment.fromName(entity.alignment) ?: TextAlignment.START
        autoWidth = entity.autoWidth
        x = entity.x
        y = entity.y
        width = entity.width
        rotation = entity.rotation
        color = entity.color
        fontSize = entity.fontSize
        background = entity.background
        next = entity.next
        continuesLine = entity.continuesLine
        // Until it is laid out
        height = TextSizes.estimatedHeightMm(entity.text, entity.fontSize)
        contentVersions = entity.contentVersions
    }

    private fun mapCustomElement(entity: CustomElementEntity) = DrawCustomElement(entity.type, entity.id).apply {
        version = entity.version
        payload = entity.payload
        x = entity.x
        y = entity.y
        width = entity.width
        height = entity.height
        rotation = entity.rotation
    }

    private fun mapImage(entity: ImageEntity) = DrawImage(entity.id).apply {
        resourceId = entity.resourceId
        x = entity.x
        y = entity.y
        width = entity.width
        height = entity.height
        rotation = entity.rotation
    }

    private fun mapPdf(entity: PdfEntity) = DrawPdf(entity.pdfPageIndex, entity.id).apply { resourceId = entity.resourceId }

    // ==========================================================
    // Documents
    // ==========================================================

    /**
     * Creates a document with one page of the given format in [folderId].
     */
    suspend fun createDocument(
        name: String,
        widthMm: Float,
        heightMm: Float,
        background: PageBackground,
        folderId: String? = null
    ): DrawDocument {
        val now = currentTimeMillis()
        val document = DrawDocument(
            title = name,
            defaultWidthMm = widthMm,
            defaultHeightMm = heightMm,
            defaultBackground = background,
            createdAt = now,
            modifiedAt = now,
            strokeModeller = strokeModeller
        )
        document.lastOpenedAt = now
        withContext(Dispatchers.IO) {
            documentDao.insert(
                DocumentEntity(
                    id = document.id,
                    name = name,
                    folderId = folderId,
                    createdAt = now,
                    modifiedAt = now,
                    lastOpenedAt = now,
                    defaultWidth = widthMm,
                    defaultHeight = heightMm,
                    defaultBackground = background
                )
            )
        }
        savePage(document, document.pages.single())
        return document
    }

    /**
     * Stores [document] with its whole content (removed pages and elements included) in
     * [folderId] (see [io.github.valerioisufi.pageboard.store.DocumentStore.insertDocument]).
     */
    suspend fun insertDocument(document: DrawDocument, folderId: String?, versionVector: Map<String, Long> = emptyMap()) {
        val now = currentTimeMillis()
        withContext(Dispatchers.IO) {
            documentDao.insert(
                DocumentEntity(
                    id = document.id,
                    name = document.title,
                    folderId = folderId,
                    createdAt = now,
                    modifiedAt = now,
                    lastOpenedAt = now,
                    defaultWidth = document.defaultWidthMm,
                    defaultHeight = document.defaultHeightMm,
                    defaultBackground = document.defaultBackground,
                    defaultsVersion = document.defaultsVersion,
                    titleVersion = document.titleVersion
                )
            )
        }
        // The operations it was received with (their changes are in it)
        withContext(Dispatchers.IO) {
            for ((replica, sequence) in versionVector) operationDao.upsertBase(OperationBaseEntity(document.id, replica, sequence))
        }
        document.resources.forEach { saveResource(document, it) }
        for (page in document.allPages()) {
            savePage(document, page)
            val elements = page.elements() + page.removedElements
            saveStrokes(page, elements.filterIsInstance<DrawStroke>())
            for (element in elements) {
                when (element) {
                    is DrawStroke -> {}
                    is DrawText -> saveText(page, element)
                    is DrawImage -> saveImage(page, element)
                    is DrawPdf -> savePdf(page, element)
                    is DrawTextMarkup -> saveTextMarkup(page, element)
                    is DrawCustomElement -> saveCustomElement(page, element)
                }
            }
        }
    }

    // ==========================================================
    // Replication
    // ==========================================================

    /** This device in the changes of the documents (created the first time). */
    suspend fun replicaId(): String = withContext(Dispatchers.IO) {
        replicaDao.get() ?: run {
            replicaDao.insert(ReplicaEntity(replicaId = newElementId()))
            checkNotNull(replicaDao.get())
        }
    }

    /** The last operation of every replica in the log of [documentId]. */
    suspend fun versionVector(documentId: String): Map<String, Long> = withContext(Dispatchers.IO) {
        // With the operations no longer in the log
        val vector = operationDao.bases(documentId).associate { it.replica to it.sequence }.toMutableMap()
        for (last in operationDao.lastSequences(documentId)) vector[last.replica] = maxOf(vector[last.replica] ?: 0L, last.sequence)
        vector
    }

    suspend fun compactedOperations(documentId: String): Map<String, Long> = withContext(Dispatchers.IO) {
        operationDao.bases(documentId).associate { it.replica to it.sequence }
    }

    /**
     * Removes from the log of [documentId] its operations up to [upTo] (a version vector),
     * remembering them: the stamps of the last ones, by replica.
     */
    suspend fun compactOperations(documentId: String, upTo: Map<String, Long>): Map<String, Stamp> = withContext(Dispatchers.IO) {
        val bases = operationDao.baseEntities(documentId).associateBy { it.replica }
        for ((replica, sequence) in upTo) {
            if (sequence <= (bases[replica]?.sequence ?: 0L)) continue
            val stamp = operationDao.stampOf(documentId, replica, sequence) ?: continue
            operationDao.deleteUpTo(documentId, replica, sequence)
            operationDao.deleteProofsUpTo(documentId, replica, sequence)
            operationDao.upsertBase(OperationBaseEntity(documentId, replica, sequence, stamp))
        }
        operationDao.baseEntities(documentId).associate { it.replica to it.stamp }
    }

    override suspend fun deleteForGood(document: DrawDocument, content: PurgedContent) = withContext(Dispatchers.IO) {
        purgeDao.deletePages(content.pages.map { it.id })
        val elements = content.elements.map { it.second }
        purgeDao.deleteStrokes(elements.filterIsInstance<DrawStroke>().map { it.id })
        purgeDao.deleteTexts(elements.filterIsInstance<DrawText>().map { it.id })
        purgeDao.deleteImages(elements.filterIsInstance<DrawImage>().map { it.id })
        purgeDao.deleteCustomElements(elements.filterIsInstance<DrawCustomElement>().map { it.id })
        purgeDao.deletePdfs(elements.filterIsInstance<DrawPdf>().map { it.id })
        purgeDao.deleteTextMarkups(elements.filterIsInstance<DrawTextMarkup>().map { it.id })
    }

    /** The proofs kept of the operations [ids] of [documentId] (see `OperationLog.proofs`). */
    suspend fun proofs(documentId: String, ids: Collection<OperationId>): Map<OperationId, ByteArray> = withContext(Dispatchers.IO) {
        ids.groupBy({ it.replica }, { it.sequence }).flatMap { (replica, sequences) ->
            sequences.chunked(MAX_QUERY_ARGUMENTS).flatMap { operationDao.proofs(documentId, replica, it) }
        }.associate { OperationId(it.replica, it.sequence) to it.proof }
    }

    /** Keeps [proofs] of operations of [documentId] (not for a document deleted meanwhile). */
    suspend fun saveProofs(documentId: String, proofs: Map<OperationId, ByteArray>) = withContext(Dispatchers.IO) {
        if (proofs.isEmpty() || documentDao.getDocumentById(documentId) == null) return@withContext
        operationDao.upsertProofs(proofs.map { (id, proof) -> OperationProofEntity(documentId, id.replica, id.sequence, proof) })
    }

    /** The operations of [documentId] after [known] (a version vector), in stamp order. */
    suspend fun operationsAfter(documentId: String, known: Map<String, Long>): List<Operation> = withContext(Dispatchers.IO) {
        // Only the ones missing read (the whole log of a document worked on for long is large)
        operationDao.lastSequences(documentId)
            .filter { it.sequence > (known[it.replica] ?: 0L) }
            .flatMap { operationDao.getAfter(documentId, it.replica, known[it.replica] ?: 0L) }
            .sortedBy { it.stamp }
            .map { Operation(OperationId(it.replica, it.sequence), it.stamp, json.decodeFromString(DocumentChange.serializer(), it.change)) }
    }

    // ==========================================================
    // Preferences
    // ==========================================================

    /** The preference [key] (see `PreferenceStore`). */
    suspend fun preference(key: String): String? = withContext(Dispatchers.IO) { preferencesDao.get(key) }

    /** Keeps [value] as the preference [key] (`null`: forgets it). */
    suspend fun setPreference(key: String, value: String?) = withContext(Dispatchers.IO) {
        if (value == null) preferencesDao.delete(key) else preferencesDao.upsert(PreferenceEntity(key, value))
    }

    // ==========================================================
    // DocumentPersistence (the port of the documents)
    // ==========================================================

    // The stroke as it was drawn, and its placement
    private fun DrawStroke.toEntity(pageId: String) = StrokeEntity(
        id = id,
        pageId = pageId,
        orderKey = order,
        color = baseBrush.color,
        size = baseBrush.size,
        toolType = baseMesh.inputToolType.name,
        brushFamily = baseBrush.tool.name,
        inputs = StrokeInputCodec.encode(baseMesh.points),
        transform = transform,
        added = added,
        removed = removed,
        placed = placed,
        ordered = ordered,
        updated = updated
    )

    /**
     * Runs [block] in one write transaction: its writes are stored all or none, and the flows that
     * read the tables it wrote are told once. The DAOs called in the block take its connection from
     * the coroutine context, which the `withContext(Dispatchers.IO)` of the writes keeps.
     */
    override suspend fun <T> transaction(block: suspend DocumentPersistence.() -> T): T =
        database.useWriterConnection { transactor -> transactor.immediateTransaction { block(this@DrawDocumentRepository) } }

    override suspend fun saveStrokes(page: DrawPage, strokes: List<DrawStroke>) {
        if (strokes.isEmpty()) return
        withContext(Dispatchers.IO) { strokeDao.upsertAll(strokes.map { it.toEntity(page.id) }) }
    }

    override suspend fun saveText(page: DrawPage, text: DrawText) = withContext(Dispatchers.IO) {
        textDao.upsert(
            TextEntity(
                id = text.id,
                pageId = page.id,
                orderKey = text.order,
                text = text.text,
                x = text.x,
                y = text.y,
                width = text.width,
                rotation = text.rotation,
                color = text.color,
                fontSize = text.fontSize,
                alignment = text.alignment.name,
                autoWidth = text.autoWidth,
                background = text.background,
                next = text.next,
                continuesLine = text.continuesLine,
                added = text.added,
                removed = text.removed,
                placed = text.placed,
                ordered = text.ordered,
                updated = text.updated,
                contentVersions = text.contentVersions
            )
        )
    }

    override suspend fun saveImage(page: DrawPage, image: DrawImage) = withContext(Dispatchers.IO) {
        imageDao.upsert(
            ImageEntity(
                id = image.id,
                pageId = page.id,
                orderKey = image.order,
                resourceId = image.resourceId,
                x = image.x,
                y = image.y,
                width = image.width,
                height = image.height,
                rotation = image.rotation,
                added = image.added,
                removed = image.removed,
                placed = image.placed,
                ordered = image.ordered,
                updated = image.updated
            )
        )
    }

    override suspend fun saveCustomElement(page: DrawPage, element: DrawCustomElement) = withContext(Dispatchers.IO) {
        customElementDao.upsert(
            CustomElementEntity(
                id = element.id,
                pageId = page.id,
                orderKey = element.order,
                type = element.type,
                version = element.version,
                payload = element.payload,
                x = element.x,
                y = element.y,
                width = element.width,
                height = element.height,
                rotation = element.rotation,
                added = element.added,
                removed = element.removed,
                placed = element.placed,
                ordered = element.ordered,
                updated = element.updated
            )
        )
    }

    override suspend fun savePdf(page: DrawPage, pdf: DrawPdf) = withContext(Dispatchers.IO) {
        pdfDao.upsert(
            PdfEntity(
                id = pdf.id,
                pageId = page.id,
                orderKey = pdf.order,
                resourceId = pdf.resourceId,
                pdfPageIndex = pdf.pdfPageIndex,
                added = pdf.added,
                removed = pdf.removed,
                placed = pdf.placed,
                ordered = pdf.ordered,
                updated = pdf.updated
            )
        )
    }

    override suspend fun saveTextMarkup(page: DrawPage, markup: DrawTextMarkup) = withContext(Dispatchers.IO) {
        textMarkupDao.upsert(
            TextMarkupEntity(
                id = markup.id,
                pageId = page.id,
                orderKey = markup.order,
                resourceId = markup.resourceId,
                pdfPageIndex = markup.pdfPageIndex,
                pdfWidth = markup.pdfWidthPt,
                pdfHeight = markup.pdfHeightPt,
                startIndex = markup.startIndex,
                endIndex = markup.endIndex,
                text = markup.text,
                rects = json.encodeToString(markup.toData().rects),
                kind = markup.kind.name,
                color = markup.color,
                added = markup.added,
                removed = markup.removed,
                placed = markup.placed,
                ordered = markup.ordered,
                updated = markup.updated
            )
        )
    }

    override suspend fun saveResource(document: DrawDocument, resource: DrawResource) = withContext(Dispatchers.IO) {
        resourceDao.upsert(ResourceEntity(id = resource.id, documentId = document.id, type = resource.type.name, uri = resource.path, contentHash = resource.hash))
    }

    override suspend fun fileWithContent(hash: String): String? =
        if (hash.isEmpty()) null else withContext(Dispatchers.IO) { resourceDao.findUriByHash(hash) }

    override suspend fun savePage(document: DrawDocument, page: DrawPage) = withContext(Dispatchers.IO) {
        pageDao.upsert(
            PageEntity(
                id = page.id,
                documentId = document.id,
                orderKey = page.orderKey,
                width = page.widthMm,
                height = page.heightMm,
                background = if (page.hasCustomBackground) page.background else null,
                added = page.added,
                removed = page.removed,
                orderVersion = page.orderVersion,
                formatVersion = page.formatVersion,
                contentVersions = page.contentVersions,
                removalSaw = page.removalSaw
            )
        )
    }

    override suspend fun updateDocumentDefaults(document: DrawDocument) = withContext(Dispatchers.IO) {
        documentDao.updateDocumentDefaults(
            document.id, document.defaultBackground, document.defaultWidthMm, document.defaultHeightMm, document.defaultsVersion
        )
    }

    override suspend fun updateDocumentTitle(document: DrawDocument) = withContext(Dispatchers.IO) {
        documentDao.updateDocumentTitle(document.id, document.title, document.titleVersion)
    }

    override suspend fun touchDocument(document: DrawDocument, timestamp: Long) = withContext(Dispatchers.IO) {
        documentDao.touchDocument(document.id, timestamp)
    }

    override suspend fun appendOperation(document: DrawDocument, operation: Operation) = withContext(Dispatchers.IO) {
        operationDao.insert(
            OperationEntity(
                documentId = document.id,
                replica = operation.id.replica,
                sequence = operation.id.sequence,
                stamp = operation.stamp,
                change = json.encodeToString(DocumentChange.serializer(), operation.change)
            )
        )
    }
}

/** The most values of a query at once (SQLite takes 999 at least). */
private const val MAX_QUERY_ARGUMENTS = 500

/** The strokes modeled by one task as a document is loaded: about 10 ms of work for Ink. */
private const val STROKES_PER_TASK = 64
