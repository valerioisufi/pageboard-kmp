package io.github.valerioisufi.drawview.sync.ui

import io.github.valerioisufi.drawview.platform.SecureStore
import io.github.valerioisufi.drawview.store.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.store.KeyValueDocumentStore
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.sync.InternetSync
import io.github.valerioisufi.drawview.sync.SyncEngine
import io.github.valerioisufi.drawview.sync.SyncNetwork
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery
import io.github.valerioisufi.drawview.sync.lan.LanService
import io.github.valerioisufi.drawview.sync.protocol.Invitation
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking

/** The collaboration over the internet: started, changed and stopped as the user turns it on. */
class CollaborationInternetTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A network recording what it is asked. */
    private class FakeNetwork(val relay: String, val token: String) : SyncNetwork {
        val calls = mutableListOf<String>()
        override suspend fun start() { calls += "start" }
        override suspend fun join(invitation: Invitation) { calls += "join ${invitation.documentId}" }
        override suspend fun refresh(networkChanged: Boolean) { calls += "refresh $networkChanged" }
        override suspend fun stop() { calls += "stop" }
    }

    private class FakeInternet : InternetSync {
        val networks = mutableListOf<FakeNetwork>()
        override val isAvailable: Boolean = true
        override fun network(engine: SyncEngine, scope: CoroutineScope, relay: String, token: String): SyncNetwork =
            FakeNetwork(relay, token).also { networks += it }
    }

    private object NoDiscovery : LanDiscovery {
        override suspend fun advertise(name: String, port: Int, attributes: Map<String, String>) = Unit
        override val services: Flow<List<LanService>> = MutableStateFlow(emptyList())
        override fun close() = Unit
    }

    private fun collaboration(internet: InternetSync): Collaboration {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        return Collaboration(OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope), NoDiscovery, { "Shared" }, scope, internet = internet)
    }

    @BeforeTest
    @AfterTest
    fun forgetTheSettings() {
        SecureStore.write(InternetSettingsStore.SECRET_NAME, null)
    }

    @Test
    fun theInternetFollowsTheChoiceOfTheUser() = runBlocking {
        val internet = FakeInternet()
        val collaboration = collaboration(internet)
        assertTrue(collaboration.isInternetAvailable)
        assertFalse(collaboration.internetSettings.value.enabled)
        // Turned off: only the local network
        collaboration.start()
        assertTrue(internet.networks.isEmpty())

        collaboration.setInternet(InternetSettings(enabled = true, relay = "relay.example.com", token = "secret"))
        val first = internet.networks.single()
        assertEquals("relay.example.com" to "secret", first.relay to first.token)
        assertEquals(listOf("start"), first.calls)

        // Joining and coming back to the foreground: over the internet too
        val document = collaboration.engine.invite("doc", "Lab")!!
        collaboration.join(document.toLink())
        collaboration.refresh()
        assertEquals(listOf("start", "join doc", "refresh true"), first.calls)

        // Another relay: the network starts again with it
        collaboration.setInternet(InternetSettings(enabled = true, relay = "other.example.com"))
        assertEquals("stop", first.calls.last())
        assertEquals("other.example.com", internet.networks.last().relay)

        // Turned off, then the settings of the next start
        collaboration.setInternet(InternetSettings(enabled = false, relay = "other.example.com"))
        assertEquals("stop", internet.networks.last().calls.last())
        assertEquals(2, internet.networks.size)
        collaboration.stop()
        assertEquals(InternetSettings(enabled = false, relay = "other.example.com"), collaboration(FakeInternet()).internetSettings.value)
        scope.cancel()
    }

    @Test
    fun turnedOnItStartsWithTheCollaboration() = runBlocking {
        collaboration(FakeInternet()).setInternet(InternetSettings(enabled = true))
        val internet = FakeInternet()
        val collaboration = collaboration(internet)
        collaboration.start()
        assertEquals(listOf("start"), assertNotNull(internet.networks.singleOrNull()).calls)
        collaboration.stop()
        assertEquals("stop", internet.networks.single().calls.last())
        scope.cancel()
    }
}
