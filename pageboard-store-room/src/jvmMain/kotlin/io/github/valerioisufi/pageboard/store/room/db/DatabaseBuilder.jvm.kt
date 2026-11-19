package io.github.valerioisufi.pageboard.store.room.db

import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.valerioisufi.pageboard.platform.pageboardDesktopDataDirectory
import java.io.File

internal actual fun getDatabaseBuilder(): RoomDatabase.Builder<PageboardDatabase> {
    val dbFile = File(pageboardDesktopDataDirectory, "database.db")
    return Room.databaseBuilder<PageboardDatabase>(
        name = dbFile.absolutePath
    )
}

public actual fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<PageboardDatabase> {
    return Room.inMemoryDatabaseBuilder<PageboardDatabase>()
}
