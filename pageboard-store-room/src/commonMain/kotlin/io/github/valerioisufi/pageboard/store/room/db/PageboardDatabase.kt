package io.github.valerioisufi.pageboard.store.room.db

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
        PageLayerEntity::class,
        StrokeEntity::class,
        TextEntity::class,
        ImageEntity::class,
        CustomElementEntity::class,
        PdfEntity::class,
        TextMarkupEntity::class,
        ResourceEntity::class,
        OperationEntity::class,
        OperationBaseEntity::class,
        OperationProofEntity::class,
        SharedDocumentEntity::class,
        ReplicaEntity::class,
        PreferenceEntity::class,
        SpaceEntity::class,
        CatalogItemEntity::class,
        CatalogOperationEntity::class,
        CatalogProofEntity::class
    ],
    // Every new version comes with its migration (see PAGEBOARD_DATABASE_MIGRATIONS)
    version = 27
)
@TypeConverters(
    PageBackgroundConverter::class,
    AffineTransformConverter::class,
    VersionConverters::class
)
@ConstructedBy(PageboardDatabaseConstructor::class)
/**
 * The Room database of the documents, their folders, operations, sharing, the shared folders
 * and the preferences of the applications: opened by [createPageboardDatabase], migrated from version 16 on (see
 * `PAGEBOARD_DATABASE_MIGRATIONS`).
 */
public abstract class PageboardDatabase : RoomDatabase() {
    internal abstract fun folderDao(): FolderDao
    internal abstract fun documentDao(): DocumentDao
    internal abstract fun pageDao(): PageDao
    internal abstract fun pageLayerDao(): PageLayerDao
    internal abstract fun strokeDao(): StrokeDao
    internal abstract fun textDao(): TextDao
    internal abstract fun imageDao(): ImageDao
    internal abstract fun customElementDao(): CustomElementDao
    internal abstract fun pdfDao(): PdfDao
    internal abstract fun textMarkupDao(): TextMarkupDao
    internal abstract fun resourceDao(): ResourceDao
    internal abstract fun operationDao(): OperationDao
    internal abstract fun purgeDao(): PurgeDao
    internal abstract fun sharedDocumentDao(): SharedDocumentDao
    internal abstract fun replicaDao(): ReplicaDao
    internal abstract fun preferencesDao(): PreferencesDao
    internal abstract fun spaceDao(): SpaceDao
}

// The Room compiler generates the implementation of this interface
@Suppress("NO_ACTUAL_FOR_EXPECT")
internal expect object PageboardDatabaseConstructor : RoomDatabaseConstructor<PageboardDatabase> {
    override fun initialize(): PageboardDatabase
}
