package io.github.valerioisufi.pageboard.sync.ui

import io.github.valerioisufi.pageboard.platform.SecureStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Whether this device works with the others over the internet ([enabled]), and through which
 * relay: its address ([relay]; empty: the public relays of the engine, rate-limited, for trying)
 * and its access [token].
 */
public data class InternetSettings(val enabled: Boolean = false, val relay: String = "", val token: String = "") {
    /** Without the token, a secret (logs). */
    override fun toString(): String = "InternetSettings(enabled=$enabled, relay=$relay, token=${if (token.isEmpty()) "none" else "hidden"})"
}

/**
 * The [InternetSettings] of this device, kept in the [SecureStore] (the token is a secret) as one
 * JSON value with the version of its format. A value this version cannot read (written by a newer
 * application, or damaged) is never written over: the settings then stay in memory.
 */
internal class InternetSettingsStore(
    private val read: () -> String? = { SecureStore.read(SECRET_NAME) },
    private val write: (String) -> Unit = { SecureStore.write(SECRET_NAME, it) }
) {
    private var readOnly = false

    @Serializable
    internal class Stored(val version: Int, val enabled: Boolean = false, val relay: String = "", val token: String = "")

    fun load(): InternetSettings {
        val text = read() ?: return InternetSettings()
        val stored = decode(text)
        if (stored == null || stored.version > VERSION) {
            readOnly = true
            return InternetSettings()
        }
        return InternetSettings(stored.enabled, stored.relay, stored.token)
    }

    fun save(settings: InternetSettings) {
        if (!readOnly) write(encode(settings))
    }

    internal companion object {
        const val SECRET_NAME = "pageboard-sync-internet"

        /** The version of the format written. */
        const val VERSION = 1

        private val json = Json { ignoreUnknownKeys = true }

        fun encode(settings: InternetSettings): String =
            json.encodeToString(Stored.serializer(), Stored(VERSION, settings.enabled, settings.relay, settings.token))

        fun decode(text: String): Stored? = try {
            json.decodeFromString(Stored.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
