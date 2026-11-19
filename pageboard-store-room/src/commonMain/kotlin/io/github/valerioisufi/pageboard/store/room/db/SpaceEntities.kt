package io.github.valerioisufi.pageboard.store.room.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import io.github.valerioisufi.pageboard.model.Stamp
import kotlinx.coroutines.flow.Flow

/**
 * A shared folder (a space, see `docs/plans/shared-folders.md`): its root in the library
 * ([rootFolderId], `null` for the library itself; no foreign key: the folder may go to the trash
 * or be deleted while the space stays), its sharing (a secret, as `shared_documents`) and whether
 * it may only be read here.
 */
@Entity(tableName = "spaces")
internal data class SpaceEntity(
    @PrimaryKey val id: String,
    val rootFolderId: String?,
    val sharing: String,
    val readOnly: Boolean
)

/** An item of the catalog of a space with its pieces and their stamps, as JSON (`CatalogItem`). */
@Entity(
    tableName = "catalog_items",
    primaryKeys = ["spaceId", "itemId"],
    foreignKeys = [ForeignKey(entity = SpaceEntity::class, parentColumns = ["id"], childColumns = ["spaceId"], onDelete = ForeignKey.CASCADE)]
)
internal data class CatalogItemEntity(
    val spaceId: String,
    val itemId: String,
    val item: String
)

/** A change of a space as logged (`CatalogOperation`, its change as JSON). */
@Entity(
    tableName = "catalog_operations",
    primaryKeys = ["spaceId", "replica", "sequence"],
    foreignKeys = [ForeignKey(entity = SpaceEntity::class, parentColumns = ["id"], childColumns = ["spaceId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["spaceId", "stamp"])]
)
internal data class CatalogOperationEntity(
    val spaceId: String,
    val replica: String,
    val sequence: Long,
    val stamp: Stamp,
    val change: String
)

/** The proof of a change of a space made by another device, opaque bytes kept to give it on. */
@Entity(
    tableName = "catalog_proofs",
    primaryKeys = ["spaceId", "replica", "sequence"],
    foreignKeys = [ForeignKey(entity = SpaceEntity::class, parentColumns = ["id"], childColumns = ["spaceId"], onDelete = ForeignKey.CASCADE)]
)
internal class CatalogProofEntity(
    val spaceId: String,
    val replica: String,
    val sequence: Long,
    val proof: ByteArray
)

/** Data Access Object for the spaces, their catalogs and their logs. */
@Dao
internal interface SpaceDao {
    @Query("SELECT * FROM spaces")
    suspend fun spaces(): List<SpaceEntity>

    /** The roots of the shared folders (not the library), as they change. */
    @Query("SELECT rootFolderId FROM spaces WHERE rootFolderId IS NOT NULL")
    fun rootFoldersFlow(): Flow<List<String>>

    @Query("SELECT * FROM spaces WHERE id = :spaceId")
    suspend fun space(spaceId: String): SpaceEntity?

    @Upsert
    suspend fun upsertSpace(space: SpaceEntity)

    @Query("DELETE FROM spaces WHERE id = :spaceId")
    suspend fun deleteSpace(spaceId: String)

    @Query("SELECT * FROM catalog_items WHERE spaceId = :spaceId")
    suspend fun items(spaceId: String): List<CatalogItemEntity>

    @Upsert
    suspend fun upsertItems(items: List<CatalogItemEntity>)

    /** Logs [operation] (once: an operation already logged is ignored), `-1` when it was. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperation(operation: CatalogOperationEntity): Long

    @Query("SELECT replica, MAX(sequence) AS sequence FROM catalog_operations WHERE spaceId = :spaceId GROUP BY replica")
    suspend fun lastSequences(spaceId: String): List<ReplicaSequence>

    @Query("SELECT * FROM catalog_operations WHERE spaceId = :spaceId AND replica = :replica AND sequence > :after ORDER BY sequence")
    suspend fun operationsAfter(spaceId: String, replica: String, after: Long): List<CatalogOperationEntity>

    @Upsert
    suspend fun upsertProofs(proofs: List<CatalogProofEntity>)

    @Query("SELECT * FROM catalog_proofs WHERE spaceId = :spaceId AND replica = :replica AND sequence IN (:sequences)")
    suspend fun proofs(spaceId: String, replica: String, sequences: List<Long>): List<CatalogProofEntity>
}
