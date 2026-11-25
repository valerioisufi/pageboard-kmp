package io.github.valerioisufi.pageboard.store.space

import io.github.valerioisufi.pageboard.model.Document
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A shared folder inside another one, through the libraries of three devices (see
 * `docs/plans/shared-access.md`, phase 5): Ada in both, Bob in the outer one, Carl in the inner
 * one, the changes of the catalogs carried by hand as the synchronization would.
 */
class NestedSpacesTest {

    /** The time of the devices: moved on between the changes, so that a change made after another one is stamped after it. */
    private var now = 1_000_000L

    /** A device: its store and its library of spaces. */
    private inner class Device(scope: CoroutineScope) {
        val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
        val library = SpaceLibrary(store, store, scope, wallClock = { now })

        suspend fun folders(): Map<String, Pair<String, String?>> = store.folders.first().associate { it.id to (it.name to it.parentId) }

        /**
         * The tree under [root] as the library has it: names and places relative to it, and whether
         * each item is in the trash itself, what a folder in the trash holds included (it is in the
         * spaces still, and goes on changing there).
         */
        suspend fun tree(root: String): Map<String, Triple<String, String?, Boolean>> {
            val library = store.library()
            val parents = library.folders.associate { it.id to it.parentId }
            fun under(parent: String?): Boolean {
                var current = parent
                var steps = 0
                while (current != null && steps++ < 100) {
                    if (current == root) return true
                    current = parents[current]
                }
                return false
            }
            val items = mutableMapOf<String, Triple<String, String?, Boolean>>()
            for (folder in library.folders) if (under(folder.parentId)) items[folder.id] = Triple(folder.name, folder.parentId.takeIf { it != root }, folder.trashedAt != null)
            for (document in library.documents) if (under(document.parentId)) items[document.id] = Triple("", document.parentId.takeIf { it != root }, document.trashedAt != null)
            return items
        }
    }

    /**
     * Carries the changes of [spaceId] made on [from] to [to], each library having taken in the
     * changes made there first (as it does a moment after they are made); the number carried.
     */
    private suspend fun carry(from: Device, to: Device, spaceId: String): Int {
        now += 10
        from.library.reconcile()
        to.library.reconcile()
        val missing = from.store.catalogOperationsAfter(spaceId, to.library.versionVector(spaceId))
        lastCarried = lastCarried + missing.map { "${it.id.replica.take(4)}/${it.id.sequence} ${it.stamp} ${it.change}" }
        return to.library.receive(spaceId, missing)
    }

    private var lastCarried: List<String> = emptyList()

    private class Nested(val ada: Device, val bob: Device, val carl: Device, val outer: String, val inner: String, val course: String, val lab: String, val bobRoot: String, val carlRoot: String)

    private suspend fun TestScope.nested(): Nested {
        val ada = Device(backgroundScope)
        val bob = Device(backgroundScope)
        val carl = Device(backgroundScope)
        listOf(ada, bob, carl).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.folders().keys.single()
        assertTrue(ada.store.createFolder("Lab", course))
        val lab = ada.folders().entries.single { it.value.first == "Lab" }.key
        // The documents on every device (the synchronization brings them), where Ada has them
        val inLab = Document(title = "Measures")
        val inCourse = Document(title = "Notes")
        ada.store.insertDocument(inLab, lab)
        ada.store.insertDocument(inCourse, course)
        bob.store.insertDocument(Document(id = inLab.id, title = "Measures"))
        bob.store.insertDocument(Document(id = inCourse.id, title = "Notes"))
        carl.store.insertDocument(Document(id = inLab.id, title = "Measures"))
        carl.store.insertDocument(Document(id = inCourse.id, title = "Notes"))

        val outer = assertNotNull(ada.library.share(course))
        val inner = assertNotNull(ada.library.share(lab), "a space in a space")
        assertTrue(bob.store.createFolder("From Ada", null))
        val bobRoot = bob.folders().keys.single()
        assertNotNull(bob.library.join(outer.id, bobRoot))
        assertTrue(carl.store.createFolder("Lab of Ada", null))
        val carlRoot = carl.folders().keys.single()
        assertNotNull(carl.library.join(inner.id, carlRoot))
        carry(ada, bob, outer.id)
        carry(ada, carl, inner.id)
        return Nested(ada, bob, carl, outer.id, inner.id, course, lab, bobRoot, carlRoot)
    }

