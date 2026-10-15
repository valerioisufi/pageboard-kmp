package io.github.valerioisufi.drawview.store.room.db

import androidx.room.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

expect fun getDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase>

expect fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase>

fun createDrawDatabase(
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

/**
 * Process-wide database instance: Room must not open the same file more than once.
 */
object DrawViewDatabase {
    val instance: DrawDatabase by lazy { createDrawDatabase() }
}
