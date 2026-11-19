package io.github.valerioisufi.pageboard.store.room.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.valerioisufi.pageboard.model.PageBackground
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import kotlinx.coroutines.flow.Flow

/*
 * The rows are written with @Upsert (insert, or update the row with the same identifier): an
 * INSERT OR REPLACE would delete the row first, and the deletion of a page or of a document
 * cascades to its content.
 */

/**
 * Data transfer object representing a page and its associated graphical content.
 */
internal data class PageWithContent(
    @Embedded val page: PageEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val strokes: List<StrokeEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val texts: List<TextEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val images: List<ImageEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val pdfs: List<PdfEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val markups: List<TextMarkupEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val customs: List<CustomElementEntity>
)

/**
 * A page with what can be read of it without its strokes: its texts, its PDF pages and the elements
 * of the applications (see `DocumentOutline`).
 */
internal data class PageOutlineRows(
    @Embedded val page: PageEntity,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val texts: List<TextEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val pdfs: List<PdfEntity>,

    @Relation(
        parentColumn = "id",
        entityColumn = "pageId"
    )
    val customs: List<CustomElementEntity>
)

/**
 * An element shown on a page with the stamps of its changes as they are stored (and the content
 * versions of a text, the resource of an image, a PDF page or a mark): what the fingerprint of the
 * page is made of (see `DocumentStore.pageFingerprint`).
 */
internal data class ShownElementStamps(
    val id: String,
    val added: String,
    val placed: String,
    val ordered: String,
    val updated: String,
    val contentVersions: String?,
    val resourceId: String?
)

/**
 * Data model representing a document and its hierarchical content.
 */
internal data class DocumentWithPages(
    @Embedded val document: DocumentEntity,

    @Relation(
        entity = PageEntity::class,
        parentColumn = "id",
        entityColumn = "documentId"
    )
    val pages: List<PageWithContent>
)

/**
 * Data Access Object for managing folder structures within the database.
 */
