package io.github.valerioisufi.drawview.data.db

import androidx.room.AutoMigration
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
        ResourceEntity::class,
        UserPreferencesEntity::class
    ],
    version = 2,
    autoMigrations = [
        // v2: text alignment and Markdown flag
        AutoMigration(from = 1, to = 2)
    ]
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
    abstract fun resourceDao(): ResourceDao
    abstract fun preferencesDao(): PreferencesDao
}

// The Room compiler generates the implementation of this interface
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DrawDatabaseConstructor : RoomDatabaseConstructor<DrawDatabase> {
    override fun initialize(): DrawDatabase
}
