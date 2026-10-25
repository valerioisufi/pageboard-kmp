package io.github.valerioisufi.drawview.store.room

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.BoxPlacement
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.TextData
import io.github.valerioisufi.drawview.model.change.TextPlacement
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import io.github.valerioisufi.drawview.store.room.db.DRAW_DATABASE_MIGRATIONS
import io.github.valerioisufi.drawview.store.room.db.Migration18To19
import io.github.valerioisufi.drawview.store.room.db.Migration19To20
import io.github.valerioisufi.drawview.store.room.db.Migration20To21
import io.github.valerioisufi.drawview.store.room.db.DrawDatabase
import io.github.valerioisufi.drawview.store.room.db.createDrawDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * The migrations of the database: a database of every version from 16 keeps its documents and its
 * preferences (its pages their look), a database without a migration is not opened (and not
 * dropped).
 */
class DrawDatabaseMigrationTest {

    private val directory: Path = Files.createTempDirectory("drawview-migration")
    private val databaseFile: Path = directory.resolve("database.db")

    private val helper = MigrationTestHelper(
        schemaDirectoryPath = Path.of("schemas"),
        databasePath = databaseFile,
        driver = BundledSQLiteDriver(),
        databaseClass = DrawDatabase::class
    )

    @AfterTest
    fun tearDown() {
        directory.toFile().deleteRecursively()
    }

    @Test
    fun theDocumentsAndThePreferencesOfVersion16AreKept() = runTest {
        helper.createDatabase(16).use { connection ->
            connection.execSQL(
                "INSERT INTO documents (id, name, folderId, createdAt, modifiedAt, lastOpenedAt, defaultBackground, " +
                    "defaultWidth, defaultHeight, defaultsVersion, titleVersion) VALUES ('$DOCUMENT', 'Lectures', NULL, " +
                    "1, 2, NULL, '{\"type\":\"Solid\"}', 210.0, 297.0, '$STAMP', '$STAMP')"
            )
            connection.execSQL(
                "INSERT INTO pages (id, documentId, orderKey, width, height, background, added, removed, orderVersion, " +
                    "formatVersion, contentVersions, removalSaw) VALUES ('$PAGE', '$DOCUMENT', 'k', 297.0, 210.0, NULL, " +
                    "'$STAMP', NULL, '$STAMP', '$STAMP', '', NULL)"
            )
            connection.execSQL(
                "INSERT INTO user_preferences VALUES (1, 1, 'TEXT_MARKUP', 11.0, -14722394, 'CENTER', 0, " +
                    "'[{\"sizeMm\":0.5,\"color\":-15066088,\"family\":\"PRESSURE_PEN\"}]', '[]', '[]', '[]', " +
                    "'[{\"sizeMm\":0.0,\"color\":-2999493,\"family\":\"UNDERLINE\"}]')"
            )
        }

        helper.runMigrationsAndValidate(19, DRAW_DATABASE_MIGRATIONS.toList()).close()

        val database = createDrawDatabase(Room.databaseBuilder<DrawDatabase>(databaseFile.toString()))
        try {
            val store = RoomDocumentStore(database)
            val document = assertNotNull(store.loadDocument(DOCUMENT))
            assertEquals("Lectures", document.title)
            assertEquals(listOf(PAGE), document.pages.map { it.id })
            assertEquals(
                Json.parseToJsonElement(
                    """
                    {"isStylusOnlyMode":true,"lastSelectedTool":"TEXT_MARKUP",
                     "textDefaults":{"fontSize":11.0,"color":-14722394,"alignment":"CENTER"},"isSnapEnabled":false,
                     "presets":{"INK_PEN":[{"sizeMm":0.5,"color":-15066088,"family":"PRESSURE_PEN"}],
                                "TEXT_MARKUP":[{"sizeMm":0.0,"color":-2999493,"family":"HIGHLIGHTER","markup":"UNDERLINE"}]}}
                    """
                ),
                Json.parseToJsonElement(assertNotNull(store.preference("drawview.editor")))
            )
        } finally {
            database.close()
        }
    }

