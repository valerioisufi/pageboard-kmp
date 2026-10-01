package io.github.valerioisufi.drawview.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import io.github.valerioisufi.drawview.core.platform.DrawViewAndroid

/**
 * Kept for source compatibility: the context is now provided by [DrawViewAndroid.init].
 */
object AndroidDatabaseContext {
    var appContext: Context?
        get() = if (DrawViewAndroid.isInitialized) DrawViewAndroid.context else null
        set(value) {
            if (value != null) DrawViewAndroid.init(value)
        }
}

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
