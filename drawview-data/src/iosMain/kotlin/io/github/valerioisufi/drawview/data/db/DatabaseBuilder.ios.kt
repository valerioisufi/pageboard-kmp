package io.github.valerioisufi.drawview.data.db

import androidx.room.Room
import androidx.room.RoomDatabase
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun getDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    )
    val dbFilePath = requireNotNull(documentDirectory?.path) + "/drawview.db"
    return Room.databaseBuilder<DrawDatabase>(
        name = dbFilePath
    )
}

actual fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    return Room.inMemoryDatabaseBuilder<DrawDatabase>()
}