    @Test
    fun theOperationsOfVersion18AreKeptAndTheirProofsCanBe() = runTest {
        helper.createDatabase(18).use { connection ->
            connection.execSQL(
                "INSERT INTO documents (id, name, folderId, createdAt, modifiedAt, lastOpenedAt, defaultBackground, " +
                    "defaultWidth, defaultHeight, defaultsVersion, titleVersion) VALUES ('$DOCUMENT', 'Lectures', NULL, " +
                    "1, 2, NULL, '{\"type\":\"Solid\"}', 210.0, 297.0, '$STAMP', '$STAMP')"
            )
            connection.execSQL(
                "INSERT INTO operations (documentId, replica, sequence, stamp, change) VALUES ('$DOCUMENT', 'bob', 1, " +
                    "'$STAMP', '{\"type\":\"renameDocument\",\"title\":\"Physics\"}')"
            )
        }

        helper.runMigrationsAndValidate(19, listOf(Migration18To19)).close()

        val database = createDrawDatabase(Room.databaseBuilder<DrawDatabase>(databaseFile.toString()))
        try {
            val store = RoomDocumentStore(database)
            assertEquals(mapOf("bob" to 1L), store.versionVector(DOCUMENT))
            val operation = store.operationsAfter(DOCUMENT, emptyMap()).single()
            assertEquals(OperationId("bob", 1), operation.id)
            assertTrue(store.proofs(DOCUMENT, listOf(operation.id)).isEmpty())
            store.saveProofs(DOCUMENT, mapOf(operation.id to byteArrayOf(1, 2, 3)))
            assertContentEquals(byteArrayOf(1, 2, 3), store.proofs(DOCUMENT, listOf(operation.id))[operation.id])
        } finally {
            database.close()
        }
    }

    @Test
    fun theElementsOfVersion19KeepTheirStackAndGetTheirOrderRegister() = runTest {
        // An element of every kind, stamped so that the stamps mix the kinds: the newest is a
        // PDF page, the oldest a stroke (each kind was painted in its layer)
        fun order(time: Long) = "${stamp(time)}/00000000"
        helper.createDatabase(19).use { connection ->
            connection.execSQL(
                "INSERT INTO documents (id, name, folderId, createdAt, modifiedAt, lastOpenedAt, defaultBackground, " +
                    "defaultWidth, defaultHeight, defaultsVersion, titleVersion) VALUES ('$DOCUMENT', 'Lectures', NULL, " +
                    "1, 2, NULL, '{\"type\":\"Solid\"}', 210.0, 297.0, '$STAMP', '$STAMP')"
            )
            connection.execSQL(
                "INSERT INTO pages (id, documentId, orderKey, width, height, background, added, removed, orderVersion, " +
                    "formatVersion, contentVersions, removalSaw) VALUES ('$PAGE', '$DOCUMENT', 'k', 210.0, 297.0, NULL, " +
                    "'$STAMP', NULL, '$STAMP', '$STAMP', '', NULL)"
            )
            connection.execSQL(
                "INSERT INTO strokes (id, pageId, orderKey, color, size, toolType, brushFamily, inputs, transform, added, " +
                    "removed, placed, updated) VALUES ('stroke', '$PAGE', '${order(100)}', -16777216, 0.5, 'STYLUS', " +
                    "'PRESSURE_PEN', X'', '1.0,0.0,0.0,1.0,0.0,0.0', '${stamp(100)}', NULL, '${stamp(150)}', '${stamp(100)}')"
            )
            connection.execSQL(
                "INSERT INTO texts (id, pageId, orderKey, text, x, y, width, height, rotation, color, fontSize, alignment, " +
                    "autoWidth, added, removed, placed, updated, contentVersions) VALUES ('text', '$PAGE', '${order(200)}', " +
                    "'waves', 0.0, 0.0, 40.0, 10.0, 0.0, -16777216, 16.0, 'START', 0, '${stamp(200)}', NULL, '${stamp(200)}', " +
                    "'${stamp(200)}', '')"
            )
            connection.execSQL(
                "INSERT INTO images (id, pageId, orderKey, resourceId, x, y, width, height, rotation, added, removed, " +
                    "placed, updated) VALUES ('image', '$PAGE', '${order(500)}', 'r', 0.0, 0.0, 20.0, 20.0, 0.0, " +
                    "'${stamp(500)}', NULL, '${stamp(500)}', '${stamp(500)}')"
            )
            connection.execSQL(
                "INSERT INTO custom_elements (id, pageId, orderKey, type, version, payload, x, y, width, height, rotation, " +
                    "added, removed, placed, updated) VALUES ('custom', '$PAGE', '${order(300)}', 'com.example.shape', 1, " +
                    "X'', 0.0, 0.0, 20.0, 20.0, 0.0, '${stamp(300)}', NULL, '${stamp(300)}', '${stamp(300)}')"
            )
            connection.execSQL(
                "INSERT INTO pdfs (id, pageId, orderKey, resourceId, pdfPageIndex, added, removed, placed, updated) " +
                    "VALUES ('pdf', '$PAGE', '${order(600)}', 'r', 0, '${stamp(600)}', NULL, '${stamp(600)}', '${stamp(600)}')"
            )
            connection.execSQL(
                "INSERT INTO text_markups (id, pageId, orderKey, resourceId, pdfPageIndex, pdfWidth, pdfHeight, startIndex, " +
                    "endIndex, text, rects, kind, color, added, removed, placed, updated) VALUES ('markup', '$PAGE', " +
                    "'${order(50)}', 'r', 0, 595.0, 842.0, 0, 4, 'wave', '[0.0,0.0,10.0,10.0]', 'HIGHLIGHT', 0, " +
                    "'${stamp(50)}', NULL, '${stamp(50)}', '${stamp(50)}')"
            )
        }

        helper.runMigrationsAndValidate(20, listOf(Migration19To20)).close()

        val database = createDrawDatabase(Room.databaseBuilder<DrawDatabase>(databaseFile.toString()))
        try {
            val page = assertNotNull(RoomDocumentStore(database).loadDocument(DOCUMENT)).pages.single()
            // The look it had: each kind in its layer
            assertEquals(listOf("pdf", "markup", "image", "custom", "text", "stroke"), page.snapshotContent().paintOrder.map { it.id })
            for (element in page.elements()) assertEquals(element.placed, element.ordered, element.id)
            assertEquals(stamp(150), page.find("stroke")?.ordered)
        } finally {
            database.close()
        }
    }

