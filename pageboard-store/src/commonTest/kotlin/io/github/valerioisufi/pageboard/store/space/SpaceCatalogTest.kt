package io.github.valerioisufi.pageboard.store.space

import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.model.change.HybridClock
import io.github.valerioisufi.pageboard.model.change.OperationId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class SpaceCatalogTest {
    private var now = 1_000_000L

    private fun session(replica: String) = CatalogSession("space", SpaceCatalog(), replica) { now }

    private fun SpaceCatalog.entry(id: String) = view().firstOrNull { it.id == id }

    @Test
    fun aFolderAndADocumentAreShown() {
        val a = session("a")
        a.submit(AddFolder("f", "Courses", null))
        a.submit(AddDocument("d", "f"))
        assertEquals(CatalogEntry("f", CatalogItemKind.FOLDER, "Courses", null, false, "a"), a.catalog.entry("f"))
        assertEquals("f", a.catalog.entry("d")?.parent)
    }

    @Test
    fun theMostRecentRenameWins() {
        val a = session("a")
        val b = session("b")
        val add = a.submit(AddFolder("f", "One", null))!!.operation
        b.receive(add)
        now += 1
        val ofA = a.submit(RenameFolder("f", "A"))!!.operation
        now += 1
        val ofB = b.submit(RenameFolder("f", "B"))!!.operation
        a.receive(ofB)
        b.receive(ofA)
        assertEquals("B", a.catalog.entry("f")?.name)
        assertEquals("B", b.catalog.entry("f")?.name)
    }

    @Test
    fun aChangeArrivingBeforeItsAdditionIsKept() {
        val a = session("a")
        val b = session("b")
        val c = session("c")
        val add = a.submit(AddFolder("f", "One", null))!!.operation
        b.receive(add)
        val rename = b.submit(RenameFolder("f", "Two"))!!.operation
        c.receive(rename)
        assertNull(c.catalog.entry("f"), "not added yet")
        c.receive(add)
        assertEquals("Two", c.catalog.entry("f")?.name)
    }

    @Test
    fun aCycleOfMovesMadeApartPutsTheLatestMovedAtTheRoot() {
        val a = session("a")
        val b = session("b")
        val adds = listOf(a.submit(AddFolder("x", "X", null))!!, a.submit(AddFolder("y", "Y", null))!!)
        adds.forEach { b.receive(it.operation) }
        now += 1
        val xInY = a.submit(MoveItem("x", "y"))!!.operation
        now += 1
        val yInX = b.submit(MoveItem("y", "x"))!!.operation
        a.receive(yInX)
        b.receive(xInY)
        for (catalog in listOf(a.catalog, b.catalog)) {
            assertEquals(null, catalog.entry("y")?.parent, "y moved last: at the root")
            assertEquals("y", catalog.entry("x")?.parent)
        }
    }

    @Test
    fun aFolderOutOfTheSpaceTakesWhatItHolds() {
        val a = session("a")
        a.submit(AddFolder("f", "F", null))
        a.submit(AddFolder("g", "G", "f"))
        a.submit(AddDocument("d", "g"))
        a.submit(RemoveFromSpace("f"))
        assertEquals(emptyList(), a.catalog.view())
        a.submit(AddFolder("f", "F", null))
        assertEquals(setOf("f", "g", "d"), a.catalog.view().map { it.id }.toSet())
    }

    @Test
    fun theTrashKeepsThePlace() {
        val a = session("a")
        a.submit(AddFolder("f", "F", null))
        a.submit(AddDocument("d", "f"))
        val trash = a.submit(TrashItem("f"))!!
        assertTrue(a.catalog.entry("f")!!.trashed)
        assertEquals("f", a.catalog.entry("d")?.parent)
        a.submit(trash.inverse!!)
        assertFalse(a.catalog.entry("f")!!.trashed)
    }

    @Test
    fun theInversesUndo() {
        val a = session("a")
        val changes = listOf(
            AddFolder("f", "F", null), AddFolder("g", "G", null), AddDocument("d", "f"),
            RenameFolder("f", "F2"), MoveItem("d", "g"), MoveItem("g", "f"), TrashItem("g"),
            RestoreItem("g"), RemoveFromSpace("d"), AddDocument("d", null),
            CompositeCatalogChange(listOf(RenameFolder("g", "G2"), MoveItem("f", "g")))
        )
        for (change in changes) {
            val before = a.catalog.view()
            now += 1
            val committed = a.submit(change)!!
            now += 1
            a.submit(committed.inverse!!)
            assertEquals(before, a.catalog.view(), "undo of $change")
            now += 1
            a.submit(change)
        }
    }

    @Test
    fun anItemAddedAgainKeepsWhoBroughtIt() {
        val a = session("a")
        val e = session("e")
        e.receive(a.submit(AddDocument("d", null))!!.operation)
        now += 1
        // Eve adds it again while it is in the space: Ada still brought it
        a.receive(e.submit(AddDocument("d", null))!!.operation)
        for (catalog in listOf(a.catalog, e.catalog)) assertEquals("a", catalog.entry("d")?.broughtBy)
        // Taken out and added again: brought by the one adding it then, who has it
        now += 1
        val out = e.submit(RemoveFromSpace("d"))!!.operation
        now += 1
        val back = e.submit(AddDocument("d", null))!!.operation
        a.receive(out)
        a.receive(back)
        for (catalog in listOf(a.catalog, e.catalog)) assertEquals("e", catalog.entry("d")?.broughtBy)
        assertEquals(a.catalog.items, e.catalog.items)
    }

    @Test
    fun whoBroughtAnItemIsTheSameInEveryOrder() {
        fun op(replica: String, time: Long, change: CatalogChange) = CatalogOperation(OperationId(replica, time), Stamp(time, 0, replica), change)
        val operations = listOf(
            // Added by Ada, taken out by Carl, added by Eve then by Fay, made apart: Eve brought it
            // (the first addition after the latest removal)
            op("a", 1, AddDocument("d", null)),
            op("c", 2, RemoveFromSpace("d")),
            op("e", 3, AddDocument("d", null)),
            op("f", 4, AddDocument("d", null)),
            // One operation adding and taking out: its first change counts, the item is in
            op("g", 5, CompositeCatalogChange(listOf(AddDocument("k", null), RemoveFromSpace("k")))),
            // Taking out then adding: out
            op("h", 6, AddDocument("m", null)),
            op("i", 7, CompositeCatalogChange(listOf(RemoveFromSpace("m"), AddDocument("m", null))))
        )
        fun permutations(list: List<CatalogOperation>): List<List<CatalogOperation>> =
            if (list.size <= 1) listOf(list) else list.flatMap { first -> permutations(list - first).map { listOf(first) + it } }
        val catalogs = permutations(operations).map { order ->
            SpaceCatalog().also { catalog -> order.forEach { it.change.applyTo(catalog, it.stamp) } }
        }
        val first = catalogs.first()
        for (catalog in catalogs) assertEquals(first.items, catalog.items)
        val view = first.view().associateBy { it.id }
        assertEquals("e", view["d"]?.broughtBy)
        assertEquals("g", view["k"]?.broughtBy)
        assertNull(view["m"])
        assertEquals(listOf(Stamp(3, 0, "e"), Stamp(4, 0, "f")), first["d"]?.arrivals)
        assertEquals(Stamp(2, 0, "c"), first["d"]?.removed)
    }

    @Test
    fun anItemOfBeforeIsCompletedFromTheLog() = runTest {
        // As a build of before kept it: Eve added again the document Ada brought
        val text = """{"id":"d","kind":"DOCUMENT","inSpace":true,"broughtBy":"e","presence":"000000000003.00000000.e","placed":"000000000003.00000000.e"}"""
        val stored = Json.decodeFromString(CatalogItem.serializer(), text)
        assertTrue(stored.isOfBefore)
        val log = listOf(
            CatalogOperation(OperationId("a", 1), Stamp(1, 0, "a"), AddDocument("d", null)),
            CatalogOperation(OperationId("e", 1), Stamp(3, 0, "e"), AddDocument("d", null))
        )
        val completed = CatalogMigration.complete(listOf(stored)) { log }
        val item = completed.items.single()
        assertEquals("a", item.broughtBy)
        assertEquals(listOf(Stamp(1, 0, "a"), Stamp(3, 0, "e")), item.arrivals)
        assertFalse(item.isOfBefore)
        assertEquals(listOf(item), completed.changed)
        assertEquals(emptySet(), completed.unexplained)
        // Without its operations: as it was, told
        val alone = CatalogMigration.complete(listOf(stored)) { emptyList() }
        assertEquals("e", alone.items.single().broughtBy)
        assertEquals(listOf(Stamp(3, 0, "e")), alone.items.single().arrivals)
        assertEquals(setOf("d"), alone.unexplained)
        // Nothing of before: the log is not read
        val none = CatalogMigration.complete(listOf(item)) { error("not read") }
        assertEquals(listOf(item), none.items)
    }

    @Test
    fun theGiverConfirmedStaysWhateverIsAddedLater() {
        val a = session("a")
        val e = session("e")
        e.receive(a.submit(AddDocument("d", null))!!.operation)
        now += 1
        val confirm = assertNotNull(a.submit(ConfirmGiver("d", "a")))
        e.receive(confirm.operation)
        // Taken out and added again by Eve: she brought it, Ada still gives it
        now += 1
        a.receive(e.submit(RemoveFromSpace("d"))!!.operation)
        now += 1
        a.receive(e.submit(AddDocument("d", null))!!.operation)
        for (catalog in listOf(a.catalog, e.catalog)) {
            assertEquals("e", catalog.entry("d")?.broughtBy)
            assertEquals("a", catalog.entry("d")?.giver)
        }
        // The most recent confirmation wins, and undoes
        now += 1
        val again = assertNotNull(a.submit(ConfirmGiver("d", "e")))
        assertEquals("e", a.catalog.entry("d")?.giver)
        now += 1
        a.submit(again.inverse!!)
        assertEquals("a", a.catalog.entry("d")?.giver)
        assertTrue(a.catalog.latestStamp() >= a.catalog["d"]!!.confirmed)
        assertFalse(ConfirmGiver("d", "x".repeat(SpaceCatalog.MAX_ID_LENGTH + 1)).isWellFormed(), "a replica too long")
    }

    @Test
    fun anOperationNotAdmittedIsRefused() {
        val a = session("a")
        fun op(replica: String, stampReplica: String = replica, time: Long = now, change: CatalogChange = AddFolder("f", "F", null)) =
            CatalogOperation(OperationId(replica, 1), Stamp(time, 0, stampReplica), change)
        assertFalse(a.admits(op("b", stampReplica = "c")), "stamped by another replica")
        assertFalse(a.admits(op("b", time = now + HybridClock.MAX_AHEAD_MS + 1)), "ahead")
        assertTrue(a.admits(op("b", time = now + HybridClock.MAX_AHEAD_MS)))
        assertFalse(a.admits(op("b", change = AddFolder("f", "x".repeat(SpaceCatalog.MAX_NAME_LENGTH + 1), null))), "a name too long")
        assertFalse(a.admits(op("b", change = AddFolder("x".repeat(SpaceCatalog.MAX_ID_LENGTH + 1), "F", null))), "an identifier too long")
        assertFalse(a.admits(op("b", change = MoveItem("f", "f"))), "in itself")
        assertFalse(a.admits(op("b", change = CompositeCatalogChange(listOf(CompositeCatalogChange(listOf(TrashItem("f"))))))), "nested")
        assertFalse(a.admits(op("a")), "of this replica")
        assertFailsWith<IllegalArgumentException> { a.receive(op("b", time = now + HybridClock.MAX_AHEAD_MS + 1)) }
        assertTrue(a.catalog.view().isEmpty())
    }

    @Test
    fun aReadOnlySessionSubmitsNothing() {
        val a = session("a")
        a.isReadOnly = true
        assertNull(a.submit(AddFolder("f", "F", null)))
        assertTrue(a.catalog.view().isEmpty())
    }

    @Test
    fun theOperationsTravelAsJson() {
        val a = session("a")
        val operation = assertNotNull(a.submit(CompositeCatalogChange(listOf(AddFolder("f", "F", null), AddDocument("d", "f"))))).operation
        val text = Json.encodeToString(CatalogOperation.serializer(), operation)
        assertEquals(operation, Json.decodeFromString(CatalogOperation.serializer(), text))
        val item = a.catalog["f"]!!
        assertEquals(item, Json.decodeFromString(CatalogItem.serializer(), Json.encodeToString(CatalogItem.serializer(), item)))
    }

    /**
     * Three devices making random changes (undo among them) and receiving those of the others in
     * random orders (each replica's in its order) end up with the same items, stamps included.
     */
    @Test
    fun devicesConvergeOnRandomChangesAndOrders() {
        repeat(40) { seed -> converge(Random(seed)) }
    }

    private fun converge(random: Random) {
        val replicas = listOf("a", "b", "c")
        val sessions = replicas.associateWith { session(it) }
        val log = replicas.associateWith { mutableListOf<CatalogOperation>() }
        val ids = (0 until 8).map { "i$it" }
        val undo = replicas.associateWith { mutableListOf<CatalogChange>() }

        fun randomChange(session: CatalogSession): CatalogChange {
            val id = ids.random(random)
            val parent = if (random.nextInt(3) == 0) null else ids.random(random).takeIf { it != id }
            return when (random.nextInt(11)) {
                0 -> AddFolder(id, "n${random.nextInt(4)}", parent)
                1, 9 -> AddDocument(id, parent)
                2 -> RenameFolder(id, "n${random.nextInt(4)}")
                3, 4 -> MoveItem(id, parent)
                5 -> TrashItem(id)
                6 -> RestoreItem(id)
                7 -> RemoveFromSpace(id)
                // Adding and taking out the same item in one operation, in either order
                10 -> CompositeCatalogChange(listOf(AddDocument(id, parent), RemoveFromSpace(id)).shuffled(random))
                else -> undo.getValue(session.replica).removeLastOrNull() ?: AddFolder(id, "n0", parent)
            }
        }

        fun deliver(to: CatalogSession, steps: Int) {
            repeat(steps) {
                val from = replicas.filter { it != to.replica }.random(random)
                val next = log.getValue(from).getOrNull(to.lastApplied(from).toInt()) ?: return@repeat
                assertTrue(to.receive(next))
            }
        }

        repeat(150) {
            now += random.nextLong(0, 3)
            val session = sessions.getValue(replicas.random(random))
            if (random.nextInt(3) == 0) {
                deliver(session, random.nextInt(1, 5))
            } else {
                val change = randomChange(session)
                if (!change.isWellFormed()) return@repeat
                val committed = assertNotNull(session.submit(change))
                committed.inverse?.let { undo.getValue(session.replica).add(it) }
                log.getValue(session.replica).add(committed.operation)
            }
        }
        // Everything delivered, in random orders
        while (sessions.values.any { s -> replicas.any { r -> s.lastApplied(r) < log.getValue(r).size } }) {
            for (session in sessions.values) deliver(session, 3)
        }
        val first = sessions.getValue("a").catalog
        for (session in sessions.values) {
            assertEquals(first.items, session.catalog.items)
            assertEquals(first.view(), session.catalog.view())
        }
        // Every item shown reaches the root through folders shown
        val shown = first.view().associateBy { it.id }
        for (entry in shown.values) {
            var parent = entry.parent
            var depth = 0
            while (parent != null) {
                val folder = assertNotNull(shown[parent], "the parent of ${entry.id} is shown")
                assertEquals(CatalogItemKind.FOLDER, folder.kind)
                parent = folder.parent
                assertTrue(++depth <= ids.size, "no cycle")
            }
        }
    }
}
