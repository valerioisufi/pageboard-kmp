package io.github.valerioisufi.drawview.store.room.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.TextMarkupKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/*
 * The migrations of the database from version 16, the first one holding documents to keep: every
 * new version of the schema comes with its migration, the documents are never dropped (see
 * createDrawDatabase). Each migration is tested on a database of its version (see
 * DrawDatabaseMigrationTest).
 */

/** The migrations from version 16 to [DrawDatabase]'s version. */
internal val DRAW_DATABASE_MIGRATIONS: Array<Migration> = arrayOf(Migration16To17, Migration17To18, Migration18To19)

/** 17: the elements of the types added by the applications. */
internal object Migration16To17 : Migration(16, 17) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `custom_elements` (`id` TEXT NOT NULL, `pageId` TEXT NOT NULL, " +
                "`orderKey` TEXT NOT NULL, `type` TEXT NOT NULL, `version` INTEGER NOT NULL, `payload` BLOB NOT NULL, " +
                "`x` REAL NOT NULL, `y` REAL NOT NULL, `width` REAL NOT NULL, `height` REAL NOT NULL, " +
                "`rotation` REAL NOT NULL, `added` TEXT NOT NULL, `removed` TEXT, `placed` TEXT NOT NULL, " +
                "`updated` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_custom_elements_pageId` ON `custom_elements` (`pageId`)")
    }
}

/**
 * 18: the preferences as text by key. The single row of the preferences of the editor becomes the
 * text of the key `drawview.editor`, in the format of `DrawPreferences` (see `DocumentEditorViewModel`).
 */
internal object Migration17To18 : Migration(17, 18) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("CREATE TABLE IF NOT EXISTS `preferences` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
        val editor = connection.prepare(
            "SELECT isStylusOnlyMode, lastSelectedTool, defaultTextFontSize, defaultTextColor, defaultTextAlignment, " +
                "isSnapEnabled, penPresets, highlighterPresets, eraserPresets, lazoPresets, markupPresets " +
                "FROM user_preferences WHERE id = 1"
        ).use { row -> if (row.step()) editorPreferences(row) else null }
        if (editor != null) {
            connection.prepare("INSERT OR REPLACE INTO `preferences` (`key`, `value`) VALUES (?, ?)").use {
                it.bindText(1, EDITOR_PREFERENCES)
                it.bindText(2, editor)
                it.step()
            }
        }
        connection.execSQL("DROP TABLE IF EXISTS `user_preferences`")
    }

    private const val EDITOR_PREFERENCES = "drawview.editor"

    // The names of the tools of the editor (`Tool`): a value of another name is left out
    private val TOOLS = setOf("INK_PEN", "INK_HIGHLIGHTER", "TEXT_MARKUP", "ERASER", "TEXT", "LAZO", "PAN")

    private val json = Json { ignoreUnknownKeys = true }

    /** The preferences of [row] as `DrawPreferences` writes them (a tool without presets keeps the default ones). */
    private fun editorPreferences(row: SQLiteStatement): String = buildJsonObject {
        put("isStylusOnlyMode", row.getLong(0) != 0L)
        row.getText(1).takeIf { it in TOOLS }?.let { put("lastSelectedTool", it) }
        putJsonObject("textDefaults") {
            put("fontSize", row.getDouble(2).toFloat())
            put("color", row.getLong(3).toInt())
            TextAlignment.entries.firstOrNull { it.name == row.getText(4) }?.let { put("alignment", it.name) }
        }
        put("isSnapEnabled", row.getLong(5) != 0L)
        putJsonObject("presets") {
            presets(row.getText(6), StrokeTool.PRESSURE_PEN)?.let { put("INK_PEN", it) }
            presets(row.getText(7), StrokeTool.HIGHLIGHTER)?.let { put("INK_HIGHLIGHTER", it) }
            presets(row.getText(8), StrokeTool.ERASER)?.let { put("ERASER", it) }
            presets(row.getText(9), StrokeTool.LASSO)?.let { put("LAZO", it) }
            markupPresets(row.getText(10))?.let { put("TEXT_MARKUP", it) }
        }
    }.toString()

    /** The presets written as `[{sizeMm, color, family}]`, `null` when there are none. */
    private fun presets(text: String, fallback: StrokeTool): JsonArray? = stored(text) { size, color, family ->
        put("sizeMm", size)
        put("color", color)
        put("family", (StrokeTool.entries.firstOrNull { it.name == family } ?: fallback).name)
    }

    /** The presets of the text markup, whose family was the kind of their marks. */
    private fun markupPresets(text: String): JsonArray? = stored(text) { size, color, family ->
        put("sizeMm", size)
        put("color", color)
        put("family", StrokeTool.HIGHLIGHTER.name)
        put("markup", (TextMarkupKind.entries.firstOrNull { it.name == family } ?: TextMarkupKind.HIGHLIGHT).name)
    }

    private fun stored(
        text: String,
        preset: kotlinx.serialization.json.JsonObjectBuilder.(Float, Int, String?) -> Unit
    ): JsonArray? {
        val stored = try {
            json.parseToJsonElement(text) as? JsonArray
        } catch (_: Exception) {
            null
        } ?: return null
        val presets = stored.mapNotNull { element ->
            val values = element as? JsonObject ?: return@mapNotNull null
            val size = values["sizeMm"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
            val color = values["color"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            buildJsonObject { preset(size, color, values["family"]?.jsonPrimitive?.content) }
        }
        return if (presets.isEmpty()) null else buildJsonArray { presets.forEach { add(it) } }
    }
}

/** 19: the proofs of the operations of the other devices (see `OperationProofEntity`). */
internal object Migration18To19 : Migration(18, 19) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `operation_proofs` (`documentId` TEXT NOT NULL, `replica` TEXT NOT NULL, " +
                "`sequence` INTEGER NOT NULL, `proof` BLOB NOT NULL, PRIMARY KEY(`documentId`, `replica`, `sequence`), " +
                "FOREIGN KEY(`documentId`) REFERENCES `documents`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
    }
}
