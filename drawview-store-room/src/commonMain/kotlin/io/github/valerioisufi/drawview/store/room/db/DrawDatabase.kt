package io.github.valerioisufi.drawview.store.room.db

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
        CustomElementEntity::class,
        PdfEntity::class,
        TextMarkupEntity::class,
        ResourceEntity::class,
        OperationEntity::class,
        OperationBaseEntity::class,
        SharedDocumentEntity::class,
        ReplicaEntity::class,
        PreferenceEntity::class
    ],
    // Every new version comes with its migration (see DRAW_DATABASE_MIGRATIONS)
    version = 18
)
@TypeConverters(
    PageBackgroundConverter::class,
    AffineTransformConverter::class,
    VersionConverters::class
)
@ConstructedBy(DrawDatabaseConstructor::class)
abstract class DrawDatabase : RoomDatabase() {
    abstract fun folderDao(): FolderDao
    abstract fun documentDao(): DocumentDao
    abstract fun pageDao(): PageDao
    abstract fun strokeDao(): StrokeDao
    abstract fun textDao(): TextDao
    abstract fun imageDao(): ImageDao
    abstract fun customElementDao(): CustomElementDao
    abstract fun pdfDao(): PdfDao
    abstract fun textMarkupDao(): TextMarkupDao
    abstract fun resourceDao(): ResourceDao
    abstract fun operationDao(): OperationDao
    abstract fun purgeDao(): PurgeDao
    abstract fun sharedDocumentDao(): SharedDocumentDao
    abstract fun replicaDao(): ReplicaDao
    abstract fun preferencesDao(): PreferencesDao
}

// The Room compiler generates the implementation of this interface
@Suppress("NO_ACTUAL_FOR_EXPECT")
expect object DrawDatabaseConstructor : RoomDatabaseConstructor<DrawDatabase> {
    override fun initialize(): DrawDatabase
}
