package io.github.valerioisufi.drawview.sync

import androidx.compose.ui.graphics.Color
import io.github.valerioisufi.drawview.core.change.AddElements
import io.github.valerioisufi.drawview.core.change.AddPage
import io.github.valerioisufi.drawview.core.change.AddResource
import io.github.valerioisufi.drawview.core.change.CompositeChange
import io.github.valerioisufi.drawview.core.change.DocumentChange
import io.github.valerioisufi.drawview.core.change.DocumentSession
import io.github.valerioisufi.drawview.core.change.DocumentSnapshot
import io.github.valerioisufi.drawview.core.change.MovePages
import io.github.valerioisufi.drawview.core.change.Operation
import io.github.valerioisufi.drawview.core.change.OperationId
import io.github.valerioisufi.drawview.core.change.PlaceElements
import io.github.valerioisufi.drawview.core.change.RemoveElements
import io.github.valerioisufi.drawview.core.change.RemovePage
import io.github.valerioisufi.drawview.core.change.StrokePlacement
import io.github.valerioisufi.drawview.core.change.TextData
import io.github.valerioisufi.drawview.core.change.UpdateTexts
import io.github.valerioisufi.drawview.core.change.snapshot
import io.github.valerioisufi.drawview.core.change.toData
import io.github.valerioisufi.drawview.core.geometry.AffineTransform
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.DrawDocument
import io.github.valerioisufi.drawview.core.model.DrawImage
import io.github.valerioisufi.drawview.core.model.DrawPage
import io.github.valerioisufi.drawview.core.model.DrawResource
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.DrawText
import io.github.valerioisufi.drawview.core.model.StrokeMesh
import io.github.valerioisufi.drawview.core.model.StrokePoint
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.data.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.data.KeyValueDocumentStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

class SyncEngineTest {

    // --- Invitations and encryption ---

    @Test
    fun anInvitationIsALink() {
        val invitation = Invitation("doc-1", "Appunti di Fisica & co. 100%", DocumentKey.generate(), "192.168.1.20:4567")
        assertEquals(invitation, Invitation.parse(invitation.toLink()))
        assertEquals(invitation.copy(address = null), Invitation.parse(invitation.copy(address = null).toLink()))
        assertNull(Invitation.parse("https://example.com/?d=x&k=y"))
        assertNull(Invitation.parse("drawview://join?d=doc&k=not-a-key"))
    }

    @Test
    fun theFramesAreEncryptedWithTheKeyOfTheDocument() = runTest {
        val key = DocumentKey.generate()
        val (one, other) = InMemoryChannel.pair()
        val sender = SecureChannel.of(one, key)
        sender.send("hello".encodeToByteArray())
        assertEquals("hello", SecureChannel.of(other, key).receive()?.decodeToString())

        // Another key cannot read them
        val (third, fourth) = InMemoryChannel.pair()
        SecureChannel.of(third, key).send("secret".encodeToByteArray())
        assertFailsWith<SyncException> { SecureChannel.of(fourth, DocumentKey.generate()).receive() }
        assertEquals(key, DocumentKey.decode(key.encode()))
        assertTrue(key.tag != DocumentKey.generate().tag)
    }

    @Test
    fun theMessagesSurviveTheirBinaryForm() {
        val document = DrawDocument()
        document.pages[0].add(stroke(1f, 2f))
        document.pages[0].add(text("hello"))
        val session = DocumentSession(document, "ada")
        session.submit(AddPage.of(DrawPage(orderKey = document.orderKeyAt(1))))
        val operation = Operation(
            OperationId("ada", 1),
            session.clock.next(),
            AddElements.of(document.pages[0], listOf(stroke(3f, 4f)))
        )
        val messages = listOf(
            Hello("ada", "Ada", mapOf("ada" to 3L), hasDocument = true),
            Snapshot(document.snapshot()),
            Operations(listOf(operation)),
            ResourceRequest(listOf("photo"))
        )
        for (message in messages) assertEquals(message, SyncCodec.decodeOrThrow(SyncCodec.encode(message)))
    }

    // --- Devices ---

    /** Files kept in memory. */
    private class MemoryFiles : ResourceFiles {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(path: String): ByteArray? = files[path]
        override fun write(bytes: ByteArray, extension: String): String = "mem://${files.size}.$extension".also { files[it] = bytes }
    }

    /** A device: its store, its shared documents and its engine, reachable on [network] at [name]. */
    private class Device(val name: String, scope: CoroutineScope, test: TestScope, val network: InMemoryNetwork) {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val shared = StoreSharedDocuments(store)
        val files = MemoryFiles()
        val engine = SyncEngine(documents, shared, scope, name, files) { store.folders.first().firstOrNull()?.id }
        val transport = network.transport(name)
        private val connections = mutableListOf<Job>()

