package io.github.valerioisufi.drawview.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.model.ElementOrder
import io.github.valerioisufi.drawview.core.model.PageBackground
import io.github.valerioisufi.drawview.core.model.Stamp
import io.github.valerioisufi.drawview.core.model.Versions

/*
 * Every row is identified by the identifier of the model (a UUID, see `newElementId`), the same
 * on every device. The pages, the elements and the defaults of a document keep the stamps of
 * their last changes (see `Stamp`): the changes made on several devices are merged with them.
 * Nothing is deleted from a document: a removed page or element keeps its row, with the stamp of
 * its removal (`removed`).
 */

/**
 * Table for Folders
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAt: Long = currentTimeMillis(),
    val modifiedAt: Long = currentTimeMillis()
)

/**
 * Table for Documents
 */
@Entity(
    tableName = "documents",
    foreignKeys = [
        ForeignKey(
            entity = FolderEntity::class,
            parentColumns = ["id"],
            childColumns = ["folderId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("folderId")]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val folderId: String? = null,
    val createdAt: Long = currentTimeMillis(),
    val modifiedAt: Long = currentTimeMillis(),
    val lastOpenedAt: Long? = null,

    val defaultBackground: PageBackground = PageBackground.Solid(),
    val defaultWidth: Float = 210f,
    val defaultHeight: Float = 297f,
    val defaultsVersion: Stamp = Stamp.ZERO,
    val titleVersion: Stamp = Stamp.ZERO
)

/**
 * Table for Pages, ordered by [orderKey] (then by [id]).
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId", "orderKey"])]
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val orderKey: String,
    val width: Float,
    val height: Float,
    val background: PageBackground? = null,
    val added: Stamp = Stamp.ZERO,
    val removed: Stamp? = null,
    val orderVersion: Stamp = Stamp.ZERO,
    val formatVersion: Stamp = Stamp.ZERO,
    val contentVersions: Versions = Versions.NONE,
    val removalSaw: Versions? = null
)

/**
 * EXCLUSIVE table for Strokes: the inputs and the brush the stroke was drawn with (native binary
 * encoding, BLOB) and its placement on the page ([transform]).
 */
@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class StrokeEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderKey: ElementOrder,
    val color: Int,
    val size: Float,
    val toolType: String,
    val brushFamily: String,
    val inputs: ByteArray,
    val transform: AffineTransform,
    val added: Stamp,
    val removed: Stamp?,
    val placed: Stamp,
    val updated: Stamp
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StrokeEntity) return false
        return id == other.id && pageId == other.pageId && orderKey == other.orderKey && color == other.color &&
            size == other.size && toolType == other.toolType && brushFamily == other.brushFamily &&
            inputs.contentEquals(other.inputs) && transform == other.transform && added == other.added &&
            removed == other.removed && placed == other.placed && updated == other.updated
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + pageId.hashCode()
        result = 31 * result + orderKey.hashCode()
        result = 31 * result + inputs.contentHashCode()
        result = 31 * result + transform.hashCode()
        result = 31 * result + (removed?.hashCode() ?: 0)
        result = 31 * result + placed.hashCode()
        result = 31 * result + updated.hashCode()
        return result
    }
}

@Entity(
    tableName = "texts",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class TextEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderKey: ElementOrder,
    val text: String,
    val x: Float, // mm
    val y: Float, // mm
    val width: Float, // mm
    val height: Float, // mm
    val rotation: Float, // degrees
    val color: Int,
    val fontSize: Float,
    /** The name of its `TextAlignment`. */
    val alignment: String = "START",
    /** Whether its width follows its text. */
    val autoWidth: Boolean = false,
    val added: Stamp,
    val removed: Stamp?,
    val placed: Stamp,
    val updated: Stamp,
    val contentVersions: Versions = Versions.NONE
)

@Entity(
    tableName = "images",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class ImageEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderKey: ElementOrder,
    val resourceId: String,
    val x: Float, // mm
    val y: Float, // mm
    val width: Float, // mm
    val height: Float, // mm
    val rotation: Float, // degrees
    val added: Stamp,
    val removed: Stamp?,
    val placed: Stamp,
    val updated: Stamp
)

