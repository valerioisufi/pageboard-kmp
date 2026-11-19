package io.github.valerioisufi.pageboard.store.room.db

import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.valerioisufi.pageboard.platform.DrawViewAndroid

internal actual fun getDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    val context = DrawViewAndroid.context
    val dbFile = context.getDatabasePath("pageboard.db")
    return Room.databaseBuilder<DrawDatabase>(
        context = context,
        name = dbFile.absolutePath
    )
}

public actual fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    return Room.inMemoryDatabaseBuilder<DrawDatabase>(context = DrawViewAndroid.context)
}
