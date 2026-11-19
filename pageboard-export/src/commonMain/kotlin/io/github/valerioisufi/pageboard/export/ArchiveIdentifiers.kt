package io.github.valerioisufi.pageboard.export

import io.github.valerioisufi.pageboard.model.change.DocumentSnapshot
import io.github.valerioisufi.pageboard.model.newElementId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * New identifiers for a note brought back from an archive: its pages (and removed pages), their
 * elements (and removed elements) and its resources each get a new one, so that the copy shares
 * none with the note it came from, which may still be in the library (a store may key its pages and
 * elements by their identifier alone).
 *
 * The identifiers are replaced everywhere in the JSON of the snapshot, values and keys: the
 * reference of an element to a resource or to the next text, the versions keyed by element, and any
 * reference added later to the model.
 */
internal object ArchiveIdentifiers {
    /** [snapshot] with new identifiers, and [files] (keyed by resource) following their resources. */
    fun renew(snapshot: DocumentSnapshot, files: Map<String, ByteArray>): Pair<DocumentSnapshot, Map<String, ByteArray>> {
        val renewed = identifiersOf(snapshot).associateWith { newElementId() }
        if (renewed.isEmpty()) return snapshot to files
        val json = ArchiveFormat.json
        val tree = json.encodeToJsonElement(DocumentSnapshot.serializer(), snapshot)
        val copy = json.decodeFromJsonElement(DocumentSnapshot.serializer(), tree.replacing(renewed))
        return copy to files.mapKeys { (id, _) -> renewed[id] ?: id }
    }

    /** The identifiers of the pages, the elements and the resources of [snapshot]. */
    fun identifiersOf(snapshot: DocumentSnapshot): Set<String> = buildSet {
        for (page in snapshot.pages + snapshot.removedPages) {
            add(page.data.id)
            page.data.elements.forEach { add(it.id) }
            page.removedElements.forEach { add(it.id) }
        }
        snapshot.resources.forEach { add(it.id) }
    }

    private fun JsonElement.replacing(ids: Map<String, String>): JsonElement = when (this) {
        is JsonObject -> JsonObject(entries.associate { (key, value) -> (ids[key] ?: key) to value.replacing(ids) })
        is JsonArray -> JsonArray(map { it.replacing(ids) })
        is JsonPrimitive -> if (isString) ids[content]?.let(::JsonPrimitive) ?: this else this
    }
}
