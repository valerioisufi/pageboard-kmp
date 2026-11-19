package io.github.valerioisufi.pageboard.store

import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The operations of the documents of a [KeyValueDocumentStore] and their proofs (Information
 * Expert): one entry of each per document in [storage], read once and kept. Not thread-safe: the
 * store calls it under its lock.
 */
internal class KeyValueOperationLog(private val storage: KeyValueStorage, private val json: Json) {
    private val operations = mutableMapOf<String, MutableList<Operation>>()

    /** The proofs of the operations of the documents read so far (see [OperationLog.saveProofs]). */
    private val proofs = mutableMapOf<String, MutableMap<OperationId, ByteArray>>()

    /** The operations of [documentId], to change then [writeOperations]. */
    fun operationsOf(documentId: String): MutableList<Operation> = operations.getOrPut(documentId) {
        storage.get(operationsKey(documentId))
            ?.let { decodeOrNull { json.decodeFromString(ListSerializer(Operation.serializer()), it) } }
            .orEmpty()
            .toMutableList()
    }

    /** Keeps the operations of [documentId]. */
    fun writeOperations(documentId: String) {
        storage.set(operationsKey(documentId), json.encodeToString(ListSerializer(Operation.serializer()), operationsOf(documentId)))
    }

    /** The proofs of the operations of [documentId], to change then [writeProofs]. */
    fun proofsOf(documentId: String): MutableMap<OperationId, ByteArray> = proofs.getOrPut(documentId) {
        storage.get(proofsKey(documentId))
            ?.let { decodeOrNull { json.decodeFromString(StoredProofs.serializer(), it) } }
            ?.proofs.orEmpty()
            .mapNotNull { stored -> decodeProof(stored.proof)?.let { OperationId(stored.replica, stored.sequence) to it } }
            .toMap(mutableMapOf())
    }

    /** Keeps the proofs of [documentId] (none: their entry goes). */
    @OptIn(ExperimentalEncodingApi::class)
    fun writeProofs(documentId: String) {
        val proofs = proofsOf(documentId)
        if (proofs.isEmpty()) {
            storage.remove(proofsKey(documentId))
            return
        }
        val stored = StoredProofs(proofs = proofs.map { (id, proof) -> StoredProof(id.replica, id.sequence, Base64.encode(proof)) })
        storage.set(proofsKey(documentId), json.encodeToString(StoredProofs.serializer(), stored))
    }

    /** Forgets the operations and the proofs of [documentId], deleted. */
    fun forget(documentId: String) {
        storage.remove(operationsKey(documentId))
        operations.remove(documentId)
        storage.remove(proofsKey(documentId))
        proofs.remove(documentId)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun decodeProof(text: String): ByteArray? = try {
        Base64.decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    private inline fun <T> decodeOrNull(decode: () -> T): T? = try {
        decode()
    } catch (e: Throwable) {
        println("DrawView: unreadable stored value (${e.message})")
        null
    }

    private companion object {
        // The keys of KeyValueDocumentStore (its stored format)
        fun operationsKey(id: String) = "pageboard.operations.$id"
        fun proofsKey(id: String) = "pageboard.proofs.$id"
    }
}