    @Test
    fun theTextsOfVersion20StayWhereTheyAreWithoutTheirHeight() = runTest {
        helper.createDatabase(20).use { connection ->
            connection.execSQL(
                "INSERT INTO documents (id, name, folderId, createdAt, modifiedAt, lastOpenedAt, defaultBackground, " +
                    "defaultWidth, defaultHeight, defaultsVersion, titleVersion) VALUES ('$DOCUMENT', 'Lectures', NULL, " +
                    "1, 2, NULL, '{\"type\":\"Solid\"}', 210.0, 297.0, '$STAMP', '$STAMP')"
            )
            connection.execSQL(
                "INSERT INTO pages (id, documentId, orderKey, width, height, background, added, removed, orderVersion, " +
                    "formatVersion, contentVersions, removalSaw) VALUES ('$PAGE', '$DOCUMENT', 'k', 210.0, 297.0, NULL, " +
                    "'$STAMP', NULL, '$STAMP', '$STAMP', '', NULL)"
            )
            // Upright, and turned by a quarter around the middle of its box of 40 x 10 mm
            for ((id, y, rotation) in listOf(Triple("upright", 20.0, 0.0), Triple("turned", 50.0, 90.0))) {
                connection.execSQL(
                    "INSERT INTO texts (id, pageId, orderKey, text, x, y, width, height, rotation, color, fontSize, alignment, " +
                        "autoWidth, added, removed, placed, ordered, updated, contentVersions) VALUES ('$id', '$PAGE', " +
                        "'$STAMP/00000000', 'waves', 10.0, $y, 40.0, 10.0, $rotation, -16777216, 16.0, 'START', 0, '$STAMP', " +
                        "NULL, '$STAMP', '$STAMP', '$STAMP', '')"
                )
            }
            val changes = listOf(
                """{"type":"addElements","pageId":"$PAGE","elements":[{"type":"text","id":"turned","text":"waves","x":10.0,""" +
                    """"y":50.0,"width":40.0,"height":10.0,"rotation":90.0,"color":-16777216,"fontSize":16.0}]}""",
                """{"type":"placeElements","pageId":"$PAGE","placements":[{"type":"box","id":"turned","x":10.0,"y":50.0,""" +
                    """"width":40.0,"height":10.0,"rotation":90.0,"fontSize":16.0,"autoWidth":true},{"type":"box","id":"image",""" +
                    """"x":1.0,"y":2.0,"width":3.0,"height":4.0,"rotation":90.0}]}""",
                """{"type":"updateTexts","pageId":"$PAGE","texts":[{"id":"upright","text":"changed","x":10.0,""" +
                    """"y":20.0,"width":40.0,"height":10.0,"color":-16777216,"fontSize":16.0}]}"""
            )
            for ((sequence, change) in changes.withIndex()) {
                connection.execSQL(
                    "INSERT INTO operations (documentId, replica, sequence, stamp, change) VALUES ('$DOCUMENT', 'bob', " +
                        "${sequence + 1}, '$STAMP', '$change')"
                )
            }
        }

        helper.runMigrationsAndValidate(21, listOf(Migration20To21)).close()

        val database = createDrawDatabase(Room.databaseBuilder<DrawDatabase>(databaseFile.toString()))
        try {
            val store = RoomDocumentStore(database)
            val page = assertNotNull(store.loadDocument(DOCUMENT)).pages.single()
            val upright = page.texts.single { it.id == "upright" }
            assertEquals(10f to 20f, upright.x to upright.y, "an upright text where it was")
            // Its middle was (30, 55): its corner, turned by a quarter, is 5 mm right of it and 20 mm above
            val turned = page.texts.single { it.id == "turned" }
            assertEquals(35f, turned.x, 1e-4f)
            assertEquals(35f, turned.y, 1e-4f)
            assertEquals(90f, turned.rotation)
            turned.height = 10f
            assertEquals(30f, turned.centerX, 1e-4f, "its box where it was, as high as before")
            assertEquals(55f, turned.centerY, 1e-4f)

            val (added, placed, updated) = store.operationsAfter(DOCUMENT, emptyMap()).map { it.change }
            val text = assertIs<TextData>(assertIs<AddElements>(added).elements.single())
            assertEquals(35f to 35f, text.x to text.y)
            val (textPlacement, imagePlacement) = assertIs<PlaceElements>(placed).placements
            assertEquals(TextPlacement("turned", 35f, 35f, 40f, 90f, 16f, autoWidth = true), textPlacement)
            assertEquals(BoxPlacement("image", 1f, 2f, 3f, 4f, 90f), imagePlacement, "an image as it was")
            assertEquals(10f to 20f, assertIs<UpdateTexts>(updated).texts.single().let { it.x to it.y })
        } finally {
            database.close()
        }
    }

