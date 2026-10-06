package io.github.valerioisufi.drawview.data.db

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
        // Before the first release the schema changes freely: a database of another version (older
        // or newer) is recreated. Write the migrations from the first release on.
        // (Not fallbackToDestructiveMigrationOnDowngrade too: it requires the migrations again.)
        .fallbackToDestructiveMigration(dropAllTables = true)
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
