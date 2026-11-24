import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.store.space.SpaceLibrary
import io.github.valerioisufi.pageboard.sync.AccessSource
import io.github.valerioisufi.pageboard.sync.InMemorySharedDocuments
import io.github.valerioisufi.pageboard.sync.SyncEngine
import io.github.valerioisufi.pageboard.sync.protocol.Capability
import io.github.valerioisufi.pageboard.sync.protocol.DeviceIdentity
import io.github.valerioisufi.pageboard.sync.protocol.Grant
import io.github.valerioisufi.pageboard.sync.protocol.InMemoryNetwork
import io.github.valerioisufi.pageboard.sync.protocol.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Another application sharing a folder with the synchronization alone: a folder of its library
 * shared with a second device, a grant of its own given and read back there, and who has the
 * folder read from the public API, without any UI.
 */
fun main() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val network = InMemoryNetwork()
    class Device(val name: String) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val library = SpaceLibrary(store, store, scope)
        val engine = SyncEngine(OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope), InMemorySharedDocuments(), scope, { name }, identity = DeviceIdentity.generate(), spaces = library)
        val transport = network.transport(name)
        init {
            scope.launch { transport.incoming.collect { engine.accept(it) } }
        }
    }
    val ada = Device("ada")
    val bob = Device("bob")
    ada.library.start()
    bob.library.start()
    check(ada.store.createFolder("Course", null))
    val course = ada.store.folders.first().single().id
    ada.store.createDocument("Notes", folderId = course)
    val space = checkNotNull(ada.engine.shareSpace(course))
    // A capability of this application, given by the owner to the editors: carried, never read by the engine
    ada.engine.setGrants(space, listOf(Grant("com.example.comments", Role.EDITOR), Grant(Capability.SHARE_INSIDE, Role.EDITOR)))
    val invitation = checkNotNull(ada.engine.invite(space, "Course", Role.EDITOR, addresses = mapOf("memory" to "ada")))
    bob.engine.joinSpace(invitation, io.github.valerioisufi.pageboard.sync.SpaceRoot.NewFolder)
    bob.engine.connect(bob.transport.connect("ada"), invitation.documentId, invitation.key)
    // What a transport does (LanSync, IrohSync): connecting about what the engine wants, until it has it
    withTimeout(20_000) {
        while (bob.store.documents.first().isEmpty()) {
            val connected = bob.engine.peers.value.mapTo(HashSet()) { it.documentId }
            for ((id, key) in bob.engine.connectionKeys().distinctBy { it.first }) {
                if (id !in connected) bob.engine.connect(bob.transport.connect("ada"), id, key)
            }
            delay(200)
        }
    }
    val access = withTimeout(20_000) {
        var found = bob.engine.accessOf(space)
        while (found?.members?.size != 2) {
            delay(50)
            found = bob.engine.accessOf(space)
        }
        found
    }
    val own = access.sources.single() as AccessSource.Own
    check(own.membership.holds("com.example.comments", bob.store.replicaId(), 1)) { "the grant of the application read back" }
    check(access.role == Role.EDITOR)
    println("${access.members.size} members of the folder, the grant of the application read back, ${bob.store.documents.first().size} document received")
    scope.cancel()
}