    /** Carries every change both ways until none is left; the rounds it took. */
    private suspend fun settle(n: Nested): Int {
        repeat(30) { round ->
            lastCarried = emptyList()
            val carried = carry(n.ada, n.bob, n.outer) + carry(n.bob, n.ada, n.outer) + carry(n.ada, n.carl, n.inner) + carry(n.carl, n.ada, n.inner)
            if (carried == 0) return round
        }
        error("The changes never settled: an item moving back and forth: $lastCarried")
    }

    private suspend fun assertSame(n: Nested) {
        assertEquals(n.ada.library.view(n.outer), n.bob.library.view(n.outer), "the outer catalog")
        assertEquals(n.ada.library.view(n.inner), n.carl.library.view(n.inner), "the inner catalog")
        assertEquals(n.ada.tree(n.course), n.bob.tree(n.bobRoot), "the course on Ada and on Bob")
        assertEquals(n.ada.tree(n.lab), n.carl.tree(n.carlRoot), "the lab on Ada and on Carl")
    }

    @Test
    fun aFolderInsideAFolderIsInBothAndSharedByTheDeviceInBoth() = runTest {
        val n = nested()
        settle(n)
        assertSame(n)
        assertEquals(n.lab, n.bob.folders()[n.lab]?.let { n.lab }, "Bob has the lab, in the course")
        assertEquals(listOf(n.inner, n.outer), n.ada.library.spacesOf(n.ada.store.documents.first().single { it.name == "Measures" }.id).map { it.id })

        // Carl renames a folder he makes in the lab: Bob sees it through Ada
        assertTrue(n.carl.store.createFolder("Week 1", n.carlRoot))
        val week = n.carl.folders().entries.single { it.value.first == "Week 1" }.key
        settle(n)
        assertEquals("Week 1", n.bob.folders()[week]?.first)
        assertEquals(n.lab, n.bob.folders()[week]?.second)

        // Bob renames it after Carl did: the latest wins on every device, through both catalogs
        assertTrue(n.carl.store.renameFolder(week, "Week one"))
        carry(n.carl, n.ada, n.inner)
        now += 1000
        assertTrue(n.bob.store.renameFolder(week, "First week"))
        settle(n)
        assertSame(n)
        assertEquals("First week", n.carl.folders()[week]?.first)

        // Carl takes an item out of his root: it leaves the lab, and goes to the course for the others
        val measures = n.carl.store.documents.first().single { it.name == "Measures" }.id
        assertTrue(n.carl.store.moveDocument(measures, null))
        settle(n)
        assertSame(n)
        assertEquals(n.course, n.ada.store.documents.first().single { it.id == measures }.folderId, "in the course, out of the lab")
        assertEquals(n.bobRoot, n.bob.store.documents.first().single { it.id == measures }.folderId)
        assertNull(n.carl.store.documents.first().single { it.id == measures }.folderId, "Carl keeps it where he put it")
    }

    @Test
    fun theInnerFolderMovedOutOrLeftLeavesTheOtherOneAsItIs() = runTest {
        val n = nested()
        settle(n)
        // The lab moved out of the course by Ada: out of the outer folder (Bob keeps his copy, out of
        // his root), still shared with Carl
        assertTrue(n.ada.store.moveFolder(n.lab, null))
        settle(n)
        assertTrue(n.ada.library.view(n.outer).none { it.id == n.lab })
        assertTrue(n.bob.folders()[n.lab] != null, "Bob keeps it")
        assertTrue(n.bob.folders()[n.lab]?.second != n.bobRoot, "out of his root")
        assertTrue(n.carl.store.createFolder("Week 1", n.carlRoot))
        settle(n)
        assertEquals(n.lab, n.ada.folders().entries.single { it.value.first == "Week 1" }.value.second, "the lab still shared with Carl")
        assertEquals(n.ada.tree(n.lab), n.carl.tree(n.carlRoot))

        // Back in the course, then the lab left by Carl: the course goes on with it
        assertTrue(n.ada.store.moveFolder(n.lab, n.course))
        settle(n)
        assertTrue(n.bob.folders().entries.any { it.value.first == "Week 1" }, "back in the course, with what it holds")
        n.carl.library.leave(n.inner)
        assertTrue(n.ada.store.createFolder("Week 2", n.lab))
        settle(n)
        assertEquals(n.lab, n.bob.folders().entries.single { it.value.first == "Week 2" }.value.second)
        assertTrue(n.carl.folders().values.none { it.first == "Week 2" }, "Carl left it")
    }

