package io.github.valerioisufi.drawview.core.attachment

import kotlinx.coroutines.flow.Flow

/**
 * Data an extension of the editor keeps beside the documents (e.g. the lectures recorded on
 * them), in items identified by a key (unique in their document), each at a version: a
 * synchronization keeps them the same on the devices working on a document without knowing what
 * they are (GRASP Protected Variations).
 *
 * The versions tell the contents apart (e.g. a hash of the content, or the size of a file that
 * never changes once complete): two devices with the same content have the same version. When the
 * versions of an item differ, it is sent to the device that [wants] it, which keeps what it
 * receives as it decides ([receive]: merged with its own, the most recent...); when what it keeps
 * differs from what it received, the item goes back, until both devices have the same version.
 * What a device wants may depend on the items it has (e.g. the files of a record): the items not
 * wanted are asked for again once another one is received.
 */
interface DocumentAttachments {
    /** The items of the document [documentId], by key, with their versions. */
    suspend fun versions(documentId: String): Map<String, String>

    /** The version of the item [key] of [documentId], `null` when it is not here. */
    suspend fun version(documentId: String, key: String): String?

    /** Whether the item [key] of [documentId] at [version] (of another device) is wanted here. */
    suspend fun wants(documentId: String, key: String, version: String): Boolean

    /** The item [key] of [documentId] as it is now, to be read in parts; `null` when it is not here. */
    suspend fun open(documentId: String, key: String): AttachmentSource?

    /** Receives the item [key] at [version], [size] bytes in parts (`null`: not kept). */
    suspend fun receive(documentId: String, key: String, version: String, size: Long): AttachmentReceiver?

    /** The items changed here (by the user, or by what was received). */
    val changes: Flow<AttachmentChange>
}

/** An item being sent: its content at [version] (it does not change while it is read). */
interface AttachmentSource {
    val version: String

    /** The size in bytes. */
    val size: Long

    /** At most [length] bytes from [offset], `null` when they cannot be read (anymore). */
    suspend fun read(offset: Long, length: Int): ByteArray?

    /** Read whole (or not anymore). */
    suspend fun close() {}
}

/** An item being received (see [DocumentAttachments.receive]). */
interface AttachmentReceiver {
    /** The next part of the item. */
    suspend fun write(bytes: ByteArray)

    /** The whole item received: kept (or merged). */
    suspend fun complete()

    /** Not received whole (e.g. the connection is lost): dropped. */
    suspend fun cancel()
}

/** The item [key] of the document [documentId] is now at [version]. */
data class AttachmentChange(val documentId: String, val key: String, val version: String)
