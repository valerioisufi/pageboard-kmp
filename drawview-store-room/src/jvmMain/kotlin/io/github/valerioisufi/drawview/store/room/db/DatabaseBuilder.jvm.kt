package io.github.valerioisufi.drawview.store.room.db

import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.valerioisufi.drawview.platform.drawViewDesktopDataDirectory
import java.io.File

internal actual fun getDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    val dbFile = File(drawViewDesktopDataDirectory, "database.db")
    return Room.databaseBuilder<DrawDatabase>(
        name = dbFile.absolutePath
    )
}

public actual fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    return Room.inMemoryDatabaseBuilder<DrawDatabase>()
}