    @Test
    fun aReaderOfTheOuterFolderPassesNothingIntoIt() = runTest {
        val ada = Device(backgroundScope)
        val bob = Device(backgroundScope)
        listOf(ada, bob).forEach { it.library.start() }
        assertTrue(ada.store.createFolder("Course", null))
        val course = ada.folders().keys.single()
        assertTrue(ada.store.createFolder("Lab", course))
        val lab = ada.folders().entries.single { it.value.first == "Lab" }.key
        val outer = assertNotNull(ada.library.share(course))
        val inner = assertNotNull(ada.library.share(lab))
        // Bob reads the course, and writes in the lab, where it is in his course
        assertTrue(bob.store.createFolder("From Ada", null))
        val bobRoot = bob.folders().keys.single()
        assertNotNull(bob.library.join(outer.id, bobRoot, readOnly = true))
        carry(ada, bob, outer.id)
        assertNotNull(bob.library.join(inner.id, lab))
        carry(ada, bob, inner.id)

        val bobReplica = bob.store.replicaId()
        assertTrue(bob.store.createFolder("Week 1", lab))
        now += 10
        bob.library.reconcile()
        assertTrue(bob.store.catalogOperationsAfter(outer.id, emptyMap()).none { it.id.replica == bobReplica }, "nothing of Bob in the course he reads")
        assertTrue(bob.store.catalogOperationsAfter(inner.id, emptyMap()).any { it.id.replica == bobReplica }, "his folder in the lab")
        assertTrue(bob.folders().values.any { it.first == "Week 1" }, "kept in his library, the lab having it")
        // Through Ada, the editor of both, the course has it
        carry(bob, ada, inner.id)
        ada.library.reconcile()
        assertTrue(ada.library.view(outer.id).any { it.name == "Week 1" })
    }

    @Test
    fun aMoveIntoAFolderInTheTrashGoesThroughBothFolders() = runTest {
        val n = nested()
        settle(n)
        val measures = n.ada.store.documents.first().single { it.folderId == n.lab }.id
        assertTrue(n.carl.store.createFolder("Old", n.carlRoot))
        settle(n)
        val old = n.carl.folders().entries.single { it.value.first == "Old" }.key
        // Ada puts it in the trash while Carl, who has not heard of it yet, moves a document into it
        assertTrue(n.ada.store.trashFolder(old))
        carry(n.ada, n.bob, n.outer)
        assertTrue(n.carl.store.moveDocument(measures, old))
        carry(n.carl, n.ada, n.inner)
        assertEquals(old, n.ada.store.library().documents.single { it.id == measures }.parentId, "in the folder in the trash on Ada")
        carry(n.ada, n.bob, n.outer)
        assertEquals(old, n.bob.store.library().documents.single { it.id == measures }.parentId, "and on Bob, through the course")
        assertTrue(n.bob.store.documents.first().none { it.id == measures }, "in the trash with its folder")
        settle(n)
        assertSame(n)
    }

    @Test
    fun whatWasMadeInAFolderOutOfTheLabComesBackWithIt() = runTest {
        val n = nested()
        assertTrue(n.ada.store.createFolder("Week 1", n.lab))
        settle(n)
        val week = n.ada.folders().entries.single { it.value.first == "Week 1" }.key
        // Out of the lab on Ada (into the course), while Carl makes a folder in it
        assertTrue(n.ada.store.moveFolder(week, n.course))
        now += 10
        n.ada.library.reconcile()
        assertTrue(n.carl.store.createFolder("Exercises", week))
        val exercises = n.carl.folders().entries.single { it.value.first == "Exercises" }.key
        carry(n.carl, n.ada, n.inner)
        assertTrue(n.ada.library.view(n.inner).none { it.id == exercises }, "in a folder out of the lab here")
        // Back in the lab: what Carl made in it with it
        assertTrue(n.ada.store.moveFolder(week, n.lab))
        now += 10
        n.ada.library.reconcile()
        assertEquals(week, n.ada.folders()[exercises]?.second)
        settle(n)
        assertSame(n)
    }

