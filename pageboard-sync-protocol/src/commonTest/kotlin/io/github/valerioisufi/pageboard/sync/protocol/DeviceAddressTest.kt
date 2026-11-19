package io.github.valerioisufi.pageboard.sync.protocol

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeviceAddressTest {
    @Test
    fun anAddressChangedOnTheWayIsNotSignedAnymore() {
        val identity = DeviceIdentity.generate()
        val address = DeviceAddress.sign(identity, "iroh", "f00d https://relay.example.com/", 100)
        assertTrue(address.isSigned())
        assertFalse(DeviceAddress(address.device, address.transport, "elsewhere", address.issuedAt, address.signature).isSigned())
        assertFalse(DeviceAddress(address.device, "lan", address.address, address.issuedAt, address.signature).isSigned())
        assertFalse(DeviceAddress(address.device, address.transport, address.address, 101, address.signature).isSigned())
        // Another device cannot pass for it
        val other = DeviceIdentity.generate().publicKey
        assertFalse(DeviceAddress(other, address.transport, address.address, address.issuedAt, address.signature).isSigned())
    }

    @Test
    fun theMoreRecentAddressWins() {
        val identity = DeviceIdentity.generate()
        val address = DeviceAddress.sign(identity, "iroh", "here", 100)
        assertTrue(address.isNewerThan(null))
        assertTrue(DeviceAddress.sign(identity, "iroh", "there", 101).isNewerThan(address))
        assertFalse(address.isNewerThan(address))
    }
}
