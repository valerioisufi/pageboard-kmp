package io.github.valerioisufi.drawview.core.platform

/**
 * Small secrets of the application (keys of services, the identity of the device), kept by the
 * system where it has a place for them: the Keychain on iOS, a key of the Android Keystore
 * encrypting them on Android; on the desktop a file of the data directory only its user can read.
 */
expect object SecureStore {
    /** The secret [name], or `null` when there is none. */
    fun read(name: String): String?

    /** Keeps [value] as the secret [name] (`null`: forgets it); whether it was kept. */
    fun write(name: String, value: String?): Boolean
}
