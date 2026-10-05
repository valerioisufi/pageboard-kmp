package io.github.valerioisufi.drawview.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Upsert
import io.github.valerioisufi.drawview.core.model.PageBackground
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

    @Query("UPDATE documents SET defaultBackground = :background, defaultWidth = :width, defaultHeight = :height WHERE id = :documentId")
    suspend fun updateDocumentDefaults(documentId: String, background: PageBackground, width: Float, height: Float)
}

/**
 * Data Access Object for page-level management and ordering.
 */
@Dao
interface PageDao {
    @Upsert
    suspend fun upsert(page: PageEntity)

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getPagesForDocument(documentId: String): List<PageEntity>

    @Query("UPDATE pages SET pageNumber = pageNumber + 1 WHERE documentId = :documentId AND pageNumber >= :startIndex")
    suspend fun shiftPagesUp(documentId: String, startIndex: Int)

    @Query("UPDATE pages SET pageNumber = pageNumber - 1 WHERE documentId = :documentId AND pageNumber > :deletedIndex")
    suspend fun shiftPagesDown(documentId: String, deletedIndex: Int)

    @Query("UPDATE pages SET pageNumber = :newIndex WHERE id = :pageId")
    suspend fun updatePageNumber(pageId: String, newIndex: Int)

    @Query("UPDATE pages SET isDeleted = 1 WHERE id = :pageId")
    suspend fun softDeletePage(pageId: String)

    @Query("UPDATE pages SET width = :width, height = :height, background = :background WHERE id = :pageId")
    suspend fun updateFormat(pageId: String, width: Float, height: Float, background: PageBackground?)

    /**
     * Permanently removes the soft-deleted pages of a document (the undo history does not
     * survive the editing session, so they cannot be restored anymore).
     */
    @Query("DELETE FROM pages WHERE documentId = :documentId AND isDeleted = 1")
    suspend fun purgeDeletedPages(documentId: String)
}

/**
 * Specialized Data Access Object for handling stroke data.
 */
@Dao
interface StrokeDao {
    @Upsert
    suspend fun upsertAll(strokes: List<StrokeEntity>)

    @Query("DELETE FROM strokes WHERE id IN (:strokeIds)")
    suspend fun deleteByIds(strokeIds: List<String>)
}

/**
 * Data Access Object for managing text elements.
 */
@Dao
interface TextDao {
    @Upsert
    suspend fun upsert(text: TextEntity)

    @Query("DELETE FROM texts WHERE id = :textId")
    suspend fun deleteById(textId: String)
}

/**
 * Data Access Object for managing image elements.
 */
@Dao
interface ImageDao {
    @Upsert
    suspend fun upsert(image: ImageEntity)

    @Query("DELETE FROM images WHERE id = :imageId")
    suspend fun deleteById(imageId: String)
}

/**
 * Data Access Object for managing PDF elements.
 */
@Dao
interface PdfDao {
    @Upsert
    suspend fun upsert(pdf: PdfEntity)

    @Query("DELETE FROM pdfs WHERE id = :pdfId")
    suspend fun deleteById(pdfId: String)
}

/**
 * Data Access Object for managing the marks of the text of the PDFs.
 */
@Dao
interface TextMarkupDao {
    @Upsert
    suspend fun upsert(markup: TextMarkupEntity)

    @Query("DELETE FROM text_markups WHERE id = :markupId")
    suspend fun deleteById(markupId: String)
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
