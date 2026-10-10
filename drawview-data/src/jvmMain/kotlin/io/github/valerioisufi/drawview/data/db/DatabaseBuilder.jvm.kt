package io.github.valerioisufi.drawview.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.valerioisufi.drawview.platform.drawViewDesktopDataDirectory
import java.io.File

actual fun getDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    val dbFile = File(drawViewDesktopDataDirectory, "database.db")
    return Room.databaseBuilder<DrawDatabase>(
        name = dbFile.absolutePath
    )
}

actual fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    return Room.inMemoryDatabaseBuilder<DrawDatabase>()
}
