package io.github.valerioisufi.drawview.store

/**
 * The documents shared with other devices: their secrets, and whether this device may only read
 * them.
 */
public interface SharingStore {
    /**
     * The secrets of the documents shared with other devices (written as text by `pageboard-sync`),
     * by document: whoever has the secret of a document can read and change it.
     */
    public suspend fun sharingSecrets(): Map<String, String>

    /**
     * Shares the document [documentId] with [secret], or stops sharing it (`null`); [readOnly]:
     * this device may only read it (see `DocumentSession.isReadOnly`).
     */
    public suspend fun setSharingSecret(documentId: String, secret: String?, readOnly: Boolean = false)

    /** Whether this device may only read the document [documentId] (see [setSharingSecret]). */
    public suspend fun isReadOnly(documentId: String): Boolean
}