@Entity(
    tableName = "pdfs",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class PdfEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderKey: ElementOrder,
    val resourceId: String,
    val pdfPageIndex: Int,
    val added: Stamp,
    val removed: Stamp?,
    val placed: Stamp,
    val updated: Stamp
)

/**
 * A highlight, underline or strikeout of the text of a PDF page.
 */
@Entity(
    tableName = "text_markups",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class TextMarkupEntity(
    @PrimaryKey val id: String,
    val pageId: String,
    val orderKey: ElementOrder,
    val resourceId: String,
    val pdfPageIndex: Int,
    val pdfWidth: Float, // pt
    val pdfHeight: Float, // pt
    /** First character of the passage in the text of the PDF page. */
    val startIndex: Int,
    /** End (exclusive) of the passage in the text of the PDF page. */
    val endIndex: Int,
    val text: String,
    /** JSON array of the left, top, right, bottom of each line, in points of the PDF page. */
    val rects: String,
    /** HIGHLIGHT, UNDERLINE or STRIKEOUT. */
    val kind: String,
    val color: Int,
    val added: Stamp,
    val removed: Stamp?,
    val placed: Stamp,
    val updated: Stamp
)

@Entity(
    tableName = "resources",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId"), Index("contentHash")]
)
data class ResourceEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val type: String, // "image", "pdf", "color"
    val uri: String, // File path or color value
    /** The SHA-256 of the content of the file: the same content is kept in one file. */
    val contentHash: String = ""
)

/**
 * A document shared with other devices, with its secret (see `DocumentStore.sharingSecrets`).
 */
@Entity(
    tableName = "shared_documents",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SharedDocumentEntity(
    @PrimaryKey val documentId: String,
    val secret: String,
    /** This device may only read it (see `DocumentSession.isReadOnly`). */
    val readOnly: Boolean = false
)

/**
 * The operations of a document no longer in its log (see `DocumentStore.compactOperations`): the
 * last one of every replica.
 */
@Entity(
    tableName = "operation_bases",
    primaryKeys = ["documentId", "replica"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class OperationBaseEntity(
    val documentId: String,
    val replica: String,
    val sequence: Long,
    /** The stamp of that operation. */
    val stamp: Stamp = Stamp.ZERO
)

/**
 * The log of the operations of a document (see `Operation`): the change, as JSON.
 */
@Entity(
    tableName = "operations",
    primaryKeys = ["documentId", "replica", "sequence"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["documentId", "stamp"])]
)
data class OperationEntity(
    val documentId: String,
    val replica: String,
    val sequence: Long,
    val stamp: Stamp,
    val change: String
)

/**
 * This device in the changes of the documents (a single row).
 */
@Entity(tableName = "replica")
data class ReplicaEntity(
    @PrimaryKey val id: Int = 1,
    val replicaId: String
)

/**
 * Single table for global user preferences.
 * ID is forced to 1 to ensure a single-row configuration.
 *
 * Empty lists of presets: the default presets of the tools (see `ToolManager.defaultPresets`).
 */
@Entity(tableName = "user_preferences")
data class UserPreferencesEntity(
    @PrimaryKey val id: Int = 1,

    // --- Global Preferences ---
    val isStylusOnlyMode: Boolean = false,
    val lastSelectedTool: String = "INK_PEN",

    // --- Text Preferences ---
    val defaultTextFontSize: Float = 16f,
    val defaultTextColor: Int = 0xFF000000.toInt(),
    /** The name of the `TextAlignment` of a new text. */
    val defaultTextAlignment: String = "START",
    val isSnapEnabled: Boolean = true,

    // --- Tool Configurations ---
    val penPresets: List<BrushSettingsData> = emptyList(),
    val highlighterPresets: List<BrushSettingsData> = emptyList(),
    val eraserPresets: List<BrushSettingsData> = emptyList(),
    val lazoPresets: List<BrushSettingsData> = emptyList(),
    /** Presets of the text markup tool, the kind of the marks as family. */
    val markupPresets: List<BrushSettingsData> = emptyList()
)