@Dao
internal interface FolderDao {
    @Insert
    suspend fun insert(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE parentId = :parentId")
    suspend fun getSubFolders(parentId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolderById(id: String): FolderEntity?

    @Query("SELECT * FROM folders ORDER BY name")
    fun getAllFoldersFlow(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders")
    suspend fun getAllFolders(): List<FolderEntity>

    @Query("UPDATE folders SET trashedAt = :trashedAt WHERE id = :id")
    suspend fun setTrashedAt(id: String, trashedAt: Long?)

    @Query("UPDATE folders SET name = :name, parentId = :parentId, trashedAt = NULL WHERE id = :id")
    suspend fun restore(id: String, name: String, parentId: String?)

    @Query("UPDATE folders SET name = :newName, modifiedAt = :timestamp WHERE id = :id")
    suspend fun renameFolder(id: String, newName: String, timestamp: Long = currentTimeMillis())

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE folders SET parentId = :newParentId, modifiedAt = :timestamp WHERE id = :id")
    suspend fun moveFolder(id: String, newParentId: String?, timestamp: Long = currentTimeMillis())
}

/** A document with what the library tells of its content: its pages and whether it holds a PDF. */
internal data class DocumentWithContent(
    @Embedded val document: DocumentEntity,
    val pageCount: Int,
    val hasPdf: Boolean
)

/**
 * Data Access Object for document-level operations.
 */
@Dao
internal interface DocumentDao {
    @Insert
    suspend fun insert(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getDocumentById(documentId: String): DocumentEntity?

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getFullDocumentWithPages(documentId: String): DocumentWithPages?

    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE id = :documentId")
    suspend fun updateLastOpened(documentId: String, timestamp: Long = currentTimeMillis())

    @Query("DELETE FROM documents WHERE id = :documentId")
    suspend fun deleteById(documentId: String)

    @Query("SELECT * FROM documents WHERE folderId = :folderId")
    suspend fun getDocumentsInFolder(folderId: String): List<DocumentEntity>

    @Query("UPDATE documents SET name = :name, titleVersion = :version, modifiedAt = :timestamp WHERE id = :id")
    suspend fun updateDocumentTitle(id: String, name: String, version: Stamp, timestamp: Long = currentTimeMillis())

    @Query("UPDATE documents SET folderId = :newFolderId, modifiedAt = :timestamp WHERE id = :id")
    suspend fun moveDocument(id: String, newFolderId: String?, timestamp: Long = currentTimeMillis())

    @Query("SELECT * FROM documents ORDER BY modifiedAt DESC")
    fun getAllDocumentsFlow(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents")
    suspend fun getAllDocuments(): List<DocumentEntity>

    @Query("UPDATE documents SET trashedAt = :trashedAt WHERE id = :id")
    suspend fun setTrashedAt(id: String, trashedAt: Long?)

    @Query("UPDATE documents SET name = :name, folderId = :folderId, trashedAt = NULL WHERE id = :id")
    suspend fun restore(id: String, name: String, folderId: String?)

    /** Every document with the number of its pages and whether one of them holds a PDF. */
    @Query(
        """
        SELECT documents.*,
            (SELECT COUNT(*) FROM pages WHERE pages.documentId = documents.id AND pages.removed IS NULL) AS pageCount,
            EXISTS (
                SELECT 1 FROM pdfs JOIN pages ON pdfs.pageId = pages.id
                WHERE pages.documentId = documents.id AND pages.removed IS NULL AND pdfs.removed IS NULL
            ) AS hasPdf
        FROM documents ORDER BY modifiedAt DESC
        """
    )
    fun getAllDocumentsWithContentFlow(): Flow<List<DocumentWithContent>>

    @Query("UPDATE documents SET modifiedAt = :timestamp WHERE id = :documentId")
    suspend fun touchDocument(documentId: String, timestamp: Long = currentTimeMillis())

    @Query("UPDATE documents SET defaultBackground = :background, defaultWidth = :width, defaultHeight = :height, defaultsVersion = :version WHERE id = :documentId")
    suspend fun updateDocumentDefaults(documentId: String, background: PageBackground, width: Float, height: Float, version: Stamp)
}

/**
 * Data Access Object for page-level management and ordering.
 */
@Dao
internal interface PageDao {
    @Upsert
    suspend fun upsert(page: PageEntity)

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY orderKey, id")
    suspend fun getPagesForDocument(documentId: String): List<PageEntity>

    @Transaction
    @Query("SELECT * FROM pages WHERE documentId = :documentId")
    suspend fun getPageOutlines(documentId: String): List<PageOutlineRows>

    @Transaction
    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPageWithContent(pageId: String): PageWithContent?

    @Query(
        "SELECT id, added, placed, ordered, updated, NULL AS contentVersions, NULL AS resourceId FROM strokes WHERE pageId = :pageId AND removed IS NULL " +
            "UNION ALL SELECT id, added, placed, ordered, updated, contentVersions, NULL FROM texts WHERE pageId = :pageId AND removed IS NULL " +
            "UNION ALL SELECT id, added, placed, ordered, updated, NULL, resourceId FROM images WHERE pageId = :pageId AND removed IS NULL " +
            "UNION ALL SELECT id, added, placed, ordered, updated, NULL, resourceId FROM pdfs WHERE pageId = :pageId AND removed IS NULL " +
            "UNION ALL SELECT id, added, placed, ordered, updated, NULL, resourceId FROM text_markups WHERE pageId = :pageId AND removed IS NULL " +
            "UNION ALL SELECT id, added, placed, ordered, updated, NULL, NULL FROM custom_elements WHERE pageId = :pageId AND removed IS NULL"
    )
    suspend fun getShownElementStamps(pageId: String): List<ShownElementStamps>
}

/**
 * Specialized Data Access Object for handling stroke data.
 */
@Dao
internal interface StrokeDao {
    @Upsert
    suspend fun upsertAll(strokes: List<StrokeEntity>)
}

/**
 * Data Access Object for managing text elements.
 */
@Dao
internal interface TextDao {
    @Upsert
    suspend fun upsert(text: TextEntity)
}

/**
 * Data Access Object for managing image elements.
 */
@Dao
internal interface ImageDao {
    @Upsert
    suspend fun upsert(image: ImageEntity)
}

/**
 * Data Access Object for managing the elements of the types of the applications.
 */
@Dao
internal interface CustomElementDao {
    @Upsert
    suspend fun upsert(element: CustomElementEntity)
}

/**
 * Data Access Object for managing PDF elements.
 */
@Dao
internal interface PdfDao {
    @Upsert
    suspend fun upsert(pdf: PdfEntity)
}

/**
 * Data Access Object for managing the marks of the text of the PDFs.
 */
@Dao
internal interface TextMarkupDao {
    @Upsert
    suspend fun upsert(markup: TextMarkupEntity)
}

/**
 * Data Access Object for managing supplemental document resources.
 */
@Dao
internal interface ResourceDao {
    @Upsert
    suspend fun upsert(resource: ResourceEntity)

    @Query("SELECT * FROM resources WHERE documentId = :documentId")
    suspend fun getResourcesForDocument(documentId: String): List<ResourceEntity>

    @Query("SELECT uri FROM resources WHERE contentHash = :hash AND uri != '' LIMIT 1")
    suspend fun findUriByHash(hash: String): String?

    @Query("SELECT COUNT(*) FROM resources WHERE uri = :uri")
    suspend fun countByUri(uri: String): Int
}

/**
 * Data Access Object for the documents shared with other devices.
 */
@Dao
internal interface SharedDocumentDao {
    @Upsert
    suspend fun upsert(shared: SharedDocumentEntity)

    @Query("DELETE FROM shared_documents WHERE documentId = :documentId")
    suspend fun delete(documentId: String)

    @Query("SELECT * FROM shared_documents")
    suspend fun getAll(): List<SharedDocumentEntity>

    @Query("SELECT readOnly FROM shared_documents WHERE documentId = :documentId")
    suspend fun isReadOnly(documentId: String): Boolean?

    @Query("SELECT documentId FROM shared_documents")
    fun getIdsFlow(): Flow<List<String>>
}

/** The last operation of a replica (see [OperationDao.lastSequences]). */
internal data class ReplicaSequence(val replica: String, val sequence: Long)

/**
 * Data Access Object for the log of the operations of the documents.
 */
@Dao
internal interface OperationDao {
    /** Logs [operation] (once: an operation already logged is ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(operation: OperationEntity)

    @Query("SELECT replica, MAX(sequence) AS sequence FROM operations WHERE documentId = :documentId GROUP BY replica")
    suspend fun lastSequences(documentId: String): List<ReplicaSequence>

    /** The operations of the replica [replica] after its operation [after], in their order. */
    @Query("SELECT * FROM operations WHERE documentId = :documentId AND replica = :replica AND sequence > :after ORDER BY sequence")
    suspend fun getAfter(documentId: String, replica: String, after: Long): List<OperationEntity>

    @Query("DELETE FROM operations WHERE documentId = :documentId AND replica = :replica AND sequence <= :upTo")
    suspend fun deleteUpTo(documentId: String, replica: String, upTo: Long)

    @Upsert
    suspend fun upsertProofs(proofs: List<OperationProofEntity>)

    /** The proofs of the operations [sequences] of the replica [replica]. */
    @Query("SELECT * FROM operation_proofs WHERE documentId = :documentId AND replica = :replica AND sequence IN (:sequences)")
    suspend fun proofs(documentId: String, replica: String, sequences: List<Long>): List<OperationProofEntity>

    @Query("DELETE FROM operation_proofs WHERE documentId = :documentId AND replica = :replica AND sequence <= :upTo")
    suspend fun deleteProofsUpTo(documentId: String, replica: String, upTo: Long)

    @Query("SELECT replica, sequence FROM operation_bases WHERE documentId = :documentId")
    suspend fun bases(documentId: String): List<ReplicaSequence>

    @Query("SELECT * FROM operation_bases WHERE documentId = :documentId")
    suspend fun baseEntities(documentId: String): List<OperationBaseEntity>

    @Query("SELECT stamp FROM operations WHERE documentId = :documentId AND replica = :replica AND sequence = :sequence")
    suspend fun stampOf(documentId: String, replica: String, sequence: Long): Stamp?

    @Upsert
    suspend fun upsertBase(base: OperationBaseEntity)
}

/**
 * Deletes for good the pages and the elements removed (see `Document.purgeHidden`).
 */
@Dao
internal interface PurgeDao {
    @Query("DELETE FROM pages WHERE id IN (:ids)")
    suspend fun deletePages(ids: List<String>)

    @Query("DELETE FROM strokes WHERE id IN (:ids)")
    suspend fun deleteStrokes(ids: List<String>)

    @Query("DELETE FROM texts WHERE id IN (:ids)")
    suspend fun deleteTexts(ids: List<String>)

    @Query("DELETE FROM images WHERE id IN (:ids)")
    suspend fun deleteImages(ids: List<String>)

    @Query("DELETE FROM custom_elements WHERE id IN (:ids)")
    suspend fun deleteCustomElements(ids: List<String>)

    @Query("DELETE FROM pdfs WHERE id IN (:ids)")
    suspend fun deletePdfs(ids: List<String>)

    @Query("DELETE FROM text_markups WHERE id IN (:ids)")
    suspend fun deleteTextMarkups(ids: List<String>)
}

/**
 * Data Access Object for the identity of this device.
 */
@Dao
internal interface ReplicaDao {
    @Query("SELECT replicaId FROM replica WHERE id = 1")
    suspend fun get(): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(replica: ReplicaEntity)
}