        init {
            scope.launch { transport.incoming.collect { engine.accept(it) } }
        }

        /** Joins [invitation] through the device at [address]. */
        suspend fun join(invitation: Invitation, address: String) {
            connections += engine.join(transport.connect(address), invitation)
        }

        suspend fun disconnect() {
            engine.stop()
            connections.clear()
        }

        /** Makes the change [block] gives (if any) on the document [documentId]. */
        suspend fun change(documentId: String, block: (DocumentSession) -> DocumentChange?) {
            val open = assertNotNull(documents.open(documentId))
            open.edit { session -> block(session)?.let { session.submit(it) } }
            documents.close(open)
        }

        suspend fun snapshot(documentId: String): DocumentSnapshot {
            val open = assertNotNull(documents.open(documentId))
            return open.edit { it.document.snapshot() }.also { documents.close(open) }
        }

        suspend fun versionVector(documentId: String): Map<String, Long> {
            val open = assertNotNull(documents.open(documentId))
            return open.edit { it.versionVector }.also { documents.close(open) }
        }
    }

    private fun stroke(x: Float, y: Float): DrawStroke {
        val mesh = StrokeMesh(
            positions = floatArrayOf(x, y, x + 5f, y, x + 5f, y + 5f, x, y + 5f),
            indices = shortArrayOf(0, 1, 2, 0, 2, 3),
            color = Color.Black,
            tool = StrokeTool.MARKER,
            size = 1f,
            points = listOf(StrokePoint(x, y, 0.5f, 0L), StrokePoint(x + 5f, y + 5f, 0.5f, 16L))
        )
        return DrawStroke(mesh = mesh, brushConfig = BrushConfig(tool = StrokeTool.MARKER, size = 1f))
    }

    private fun text(content: String) = DrawText().apply {
        text = content
        x = 10f; y = 20f; width = 40f; height = 10f
        fontSize = 16f
    }

    /** A change of a random kind on a random page of [session]'s document. */
    private fun randomChange(session: DocumentSession, random: Random): DocumentChange? {
        val document = session.document
        if (document.pages.isEmpty()) return AddPage.of(DrawPage(orderKey = document.orderKeyAt(0)))
        val page = document.pages[random.nextInt(document.pages.size)]
        val strokes = page.strokes.toList()
        return when (random.nextInt(8)) {
            0, 1 -> AddElements.of(page, listOf(stroke(random.nextFloat() * 100f, random.nextFloat() * 100f), text("t${random.nextInt(100)}")))
            2 -> strokes.randomOrNull(random)?.let { RemoveElements.of(page, listOf(it)) }
            3 -> strokes.randomOrNull(random)?.let {
                val target = document.pages[random.nextInt(document.pages.size)]
                PlaceElements(page.id, listOf(StrokePlacement(it.id, AffineTransform(tx = random.nextInt(10).toFloat()))), target.id)
            }
            4 -> page.texts.firstOrNull()?.let { UpdateTexts(page.id, listOf(it.toData().copy(text = "u${random.nextInt(100)}"))) }
            5 -> AddPage.of(DrawPage(orderKey = document.orderKeyAt(random.nextInt(document.pages.size + 1))).apply { add(stroke(5f, 5f)) })
            6 -> if (document.pages.size > 1) RemovePage(page.id) else null
            else -> MovePages.toOrder(document, document.pages.map { it.id }.shuffled(random))
        }
    }

    private suspend fun assertSame(documentId: String, devices: List<Device>) {
        val expected = devices[0].snapshot(documentId)
        val vector = devices[0].versionVector(documentId)
        for (device in devices.drop(1)) {
            assertEquals(vector, device.versionVector(documentId), "the operations applied on ${device.name}")
            assertEquals(expected, device.snapshot(documentId), "the document on ${device.name}")
        }
    }

    // --- Synchronization ---

