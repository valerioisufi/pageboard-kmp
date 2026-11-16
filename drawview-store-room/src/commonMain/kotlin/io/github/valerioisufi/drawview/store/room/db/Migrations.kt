package io.github.valerioisufi.drawview.store.room.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL
import io.github.valerioisufi.drawview.model.DrawText
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.TextAlignment
import io.github.valerioisufi.drawview.model.TextMarkupKind
import io.github.valerioisufi.drawview.model.text.TextRoom
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
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
internal val DRAW_DATABASE_MIGRATIONS: Array<Migration> =
    arrayOf(Migration16To17, Migration17To18, Migration18To19, Migration19To20, Migration20To21, Migration21To22, Migration22To23, Migration23To24, Migration24To25)

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

/**
 * 20: the order of every element has its own register (`ordered`, see `DrawElement.ordered`): the
 * stamp of its placement, which held its order until then. The orders themselves are kept as they
 * are (an element reads them in the layer of its kind, see `ElementOrder`).
 */
internal object Migration19To20 : Migration(19, 20) {
    override fun migrate(connection: SQLiteConnection) {
        for (table in listOf("strokes", "texts", "images", "custom_elements", "pdfs", "text_markups")) {
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `ordered` TEXT NOT NULL DEFAULT ''")
            connection.execSQL("UPDATE `$table` SET `ordered` = `placed`")
        }
    }
}

/**
 * 21: the box of a text is laid out, not stored (docs/plans/text-heights.md). The height of the texts is
 * dropped, and a turned text, which turned around the middle of its box, gets the corner it turns
 * around now, computed with the height it had: it stays where it is. The texts and the placements
 * of texts in the log of the operations are rewritten the same way (a placement of a text becomes
 * a `TextPlacement`), so that they are given to the other devices as they are applied here.
 *
 * A text whose width follows it keeps the room its lines may take (`TextRoom`) instead of the box
 * they took: its lines, aligned in the room, stay where they are. In the log its box is left as
 * it was: taken as its room, it is drawn as it was (its lines wrap on it as they did).
 */
internal object Migration20To21 : Migration(20, 21) {
    private const val COLUMNS = "`id`, `pageId`, `orderKey`, `text`, `x`, `y`, `width`, `rotation`, `color`, `fontSize`, " +
        "`alignment`, `autoWidth`, `added`, `removed`, `placed`, `ordered`, `updated`, `contentVersions`"

    private val json = Json

