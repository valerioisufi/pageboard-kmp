package io.github.valerioisufi.drawview.store.space

import io.github.valerioisufi.drawview.model.Stamp
import io.github.valerioisufi.drawview.model.change.HybridClock
import io.github.valerioisufi.drawview.model.change.OperationId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
            return when (random.nextInt(9)) {
                0 -> AddFolder(id, "n${random.nextInt(4)}", parent)
                1 -> AddDocument(id, parent)
                2 -> RenameFolder(id, "n${random.nextInt(4)}")
                3, 4 -> MoveItem(id, parent)
                5 -> TrashItem(id)
                6 -> RestoreItem(id)
                7 -> RemoveFromSpace(id)
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