    /**
     * Runs [block] with a scope for the work of the devices on the scheduler of the test, whose
     * tasks [advanceUntilIdle] runs (it leaves the ones of `backgroundScope` alone).
     */
    private fun syncTest(block: suspend TestScope.(CoroutineScope) -> Unit) = runTest {
        val work = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            block(work)
        } finally {
            work.cancel()
        }
    }

    @Test
    fun aDeviceJoinsADocumentAndBothKeepItTheSame() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        bob.store.createFolder("Shared", null)

        val document = ada.store.createDocument("Physics")
        ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(10f, 10f), text("waves"))) }
        val invitation = ada.engine.share(document.id, "Physics")

        bob.join(invitation, "ada")
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob))
        assertEquals(listOf("ada"), bob.engine.peers.value.map { it.name })
        assertEquals(listOf("bob"), ada.engine.peers.value.map { it.name })
        // In the folder of the documents received
        val received = bob.store.documents.first().single()
        assertEquals("Physics", received.name)
        // Shared on both sides (the key of Bob kept with the document received)
        assertTrue(received.isShared)
        assertTrue(ada.store.documents.first().single().isShared)
        assertEquals(invitation.key, bob.shared.keyOf(document.id))
        assertEquals(bob.store.folders.first().single().id, received.folderId)

        // Live: the changes of each one reach the other
        ada.change(document.id) { AddPage.of(DrawPage(orderKey = it.document.orderKeyAt(1))) }
        bob.change(document.id) { session -> RemoveElements.of(session.document.pages[0], session.document.pages[0].strokes.toList()) }
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob))
        assertEquals(2, ada.snapshot(document.id).pages.size)

        // Apart, then together again: what each one missed arrives
        bob.disconnect()
        advanceUntilIdle()
        assertTrue(ada.engine.peers.value.isEmpty())
        ada.change(document.id) { session -> UpdateTexts(session.document.pages[0].id, listOf(session.document.pages[0].texts[0].toData().copy(text = "particles"))) }
        bob.change(document.id) { AddElements.of(it.document.pages[1], listOf(stroke(1f, 1f))) }
        bob.join(invitation, "ada")
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob))
        assertEquals("particles", (bob.snapshot(document.id).pages[0].data.elements.last() as TextData).text)

        // The document received survives a restart, with its operations
        bob.disconnect()
        advanceUntilIdle()
        bob.store.flush()
        val restarted = KeyValueDocumentStore(bob.storage, scope = work)
        assertEquals(ada.snapshot(document.id), assertNotNull(restarted.loadDocument(document.id)).snapshot())
        assertEquals(ada.versionVector(document.id), restarted.versionVector(document.id))
    }

    @Test
    fun devicesChangingADocumentThroughEachOtherConverge() {
        repeat(5) { seed -> relay(Random(seed)) }
    }

    /**
     * Ada shares a document; Bob and Cy join it through her or through each other (relaying the
     * operations of the others, in cycles too), go away and come back, at random.
     */
    private fun relay(random: Random) = syncTest { work ->
        val network = InMemoryNetwork()
        val devices = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val (ada, bob, cy) = devices
        val document = ada.store.createDocument("Notes")
        val invitation = ada.engine.share(document.id, "Notes")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()

        val online = mutableSetOf(bob, cy)
        repeat(300) {
            val device = devices.random(random)
            when {
                // Bob and Cy go away and come back; they work on the document meanwhile
                device !== ada && random.nextInt(20) == 0 -> {
                    if (device in online) device.disconnect() else device.join(invitation, if (random.nextBoolean()) "ada" else (devices - ada - device).single().name)
                    if (!online.remove(device)) online.add(device)
                }
                else -> device.change(document.id) { randomChange(it, random) }
            }
            if (random.nextInt(4) == 0) advanceUntilIdle()
        }
        // Everyone with Ada (some also with each other)
        for (device in devices) if (device !== ada) device.join(invitation, "ada")
        advanceUntilIdle()
        assertSame(document.id, devices)
    }

    @Test
    fun theDevicesSeeWhereTheOthersAre() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.engine.share(document.id, "Notes")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()

        bob.engine.updatePresence(Presence(document.id, pageId = "page", x = 1f, y = 2f, ink = listOf(1f, 2f, 3f, 4f)))
        advanceUntilIdle()
        // Relayed by Ada to Cy
        val seen = cy.engine.presences.value.single()
        assertEquals("bob", seen.name)
        assertEquals(listOf(1f, 2f, 3f, 4f), seen.ink)
        assertEquals("bob", ada.engine.presences.value.single().name)
        assertTrue(bob.engine.presences.value.isEmpty(), "its own presence is not among the others")

        // Gone with its connection
        bob.disconnect()
        advanceUntilIdle()
        assertTrue(ada.engine.presences.value.isEmpty())
    }

    @Test
    fun theFilesOfTheResourcesTravelWithTheDocument() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        val document = ada.store.createDocument("Slides")
        val bytes = Random(1).nextBytes(600_000)
        ada.files.files["/ada/photo.jpg"] = bytes
        ada.change(document.id) { session ->
            val image = DrawImage().apply { resourceId = "photo"; x = 10f; y = 10f; width = 50f; height = 40f }
            CompositeChange(
                listOf(AddResource.of("photo", DrawResource.ResourceType.IMAGE, "/ada/photo.jpg"), AddElements.of(session.document.pages[0], listOf(image)))
            )
        }

        bob.join(ada.engine.share(document.id, "Slides"), "ada")
        advanceUntilIdle()
        val open = assertNotNull(bob.documents.open(document.id))
        val path = open.edit { it.document.findResource("photo")?.path }
        bob.documents.close(open)
        assertTrue(path!!.endsWith(".jpg"), path)
        assertContentEquals(bytes, bob.files.files[path])
    }
}
