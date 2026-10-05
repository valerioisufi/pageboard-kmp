package io.github.valerioisufi.drawview.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.valerioisufi.drawview.core.model.PageBackground

/**
 * Table for Folders
 */
@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val parentId: Int? = null,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val folderId: Int? = null,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val documentId: Int,
    val pageNumber: Int,
    val width: Float,
    val height: Float,
    val isDeleted: Boolean = false,
    val background: PageBackground? = null
)

/**
 * EXCLUSIVE table for Strokes.
 * Uses native binary encoding (BLOB) for extreme performance.
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pageId: Int,
    val zIndex: Int,
    val color: Int,
    val size: Float,
    val toolType: String,
    val brushFamily: String,
    val inputs: ByteArray
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

        return true
    }

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + pageId
        result = 31 * result + zIndex
        result = 31 * result + color
        result = 31 * result + size.hashCode()
        result = 31 * result + toolType.hashCode()
        result = 31 * result + brushFamily.hashCode()
        result = 31 * result + inputs.contentHashCode()
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pageId: Int,
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
    /** 0 = start, 1 = center, 2 = end (added in schema version 2). */
    @ColumnInfo(defaultValue = "0") val alignment: Int = 0,
    /** Added in schema version 2. */
    @ColumnInfo(defaultValue = "1") val isMarkdownEnabled: Boolean = true
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pageId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pageId: Int,
    val zIndex: Int,
    val resourceId: String,
    val pdfPageIndex: Int
)

/**
 * A highlight, underline or strikeout of the text of a PDF page (added in schema version 3).
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val pageId: Int,
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
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val documentId: Int,
    val type: String, // "image", "pdf", "color"
    val uri: String // File path or color value
)

/**
 * Single table for global user preferences.
 * ID is forced to 1 to ensure a single-row configuration.
 */
@Entity(tableName = "user_preferences")
data class UserPreferencesEntity(
    @PrimaryKey val id: Int = 1,

    // --- Global Preferences ---
    val isStylusOnlyMode: Boolean = false,
    val lastSelectedTool: String = "INK_PEN",
    val lastLassoMode: String = "FREEHAND",

    // --- Text Preferences ---
    val defaultTextFontSize: Float = 16f,
    val defaultTextColor: Int = 0xFF000000.toInt(),
    val defaultTextIsLatex: Boolean = false,

    // --- Tool Configurations ---
    val penPresets: List<BrushSettingsData> = listOf(
        BrushSettingsData(0.5f, 0xFF000000.toInt(), "PRESSURE_PEN"),
        BrushSettingsData(1.0f, 0xFF0000FF.toInt(), "PRESSURE_PEN"),
        BrushSettingsData(2.0f, 0xFFFF0000.toInt(), "PRESSURE_PEN")
    ),

    val highlighterPresets: List<BrushSettingsData> = listOf(
        BrushSettingsData(4.0f, 0x40FFFF00.toInt(), "HIGHLIGHTER"),
        BrushSettingsData(4.0f, 0x4000FF00.toInt(), "HIGHLIGHTER")
    ),

    val eraserPresets: List<BrushSettingsData> = listOf(
        BrushSettingsData(8.0f, 0xC8FF8DA1.toInt(), "LASER")
    ),

    val lazoPresets: List<BrushSettingsData> = listOf(
        BrushSettingsData(0.5f, 0xFF8799B2.toInt(), "DASHED")
    ),

    /** Presets of the text markup tool, the kind of the marks as family (added in schema version 3). */
    @ColumnInfo(defaultValue = "[]")
    val markupPresets: List<BrushSettingsData> = emptyList()
)
