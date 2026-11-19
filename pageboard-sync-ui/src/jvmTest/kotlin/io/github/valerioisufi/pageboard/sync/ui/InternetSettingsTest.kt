package io.github.valerioisufi.pageboard.sync.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class InternetSettingsTest {

    /** A secure store in memory. */
    private class Kept(var text: String? = null) {
        fun store() = InternetSettingsStore(read = { text }, write = { text = it })
    }

    @Test
    fun theSettingsAreKept() {
        val kept = Kept()
        assertEquals(InternetSettings(), kept.store().load())
        val settings = InternetSettings(enabled = true, relay = "https://relay.example.com", token = "secret")
        kept.store().save(settings)
        assertEquals(settings, kept.store().load())
        assertFalse("secret" in settings.toString())
    }

    @Test
    fun theFormatOfVersion1IsRead() {
        // As version 1 writes it: what the devices of the user keep from now on
        val kept = Kept("""{"version":1,"enabled":true,"relay":"https://relay.example.com","token":"secret"}""")
        assertEquals(InternetSettings(true, "https://relay.example.com", "secret"), kept.store().load())
    }

    @Test
    fun aValueThisVersionCannotReadIsNeverWrittenOver() {
        for (value in listOf("""{"version":2,"enabled":true,"relays":["a","b"]}""", "not json")) {
            val kept = Kept(value)
            val store = kept.store()
            assertEquals(InternetSettings(), store.load())
            store.save(InternetSettings(enabled = true))
            assertEquals(value, kept.text)
        }
    }
}