    @Test
    fun aDatabaseWithoutItsMigrationIsKept() = runTest {
        BundledSQLiteDriver().open(databaseFile.toString()).use { connection ->
            connection.execSQL("CREATE TABLE notes (text TEXT)")
            connection.execSQL("INSERT INTO notes VALUES ('kept')")
            connection.execSQL("PRAGMA user_version = 99")
        }

        val database = createDrawDatabase(Room.databaseBuilder<DrawDatabase>(databaseFile.toString()))
        try {
            assertFails { RoomDocumentStore(database).loadDocument(DOCUMENT) }
        } finally {
            database.close()
        }

        BundledSQLiteDriver().open(databaseFile.toString()).use { connection ->
            connection.prepare("SELECT text FROM notes").use {
                assertTrue(it.step())
                assertEquals("kept", it.getText(0))
            }
        }
    }

    private companion object {
        fun stamp(time: Long) = Stamp(time, 0, "a10804a2-6191-46be-950d-3394ba97cfef")

        const val DOCUMENT = "eb419115-8ec7-4602-a552-75bfebb25f5e"
        const val PAGE = "ba66823c-4af6-40d2-bfa6-aa0f907b5d1d"
        const val STAMP = "01a11f9462ac.00000000.a10804a2-6191-46be-950d-3394ba97cfef"
    }
}