    override fun migrate(connection: SQLiteConnection) {
        val corners = mutableListOf<Triple<String, Float, Float>>()
        connection.prepare("SELECT `id`, `x`, `y`, `width`, `height`, `rotation` FROM `texts` WHERE `rotation` != 0").use { row ->
            while (row.step()) {
                val corner = corner(row.getDouble(1).toFloat(), row.getDouble(2).toFloat(), row.getDouble(3).toFloat(), row.getDouble(4).toFloat(), row.getDouble(5).toFloat())
                corners += Triple(row.getText(0), corner.first, corner.second)
            }
        }
        connection.prepare("UPDATE `texts` SET `x` = ?, `y` = ? WHERE `id` = ?").use { update ->
            for ((id, x, y) in corners) {
                update.bindDouble(1, x.toDouble())
                update.bindDouble(2, y.toDouble())
                update.bindText(3, id)
                update.step()
                update.reset()
            }
        }
        val rooms = mutableListOf<Triple<String, Pair<Float, Float>, Float>>()
        connection.prepare(
            "SELECT t.`id`, t.`x`, t.`y`, t.`width`, t.`alignment`, t.`rotation`, p.`width` FROM `texts` t " +
                "JOIN `pages` p ON p.`id` = t.`pageId` WHERE t.`autoWidth` != 0"
        ).use { row ->
            while (row.step()) {
                val x = row.getDouble(1).toFloat()
                val y = row.getDouble(2).toFloat()
                val alignment = TextAlignment.fromName(row.getText(4)) ?: TextAlignment.START
                val rotation = row.getDouble(5).toFloat()
                val (left, width) = TextRoom.of(x, x + row.getDouble(3).toFloat(), alignment, row.getDouble(6).toFloat())
                // Its corner moved along its lines to the start of the room
                val rad = rotation * PI.toFloat() / 180f
                val along = left - x
                rooms += Triple(row.getText(0), (x + along * cos(rad)) to (y + along * sin(rad)), width)
            }
        }
        connection.prepare("UPDATE `texts` SET `x` = ?, `y` = ?, `width` = ? WHERE `id` = ?").use { update ->
            for ((id, corner, width) in rooms) {
                update.bindDouble(1, corner.first.toDouble())
                update.bindDouble(2, corner.second.toDouble())
                update.bindDouble(3, width.toDouble())
                update.bindText(4, id)
                update.step()
                update.reset()
            }
        }
        connection.execSQL(
            "CREATE TABLE `texts_new` (`id` TEXT NOT NULL, `pageId` TEXT NOT NULL, `orderKey` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                "`x` REAL NOT NULL, `y` REAL NOT NULL, `width` REAL NOT NULL, `rotation` REAL NOT NULL, `color` INTEGER NOT NULL, " +
                "`fontSize` REAL NOT NULL, `alignment` TEXT NOT NULL, `autoWidth` INTEGER NOT NULL, `added` TEXT NOT NULL, " +
                "`removed` TEXT, `placed` TEXT NOT NULL, `ordered` TEXT NOT NULL, `updated` TEXT NOT NULL, " +
                "`contentVersions` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`pageId`) REFERENCES `pages`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL("INSERT INTO `texts_new` ($COLUMNS) SELECT $COLUMNS FROM `texts`")
        connection.execSQL("DROP TABLE `texts`")
        connection.execSQL("ALTER TABLE `texts_new` RENAME TO `texts`")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_texts_pageId` ON `texts` (`pageId`)")

        val changes = mutableListOf<Pair<List<Any>, String>>()
        connection.prepare("SELECT `documentId`, `replica`, `sequence`, `change` FROM `operations`").use { row ->
            while (row.step()) {
                val change = row.getText(3)
                val rewritten = laidOut(json.parseToJsonElement(change)).toString()
                if (rewritten != change) changes += listOf<Any>(row.getText(0), row.getText(1), row.getLong(2)) to rewritten
            }
        }
        connection.prepare("UPDATE `operations` SET `change` = ? WHERE `documentId` = ? AND `replica` = ? AND `sequence` = ?").use { update ->
            for ((key, change) in changes) {
                update.bindText(1, change)
                update.bindText(2, key[0] as String)
                update.bindText(3, key[1] as String)
                update.bindLong(4, key[2] as Long)
                update.step()
                update.reset()
            }
        }
    }

    /** The corner of a text [width] x [height] at ([x], [y]) turned by [rotation] around its middle. */
    private fun corner(x: Float, y: Float, width: Float, height: Float, rotation: Float): Pair<Float, Float> {
        if (rotation == 0f) return x to y
        val corner = DrawText.cornerFor(x + width / 2f, y + height / 2f, width, height, rotation)
        return corner.x to corner.y
    }

    /** [element] with its texts (`TextData`) and the placements of its texts as they are written now. */
    internal fun laidOut(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonArray(element.map { laidOut(it) })
        is JsonObject -> {
            // A text (with its type among elements, without it in `updateTexts`) or the placement
            // of a text (a box with a font size): the only values with a height and a font size
            val text = "height" in element && element["fontSize"].let { it != null && it !is JsonNull }
            if (text) laidOutText(element, placement = (element["type"] as? JsonPrimitive)?.contentOrNull == "box")
            else JsonObject(element.mapValues { laidOut(it.value) })
        }
        else -> element
    }

    private fun laidOutText(text: JsonObject, placement: Boolean): JsonObject {
        fun float(key: String) = (text[key] as? JsonPrimitive)?.floatOrNull ?: 0f
        val (x, y) = corner(float("x"), float("y"), float("width"), float("height"), float("rotation"))
        return buildJsonObject {
            for ((key, value) in text) {
                when (key) {
                    "height" -> Unit
                    "x" -> put("x", x)
                    "y" -> put("y", y)
                    "type" -> put("type", "text")
                    else -> put(key, value)
                }
            }
            if (placement && text["autoWidth"].let { it == null || it is JsonNull }) put("autoWidth", false)
        }
    }
}

/** 22: the trash, the time a document or a folder went to it (see `DocumentCatalog.trash`); nothing is in it. */
internal object Migration21To22 : Migration(21, 22) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `folders` ADD COLUMN `trashedAt` INTEGER")
        connection.execSQL("ALTER TABLE `documents` ADD COLUMN `trashedAt` INTEGER")
    }
}

/**
 * 23: the fill of a text box (`DrawText.background`, docs/plans/text-boxes.md); no text has one. The
 * texts in the log of the operations need nothing: a text without the field has no fill.
 */
internal object Migration22To23 : Migration(22, 23) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `texts` ADD COLUMN `background` INTEGER")
    }
}

/**
 * 24: the chains of text boxes across the pages (`DrawText.next`, `continuesLine`,
 * docs/plans/text-boxes.md); no text is in one. The texts in the log of the operations need nothing: a
 * text without the fields goes on into none.
 */
internal object Migration23To24 : Migration(23, 24) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `texts` ADD COLUMN `next` TEXT")
        connection.execSQL("ALTER TABLE `texts` ADD COLUMN `continuesLine` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * 25: the shared folders (docs/plans/shared-folders.md): the spaces, the items of their catalogs, their
 * logs and the proofs of their changes. Nothing of the documents and folders changes: no space is
 * in a database of before.
 */
internal object Migration24To25 : Migration(24, 25) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `spaces` (`id` TEXT NOT NULL, `rootFolderId` TEXT, `sharing` TEXT NOT NULL, " +
                "`readOnly` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_items` (`spaceId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `item` TEXT NOT NULL, " +
                "PRIMARY KEY(`spaceId`, `itemId`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_operations` (`spaceId` TEXT NOT NULL, `replica` TEXT NOT NULL, " +
                "`sequence` INTEGER NOT NULL, `stamp` TEXT NOT NULL, `change` TEXT NOT NULL, " +
                "PRIMARY KEY(`spaceId`, `replica`, `sequence`), FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_catalog_operations_spaceId_stamp` ON `catalog_operations` (`spaceId`, `stamp`)"
        )
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `catalog_proofs` (`spaceId` TEXT NOT NULL, `replica` TEXT NOT NULL, " +
                "`sequence` INTEGER NOT NULL, `proof` BLOB NOT NULL, PRIMARY KEY(`spaceId`, `replica`, `sequence`), " +
                "FOREIGN KEY(`spaceId`) REFERENCES `spaces`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )"
        )
    }
}
