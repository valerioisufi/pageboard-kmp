package io.github.valerioisufi.drawview.data

import androidx.compose.ui.geometry.Rect
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.valerioisufi.drawview.core.controller.ToolManager
import io.github.valerioisufi.drawview.core.model.DrawTextMarkup
import io.github.valerioisufi.drawview.core.model.TextMarkupKind
import io.github.valerioisufi.drawview.core.model.Tool
import io.github.valerioisufi.drawview.data.db.DrawDatabase
import io.github.valerioisufi.drawview.data.db.createDrawDatabase
import io.github.valerioisufi.drawview.data.repository.DrawDocumentRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DrawDatabaseMigrationTest {

    /** Creates a database of schema [version] (from its exported schema) at [file]. */
    private fun createDatabase(file: File, version: Int, rows: List<String>) {
        val schema = Json.parseToJsonElement(File("schemas/${DrawDatabase::class.qualifiedName}/$version.json").readText())
            .jsonObject.getValue("database").jsonObject
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            for (entity in schema.getValue("entities").jsonArray) {
                val table = entity.jsonObject.getValue("tableName").jsonPrimitive.content
                connection.execSQL(entity.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity.jsonObject["indices"]?.jsonArray?.forEach { index ->
                    connection.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
            for (query in schema.getValue("setupQueries").jsonArray) connection.execSQL(query.jsonPrimitive.content)
            rows.forEach { connection.execSQL(it) }
            connection.execSQL("PRAGMA user_version = $version")
        } finally {
            connection.close()
        }
    }

    @Test
    fun documentsOfSchema2AreKeptAndCanBeMarked() = runTest {
        val file = File.createTempFile("drawview-v2", ".db").apply { delete() }
        try {
            createDatabase(
                file, 2,
                listOf(
                    "INSERT INTO documents (id, name, createdAt, modifiedAt, defaultBackground, defaultWidth, defaultHeight) " +
                        "VALUES (1, 'Lecture', 0, 0, '{\"type\":\"Solid\"}', 210, 297)",
                    "INSERT INTO pages (id, documentId, pageNumber, width, height, isDeleted) VALUES (1, 1, 0, 210, 297, 0)",
                    "INSERT INTO pdfs (id, pageId, zIndex, resourceId, pdfPageIndex) VALUES (1, 1, 0, '1', 0)",
                    "INSERT INTO user_preferences (id, isStylusOnlyMode, lastSelectedTool, lastLassoMode, defaultTextFontSize, " +
                        "defaultTextColor, defaultTextIsLatex, penPresets, highlighterPresets, eraserPresets, lazoPresets) " +
                        "VALUES (1, 1, 'INK_PEN', 'FREEHAND', 16, -16777216, 0, '[]', '[]', '[]', '[]')"
                )
            )

            val database = createDrawDatabase(Room.databaseBuilder<DrawDatabase>(name = file.absolutePath))
            try {
                val repository = DrawDocumentRepository(database)
                val document = assertNotNull(repository.loadDocument(1))
                val page = document.pages.single()
                assertEquals(1, page.pdfs.size)

                // The presets of the text markup tool start from the defaults
                val preferences = repository.drawPreferencesFlow.first()
                assertEquals(true, preferences.isStylusOnlyMode)
                assertEquals(ToolManager.defaultPresets()[Tool.TEXT_MARKUP], preferences.presets[Tool.TEXT_MARKUP])

                val markup = DrawTextMarkup(
                    resourceId = "1",
                    pdfPageIndex = 0,
                    pdfWidthPt = 595f,
                    pdfHeightPt = 842f,
                    startIndex = 0,
                    endIndex = 4,
                    text = "Hash",
                    rects = listOf(Rect(72f, 100f, 120f, 124f)),
                    kind = TextMarkupKind.HIGHLIGHT,
                    color = 0xFFFFF176.toInt()
                )
                repository.saveTextMarkup(page, markup)
                assertEquals("Hash", assertNotNull(repository.loadDocument(1)).pages.single().markups.single().text)
            } finally {
                database.close()
            }
        } finally {
            file.delete()
            File(file.absolutePath + "-wal").delete()
            File(file.absolutePath + "-shm").delete()
        }
    }
}
