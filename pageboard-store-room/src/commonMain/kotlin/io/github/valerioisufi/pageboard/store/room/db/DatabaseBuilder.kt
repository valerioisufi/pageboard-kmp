package io.github.valerioisufi.pageboard.store.room.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal expect fun getDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase>

/** A database in memory, gone with the process (tests, tools). */
public expect fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase>

/**
 * Opens the database of [builder] (by default, the file of the data directory of the
 * application) with its migrations: the documents are never dropped. Room must not open the same
 * file more than once: the application opens it once per process and keeps it.
 */
public fun createDrawDatabase(
    builder: RoomDatabase.Builder<DrawDatabase> = getDatabaseBuilder()
): DrawDatabase {
    return builder
        // The documents are never dropped: a new version of the schema comes with its migration, a
        // database without one (older than 16, or of a newer version) is not opened.
        .addMigrations(*DRAW_DATABASE_MIGRATIONS)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .build()
}
