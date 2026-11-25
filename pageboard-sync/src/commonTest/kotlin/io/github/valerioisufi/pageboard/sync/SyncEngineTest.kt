package io.github.valerioisufi.pageboard.sync

import io.github.valerioisufi.pageboard.model.change.BoxPlacement
import io.github.valerioisufi.pageboard.model.ElementOrder
import io.github.valerioisufi.pageboard.model.Versions
import io.github.valerioisufi.pageboard.model.AttachmentChange
import io.github.valerioisufi.pageboard.model.AttachmentReceiver
import io.github.valerioisufi.pageboard.model.AttachmentSource
import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.DocumentAttachments
import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.model.ImageElement
import io.github.valerioisufi.pageboard.model.Page
import io.github.valerioisufi.pageboard.model.DocumentResource
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.SelectionActivity
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.AddPage
import io.github.valerioisufi.pageboard.model.change.AddResource
import io.github.valerioisufi.pageboard.model.change.ChangeObserver
import io.github.valerioisufi.pageboard.model.change.CompositeChange
import io.github.valerioisufi.pageboard.model.change.DocumentChange
import io.github.valerioisufi.pageboard.model.change.DocumentSession
import io.github.valerioisufi.pageboard.model.change.DocumentSnapshot
import io.github.valerioisufi.pageboard.model.change.MovePages
import io.github.valerioisufi.pageboard.model.change.Operation
import io.github.valerioisufi.pageboard.model.change.OperationId
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.ChangeOrigin
import io.github.valerioisufi.pageboard.model.change.RemoveElements
import io.github.valerioisufi.pageboard.model.change.RemovePage
import io.github.valerioisufi.pageboard.model.change.RenameDocument
import io.github.valerioisufi.pageboard.model.change.ResourceData
import io.github.valerioisufi.pageboard.model.change.StrokePlacement
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.change.UpdateTexts
import io.github.valerioisufi.pageboard.model.change.snapshot
import io.github.valerioisufi.pageboard.model.change.toData
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.platform.Sha256
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.space.AddDocument
import io.github.valerioisufi.pageboard.store.space.AddFolder
import io.github.valerioisufi.pageboard.store.space.CatalogOperation
import io.github.valerioisufi.pageboard.store.space.CompositeCatalogChange
import io.github.valerioisufi.pageboard.store.space.ConfirmGiver
import io.github.valerioisufi.pageboard.store.space.MoveItem
import io.github.valerioisufi.pageboard.store.space.RemoveFromSpace
import io.github.valerioisufi.pageboard.store.space.RenameFolder
import io.github.valerioisufi.pageboard.store.space.RestoreItem
import io.github.valerioisufi.pageboard.store.space.TrashItem
import io.github.valerioisufi.pageboard.sync.protocol.DeviceAddress
import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import io.github.valerioisufi.pageboard.sync.protocol.DocumentKey
import io.github.valerioisufi.pageboard.sync.protocol.InMemoryChannel
import io.github.valerioisufi.pageboard.sync.protocol.InMemoryNetwork
import io.github.valerioisufi.pageboard.sync.protocol.Invitation
import io.github.valerioisufi.pageboard.sync.protocol.MAX_FRAME_SIZE
import io.github.valerioisufi.pageboard.sync.protocol.Member
import io.github.valerioisufi.pageboard.sync.protocol.Membership
import io.github.valerioisufi.pageboard.sync.protocol.OpenFrame
import io.github.valerioisufi.pageboard.sync.protocol.OperationProof
import io.github.valerioisufi.pageboard.sync.protocol.OperationSeal
import io.github.valerioisufi.pageboard.sync.protocol.PROTOCOL_VERSION
import io.github.valerioisufi.pageboard.sync.protocol.MembershipKind
import io.github.valerioisufi.pageboard.sync.protocol.SpaceRef
import io.github.valerioisufi.pageboard.sync.protocol.Placement
import io.github.valerioisufi.pageboard.sync.protocol.Grant
import io.github.valerioisufi.pageboard.sync.protocol.Capability
import io.github.valerioisufi.pageboard.sync.protocol.Role
import io.github.valerioisufi.pageboard.sync.protocol.SealedOperation
import io.github.valerioisufi.pageboard.sync.protocol.SecureChannel
import io.github.valerioisufi.pageboard.sync.protocol.SyncChannel
import io.github.valerioisufi.pageboard.sync.protocol.SyncException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

class SyncEngineTest {

    // --- Invitations and encryption ---

    @Test
    fun anInvitationIsALink() {
        val addresses = mapOf("lan" to "192.168.1.20:4567", "iroh" to "f00d https://relay.example.com/ [2001:db8::1]:4433")
        val invitation = Invitation("doc-1", "Appunti di Fisica & co. 100%", DocumentKey.generate(), "0123456789abcdef", addresses)
        assertEquals(invitation, Invitation.parse(invitation.toLink()))
        assertEquals(invitation.copy(addresses = emptyMap()), Invitation.parse(invitation.copy(addresses = emptyMap()).toLink()))
        assertNull(Invitation.parse("https://example.com/?d=x&k=y"))
        assertNull(Invitation.parse("pageboard://join?d=doc&k=not-a-key"))
    }

    @Test
    fun aLinkOfBeforeIsReadWithItsAddressOnTheLocalNetwork() {
        // As kept by a device joining (SharingState.joining) before the addresses by transport
        val key = DocumentKey.generate()
        val link = "pageboard://join?d=doc-1&k=${key.encode()}&o=0123456789abcdef&t=Lab&a=192.168.1.20%3A4567"
        assertEquals(Invitation("doc-1", "Lab", key, "0123456789abcdef", mapOf("lan" to "192.168.1.20:4567")), Invitation.parse(link))
        // What a link of a later version adds is skipped
        assertEquals(Invitation("doc-1", "Lab", key, "0123456789abcdef"), Invitation.parse("pageboard://join?d=doc-1&k=${key.encode()}&o=0123456789abcdef&t=Lab&z=1"))
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
        val document = Document()
        document.pages[0].add(stroke(1f, 2f))
        document.pages[0].add(text("hello"))
        val session = DocumentSession(document, "ada")
        session.submit(AddPage.of(Page(orderKey = document.orderKeyAt(1))))
        val operation = Operation(
            OperationId("ada", 1),
            session.clock.next(),
            AddElements.of(document.pages[0], listOf(stroke(3f, 4f)))
        )
        val messages = listOf(
            Hello("ada", "Ada", mapOf("ada" to 3L), hasDocument = true, device = "key", proof = byteArrayOf(1, 2), membershipVersion = 3, epoch = 2),
            Snapshot(document.snapshot(), mapOf("bob" to 4L)),
            Operations(listOf(OperationSeal(DocumentKey.generate()).seal("ada", 1, OperationProof.sign(DeviceIdentity.generate(), "doc", "ada", 1, SyncCodec.encodeOperation(operation))))),
            ResourceRequest(listOf(ResourceAsk("photo", 1024))),
            AttachmentRequest(listOf(AttachmentAsk("audio", "a1", 5)))
        )
        for (message in messages) {
            val bytes = SyncCodec.encode(message)
            assertContentEquals(bytes, SyncCodec.encode(SyncCodec.decodeOrThrow(bytes)))
        }
    }

    // --- Devices ---

    /** Files kept in memory. */
    private class MemoryFiles : ResourceFiles {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(path: String): ByteArray? = files[path]
        override fun write(bytes: ByteArray, extension: String): String = "mem://${files.size}.$extension".also { files[it] = bytes }
    }

