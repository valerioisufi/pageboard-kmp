package io.github.valerioisufi.drawview.store.room

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.store.room.db.DRAW_DATABASE_MIGRATIONS
import io.github.valerioisufi.drawview.store.room.db.Migration18To19
import io.github.valerioisufi.drawview.store.room.db.DrawDatabase
import io.github.valerioisufi.drawview.store.room.db.createDrawDatabase
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * The migrations of the database: a database of every version from 16 keeps its documents and its
 * preferences, a database without a migration is not opened (and not dropped).
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
        const val DOCUMENT = "eb419115-8ec7-4602-a552-75bfebb25f5e"
        const val PAGE = "ba66823c-4af6-40d2-bfa6-aa0f907b5d1d"
        const val STAMP = "01a11f9462ac.00000000.a10804a2-6191-46be-950d-3394ba97cfef"
    }
}
