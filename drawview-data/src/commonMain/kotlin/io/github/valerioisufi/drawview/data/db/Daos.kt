package io.github.valerioisufi.drawview.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.Stamp
import kotlinx.coroutines.flow.Flow

/*
 * The rows are written with @Upsert (insert, or update the row with the same identifier): an
 * INSERT OR REPLACE would delete the row first, and the deletion of a page or of a document
 * cascades to its content.
 */

/**
 * Data transfer object representing a page and its associated graphical content.
 */
data class PageWithContent(
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
    val markups: List<TextMarkupEntity>
)

/**
 * Data model representing a document and its hierarchical content.
 */
data class DocumentWithPages(
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
interface FolderDao {
    @Insert
    suspend fun insert(folder: FolderEntity)

    @Query("SELECT * FROM folders WHERE name = :name AND (parentId = :parentId OR (parentId IS NULL AND :parentId IS NULL))")
    suspend fun getFolderByNameAndParent(name: String, parentId: String?): FolderEntity?

    @Query("SELECT * FROM folders WHERE parentId = :parentId")
    suspend fun getSubFolders(parentId: String): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolderById(id: String): FolderEntity?

    @Query("SELECT * FROM folders ORDER BY name")
    fun getAllFoldersFlow(): Flow<List<FolderEntity>>

    @Query("UPDATE folders SET name = :newName, modifiedAt = :timestamp WHERE id = :id")
    suspend fun renameFolder(id: String, newName: String, timestamp: Long = currentTimeMillis())

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE folders SET parentId = :newParentId, modifiedAt = :timestamp WHERE id = :id")
    suspend fun moveFolder(id: String, newParentId: String?, timestamp: Long = currentTimeMillis())
}

/**
 * Data Access Object for document-level operations.
 */
@Dao
interface DocumentDao {
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

    @Query("SELECT * FROM documents WHERE name = :name AND (folderId = :folderId OR (folderId IS NULL AND :folderId IS NULL))")
    suspend fun getDocumentByNameAndFolder(name: String, folderId: String?): DocumentEntity?

    @Query("SELECT * FROM documents WHERE folderId = :folderId")
    suspend fun getDocumentsInFolder(folderId: String): List<DocumentEntity>

    @Query("UPDATE documents SET name = :newName, modifiedAt = :timestamp WHERE id = :id")
    suspend fun renameDocument(id: String, newName: String, timestamp: Long = currentTimeMillis())

    @Query("UPDATE documents SET folderId = :newFolderId, modifiedAt = :timestamp WHERE id = :id")
    suspend fun moveDocument(id: String, newFolderId: String?, timestamp: Long = currentTimeMillis())

    @Query("SELECT * FROM documents ORDER BY modifiedAt DESC")
    fun getAllDocumentsFlow(): Flow<List<DocumentEntity>>

    @Query("UPDATE documents SET modifiedAt = :timestamp WHERE id = :documentId")
    suspend fun touchDocument(documentId: String, timestamp: Long = currentTimeMillis())

    @Query("UPDATE documents SET defaultBackground = :background, defaultWidth = :width, defaultHeight = :height, defaultsVersion = :version WHERE id = :documentId")
    suspend fun updateDocumentDefaults(documentId: String, background: PageBackground, width: Float, height: Float, version: Stamp)
}

/**
 * Data Access Object for page-level management and ordering.
 */
@Dao
interface PageDao {
    @Upsert
    suspend fun upsert(page: PageEntity)

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY orderKey, id")
    suspend fun getPagesForDocument(documentId: String): List<PageEntity>
}

/**
 * Specialized Data Access Object for handling stroke data.
 */
@Dao
interface StrokeDao {
    @Upsert
    suspend fun upsertAll(strokes: List<StrokeEntity>)
}

/**
 * Data Access Object for managing text elements.
 */
@Dao
interface TextDao {
    @Upsert
    suspend fun upsert(text: TextEntity)
}

/**
 * Data Access Object for managing image elements.
 */
@Dao
interface ImageDao {
    @Upsert
    suspend fun upsert(image: ImageEntity)
}

/**
 * Data Access Object for managing PDF elements.
 */
@Dao
interface PdfDao {
    @Upsert
    suspend fun upsert(pdf: PdfEntity)
}

/**
 * Data Access Object for managing the marks of the text of the PDFs.
 */
@Dao
interface TextMarkupDao {
    @Upsert
    suspend fun upsert(markup: TextMarkupEntity)
}

/**
 * Data Access Object for managing supplemental document resources.
 */
@Dao
interface ResourceDao {
    @Upsert
    suspend fun upsert(resource: ResourceEntity)

    @Query("SELECT * FROM resources WHERE documentId = :documentId")
    suspend fun getResourcesForDocument(documentId: String): List<ResourceEntity>
}

/**
 * Data Access Object for the documents shared with other devices.
 */
@Dao
interface SharedDocumentDao {
    @Upsert
    suspend fun upsert(shared: SharedDocumentEntity)

    @Query("DELETE FROM shared_documents WHERE documentId = :documentId")
    suspend fun delete(documentId: String)

    @Query("SELECT * FROM shared_documents")
    suspend fun getAll(): List<SharedDocumentEntity>

    @Query("SELECT documentId FROM shared_documents")
    fun getIdsFlow(): Flow<List<String>>
}

/** The last operation of a replica (see [OperationDao.lastSequences]). */
data class ReplicaSequence(val replica: String, val sequence: Long)

/**
 * Data Access Object for the log of the operations of the documents.
 */
@Dao
interface OperationDao {
    /** Logs [operation] (once: an operation already logged is ignored). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(operation: OperationEntity)

    @Query("SELECT replica, MAX(sequence) AS sequence FROM operations WHERE documentId = :documentId GROUP BY replica")
    suspend fun lastSequences(documentId: String): List<ReplicaSequence>

    /** The operations of the replica [replica] after its operation [after], in their order. */
    @Query("SELECT * FROM operations WHERE documentId = :documentId AND replica = :replica AND sequence > :after ORDER BY sequence")
    suspend fun getAfter(documentId: String, replica: String, after: Long): List<OperationEntity>
}

/**
 * Data Access Object for the identity of this device.
 */
@Dao
interface ReplicaDao {
    @Query("SELECT replicaId FROM replica WHERE id = 1")
    suspend fun get(): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(replica: ReplicaEntity)
}