    /**
     * A device: its store, its shared documents and its engine, reachable on [network] at [name]
     * (its connections say they are there every [keepAliveMs], `0`: never, so that
     * [advanceUntilIdle] ends).
     */
    private class Device(
        val name: String,
        scope: CoroutineScope,
        test: TestScope,
        val network: InMemoryNetwork,
        keepAliveMs: Long = 0,
        addressBook: AddressBook = InMemoryAddressBook()
    ) {
        val storage = InMemoryKeyValueStorage()
        val store = KeyValueDocumentStore(storage, scope = scope)
        val documents = OpenDocuments(store, StandardTestDispatcher(test.testScheduler), scope)
        val shared = StoreSharedDocuments(store, documents)
        val files = MemoryFiles()
        val attachments = MemoryAttachments()
        val identity = DeviceIdentity.generate()
        val engine = SyncEngine(
            documents, shared, scope, { name }, files, keepAliveMs, attachments, identity,
            receivedFolder = { store.folders.first().firstOrNull()?.id },
            addressBook = addressBook
        )
        val transport = network.transport(name)
        private val connections = mutableListOf<Job>()

        init {
            scope.launch { transport.incoming.collect { engine.accept(it) } }
        }

        /**
         * Shares the document [documentId] (this device its owner, reachable at its name): an
         * invitation for the devices joining as [role], approved first when [approval].
         */
        suspend fun share(documentId: String, title: String, role: Role = Role.EDITOR, approval: Boolean = false): Invitation =
            assertNotNull(engine.invite(documentId, title, role, approval, addresses = mapOf(MEMORY to name)))

        /**
         * Works on the document of [invitation] through the device at [address]: the first time
         * with the invitation, through the owner (at the address of the invitation), which admits
         * this device; then as a member, with the key of the document.
         */
        suspend fun join(invitation: Invitation, address: String) {
            val key = engine.connectionKeys().firstOrNull { it.first == invitation.documentId && it.second != invitation.key }?.second
            if (key == null) {
                engine.join(invitation)
                connections += engine.connect(transport.connect(invitation.addresses[MEMORY] ?: address), invitation.documentId, invitation.key)
            } else {
                connections += engine.connect(transport.connect(address), invitation.documentId, key)
            }
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

        suspend fun title(documentId: String): String {
            val open = assertNotNull(documents.open(documentId))
            return open.edit { it.document.title }.also { documents.close(open) }
        }

        suspend fun compactedOperations(documentId: String): Map<String, Long> {
            val open = assertNotNull(documents.open(documentId))
            return open.compactedOperations().also { documents.close(open) }
        }

        /** Where the file of the resource [resourceId] of [documentId] is here (empty: not received). */
        suspend fun resourcePath(documentId: String, resourceId: String): String? {
            val open = assertNotNull(documents.open(documentId))
            return open.edit { it.document.findResource(resourceId)?.path }.also { documents.close(open) }
        }
    }

    /**
     * Items beside the documents, in memory: the ones under `blob/` are kept as received (when
     * missing), the ones under `file/` too, when an item here lists them; the others are sets of
     * lines, merged (their version: their content).
     */
    private class MemoryAttachments : DocumentAttachments {
        val items = mutableMapOf<Pair<String, String>, ByteArray>()
        override val changes = MutableSharedFlow<AttachmentChange>(extraBufferCapacity = 64)

        fun text(documentId: String, key: String): String? = items[documentId to key]?.decodeToString()

        /** Changes the item [key] here (e.g. a lecture written). */
        fun put(documentId: String, key: String, lines: Set<String>) {
            val content = lines.sorted().joinToString("\n")
            items[documentId to key] = content.encodeToByteArray()
            changes.tryEmit(AttachmentChange(documentId, key, content))
        }

        private fun isBlob(key: String) = key.startsWith("blob/") || key.startsWith("file/")

        private fun versionOf(bytes: ByteArray, key: String) = if (isBlob(key)) "size ${bytes.size}" else bytes.decodeToString()

        override suspend fun versions(documentId: String) =
            items.filterKeys { it.first == documentId }.map { (id, bytes) -> id.second to versionOf(bytes, id.second) }.toMap()

        override suspend fun version(documentId: String, key: String) = items[documentId to key]?.let { versionOf(it, key) }

        override suspend fun wants(documentId: String, key: String, version: String) = when {
            !isBlob(key) -> true
            (documentId to key) in items -> false
            key.startsWith("file/") -> items.any { (id, bytes) -> id.first == documentId && !isBlob(id.second) && key in bytes.decodeToString().lines() }
            else -> true
        }

        override suspend fun open(documentId: String, key: String): AttachmentSource? {
            val bytes = items[documentId to key] ?: return null
            return object : AttachmentSource {
                override val version = versionOf(bytes, key)
                override val size = bytes.size.toLong()
                override suspend fun read(offset: Long, length: Int) = bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + length))
            }
        }

        /** The parts of the blobs received by transfers interrupted, and where they were asked from. */
        val partial = mutableMapOf<Pair<String, String>, ByteArray>()
        val askedFrom = mutableListOf<Long>()

        override suspend fun received(documentId: String, key: String, version: String): Long =
            if (isBlob(key)) partial[documentId to key]?.size?.toLong() ?: 0L else 0L

        /** The items whose storage fails here (a full disk...). */
        val broken = mutableSetOf<String>()

