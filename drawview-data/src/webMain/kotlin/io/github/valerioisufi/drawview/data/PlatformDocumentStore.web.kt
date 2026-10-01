package io.github.valerioisufi.drawview.data

/**
 * Room is not available in the browser: documents are stored in `localStorage`.
 */
actual fun createPlatformDocumentStore(): DocumentStore = KeyValueDocumentStore(BrowserLocalStorage)

/**
 * The browser `localStorage` (or an in-memory map when it is not accessible, e.g. when the
 * storage is disabled by the user).
 */
internal object BrowserLocalStorage : KeyValueStorage {
    private val fallback = InMemoryKeyValueStorage()
    private val available: Boolean by lazy { localStorageAvailable() }

    override fun get(key: String): String? = if (available) localStorageGet(key) else fallback.get(key)

    override fun set(key: String, value: String): Boolean =
        if (available) localStorageSet(key, value) else fallback.set(key, value)

    override fun remove(key: String) {
        if (available) localStorageRemove(key) else fallback.remove(key)
    }
}

internal expect fun localStorageAvailable(): Boolean
internal expect fun localStorageGet(key: String): String?

/** `false` when the value could not be stored (quota exceeded). */
internal expect fun localStorageSet(key: String, value: String): Boolean
internal expect fun localStorageRemove(key: String)
