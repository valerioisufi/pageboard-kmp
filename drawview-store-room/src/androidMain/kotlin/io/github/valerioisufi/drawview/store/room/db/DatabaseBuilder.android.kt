package io.github.valerioisufi.drawview.store.room.db

import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.valerioisufi.drawview.platform.DrawViewAndroid

actual fun getDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    val context = DrawViewAndroid.context
    val dbFile = context.getDatabasePath("drawview.db")
    return Room.databaseBuilder<DrawDatabase>(
        context = context,
        name = dbFile.absolutePath
    )
}

actual fun getInMemoryDatabaseBuilder(): RoomDatabase.Builder<DrawDatabase> {
    return Room.inMemoryDatabaseBuilder<DrawDatabase>(context = DrawViewAndroid.context)
}