    /** What each device and each catalog holds of the items the trees differ on (to find why). */
    private suspend fun describe(n: Nested): String = buildString {
        val trees = listOf("ada course" to n.ada.tree(n.course), "bob" to n.bob.tree(n.bobRoot), "ada lab" to n.ada.tree(n.lab), "carl" to n.carl.tree(n.carlRoot))
        val ids = (trees[0].second.keys + trees[1].second.keys).filter { trees[0].second[it] != trees[1].second[it] } +
            (trees[2].second.keys + trees[3].second.keys).filter { trees[2].second[it] != trees[3].second[it] }
        for (id in ids.distinct()) {
            appendLine("item ${id.take(4)}")
            for ((who, device) in listOf("ada" to n.ada, "bob" to n.bob, "carl" to n.carl)) {
                val folder = device.store.folders.first().firstOrNull { it.id == id }
                val document = device.store.documents.first().firstOrNull { it.id == id }
                val trashed = device.store.trash.first().firstOrNull { it.id == id }
                appendLine("  $who library: folder ${folder?.let { it.name + " in " + it.parentId?.take(4) }} document ${document?.folderId?.take(4)} trash ${trashed?.folderId?.take(4)}")
            }
            for ((who, device, space) in listOf(Triple("ada", n.ada, n.outer), Triple("bob", n.bob, n.outer), Triple("ada", n.ada, n.inner), Triple("carl", n.carl, n.inner))) {
                val item = device.store.catalogItems(space).firstOrNull { it.id == id }
                val shown = device.library.view(space).firstOrNull { it.id == id }
                appendLine("  $who ${if (space == n.outer) "outer" else "inner"}: in=${item?.inSpace} parent=${item?.parent?.take(4)}@${item?.placed} trashed=${item?.trashed}@${item?.trashedVersion} shown=${shown != null}")
            }
        }
    }

    @Test
    fun randomChangesThroughNestedSpacesConverge() = runTest {
        repeat(100) { seed -> converge(Random(seed), seed) }
    }

    private suspend fun TestScope.converge(random: Random, seed: Int, length: Int = 150) {
        val steps = mutableListOf<String>()
        val n = nested()
        settle(n)
        repeat(length) { step ->
            now += random.nextLong(1, 20)
            val device = listOf(n.ada, n.bob, n.carl).random(random)
            val root = when (device) {
                n.ada -> n.course
                n.bob -> n.bobRoot
                else -> n.carlRoot
            }
            // The folders the user sees (out of the trash), as it would choose
            val live = device.store.folders.first().mapTo(HashSet()) { it.id }
            val folders = device.tree(root).keys.filter { it in live }
            val inRoot = listOf(root) + folders + (if (device === n.ada) listOf(n.lab) else emptyList())
            val documents = device.store.documents.first().map { it.id }
            val who = when (device) {
                n.ada -> "ada"
                n.bob -> "bob"
                else -> "carl"
            }
            fun short(id: String?) = id?.take(4)
            // Made, renamed, moved in and out of the lab, put in the trash and taken out of it (see
            // the plan, phases 5 and 9)
            when (random.nextInt(7)) {
                5 -> if (random.nextBoolean()) {
                    // The lab too, itself or through a folder above it: the devices of the lab keep it
                    inRoot.drop(1).distinct().randomOrNull(random)?.let { device.store.trashFolder(it); steps += "$who trashes ${short(it)}" }
                } else {
                    documents.randomOrNull(random)?.let { device.store.trashDocument(it); steps += "$who trashes document ${short(it)}" }
                }
                6 -> device.store.trash.first().randomOrNull(random)?.let { device.store.restore(it.id); steps += "$who restores ${short(it.id)}" }
                0 -> inRoot.random(random).let { device.store.createFolder("f$step", it); steps += "$who makes f$step in ${short(it)}" }
                1 -> folders.randomOrNull(random)?.let { val name = "n${random.nextInt(5)}"; device.store.renameFolder(it, name); steps += "$who renames ${short(it)} $name" }
                2 -> folders.randomOrNull(random)?.let { val to = inRoot.random(random); steps += "$who moves ${short(it)} to ${short(to)}: ${device.store.moveFolder(it, to)}" }
                3 -> documents.randomOrNull(random)?.let { val to = inRoot.random(random); steps += "$who moves document ${short(it)} to ${short(to)}: ${device.store.moveDocument(it, to)}" }
                4 -> repeat(random.nextInt(1, 4)) {
                    when (random.nextInt(4)) {
                        0 -> steps += "ada to bob ${carry(n.ada, n.bob, n.outer)}"
                        1 -> steps += "bob to ada ${carry(n.bob, n.ada, n.outer)}"
                        2 -> steps += "ada to carl ${carry(n.ada, n.carl, n.inner)}"
                        else -> steps += "carl to ada ${carry(n.carl, n.ada, n.inner)}"
                    }
                }
            }
        }
        try {
            settle(n)
            assertSame(n)
        } catch (e: IllegalStateException) {
            throw AssertionError("seed $seed (lab ${n.lab.take(4)}, course ${n.course.take(4)}): ${e.message}\n" + steps.joinToString("\n"), e)
        } catch (e: AssertionError) {
            throw AssertionError("seed $seed (lab ${n.lab.take(4)}, course ${n.course.take(4)}): ${e.message}\n" + describe(n) + "\n" + steps.joinToString("\n"), e)
        }
        listOf(n.ada, n.bob, n.carl).forEach { it.library.stop() }
    }
}
