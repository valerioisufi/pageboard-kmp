package io.github.valerioisufi.pageboard.store.room.db

import androidx.room.Room
import androidx.room.RoomDatabase
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal actual fun getDatabaseBuilder(): RoomDatabase.Builder<PageboardDatabase> {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    )
    val dbFilePath = requireNotNull(documentDirectory?.path) + "/pageboard.db"
    return Room.databaseBuilder<PageboardDatabase>(
        name = dbFilePath
    )
}

public actual fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<PageboardDatabase> {
    return Room.inMemoryDatabaseBuilder<PageboardDatabase>()
}
