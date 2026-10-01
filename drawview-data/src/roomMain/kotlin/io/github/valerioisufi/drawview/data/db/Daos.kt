package io.github.valerioisufi.drawview.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import io.github.valerioisufi.drawview.core.model.PageBackground
import kotlinx.coroutines.flow.Flow

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
    val pdfs: List<PdfEntity>
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
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(folder: FolderEntity): Long

    @Query("SELECT * FROM folders WHERE name = :name AND (parentId = :parentId OR (parentId IS NULL AND :parentId IS NULL))")
    suspend fun getFolderByNameAndParent(name: String, parentId: Int?): FolderEntity?

    @Query("SELECT * FROM folders WHERE parentId IS NULL")
    suspend fun getRootFolders(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE parentId = :parentId")
    suspend fun getSubFolders(parentId: Int): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun getFolderById(id: Int): FolderEntity?

    @Query("UPDATE folders SET name = :newName, modifiedAt = :timestamp WHERE id = :id")
    suspend fun renameFolder(id: Int, newName: String, timestamp: Long = currentTimeMillis())

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Int)

    @Query("UPDATE folders SET parentId = :newParentId, modifiedAt = :timestamp WHERE id = :id")
    suspend fun moveFolder(id: Int, newParentId: Int?, timestamp: Long = currentTimeMillis())
}

/**
 * Data Access Object for document-level operations.
 */
@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getDocumentById(documentId: Int): DocumentEntity?

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :documentId")
    suspend fun getFullDocumentWithPages(documentId: Int): DocumentWithPages?

    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE id = :documentId")
    suspend fun updateLastOpened(documentId: Int, timestamp: Long = currentTimeMillis())

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Query("SELECT * FROM documents WHERE name = :name AND folderId IS NULL")
    suspend fun getRootDocumentByName(name: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE name = :name AND folderId = :folderId")
    suspend fun getDocumentByNameAndFolder(name: String, folderId: Int): DocumentEntity?

    @Query("SELECT * FROM documents WHERE folderId IS NULL ORDER BY modifiedAt DESC")
    suspend fun getRootDocuments(): List<DocumentEntity>

    @Query("SELECT * FROM documents WHERE folderId = :folderId ORDER BY modifiedAt DESC")
    suspend fun getDocumentsInFolder(folderId: Int): List<DocumentEntity>

    @Query("UPDATE documents SET name = :newName, modifiedAt = :timestamp WHERE id = :id")
    suspend fun renameDocument(id: Int, newName: String, timestamp: Long = currentTimeMillis())

    @Query("UPDATE documents SET folderId = :newFolderId, modifiedAt = :timestamp WHERE id = :id")
    suspend fun moveDocument(id: Int, newFolderId: Int?, timestamp: Long = currentTimeMillis())

    @Query("SELECT * FROM documents ORDER BY lastOpenedAt DESC LIMIT :limit")
    suspend fun getRecentDocuments(limit: Int): List<DocumentEntity>

    @Query("SELECT * FROM documents ORDER BY modifiedAt DESC LIMIT :limit")
    fun getRecentDocumentsFlow(limit: Int): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY modifiedAt DESC")
    fun getAllDocumentsFlow(): Flow<List<DocumentEntity>>

    @Query("UPDATE documents SET modifiedAt = :timestamp WHERE id = :documentId")
    suspend fun touchDocument(documentId: Int, timestamp: Long = currentTimeMillis())

    @Query("UPDATE documents SET defaultBackground = :background, defaultWidth = :width, defaultHeight = :height WHERE id = :documentId")
    suspend fun updateDocumentDefaults(documentId: Int, background: PageBackground, width: Float, height: Float)
}

/**
 * Data Access Object for page-level management and ordering.
 */
@Dao
interface PageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity): Long

    @Query("SELECT * FROM pages WHERE documentId = :documentId ORDER BY pageNumber ASC")
    suspend fun getPagesForDocument(documentId: Int): List<PageEntity>

    @Transaction
    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPageWithContent(pageId: Int): PageWithContent?

    @Query("DELETE FROM pages WHERE id = :pageId")
    suspend fun deleteById(pageId: Int)

    @Query("UPDATE pages SET pageNumber = pageNumber + 1 WHERE documentId = :docId AND pageNumber >= :startIndex")
    suspend fun shiftPagesUp(docId: Int, startIndex: Int)

    @Query("UPDATE pages SET pageNumber = pageNumber - 1 WHERE documentId = :docId AND pageNumber > :deletedIndex")
    suspend fun shiftPagesDown(docId: Int, deletedIndex: Int)

    @Query("UPDATE pages SET pageNumber = :newIndex WHERE id = :pageDbId")
    suspend fun updatePageNumber(pageDbId: Int, newIndex: Int)

    @Query("UPDATE pages SET isDeleted = 1 WHERE id = :pageId")
    suspend fun softDeletePage(pageId: Int)

    @Query("UPDATE pages SET isDeleted = 0, pageNumber = :newIndex WHERE id = :pageId")
    suspend fun restorePage(pageId: Int, newIndex: Int)

    @Query("SELECT * FROM pages WHERE id = :pageId")
    suspend fun getPageById(pageId: Int): PageEntity?

    /**
     * Permanently removes the soft-deleted pages of a document (the undo history does not
     * survive the editing session, so they cannot be restored anymore).
     */
    @Query("DELETE FROM pages WHERE documentId = :documentId AND isDeleted = 1")
    suspend fun purgeDeletedPages(documentId: Int)

    @Update
    suspend fun update(page: PageEntity)
}

/**
 * Specialized Data Access Object for handling stroke data.
 */
@Dao
interface StrokeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(stroke: StrokeEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(strokes: List<StrokeEntity>)

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getStrokesForPage(pageId: Int): List<StrokeEntity>

    @Query("DELETE FROM strokes WHERE id = :strokeId")
    suspend fun deleteById(strokeId: Int)

    @Query("DELETE FROM strokes WHERE id IN (:strokeIds)")
    suspend fun deleteByIds(strokeIds: List<Int>)

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteAllFromPage(pageId: Int)
}

/**
 * Data Access Object for managing text elements.
 */
@Dao
interface TextDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(text: TextEntity): Long

    @Query("SELECT * FROM texts WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getTextsForPage(pageId: Int): List<TextEntity>

    @Update
    suspend fun update(text: TextEntity)

    @Query("DELETE FROM texts WHERE id = :textId")
    suspend fun deleteById(textId: Int)
}

/**
 * Data Access Object for managing image elements.
 */
@Dao
interface ImageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(image: ImageEntity): Long

    @Query("SELECT * FROM images WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getImagesForPage(pageId: Int): List<ImageEntity>

    @Query("DELETE FROM images WHERE id = :imageId")
    suspend fun deleteById(imageId: Int)

    @Update
    suspend fun update(image: ImageEntity)
}

/**
 * Data Access Object for managing PDF elements.
 */
@Dao
interface PdfDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pdf: PdfEntity): Long

    @Query("SELECT * FROM pdfs WHERE pageId = :pageId ORDER BY zIndex ASC")
    suspend fun getPdfsForPage(pageId: Int): List<PdfEntity>
}

/**
 * Data Access Object for managing supplemental document resources.
 */
@Dao
interface ResourceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(resource: ResourceEntity): Long

    @Query("SELECT * FROM resources WHERE documentId = :documentId")
    suspend fun getResourcesForDocument(documentId: Int): List<ResourceEntity>

    @Query("DELETE FROM resources WHERE id = :resourceId")
    suspend fun deleteById(resourceId: Int)
}
