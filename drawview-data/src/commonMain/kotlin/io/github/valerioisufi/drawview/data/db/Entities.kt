package io.github.valerioisufi.drawview.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.model.PageBackground

/*
 * Every row is identified by the identifier of the model (a UUID, see `newElementId`), the same
 * on every device.
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
    val defaultHeight: Float = 297f
)

/**
 * Table for Pages
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
    indices = [Index(value = ["documentId", "pageNumber"])]
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val pageNumber: Int,
    val width: Float,
    val height: Float,
    val isDeleted: Boolean = false,
    val background: PageBackground? = null
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
    val zIndex: Int,
    val color: Int,
    val size: Float,
    val toolType: String,
    val brushFamily: String,
    val inputs: ByteArray,
    val transform: AffineTransform
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StrokeEntity) return false

        if (id != other.id) return false
        if (pageId != other.pageId) return false
        if (zIndex != other.zIndex) return false
        if (color != other.color) return false
        if (size != other.size) return false
        if (toolType != other.toolType) return false
        if (brushFamily != other.brushFamily) return false
        if (!inputs.contentEquals(other.inputs)) return false
        if (transform != other.transform) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + pageId.hashCode()
        result = 31 * result + zIndex
        result = 31 * result + color
        result = 31 * result + size.hashCode()
        result = 31 * result + toolType.hashCode()
        result = 31 * result + brushFamily.hashCode()
        result = 31 * result + inputs.contentHashCode()
        result = 31 * result + transform.hashCode()
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
    val zIndex: Int,
    val text: String,
    val isLatex: Boolean,
    val x: Float, // mm
    val y: Float, // mm
    val width: Float, // mm
    val height: Float, // mm
    val rotation: Float, // degrees
    val color: Int,
    val fontSize: Float,
    /** 0 = start, 1 = center, 2 = end. */
    val alignment: Int = 0,
    val isMarkdownEnabled: Boolean = true
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
    val zIndex: Int,
    val resourceId: String,
    val x: Float, // mm
    val y: Float, // mm
    val width: Float, // mm
    val height: Float, // mm
    val rotation: Float // degrees
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
    val zIndex: Int,
    val resourceId: String,
    val pdfPageIndex: Int
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
    val zIndex: Int,
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
    val color: Int
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
    indices = [Index("documentId")]
)
data class ResourceEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val type: String, // "image", "pdf", "color"
    val uri: String // File path or color value
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

    // --- Tool Configurations ---
    val penPresets: List<BrushSettingsData> = emptyList(),
    val highlighterPresets: List<BrushSettingsData> = emptyList(),
    val eraserPresets: List<BrushSettingsData> = emptyList(),
    val lazoPresets: List<BrushSettingsData> = emptyList(),
    /** Presets of the text markup tool, the kind of the marks as family. */
    val markupPresets: List<BrushSettingsData> = emptyList()
)
