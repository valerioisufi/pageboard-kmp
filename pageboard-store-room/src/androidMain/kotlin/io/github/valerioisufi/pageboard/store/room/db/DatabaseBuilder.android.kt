package io.github.valerioisufi.pageboard.store.room.db

import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.valerioisufi.pageboard.platform.PageboardAndroid

internal actual fun getDatabaseBuilder(): RoomDatabase.Builder<PageboardDatabase> {
    val context = PageboardAndroid.context
    val dbFile = context.getDatabasePath("pageboard.db")
    return Room.databaseBuilder<PageboardDatabase>(
        context = context,
        name = dbFile.absolutePath
    )
}

public actual fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<PageboardDatabase> {
    return Room.inMemoryDatabaseBuilder<PageboardDatabase>(context = PageboardAndroid.context)
}
