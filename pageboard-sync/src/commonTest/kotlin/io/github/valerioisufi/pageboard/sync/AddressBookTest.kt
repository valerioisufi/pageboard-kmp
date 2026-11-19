package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.sync.protocol.DeviceAddress
import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AddressBookTest {

    @Test
    fun theAddressesAreKeptInThePreferences() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val addresses = listOf(
            DeviceAddress.sign(DeviceIdentity.generate(), "iroh", "f00d https://relay.example.com/ 192.0.2.1:4433", 42),
            DeviceAddress.sign(DeviceIdentity.generate(), "lan", "192.168.1.20:4567", 43)
        )
        PreferenceAddressBook(store).save(addresses)
        val loaded = PreferenceAddressBook(store).load()
        assertEquals(addresses, loaded)
        assertTrue(loaded.all { it.isSigned() })
    }

    @Test
    fun theFormatOfVersion1IsRead() = runTest {
        // As version 1 writes it: what the devices of the user keep from now on
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        store.setPreference(
            PreferenceAddressBook.DEFAULT_KEY,
            """{"version":1,"addresses":[{"device":"MFkw","transport":"iroh","address":"f00d https://relay.example.com/","issuedAt":42,"signature":"AQID"}]}"""
        )
        val address = PreferenceAddressBook(store).load().single()
        assertEquals("MFkw", address.device)
        assertEquals("iroh", address.transport)
        assertEquals("f00d https://relay.example.com/", address.address)
        assertEquals(42, address.issuedAt)
        assertEquals(listOf<Byte>(1, 2, 3), address.signature.toList())
    }

    @Test
    fun aValueThisVersionCannotReadIsNeverWrittenOver() = runTest {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = backgroundScope)
        val address = DeviceAddress.sign(DeviceIdentity.generate(), "iroh", "here", 1)
        for (kept in listOf("""{"version":2,"addresses":[],"more":true}""", "not json")) {
            store.setPreference(PreferenceAddressBook.DEFAULT_KEY, kept)
            val book = PreferenceAddressBook(store)
            assertEquals(emptyList(), book.load())
            book.save(listOf(address))
            assertEquals(kept, store.preference(PreferenceAddressBook.DEFAULT_KEY))
        }
    }

    @Test
    fun onlyTheAddressesSignedByAMemberAreKept() {
        val ada = DeviceIdentity.generate()
        val bob = DeviceIdentity.generate()
        val known = KnownAddresses()
        val good = DeviceAddress.sign(bob, "iroh", "here", 10)
        val forged = DeviceAddress(bob.publicKey, "iroh", "elsewhere", 20, good.signature)
        val stranger = DeviceAddress.sign(DeviceIdentity.generate(), "iroh", "somewhere", 10)
        val mine = DeviceAddress.sign(ada, "iroh", "mine", 10)
        val members = setOf(ada.publicKey, bob.publicKey)

        assertEquals(listOf(good), known.take(listOf(good, forged, stranger, mine), ada.publicKey) { it in members })
        // Older, or the same: not again
        assertEquals(emptyList(), known.take(listOf(DeviceAddress.sign(bob, "iroh", "before", 5), good), ada.publicKey) { it in members })
        val newer = DeviceAddress.sign(bob, "iroh", "there", 30)
        assertEquals(listOf(newer), known.take(listOf(newer), ada.publicKey) { it in members })
        assertEquals(listOf(newer), known.list())
        // No longer a member: forgotten
        assertTrue(known.retain { it == ada.publicKey })
        assertEquals(emptyList(), known.list())
    }
}
