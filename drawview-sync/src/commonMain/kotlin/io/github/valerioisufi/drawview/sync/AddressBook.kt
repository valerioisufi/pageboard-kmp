package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.store.PreferenceStore
import io.github.valerioisufi.drawview.sync.protocol.DeviceAddress
import kotlin.io.encoding.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Keeps where the members of the shared documents can be reached (their [DeviceAddress]es,
 * signed by each one) from one start of the application to the next (GRASP Protected Variations:
 * the engine does not know where). The engine holds them in memory and gives them all at every
 * change.
 */
public interface AddressBook {
    /** The addresses kept. */
    public suspend fun load(): List<DeviceAddress>

    /** Keeps [addresses], all of them (they replace the ones kept). */
    public suspend fun save(addresses: List<DeviceAddress>)
}

/** An [AddressBook] in memory: the tests, an application keeping nothing. */
public class InMemoryAddressBook : AddressBook {
    private var addresses: List<DeviceAddress> = emptyList()

    override suspend fun load(): List<DeviceAddress> = addresses

    override suspend fun save(addresses: List<DeviceAddress>) {
        this.addresses = addresses.toList()
    }
}

/**
 * An [AddressBook] in the preferences of a store ([PreferenceStore], under [key]): one JSON value
 * with the version of its format. A value this version cannot read (written by a newer
 * application, or damaged) is never written over: the addresses are then kept in memory only.
 */
public class PreferenceAddressBook(
    private val preferences: PreferenceStore,
    private val key: String = DEFAULT_KEY
) : AddressBook {
    private var readOnly = false

    @Serializable
    internal class Stored(val version: Int, val addresses: List<StoredAddress> = emptyList())

    @Serializable
    internal class StoredAddress(
        val device: String,
        val transport: String,
        val address: String,
        val issuedAt: Long,
        val signature: String
    )

    override suspend fun load(): List<DeviceAddress> {
        val text = preferences.preference(key) ?: return emptyList()
        val stored = decode(text)
        if (stored == null || stored.version > VERSION) {
            readOnly = true
            return emptyList()
        }
        return stored.addresses.mapNotNull { address ->
            val signature = runCatching { Base64.decode(address.signature) }.getOrNull() ?: return@mapNotNull null
            DeviceAddress(address.device, address.transport, address.address, address.issuedAt, signature)
        }
    }

    override suspend fun save(addresses: List<DeviceAddress>) {
        if (readOnly) return
        preferences.setPreference(key, encode(addresses))
    }

    public companion object {
        /** The preference the addresses are kept under, by default. */
        public const val DEFAULT_KEY: String = "drawview.sync.addresses"

        /** The version of the format written. */
        internal const val VERSION: Int = 1

        private val json = Json { ignoreUnknownKeys = true }

        internal fun encode(addresses: List<DeviceAddress>): String = json.encodeToString(
            Stored.serializer(),
            Stored(
                VERSION,
                addresses.map { StoredAddress(it.device, it.transport, it.address, it.issuedAt, Base64.encode(it.signature)) }
            )
        )

        internal fun decode(text: String): Stored? = try {
            json.decodeFromString(Stored.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