        override suspend fun receive(documentId: String, key: String, version: String, size: Long, from: Long) = object : AttachmentReceiver {
            init {
                if (key in broken) throw IllegalStateException("The storage of $key failed")
            }

            val received = mutableListOf<ByteArray>().apply {
                askedFrom.add(from)
                if (from > 0) partial.remove(documentId to key)?.let { add(it) }
            }

            override suspend fun write(bytes: ByteArray) {
                received.add(bytes)
            }

            override suspend fun complete() {
                val bytes = received.fold(ByteArray(0)) { all, part -> all + part }
                if (isBlob(key)) {
                    items[documentId to key] = bytes
                    changes.emit(AttachmentChange(documentId, key, versionOf(bytes, key)))
                    return
                }
                val mine = text(documentId, key)?.lines().orEmpty().filter { it.isNotEmpty() }.toSet()
                val merged = mine + bytes.decodeToString().lines().filter { it.isNotEmpty() }
                if (merged != mine) put(documentId, key, merged)
            }

            override suspend fun cancel() {
                // A blob goes on later
                if (isBlob(key)) partial[documentId to key] = received.fold(ByteArray(0)) { all, part -> all + part }
            }
        }
    }

    private fun stroke(x: Float, y: Float): StrokeElement {
        val mesh = StrokeMesh(
            positions = floatArrayOf(x, y, x + 5f, y, x + 5f, y + 5f, x, y + 5f),
            indices = shortArrayOf(0, 1, 2, 0, 2, 3),
            color = 0xFF000000.toInt(),
            tool = StrokeTool.MARKER,
            size = 1f,
            points = listOf(StrokePoint(x, y, 0.5f, 0L), StrokePoint(x + 5f, y + 5f, 0.5f, 16L))
        )
        return StrokeElement(mesh = mesh, brushConfig = BrushConfig(tool = StrokeTool.MARKER, size = 1f))
    }

    private fun text(content: String) = TextElement().apply {
        text = content
        x = 10f; y = 20f; width = 40f; height = 10f
        fontSize = 16f
    }

    /** A change of a random kind on a random page of [session]'s document. */
    private fun randomChange(session: DocumentSession, random: Random): DocumentChange? {
        val document = session.document
        if (document.pages.isEmpty()) return AddPage.of(Page(orderKey = document.orderKeyAt(0)))
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
            5 -> AddPage.of(Page(orderKey = document.orderKeyAt(random.nextInt(document.pages.size + 1))).apply { add(stroke(5f, 5f)) })
            6 -> if (document.pages.size > 1) RemovePage(page.id) else null
            else -> MovePages.toOrder(document, document.pages.map { it.id }.shuffled(random))
        }
    }

    /**
     * [devices] applied the same operations, and show the same document: what they removed may
     * differ, a device dropping it for good when it compacts its log, at its own time (see
     * `SyncEngine.compact`).
     */
    private suspend fun assertSameShown(documentId: String, devices: List<Device>) {
        fun DocumentSnapshot.shown() = copy(
            removedPages = emptyList(),
            pages = pages.map { page -> page.copy(removedElements = emptyList(), versions = page.versions.filterKeys { id -> page.data.elements.any { it.id == id } }) }
        )
        val expected = devices[0].snapshot(documentId).shown()
        val vector = devices[0].versionVector(documentId)
        for (device in devices.drop(1)) {
            assertEquals(vector, device.versionVector(documentId), "the operations applied on ${device.name}")
            assertEquals(expected, device.snapshot(documentId).shown(), "the document on ${device.name}")
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
    // Minutes: the encryption of many frames is slow on a simulator busy with other builds
    private fun syncTest(block: suspend TestScope.(CoroutineScope) -> Unit) = runTest(timeout = 5.minutes) {
        // What fails in an engine without being caught fails the test (in an application, it
        // would be an uncaught exception)
        val failures = mutableListOf<Throwable>()
        val work = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, e -> failures += e })
        try {
            block(work)
        } finally {
            work.cancel()
        }
        assertEquals(emptyList(), failures.map { it.toString() }, "failures not caught by the engines")
    }

    @Test
    fun anInvitationOpenedAgainIsJoinedAlready() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        advanceUntilIdle()
        val key = bob.shared.stateOf(document.id)?.key

        // Bob opens the invitation again: in at once, nothing waits for the owner
        bob.engine.join(invitation)
        assertEquals(JoinStatus.ADMITTED, bob.engine.joining.value[document.id])
        assertEquals(key, bob.shared.stateOf(document.id)?.key)
    }

    @Test
    fun aDeviceJoinsADocumentAndBothKeepItTheSame() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        bob.store.createFolder("Shared", null)

        val document = ada.store.createDocument("Physics")
        ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(10f, 10f), text("waves"))) }
        val invitation = ada.share(document.id, "Physics")

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
        assertEquals(assertNotNull(ada.shared.stateOf(document.id)?.key), bob.shared.stateOf(document.id)?.key)
        assertEquals(bob.store.folders.first().single().id, received.folderId)

        // Live: the changes of each one reach the other
        ada.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
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
    fun aTextChangedApartOnTwoDevicesKeepsBothContents() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        val document = ada.store.createDocument("Physics")
        ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(text("waves"))) }
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        advanceUntilIdle()

        // Apart, both change the text; together again: the most recent content and a copy of the other one
        bob.disconnect()
        advanceUntilIdle()
        fun write(content: String): (DocumentSession) -> DocumentChange = { session ->
            val page = session.document.pages[0]
            UpdateTexts(page.id, listOf(page.texts[0].toData().copy(text = content)))
        }
        ada.change(document.id, write("Ada's"))
        bob.change(document.id, write("Bob's"))
        bob.join(invitation, "ada")
        advanceUntilIdle()
        // The copies made on each side reached the other one (from the connection being received)
        assertSame(document.id, listOf(ada, bob))
        val (text, copy) = ada.snapshot(document.id).pages[0].data.elements.filterIsInstance<TextData>()
        assertEquals(setOf("Ada's", "Bob's"), setOf(text.text, copy.text))
        assertTrue(copy.y > text.y, "the copy under the text")
    }

    @Test
    fun aDeviceOfAnotherProtocolIsRefusedAndToldOf() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        network.transport("older").connect("ada").send(OpenFrame(PROTOCOL_VERSION + 1, invitation.key.tag).encode())
        advanceUntilIdle()
        assertTrue(ada.engine.otherVersionMet.value)
        assertTrue(ada.engine.peers.value.isEmpty())
    }

    @Test
    fun aMessageThisVersionCannotReadClosesTheConnection() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        val channel = network.transport("newer").connect("ada")
        channel.send(OpenFrame(PROTOCOL_VERSION, invitation.key.tag).encode())
        val newer = SecureChannel.of(channel, invitation.key)
        newer.send(SyncCodec.encode(Challenge(ByteArray(16))))
        advanceUntilIdle()
        // Who it is, proven, admitted by the owner
        val challenge = SyncCodec.decodeOrThrow(assertNotNull(newer.receive())) as Challenge
        val identity = DeviceIdentity.generate()
        val proof = identity.sign(helloProof(challenge.nonce, invitation.key.tag, "newer-replica", identity.publicKey))
        newer.send(SyncCodec.encode(Hello("newer-replica", "Newer", emptyMap(), hasDocument = true, device = identity.publicKey, proof = proof)))
        advanceUntilIdle()
        assertEquals(listOf("Newer"), ada.engine.peers.value.map { it.name })
        assertFalse(ada.engine.otherVersionMet.value)

        // E.g. a change this version does not know: closed, not left waiting for ever for the
        // operations after it, and told of
        newer.send(byteArrayOf(1, 2, 3))
        advanceUntilIdle()
        assertTrue(ada.engine.peers.value.isEmpty(), "its connection closed")
        assertTrue(ada.engine.otherVersionMet.value)
    }

    @Test
    fun theItemsBesideADocumentTravelAndAreMerged() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        // Made apart: merged both ways when the devices meet
        ada.attachments.put(document.id, "lecture/1", setOf("outline"))
        bob.attachments.put(document.id, "lecture/1", setOf("review"))
        bob.attachments.put(document.id, "lecture/2", setOf("suggestion"))
        val audio = Random(3).nextBytes(700_000)
        ada.attachments.items[document.id to "blob/audio"] = audio
        bob.join(invitation, "ada")
        advanceUntilIdle()
        for (device in listOf(ada, bob)) {
            assertEquals("outline\nreview", device.attachments.text(document.id, "lecture/1"), device.name)
            assertEquals("suggestion", device.attachments.text(document.id, "lecture/2"), device.name)
        }
        // A large one in parts
        assertContentEquals(audio, bob.attachments.items[document.id to "blob/audio"])

        // Through Bob to Cy, then as they change
        cy.join(invitation, "bob")
        advanceUntilIdle()
        assertEquals("outline\nreview", cy.attachments.text(document.id, "lecture/1"))
        assertContentEquals(audio, cy.attachments.items[document.id to "blob/audio"])
        ada.attachments.put(document.id, "lecture/1", setOf("outline", "review", "note inserted"))
        advanceUntilIdle()
        assertEquals("note inserted\noutline\nreview", cy.attachments.text(document.id, "lecture/1"))
    }

    @Test
    fun anItemWantedOnceAnotherOneIsReceivedIsAskedFor() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        // The file is offered with the list naming it, which Bob has not yet
        ada.attachments.items[document.id to "file/page.jpg"] = Random(5).nextBytes(1000)
        ada.attachments.put(document.id, "lecture/1", setOf("file/page.jpg"))
        bob.join(invitation, "ada")
        advanceUntilIdle()
        assertEquals("file/page.jpg", bob.attachments.text(document.id, "lecture/1"))
        assertContentEquals(ada.attachments.items[document.id to "file/page.jpg"], bob.attachments.items[document.id to "file/page.jpg"])
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
        val invitation = ada.share(document.id, "Notes")
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
        // Everyone with Ada (some also with each other): the same document shown
        for (device in devices) if (device !== ada) device.join(invitation, "ada")
        advanceUntilIdle()
        assertSameShown(document.id, devices)
        // Bob and Cy with each other too: every one knows what the others have, and drops the same
        cy.join(invitation, "bob")
        advanceUntilIdle()
        assertSame(document.id, devices)
    }

    @Test
    fun theDevicesSeeWhereTheOthersAre() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()

        fun inputs(count: Int) = (0 until count).flatMap { listOf(it.toFloat(), 2f * it, 0.5f, 8f * it) }
        bob.engine.updatePresence(Presence(document.id, pageId = "page", x = 1f, y = 2f, stroke = 7, ink = inputs(3)))
        advanceUntilIdle()
        // Relayed by Ada to Cy
        val seen = cy.engine.presences.value.single()
        assertEquals("bob", seen.name)
        assertEquals(inputs(3), seen.ink)
        assertEquals("bob", ada.engine.presences.value.single().name)
        assertTrue(bob.engine.presences.value.isEmpty(), "its own presence is not among the others")

        // The stroke goes on: only the new inputs travel, the others have it whole
        bob.engine.updatePresence(Presence(document.id, pageId = "page", x = 3f, y = 4f, stroke = 7, ink = inputs(5)))
        advanceUntilIdle()
        assertEquals(inputs(5), cy.engine.presences.value.single().ink)
        bob.engine.updatePresence(Presence(document.id, pageId = "page", x = 3f, y = 4f))
        advanceUntilIdle()
        assertTrue(cy.engine.presences.value.single().ink.isEmpty(), "the stroke ended")
        // Another one given up (e.g. for a gesture)
        bob.engine.updatePresence(Presence(document.id, pageId = "page", x = 3f, y = 4f, stroke = 8, ink = inputs(2)))
        advanceUntilIdle()
        bob.engine.updatePresence(Presence(document.id, pageId = "page", x = 3f, y = 4f, inkCancelled = true))
        advanceUntilIdle()
        assertTrue(cy.engine.presences.value.single().let { it.inkCancelled && it.ink.isEmpty() }, "the stroke given up")

        // A text being written and elements selected (sent again only when they change)
        val draft = TextData(id = "text", text = "Ciao", x = 10f, y = 20f, width = 40f, color = 0, fontSize = 16f)
        val selection = SelectionActivity("page", listOf("a", "b"))
        bob.engine.updatePresence(Presence(document.id, text = draft, textPageId = "page", selection = selection))
        advanceUntilIdle()
        bob.engine.updatePresence(Presence(document.id, pageId = "page", text = draft, textPageId = "page", selection = selection.copy(transform = AffineTransform(tx = 5f))))
        advanceUntilIdle()
        val working = cy.engine.presences.value.single()
        assertEquals(draft, working.text)
        assertEquals(listOf("a", "b"), working.selection?.ids)
        assertEquals(AffineTransform(tx = 5f), working.selection?.transform)

        // Gone with its connection
        bob.disconnect()
        advanceUntilIdle()
        assertTrue(ada.engine.presences.value.isEmpty())
    }

    @Test
    fun twoConnectionsBetweenTheSameDevicesBecomeOne() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        // Cy admitted first (by the owner)
        cy.join(invitation, "ada")
        advanceUntilIdle()
        cy.disconnect()
        advanceUntilIdle()
        // Bob connects again, Ada connects to Bob too (both connected to the other at once)
        bob.join(invitation, "ada")
        ada.join(invitation, "bob")
        cy.join(invitation, "bob")
        advanceUntilIdle()
        assertEquals(listOf("bob"), ada.engine.peers.value.map { it.name })
        assertEquals(listOf("ada", "cy"), bob.engine.peers.value.map { it.name }.sorted())

        // Still the same document through the connection kept
        ada.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(0))) }
        cy.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(0))) }
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob, cy))

        // Never back to the device it comes from
        bob.engine.updatePresence(Presence(document.id, pageId = "page", x = 1f, y = 2f))
        advanceUntilIdle()
        assertTrue(bob.engine.presences.value.isEmpty(), "its own presence is not among the others")
        assertEquals("bob", ada.engine.presences.value.single().name)
        assertEquals("bob", cy.engine.presences.value.single().name)
    }

    /**
     * A connection that can be cut silently, like by a device gone out of the network
     * ([framesLeft]: after so many frames; [cutAbove]: at the first frame larger than that).
     */
    private class Cable(var framesLeft: Int = Int.MAX_VALUE, val cutAbove: Int = Int.MAX_VALUE) {
        val cut = MutableStateFlow(false)
    }

    /** One end of a connection through [cable]: once it is cut nothing is sent nor received. */
    private class CableEnd(private val inner: SyncChannel, private val cable: Cable) : SyncChannel {
        private val closed = CompletableDeferred<Unit>()

        override suspend fun send(frame: ByteArray) {
            if (cable.framesLeft-- <= 0 || frame.size > cable.cutAbove) cable.cut.value = true
            if (!cable.cut.value) inner.send(frame)
        }

        override suspend fun receive(maxSize: Int): ByteArray? {
            if (!cable.cut.value) {
                // Closed by this side meanwhile: nothing more, cut or not
                val frame = coroutineScope {
                    val next = async { inner.receive(maxSize) }
                    select {
                        next.onAwait { it }
                        closed.onAwait {
                            next.cancel()
                            null
                        }
                    }
                }
                if (!cable.cut.value) return frame
            }
            closed.await()
            return null
        }

        override fun close() {
            closed.complete(Unit)
            // Cut: the other end never knows
            if (!cable.cut.value) inner.close()
        }
    }

    /** Connects [joining] to [sharing] for [invitation] (the key of the document once admitted) through [cable]. */
    private suspend fun connect(sharing: Device, joining: Device, invitation: Invitation, cable: Cable) {
        val (one, other) = InMemoryChannel.pair()
        sharing.engine.accept(CableEnd(one, cable))
        val key = joining.engine.connectionKeys().firstOrNull { it.first == invitation.documentId && it.second != invitation.key }?.second
        if (key == null) joining.engine.join(invitation)
        joining.engine.connect(CableEnd(other, cable), invitation.documentId, key ?: invitation.key)
    }

    @Test
    fun aConnectionCutSilentlyIsLostThenMadeAgain() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network, keepAliveMs = 1_000)
        val bob = Device("bob", work, this, network, keepAliveMs = 1_000)
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        val cable = Cable()
        connect(ada, bob, invitation, cable)
        advanceTimeBy(500)
        assertSame(document.id, listOf(ada, bob))

        // Out of the network: still connected at first, both go on changing the document
        cable.cut.value = true
        ada.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        bob.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(1f, 1f))) }
        advanceTimeBy(500)
        assertEquals(listOf("bob"), ada.engine.peers.value.map { it.name })
        // Silent for too long: lost on both sides
        advanceTimeBy(5_000)
        assertTrue(ada.engine.peers.value.isEmpty())
        assertTrue(bob.engine.peers.value.isEmpty())

        // Connected again: what each one missed arrives
        connect(ada, bob, invitation, Cable())
        advanceTimeBy(500)
        assertSame(document.id, listOf(ada, bob))
        assertEquals(2, ada.snapshot(document.id).pages.size)
        assertEquals(listOf("ada"), bob.engine.peers.value.map { it.name })
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
            val image = ImageElement().apply { resourceId = "photo"; x = 10f; y = 10f; width = 50f; height = 40f }
            CompositeChange(
                listOf(AddResource.of("photo", DocumentResource.ResourceType.IMAGE, "/ada/photo.jpg"), AddElements.of(session.document.pages[0], listOf(image)))
            )
        }

        bob.join(ada.share(document.id, "Slides"), "ada")
        advanceUntilIdle()
        val open = assertNotNull(bob.documents.open(document.id))
        val path = open.edit { it.document.findResource("photo")?.path }
        bob.documents.close(open)
        assertTrue(path!!.endsWith(".jpg"), path)
        assertContentEquals(bytes, bob.files.files[path])
    }

    // --- Devices not known yet ---

    /** [inner], recording the largest frame each receive takes. */
    private class Recording(private val inner: SyncChannel) : SyncChannel by inner {
        val sizes = mutableListOf<Int>()

        override suspend fun receive(maxSize: Int): ByteArray? {
            sizes += maxSize
            return inner.receive(maxSize)
        }
    }

    /** An [OpenFrame] with more in it. */
    @Serializable
    private class PaddedOpenFrame(val protocol: Int, val tag: String, val padding: ByteArray)

    /** Whether the other end closed [end]: what it sent is read, and nothing more comes. */
    private suspend fun TestScope.isClosed(end: SyncChannel): Boolean {
        while (true) {
            val next = backgroundScope.async { end.receive() }
            runCurrent()
            if (!next.isCompleted) {
                next.cancel()
                return false
            }
            next.await() ?: return true
        }
    }

    @Test
    fun theFramesOfADeviceGrowWithWhatItProved() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val bob = Device("bob", work, this, network)
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        val (one, other) = InMemoryChannel.pair()
        val accepted = Recording(one)
        ada.engine.accept(accepted)
        bob.engine.join(invitation)
        bob.engine.connect(other, document.id, invitation.key)
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob))
        // Its open frame, its handshake (256 KiB, and what the encryption adds), anything once admitted
        assertEquals(listOf(OpenFrame.MAX_SIZE, 256 * 1024 + 28, MAX_FRAME_SIZE), accepted.sizes.distinct())
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun aDeviceNotKnownSendingLargerFramesIsClosed() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network)
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")

        // A good open frame, too large (what it adds is skipped otherwise)
        val (one, other) = InMemoryChannel.pair()
        ada.engine.accept(one)
        other.send(Cbor.encodeToByteArray(PaddedOpenFrame.serializer(), PaddedOpenFrame(PROTOCOL_VERSION, invitation.key.tag, ByteArray(OpenFrame.MAX_SIZE))))
        assertTrue(isClosed(other))

        // With the key of the invitation, a good challenge larger than a handshake (answered otherwise)
        val (three, four) = InMemoryChannel.pair()
        ada.engine.accept(three)
        four.send(OpenFrame(PROTOCOL_VERSION, invitation.key.tag).encode())
        SecureChannel.of(four, invitation.key).send(SyncCodec.encode(Challenge(ByteArray(300 * 1024))))
        assertTrue(isClosed(four))

        // The same, small: answered
        val (five, six) = InMemoryChannel.pair()
        ada.engine.accept(five)
        six.send(OpenFrame(PROTOCOL_VERSION, invitation.key.tag).encode())
        SecureChannel.of(six, invitation.key).send(SyncCodec.encode(Challenge(ByteArray(16))))
        assertFalse(isClosed(six))
        assertTrue(ada.engine.peers.value.isEmpty())
    }

    @Test
    fun theDevicesNotKnownAreAFewAtATimeAndSaySoonWhatTheyWant() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network, keepAliveMs = 1_000)
        val bob = Device("bob", work, this, network, keepAliveMs = 1_000)
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")

        // Silent ones: 16 at once, the next one closed at once
        val silent = List(17) { InMemoryChannel.pair().also { (one, _) -> ada.engine.accept(one) }.second }
        runCurrent()
        assertTrue(silent.take(16).none { isClosed(it) })
        assertTrue(isClosed(silent.last()))

        // Silent for as long as a connection is lost in: closed, room for the others
        advanceTimeBy(3_100)
        assertTrue(silent.all { isClosed(it) })
        bob.join(invitation, "ada")
        advanceTimeBy(500)
        assertSame(document.id, listOf(ada, bob))
    }

    // --- Members not trusted ---

    /**
     * A member with an application of its own: [member]'s identity, sending [target] what it likes
     * about [documentId] (with [key], the key of the document by default). The channel, once
     * [target] took it as [member] (what [target] sends on it is not read).
     */
    private suspend fun TestScope.impostor(member: Device, target: Device, documentId: String, key: DocumentKey? = null): SecureChannel {
        val documentKey = key ?: member.engine.connectionKeys().first { it.first == documentId }.second
        val (mine, theirs) = InMemoryChannel.pair()
        target.engine.accept(theirs)
        mine.send(OpenFrame(PROTOCOL_VERSION, documentKey.tag).encode())
        val channel = SecureChannel.of(mine, documentKey)
        runCurrent()
        val challenge = assertIs<Challenge>(SyncCodec.decode(assertNotNull(channel.receive())))
        val replica = member.store.replicaId()
        val device = member.identity.publicKey
        val membership = assertNotNull(member.engine.memberships.value[documentId])
        val proof = member.identity.sign(helloProof(challenge.nonce, documentKey.tag, replica, device))
        channel.send(
            SyncCodec.encode(
                Hello(replica, member.name, emptyMap(), hasDocument = true, device = device, proof = proof, membershipVersion = membership.version, epoch = membership.epoch)
            )
        )
        runCurrent()
        return channel
    }

    /** The operation [sequence] of [replica] making [change] in [documentId] with [stamp], sealed with [key] and signed by [signer]. */
    private fun forged(
        documentId: String,
        key: DocumentKey,
        replica: String,
        sequence: Long,
        change: DocumentChange,
        signer: DeviceIdentity,
        stamp: Stamp = Stamp(currentTimeMillis(), sequence.toInt(), replica)
    ): SealedOperation {
        val operation = Operation(OperationId(replica, sequence), stamp, change)
        return OperationSeal(key).seal(replica, sequence, OperationProof.sign(signer, documentId, replica, sequence, SyncCodec.encodeOperation(operation)))
    }

    /** The messages [channel] received so far. */
    private suspend fun TestScope.messages(channel: SyncChannel): List<SyncMessage> = buildList {
        while (true) {
            val next = backgroundScope.async { channel.receive() }
            runCurrent()
            if (!next.isCompleted) {
                next.cancel()
                break
            }
            add(SyncCodec.decode(next.await() ?: break) ?: continue)
        }
    }

    @Test
    fun anOperationIsTakenFromTheDeviceOfItsReplicaOnly() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        val key = bob.engine.connectionKeys().first { it.first == document.id }.second
        val cyReplica = cy.store.replicaId()

        // Bob writing as Cy: refused, and Bob is not worked with anymore
        val forgery = impostor(bob, ada, document.id)
        forgery.send(SyncCodec.encode(Operations(listOf(forged(document.id, key, cyReplica, 1, RenameDocument("Forged"), bob.identity)))))
        assertTrue(isClosed(forgery))
        assertEquals("Physics", ada.title(document.id))

        // Bob made reader, writing as itself: signed, refused
        ada.engine.setRole(document.id, bob.engine.device, Role.READER)
        advanceUntilIdle()
        val reader = impostor(bob, ada, document.id)
        reader.send(SyncCodec.encode(Operations(listOf(forged(document.id, key, bob.store.replicaId(), 1, RenameDocument("Read only"), bob.identity)))))
        advanceUntilIdle()
        assertEquals("Physics", ada.title(document.id))

        // An operation of Cy, signed by Cy, given on by Bob: taken
        reader.send(SyncCodec.encode(Operations(listOf(forged(document.id, key, cyReplica, 1, RenameDocument("By Cy"), cy.identity)))))
        advanceUntilIdle()
        assertEquals("By Cy", ada.title(document.id))
    }

    @Test
    fun theOperationsOfADeviceNotKnownWaitAndSayWhy() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        advanceUntilIdle()
        val key = bob.engine.connectionKeys().first { it.first == document.id }.second
        val cyReplica = cy.store.replicaId()
        assertTrue(ada.engine.waitingOf(document.id).isEmpty())

        // Two operations of Cy, whom Ada has not admitted, given on by Bob: kept, not taken, and told why
        val channel = impostor(bob, ada, document.id)
        channel.send(SyncCodec.encode(Operations((1L..2L).map { forged(document.id, key, cyReplica, it, RenameDocument("By Cy $it"), cy.identity) })))
        advanceUntilIdle()
        assertEquals("Physics", ada.title(document.id))
        val waiting = ada.engine.waitingOf(document.id).single()
        assertEquals(cyReplica, waiting.replica)
        assertEquals(WaitingChanges.Reason.UNKNOWN_DEVICE, waiting.reason)
        assertNull(waiting.device, "no membership names it")
        assertEquals(2, waiting.count)
        assertTrue(ada.engine.waitingOf("other").isEmpty())

        // The connection closed: they go with it (they come again with the next one)
        channel.close()
        advanceUntilIdle()
        assertTrue(ada.engine.waitingOf(document.id).isEmpty())
    }

    @Test
    fun anOperationStampedFarAheadIsRefused() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        val key = bob.engine.connectionKeys().first { it.first == document.id }.second
        val bobReplica = bob.store.replicaId()

        // Bob, an editor, stamps its renaming a century ahead: signed as it should be, refused
        // with the operation before it in the same batch, nothing of them kept, and Bob is not
        // worked with anymore
        val century = 100L * 365 * 24 * 60 * 60 * 1000
        val ahead = impostor(bob, ada, document.id)
        val first = forged(document.id, key, bobReplica, 1, RenameDocument("First"), bob.identity, Stamp(currentTimeMillis(), 1, bobReplica))
        val far = forged(document.id, key, bobReplica, 2, RenameDocument("Ahead"), bob.identity, Stamp(currentTimeMillis() + century, 0, bobReplica))
        ahead.send(SyncCodec.encode(Operations(listOf(first, far))))
        assertTrue(isClosed(ahead))
        advanceUntilIdle()
        assertEquals("Physics", ada.title(document.id))
        assertEquals("Physics", cy.title(document.id))
        assertTrue(ada.store.proofs(document.id, listOf(OperationId(bobReplica, 1), OperationId(bobReplica, 2))).isEmpty())

        // The clocks of Ada and Cy are not pushed: their next changes are stamped now
        ada.change(document.id) { RenameDocument("Optics") }
        cy.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        advanceUntilIdle()
        val day = 24L * 60 * 60 * 1000
        assertTrue(ada.snapshot(document.id).titleVersion.time < currentTimeMillis() + day)
        assertTrue(cy.snapshot(document.id).pages.all { it.added.time < currentTimeMillis() + day })
        assertSame(document.id, listOf(ada, cy))
    }

    @Test
    fun anOperationOfAClockAFewMinutesAheadIsTaken() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        val key = bob.engine.connectionKeys().first { it.first == document.id }.second
        val bobReplica = bob.store.replicaId()

        // The clock of Bob ten minutes ahead: within the hour, taken by Ada and given on to Cy
        val ahead = Stamp(currentTimeMillis() + 10 * 60 * 1000L, 0, bobReplica)
        val channel = impostor(bob, ada, document.id)
        channel.send(SyncCodec.encode(Operations(listOf(forged(document.id, key, bobReplica, 1, RenameDocument("Ahead"), bob.identity, ahead)))))
        advanceUntilIdle()
        assertFalse(isClosed(channel))
        assertEquals("Ahead", ada.title(document.id))
        assertEquals("Ahead", cy.title(document.id))

        // The renaming Cy makes after it wins
        cy.change(document.id) { RenameDocument("Optics") }
        advanceUntilIdle()
        assertEquals("Optics", ada.title(document.id))
        assertTrue(ada.snapshot(document.id).titleVersion > ahead)
    }

    @Test
    fun anOperationCarryingAStampAfterItsOwnIsRefused() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(text("waves"))) }
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        advanceUntilIdle()
        val key = bob.engine.connectionKeys().first { it.first == document.id }.second
        val bobReplica = bob.store.replicaId()
        val adaReplica = ada.store.replicaId()
        val page = ada.snapshot(document.id).pages[0]
        val text = page.data.elements.single() as TextData
        val stamp = Stamp(currentTimeMillis(), 0, bobReplica)
        val after = Stamp(stamp.time + 1, 0, adaReplica)

        // Saying it included a change of Ada made after it, or with an order after it: refused
        for (change in listOf(
            UpdateTexts(page.data.id, listOf(text.copy(text = "particles", versions = Versions(mapOf(adaReplica to after))))),
            PlaceElements(page.data.id, listOf(BoxPlacement(text.id, 1f, 2f, 40f, 10f, 0f, order = ElementOrder.onTop(after, 0))))
        )) {
            val channel = impostor(bob, ada, document.id)
            channel.send(SyncCodec.encode(Operations(listOf(forged(document.id, key, bobReplica, 1, change, bob.identity, stamp)))))
            assertTrue(isClosed(channel))
        }
        advanceUntilIdle()
        assertEquals("waves", (ada.snapshot(document.id).pages[0].data.elements.single() as TextData).text)
        assertEquals(0L, ada.versionVector(document.id)[bobReplica] ?: 0L)
    }

    @Test
    fun anOperationStampedAheadGivenOnByAnotherMemberIsRefused() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        val key = bob.engine.connectionKeys().first { it.first == document.id }.second
        val bobReplica = bob.store.replicaId()

        // Signed by Bob, two hours ahead, given on by Cy (a device of before would have taken it)
        val ahead = Stamp(currentTimeMillis() + 2 * 60 * 60 * 1000L, 0, bobReplica)
        val channel = impostor(cy, ada, document.id)
        channel.send(SyncCodec.encode(Operations(listOf(forged(document.id, key, bobReplica, 1, RenameDocument("Ahead"), bob.identity, ahead)))))
        assertTrue(isClosed(channel))
        advanceUntilIdle()
        assertEquals("Physics", ada.title(document.id))
    }

    @Test
    fun theOperationsOfAMemberReachTheOthersThroughAnotherOne() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        listOf(ada, bob, cy).forEach { it.disconnect() }
        advanceUntilIdle()

        // Cy meets Bob only, then goes; Bob meets Ada later, with the proofs it kept
        cy.join(invitation, "bob")
        cy.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        advanceUntilIdle()
        assertSame(document.id, listOf(bob, cy))
        listOf(bob, cy).forEach { it.disconnect() }
        advanceUntilIdle()
        bob.join(invitation, "ada")
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob, cy))
    }

    @Test
    fun aKeyIsTakenAsTheOwnerChoseIt() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy, dan) = listOf("ada", "bob", "cy", "dan").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        listOf(bob, cy, dan).forEach { it.join(invitation, "ada") }
        advanceUntilIdle()
        val oldKey = cy.engine.connectionKeys().first { it.first == document.id }.second
        cy.disconnect()
        advanceUntilIdle()
        // Dan removed: a new key, which Cy, away, does not have
        ada.engine.removeMember(document.id, dan.engine.device)
        advanceUntilIdle()
        val state = assertNotNull(ada.shared.stateOf(document.id))
        val newKey = state.keys.single { it.epoch == 2 }.key

        // Bob, with the key Cy has, gives it the new membership, then a key of its own: not taken
        val channel = impostor(bob, cy, document.id, oldKey)
        channel.send(SyncCodec.encode(MembershipMessage(assertNotNull(state.membership))))
        channel.send(SyncCodec.encode(KeyUpdate(2, DocumentKey.generate().encode())))
        advanceUntilIdle()
        assertEquals(2, cy.engine.memberships.value[document.id]?.epoch)
        assertNull(cy.shared.stateOf(document.id)?.keys?.firstOrNull { it.epoch == 2 })
        // The one of the owner: taken
        channel.send(SyncCodec.encode(KeyUpdate(2, newKey)))
        advanceUntilIdle()
        assertEquals(newKey, cy.shared.stateOf(document.id)?.keys?.firstOrNull { it.epoch == 2 }?.key)
    }

    @Test
    fun aDocumentComesFromItsOwnerOnly() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, eve) = listOf("ada", "bob", "eve").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        ada.change(document.id) { AddElements.of(it.document.pages[0], List(100) { i -> stroke(i * 6f, 10f) }) }
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        advanceUntilIdle()

        // Eve admitted, the document lost on the way (the frames as large as it are cut)
        connect(ada, eve, invitation, Cable(cutAbove = 8_000))
        advanceUntilIdle()
        assertNotNull(eve.shared.stateOf(document.id)?.key)
        assertTrue(eve.store.documents.first().isEmpty())

        // A member does not give it, nor is it taken from one
        eve.join(invitation, "bob")
        advanceUntilIdle()
        assertTrue(eve.store.documents.first().isEmpty())
        eve.disconnect()
        advanceUntilIdle()
        impostor(bob, eve, document.id).send(SyncCodec.encode(Snapshot(ada.snapshot(document.id))))
        advanceUntilIdle()
        assertTrue(eve.store.documents.first().isEmpty())

        // From its owner
        eve.disconnect()
        advanceUntilIdle()
        eve.join(invitation, "ada")
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, eve))
    }

    @Test
    fun theMembersStayingConnectedCompactTheirLogs() = syncTest { work ->
        val network = InMemoryNetwork()
        val devices = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val (ada, bob, cy) = devices
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        // Bob and Cy with each other too (Cy a member now): every one knows from every other one
        // what it has
        cy.join(invitation, "bob")
        advanceUntilIdle()

        repeat(3) { i -> bob.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(i * 5f, 50f))) } }
        advanceUntilIdle()
        cy.change(document.id) { session -> RemoveElements.of(session.document.pages[0], session.document.pages[0].strokes.take(2)) }
        advanceUntilIdle()
        assertSame(document.id, devices)

        // Every one has everything, and they stay connected: the logs compacted, what was
        // removed gone for good, on every device
        for (device in devices) {
            assertEquals(device.versionVector(document.id), device.compactedOperations(document.id), "the log of ${device.name}")
            assertTrue(device.snapshot(document.id).pages[0].removedElements.isEmpty(), "what ${device.name} keeps removed")
        }
    }

    @Test
    fun whatADeviceHasIsSaidAgainOnceAfterABurstOfChanges() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        advanceUntilIdle()
        val channel = impostor(bob, ada, document.id)
        advanceUntilIdle()
        // Said when the connection starts
        assertEquals(1, messages(channel).count { it is KnownVersions })

        repeat(5) { i -> ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(i * 5f, 1f))) } }
        advanceUntilIdle()
        val said = messages(channel).filterIsInstance<KnownVersions>()
        assertEquals(listOf(ada.versionVector(document.id)), said.map { it.versions.getValue(ada.engine.device) })
        // Nothing changed: nothing said
        advanceTimeBy(60_000)
        advanceUntilIdle()
        assertTrue(messages(channel).none { it is KnownVersions })
    }

    @Test
    fun aMemberNotConnectedToEveryOtherOneKeepsWhatWasRemoved() = syncTest { work ->
        val network = InMemoryNetwork()
        val devices = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val (ada, bob, cy) = devices
        val document = ada.store.createDocument("Notes")
        ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(1f, 1f), stroke(10f, 1f))) }
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()

        bob.change(document.id) { session -> RemoveElements.of(session.document.pages[0], session.document.pages[0].strokes.take(1)) }
        advanceUntilIdle()
        // Ada met both: dropped; Bob and Cy never met, each one does not know what the other has
        assertTrue(ada.snapshot(document.id).pages[0].removedElements.isEmpty())
        for (device in listOf(bob, cy)) assertEquals(1, device.snapshot(document.id).pages[0].removedElements.size, device.name)
        assertSameShown(document.id, devices)
    }

    @Test
    fun aRemovalUndoneAfterItWasDroppedPutsTheContentBackEverywhere() = syncTest { work ->
        val network = InMemoryNetwork()
        val devices = listOf("ada", "bob").map { Device(it, work, this, network) }
        val (ada, bob) = devices
        val document = ada.store.createDocument("Notes")
        ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(1f, 1f), text("waves"))) }
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        advanceUntilIdle()

        // Bob removes the text, keeping the change undoing it (as its history does)
        val open = assertNotNull(bob.documents.open(document.id))
        val undo = open.edit { session -> session.submit(RemoveElements.of(session.document.pages[0], session.document.pages[0].texts.toList())) }
        advanceUntilIdle()
        for (device in devices) assertTrue(device.snapshot(document.id).pages[0].removedElements.isEmpty(), "dropped on ${device.name}")

        open.edit { session -> session.submit(assertNotNull(undo), ChangeOrigin.UNDO) }
        bob.documents.close(open)
        advanceUntilIdle()
        assertSame(document.id, devices)
        assertEquals("waves", ada.snapshot(document.id).pages[0].data.elements.filterIsInstance<TextData>().single().text)
    }

    @Test
    fun aPageRemovedStaysInTheTrashAfterTheLogsAreCompacted() = syncTest { work ->
        val network = InMemoryNetwork()
        val devices = listOf("ada", "bob").map { Device(it, work, this, network) }
        val (ada, bob) = devices
        val document = ada.store.createDocument("Notes")
        ada.change(document.id) { session ->
            AddPage.of(Page(orderKey = session.document.orderKeyAt(1)).apply { add(stroke(5f, 5f)) })
        }
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        advanceUntilIdle()

        // Bob removes the second page; both have everything and compact their logs
        val removed = bob.snapshot(document.id).pages[1].data.id
        bob.change(document.id) { RemovePage(removed) }
        advanceUntilIdle()
        for (device in devices) {
            assertEquals(device.versionVector(document.id), device.compactedOperations(document.id), "the log of ${device.name}")
            assertEquals(listOf(removed), device.snapshot(document.id).removedPages.map { it.data.id }, "kept in the trash on ${device.name}")
        }

        // Put back from the trash of Ada: shown again on both, with its stroke
        ada.change(document.id) { session -> AddPage.of(assertNotNull(session.document.removedPages[removed])) }
        advanceUntilIdle()
        assertSame(document.id, devices)
        assertEquals(1, bob.snapshot(document.id).pages.single { it.data.id == removed }.data.elements.size)
    }

    @Test
    fun whatAMemberHasIsSaidByThatMember() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        cy.disconnect()
        ada.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        advanceUntilIdle()
        val vector = ada.versionVector(document.id)
        val adaReplica = ada.store.replicaId()

        // Bob says Cy has everything: Ada keeps what Cy misses
        val channel = impostor(bob, ada, document.id)
        channel.send(SyncCodec.encode(KnownVersions(mapOf(bob.engine.device to vector, cy.engine.device to vector))))
        advanceUntilIdle()
        assertTrue((ada.compactedOperations(document.id)[adaReplica] ?: 0L) < vector.getValue(adaReplica))
        // Cy says so itself (when it connects, once it has them): the log is compacted
        cy.join(invitation, "ada")
        advanceUntilIdle()
        cy.disconnect()
        cy.join(invitation, "ada")
        advanceUntilIdle()
        assertEquals(vector.getValue(adaReplica), ada.compactedOperations(document.id)[adaReplica])
    }

    @Test
    fun aFileIsTheOneItsOperationNames() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        advanceUntilIdle()
        val key = bob.engine.connectionKeys().first { it.first == document.id }.second
        val bytes = Random(3).nextBytes(10_000)
        val bobReplica = bob.store.replicaId()
        val sequence = (ada.versionVector(document.id)[bobReplica] ?: 0L) + 1
        val image = AddResource(ResourceData("photo", DocumentResource.ResourceType.IMAGE, Sha256.of(bytes)))

        // Bob adds an image, then gives another file for it: not kept
        val channel = impostor(bob, ada, document.id)
        channel.send(SyncCodec.encode(Operations(listOf(forged(document.id, key, bobReplica, sequence, image, bob.identity)))))
        advanceUntilIdle()
        assertTrue(messages(channel).any { it is ResourceRequest })
        channel.send(SyncCodec.encode(ResourceChunk("photo", "jpg", 0, bytes.size.toLong(), Random(4).nextBytes(bytes.size))))
        advanceUntilIdle()
        assertEquals("", ada.resourcePath(document.id, "photo"))

        // Asked again at the next connection: the right one is kept
        val again = impostor(bob, ada, document.id)
        advanceUntilIdle()
        assertTrue(messages(again).any { it is ResourceRequest })
        again.send(SyncCodec.encode(ResourceChunk("photo", "jpg", 0, bytes.size.toLong(), bytes)))
        advanceUntilIdle()
        assertContentEquals(bytes, ada.files.files[assertNotNull(ada.resourcePath(document.id, "photo"))])
    }

    // --- What a member sends: anything ---

    /** [bytes] changed at random: some of them changed, cut, more of them, or other bytes altogether. */
    private fun mutated(bytes: ByteArray, random: Random): ByteArray = when (random.nextInt(4)) {
        0 -> bytes.copyOf().also { if (it.isNotEmpty()) repeat(1 + random.nextInt(4)) { _ -> it[random.nextInt(it.size)] = random.nextInt(256).toByte() } }
        1 -> bytes.copyOf(random.nextInt(bytes.size + 1))
        2 -> {
            val at = random.nextInt(bytes.size + 1)
            bytes.copyOfRange(0, at) + random.nextBytes(1 + random.nextInt(16)) + bytes.copyOfRange(at, bytes.size)
        }
        else -> random.nextBytes(random.nextInt(64))
    }

    /** [bytes] with one or two of them changed: often still an operation, with odd values. */
    private fun changedLightly(bytes: ByteArray, random: Random): ByteArray =
        bytes.copyOf().also { repeat(1 + random.nextInt(2)) { _ -> it[random.nextInt(it.size)] = random.nextInt(256).toByte() } }

    /** The operations of [count] changes of every kind made on a document of its own (with their pages and elements). */
    private fun operations(count: Int, random: Random): List<Operation> {
        val session = DocumentSession(Document(), "ada")
        val made = mutableListOf<Operation>()
        session.addObserver(ChangeObserver { made += it.operation })
        repeat(count) { randomChange(session, random)?.let(session::submit) }
        session.submit(CompositeChange(listOf(RenameDocument("Lab"), AddPage.of(Page(orderKey = session.document.orderKeyAt(0))))))
        session.submit(AddResource.of("photo", DocumentResource.ResourceType.IMAGE, "", "abc"))
        return made
    }

    /** Messages of every kind, as the devices send them. */
    private fun messages(random: Random): List<SyncMessage> {
        val identity = DeviceIdentity.generate()
        val key = DocumentKey.generate()
        val document = Document().apply { pages[0].add(stroke(1f, 2f)); pages[0].add(text("hello")) }
        val signed = Membership("doc", identity.publicKey, members = listOf(Member(identity.publicKey, "ada", "Ada", Role.OWNER)), keyHash = key.hash).signedBy(identity)
        val sealed = operations(10, random).map { OperationSeal(key).seal(it.id.replica, it.id.sequence, OperationProof.sign(identity, "doc", it.id.replica, it.id.sequence, SyncCodec.encodeOperation(it))) }
        return listOf(
            Challenge(random.nextBytes(16)),
            Hello("ada", "Ada", mapOf("ada" to 3L), hasDocument = true, device = identity.publicKey, proof = random.nextBytes(64), membershipVersion = 2, epoch = 1),
            MembershipMessage(signed),
            AccessMembership(
                Membership("space", identity.publicKey, members = listOf(Member(identity.publicKey, "ada", "Ada", Role.OWNER)), kind = MembershipKind.SPACE, grants = listOf(Grant(Capability.SHARE_INSIDE, Role.EDITOR, upTo = mapOf("bob" to 3L))), spaces = listOf(SpaceRef("outer", identity.publicKey))).signedBy(identity),
                identity.publicKey,
                Placement.sign(identity, "doc", SpaceRef("space", identity.publicKey))
            ),
            MembershipQuery(1),
            Admission(1, key.encode(), signed),
            JoinRefused,
            KeyUpdate(1, key.encode()),
            Leave,
            KnownVersions(mapOf(identity.publicKey to mapOf("ada" to 3L))),
            Snapshot(document.snapshot(), mapOf("ada" to 2L)),
            Operations(sealed),
            ResourceRequest(listOf(ResourceAsk("photo", 10))),
            ResourceChunk("photo", "jpg", 0, 100, random.nextBytes(100)),
            ResourceMissing("photo"),
            PresenceMessage(Presence("doc", pageId = document.pages[0].id, x = 1f, y = 2f, replica = "ada", name = "Ada", sequence = 4)),
            AttachmentVersions(mapOf("lecture" to "v1")),
            AttachmentRequest(listOf(AttachmentAsk("lecture", "v1"))),
            AttachmentPart("lecture", "v1", 0, 10, random.nextBytes(10)),
            DeviceAddresses(listOf(DeviceAddress.sign(identity, "iroh", "id https://relay.example.com/", 1))),
            CatalogOperations(catalogOperations().map { OperationSeal(key).seal(it.id.replica, it.id.sequence, OperationProof.signCatalog(identity, "space", it.id.replica, it.id.sequence, SyncCodec.encodeCatalogOperation(it))) }),
            Ping
        )
    }

    /** Changes of a shared folder of every kind, as a device makes them. */
    private fun catalogOperations(): List<CatalogOperation> = listOf(
        AddFolder("f", "Week 1", null), AddDocument("d", "f"), RenameFolder("f", "Week one"), MoveItem("d", null),
        TrashItem("f"), RestoreItem("f"), RemoveFromSpace("d"), ConfirmGiver("d", "ada"),
        CompositeCatalogChange(listOf(AddFolder("g", "Week 2", "f"), AddDocument("e", "g")))
    ).mapIndexed { i, change -> CatalogOperation(OperationId("ada", i + 1L), Stamp(1_000L + i, 0, "ada"), change) }

    @Test
    fun whatADeviceSendsNeverMakesTheDecodingFail() {
        val random = Random(7)
        val frames = messages(random).map(SyncCodec::encode)
        val operations = operations(40, random).map(SyncCodec::encodeOperation)
        val catalog = catalogOperations().map(SyncCodec::encodeCatalogOperation)
        repeat(3_000) {
            // Anything: a message or null, an operation or a SyncException, never another failure
            SyncCodec.decode(mutated(frames.random(random), random))
            try {
                SyncCodec.decodeOperation(mutated(operations.random(random), random))
            } catch (_: SyncException) {
            }
            OpenFrame.decode(mutated(OpenFrame(PROTOCOL_VERSION, "tag").encode(), random))
            OperationProof.decode(mutated(OperationProof(random.nextBytes(20), random.nextBytes(64)).encode(), random))
            try {
                SyncCodec.decodeCatalogOperation(mutated(catalog.random(random), random))
            } catch (_: SyncException) {
            }
        }
        // What the devices send is read as it was
        for (frame in frames) assertContentEquals(frame, SyncCodec.encode(assertNotNull(SyncCodec.decode(frame))))
        for (operation in operations) assertContentEquals(operation, SyncCodec.encodeOperation(SyncCodec.decodeOperation(operation)))
        for (operation in catalog) assertContentEquals(operation, SyncCodec.encodeCatalogOperation(SyncCodec.decodeCatalogOperation(operation)))
    }

    @Test
    fun aMemberSendingAnythingStopsNothing() = syncTest { work ->
        val random = Random(11)
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        repeat(10) { ada.change(document.id) { randomChange(it, random) } }
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        val frames = messages(random).map(SyncCodec::encode)

        // Bob sends Ada changed messages of every kind, a connection each
        repeat(150) {
            impostor(bob, ada, document.id).send(mutated(frames.random(random), random))
            advanceUntilIdle()
        }
        // Then changes of every kind with odd values, signed by Bob as they should be
        val bobReplica = bob.store.replicaId()
        val key = bob.engine.connectionKeys().first { it.first == document.id }.second
        val changes = operations(40, random).map { SyncCodec.encodeOperation(it) }
        repeat(300) {
            val change = try {
                SyncCodec.decodeOperation(changedLightly(changes.random(random), random)).change
            } catch (_: SyncException) {
                return@repeat
            }
            val sequence = (ada.versionVector(document.id)[bobReplica] ?: 0L) + 1
            impostor(bob, ada, document.id).send(SyncCodec.encode(Operations(listOf(forged(document.id, key, bobReplica, sequence, change, bob.identity)))))
            advanceUntilIdle()
        }

        // Ada and Cy go on: the changes of Cy reach Ada, and the document is the same on both (Cy,
        // not connected to Bob, keeps what was removed: Ada drops it)
        cy.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(0))) }
        advanceUntilIdle()
        assertSameShown(document.id, listOf(ada, cy))
        // And it is read again from the store of Ada as it is
        ada.store.flush()
        assertNotNull(KeyValueDocumentStore(ada.storage, scope = work).loadDocument(document.id))
    }

    @Test
    fun aFailureHandlingWhatAMemberSentClosesItsConnectionOnly() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Physics")
        val invitation = ada.share(document.id, "Physics")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()

        // Storing an item Bob sends fails on Ada: Bob's connection closes
        ada.attachments.broken += "lecture"
        val channel = impostor(bob, ada, document.id)
        channel.send(SyncCodec.encode(AttachmentPart("lecture", "v1", 0, 3, byteArrayOf(1, 2, 3))))
        assertTrue(isClosed(channel))

        // Ada goes on with Cy
        cy.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(0))) }
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, cy))
    }

    // --- Members ---

    @Test
    fun theOwnerApprovesOrRefusesTheDevicesJoining() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes", approval = true)
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        // Waiting for Ada
        assertEquals(setOf("bob", "cy"), ada.engine.joinRequests.value.map { it.name }.toSet())
        assertEquals(JoinStatus.WAITING_APPROVAL, bob.engine.joining.value[document.id])
        assertTrue(bob.store.documents.first().isEmpty())

        ada.engine.answer(ada.engine.joinRequests.value.single { it.name == "bob" }, approve = true)
        ada.engine.answer(ada.engine.joinRequests.value.single { it.name == "cy" }, approve = false)
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob))
        assertEquals(JoinStatus.ADMITTED, bob.engine.joining.value[document.id])
        assertEquals(JoinStatus.REFUSED, cy.engine.joining.value[document.id])
        assertTrue(cy.store.documents.first().isEmpty())
        assertNull(cy.shared.stateOf(document.id))
        val members = assertNotNull(ada.engine.memberships.value[document.id]).members
        assertEquals(listOf(Role.OWNER, Role.EDITOR), members.map { it.role })
        assertEquals(members, bob.engine.memberships.value[document.id]?.members)
    }

    @Test
    fun theChangesOfAReaderAreRefusedByEveryDevice() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        bob.join(ada.share(document.id, "Notes", role = Role.READER), "ada")
        advanceUntilIdle()
        assertTrue(bob.store.isReadOnly(document.id))
        // Refused here
        bob.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        assertEquals(1, bob.snapshot(document.id).pages.size)
        // The changes of Ada reach Bob
        ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(1f, 1f))) }
        advanceUntilIdle()
        assertEquals(1, bob.snapshot(document.id).pages[0].data.elements.size)

        // Made editor: its changes are accepted
        ada.engine.setRole(document.id, bob.engine.device, Role.EDITOR)
        advanceUntilIdle()
        assertFalse(bob.store.isReadOnly(document.id))
        bob.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(5f, 5f))) }
        advanceUntilIdle()
        assertEquals(2, ada.snapshot(document.id).pages[0].data.elements.size)

        // Reader again: the changes it makes all the same (by another application) are refused by the others
        ada.engine.setRole(document.id, bob.engine.device, Role.READER)
        advanceUntilIdle()
        assertTrue(ada.engine.refusedOf(document.id).isEmpty())
        val open = assertNotNull(bob.documents.open(document.id))
        open.edit { session ->
            session.isReadOnly = false
            session.submit(AddPage.of(Page(orderKey = session.document.orderKeyAt(1))))
        }
        bob.documents.close(open)
        advanceUntilIdle()
        assertEquals(1, ada.snapshot(document.id).pages.size)
        // And told on Ada: whose, how many, why
        val refused = ada.engine.refusedOf(document.id).single()
        assertEquals(RefusedChanges.Reason.NOT_ALLOWED, refused.reason)
        assertEquals(bob.store.replicaId(), refused.replica)
        assertEquals(bob.engine.device, refused.device)
        assertEquals(1, refused.count)
    }

    @Test
    fun aDeviceRemovedCannotFollowTheDocumentAnymore() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        val oldKey = assertNotNull(cy.shared.stateOf(document.id)?.key)

        ada.engine.removeMember(document.id, cy.engine.device)
        advanceUntilIdle()
        // A new key for the others; Cy keeps its copy only
        val newKey = assertNotNull(ada.shared.stateOf(document.id)?.key)
        assertTrue(newKey != oldKey)
        assertEquals(newKey, bob.shared.stateOf(document.id)?.key)
        assertNull(cy.shared.stateOf(document.id))
        assertTrue(cy.engine.peers.value.isEmpty())
        assertEquals(listOf("bob"), ada.engine.peers.value.map { it.name })

        // With the old key Cy is refused, and its changes stay with it
        cy.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        cy.engine.connect(cy.transport.connect("bob"), document.id, oldKey)
        advanceUntilIdle()
        assertTrue(bob.engine.peers.value.none { it.name == "cy" })
        assertEquals(1, bob.snapshot(document.id).pages.size)
        // The others go on together, with the new key
        bob.disconnect()
        bob.join(invitation, "ada")
        ada.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob))
    }

    @Test
    fun theOwnerEndsTheSharingAndADeviceLeavesIt() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()

        // Cy leaves: the owner removes it
        cy.engine.unshare(document.id)
        advanceUntilIdle()
        assertNull(cy.shared.stateOf(document.id))
        assertEquals(listOf("ada", "bob"), assertNotNull(ada.engine.memberships.value[document.id]).members.map { it.name })

        // Ada ends the sharing: Bob keeps its copy
        ada.engine.unshare(document.id)
        advanceUntilIdle()
        assertNull(ada.shared.stateOf(document.id))
        assertNull(bob.shared.stateOf(document.id))
        assertFalse(bob.store.documents.first().single().isShared)
        assertEquals(1, bob.snapshot(document.id).pages.size)
    }

    @Test
    fun aDocumentRenamedIsRenamedOnTheOtherDevices() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        bob.join(ada.share(document.id, "Notes"), "ada")
        advanceUntilIdle()
        assertTrue(bob.documents.rename(document.id, "Physics"))
        advanceUntilIdle()
        assertEquals("Physics", ada.store.documents.first().single().name)
        assertEquals("Physics", ada.snapshot(document.id).title)
    }

    @Test
    fun aFileTheDeviceHasAlreadyIsNotReceivedAgain() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, work, this, network) }
        val bytes = Random(2).nextBytes(300_000)
        val hash = io.github.valerioisufi.pageboard.platform.Sha256.of(bytes)
        // Bob has the same image in a document of its own
        val own = bob.store.createDocument("Mine")
        bob.files.files["/bob/photo.jpg"] = bytes
        bob.change(own.id) { AddResource.of("own-photo", DocumentResource.ResourceType.IMAGE, "/bob/photo.jpg", hash) }
        val document = ada.store.createDocument("Slides")
        ada.files.files["/ada/photo.jpg"] = bytes
        ada.change(document.id) { AddResource.of("photo", DocumentResource.ResourceType.IMAGE, "/ada/photo.jpg", hash) }

        bob.join(ada.share(document.id, "Slides"), "ada")
        advanceUntilIdle()
        val open = assertNotNull(bob.documents.open(document.id))
        assertEquals("/bob/photo.jpg", open.edit { it.document.findResource("photo")?.path })
        bob.documents.close(open)
        assertEquals(1, bob.files.files.size, "not written again")
    }

    @Test
    fun anItemInterruptedGoesOnFromWhereItStopped() = syncTest { work ->
        val network = InMemoryNetwork()
        val ada = Device("ada", work, this, network, keepAliveMs = 1_000)
        val bob = Device("bob", work, this, network, keepAliveMs = 1_000)
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        connect(ada, bob, invitation, Cable())
        advanceTimeBy(500)
        bob.disconnect()
        advanceTimeBy(5_000)
        val audio = Random(4).nextBytes(10_000_000)
        ada.attachments.items[document.id to "blob/audio"] = audio
        // Cut after a part of it
        connect(ada, bob, invitation, Cable(framesLeft = 30))
        advanceTimeBy(10_000)
        val kept = bob.attachments.received(document.id, "blob/audio", "size ${audio.size}")
        assertTrue(kept in 1 until audio.size.toLong(), "a part of it kept ($kept)")

        connect(ada, bob, invitation, Cable())
        advanceTimeBy(5_000)
        assertEquals(kept, bob.attachments.askedFrom.last(), "asked from where it stopped")
        assertContentEquals(audio, bob.attachments.items[document.id to "blob/audio"])
    }

    @Test
    fun theOperationsEveryMemberHasLeaveTheLog() = syncTest { work ->
        val network = InMemoryNetwork()
        val (ada, bob, cy) = listOf("ada", "bob", "cy").map { Device(it, work, this, network) }
        val document = ada.store.createDocument("Notes")
        val invitation = ada.share(document.id, "Notes")
        bob.join(invitation, "ada")
        advanceUntilIdle()
        repeat(5) { i -> ada.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(i * 5f, 1f))) } }
        repeat(3) { i -> bob.change(document.id) { AddElements.of(it.document.pages[0], listOf(stroke(i * 5f, 50f))) } }
        advanceUntilIdle()
        bob.change(document.id) { session -> RemoveElements.of(session.document.pages[0], session.document.pages[0].strokes.take(2)) }
        advanceUntilIdle()
        // Each one says again what it has as it changes: the log of Ada compacted, connected as they are
        assertTrue(ada.store.compactedOperations(document.id).isNotEmpty(), "the log of Ada compacted")
        assertTrue(ada.store.operationsAfter(document.id, emptyMap()).size < 9)
        // Nothing can bring back what was removed: gone for good
        assertTrue(ada.snapshot(document.id).pages[0].removedElements.isEmpty())
        assertEquals(6, ada.snapshot(document.id).pages[0].data.elements.size)

        // A device joining later gets the document all the same
        cy.join(invitation, "ada")
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob, cy))
        cy.change(document.id) { AddPage.of(Page(orderKey = it.document.orderKeyAt(1))) }
        advanceUntilIdle()
        assertSame(document.id, listOf(ada, bob, cy))
    }

    @Test
    fun theMembersGiveEachOtherWhereTheyCanBeReached() = syncTest { work ->
        val network = InMemoryNetwork()
        val book = InMemoryAddressBook()
        val (ada, bob) = listOf("ada", "bob").map { Device(it, work, this, network) }
        val cy = Device("cy", work, this, network, addressBook = book)
        fun Device.addressOf(other: Device) = engine.addresses.value[other.engine.device]?.get("iroh")?.address

        cy.engine.publishAddress("iroh", "cy-at-home")
        val document = ada.store.createDocument("Lab")
        val invitation = ada.share(document.id, "Lab")
        bob.join(invitation, "ada")
        cy.join(invitation, "ada")
        advanceUntilIdle()
        // Ada met Cy; Bob knows where Cy is through Ada
        assertEquals("cy-at-home", ada.addressOf(cy))
        assertEquals("cy-at-home", bob.addressOf(cy))

        // Cy moves: the new address reaches the others
        cy.engine.publishAddress("iroh", "cy-at-school")
        advanceUntilIdle()
        assertEquals("cy-at-school", bob.addressOf(cy))
        // Bob says where it is once connected: Cy learns it through Ada, and keeps it
        bob.engine.publishAddress("iroh", "bob-on-mobile")
        advanceUntilIdle()
        assertEquals("bob-on-mobile", cy.addressOf(bob))
        assertEquals("bob-on-mobile", book.load().single { it.device == bob.engine.device }.address)
        // The invitations give where the owner is
        ada.engine.publishAddress("iroh", "ada-at-home")
        assertEquals("ada-at-home", ada.engine.invite(document.id, "Lab")?.addresses?.get("iroh"))

        // Bob removed: its addresses are forgotten
        ada.engine.removeMember(document.id, bob.engine.device)
        advanceUntilIdle()
        assertNull(cy.addressOf(bob))
        assertTrue(book.load().none { it.device == bob.engine.device })
        assertEquals("cy-at-school", ada.addressOf(cy))
    }

    private companion object {
        /** The name of the in-memory transport in the addresses of the invitations. */
        const val MEMORY = "memory"
    }
}
