package io.github.valerioisufi.drawview.data.db

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor
import androidx.room.TypeConverters

@Database(
    entities = [
        FolderEntity::class,
        DocumentEntity::class,
        PageEntity::class,
        StrokeEntity::class,
        TextEntity::class,
        ImageEntity::class,
        PdfEntity::class,
        TextMarkupEntity::class,
        ResourceEntity::class,
        UserPreferencesEntity::class
    ],
    // No migrations before the first release: an older database is recreated (see createDrawDatabase)
    version = 4
)
@TypeConverters(
    PageBackgroundConverter::class,
    BrushPresetListConverter::class
)
@ConstructedBy(DrawDatabaseConstructor::class)
abstract class DrawDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun textDao(): TextDao
    abstract fun imageDao(): ImageDao
    abstract fun pdfDao(): PdfDao
    abstract fun textMarkupDao(): TextMarkupDao
    abstract fun resourceDao(): ResourceDao
    abstract fun preferencesDao(): PreferencesDao
}

// The Room compiler generates the implementation of this interface
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DrawDatabaseConstructor : RoomDatabaseConstructor<DrawDatabase> {
    override fun initialize(): DrawDatabase
}
