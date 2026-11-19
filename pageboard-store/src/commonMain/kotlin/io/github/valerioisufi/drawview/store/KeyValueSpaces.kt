package io.github.valerioisufi.drawview.store

import io.github.valerioisufi.drawview.model.change.OperationId
import io.github.valerioisufi.drawview.store.space.CatalogItem
import io.github.valerioisufi.drawview.store.space.CatalogOperation
import io.github.valerioisufi.drawview.store.space.StoredSpace
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** A space as a [KeyValueDocumentStore] keeps it: one value per space, of the format [format]. */
@Serializable
internal data class StoredSpaceValue(
    val format: Int = 1,
    val rootFolderId: String? = null,
    val sharing: String = "",
    val readOnly: Boolean = false,
    val items: List<CatalogItem> = emptyList(),
    val operations: List<CatalogOperation> = emptyList(),
    val proofs: List<StoredProof> = emptyList()
)

/**
 * The spaces of a [KeyValueDocumentStore] (Information Expert): the list of their identifiers
 * under one key, each space under one key of its own (catalog, log, proofs), read once and kept.
 * A storage written before has none. Not thread-safe: the store calls it under its lock.
 */
internal class KeyValueSpaces(private val storage: KeyValueStorage, private val json: Json) {
    private var ids: MutableList<String>? = null
    private val values = mutableMapOf<String, StoredSpaceValue>()

    fun ids(): MutableList<String> = ids ?: (
        storage.get(SPACES_KEY)
            ?.let { decodeOrNull { json.decodeFromString(ListSerializer(String.serializer()), it) } }
            .orEmpty()
            .toMutableList()
            .also { ids = it }
        )

    fun valueOf(spaceId: String): StoredSpaceValue? = values[spaceId] ?: storage.get(spaceKey(spaceId))
        ?.let { decodeOrNull { json.decodeFromString(StoredSpaceValue.serializer(), it) } }
        ?.also { values[spaceId] = it }

    fun spaces(): List<StoredSpace> = ids().mapNotNull { id -> valueOf(id)?.let { StoredSpace(id, it.rootFolderId, it.sharing, it.readOnly) } }

    fun write(spaceId: String, value: StoredSpaceValue) {
        values[spaceId] = value
        storage.set(spaceKey(spaceId), json.encodeToString(StoredSpaceValue.serializer(), value))
        val ids = ids()
        if (spaceId !in ids) {
            ids.add(spaceId)
            storage.set(SPACES_KEY, json.encodeToString(ListSerializer(String.serializer()), ids))
        }
    }

    fun remove(spaceId: String) {
        values.remove(spaceId)
        storage.remove(spaceKey(spaceId))
        val ids = ids()
        if (ids.remove(spaceId)) storage.set(SPACES_KEY, json.encodeToString(ListSerializer(String.serializer()), ids))
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun proofsOf(value: StoredSpaceValue): Map<OperationId, ByteArray> = value.proofs.mapNotNull { stored ->
        try {
            OperationId(stored.replica, stored.sequence) to Base64.decode(stored.proof)
        } catch (_: IllegalArgumentException) {
            null
        }
    }.toMap()

    @OptIn(ExperimentalEncodingApi::class)
    fun storedProofs(proofs: Map<OperationId, ByteArray>): List<StoredProof> =
        proofs.map { (id, proof) -> StoredProof(id.replica, id.sequence, Base64.encode(proof)) }

    private inline fun <T> decodeOrNull(decode: () -> T): T? = try {
        decode()
    } catch (e: Throwable) {
        println("DrawView: unreadable stored space (${e.message})")
        null
    }

    private companion object {
        const val SPACES_KEY = "pageboard.spaces"
        fun spaceKey(id: String) = "pageboard.space.$id"
    }
}
