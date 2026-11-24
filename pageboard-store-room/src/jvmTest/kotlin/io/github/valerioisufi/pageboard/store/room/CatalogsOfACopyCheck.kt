package io.github.valerioisufi.pageboard.store.room

import androidx.room.Room
import io.github.valerioisufi.pageboard.ink.InkStrokeModeller
import io.github.valerioisufi.pageboard.model.Stamp
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.room.db.PageboardDatabase
import io.github.valerioisufi.pageboard.store.room.db.createPageboardDatabase
import io.github.valerioisufi.pageboard.store.space.SpaceLibrary
import java.io.File
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Who brought each item of the shared folders of a copy of a database (see
 * `docs/plans/brought-by.md`), never run by jvmTest: the catalogs completed from their logs as a
 * [SpaceLibrary] opening them does, and what changed printed. It writes the completed items into
 * the copy only, and refuses the data directories of the applications:
 *
 *     ./gradlew :pageboard-store-room:jvmTest --tests '*CatalogsOfACopyCheck*' -Ppageboard.catalogCopy=<copy of the database file>
 */
class CatalogsOfACopyCheck {
    @Test
    fun check() = runBlocking {
        val copy = System.getProperty("pageboard.catalogCopy")?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile } ?: return@runBlocking
        val home = File(System.getProperty("user.home")).canonicalFile
        for (name in listOf(".pencilnotes", ".pageboard", ".drawview")) {
            check(!copy.path.startsWith(File(home, name).path)) { "$copy is in ~/$name: give a copy of the database, not the database" }
        }
        check(copy.isFile) { "$copy is not a file" }
        val database = createPageboardDatabase(Room.databaseBuilder<PageboardDatabase>(name = copy.path))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = RoomDocumentStore(database, InkStrokeModeller)
            val before = store.spaces().associate { space -> space.id to store.catalogItems(space.id).associateBy { it.id } }
            // The catalogs only: an empty library beside them, so that nothing of the library is
            // compared with them and no change is made
            val library = SpaceLibrary(KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope), store, scope)
            library.start()
            library.stop()
            for ((spaceId, items) in before) {
                val after = store.catalogItems(spaceId).associateBy { it.id }
                val ofBefore = items.values.count { it.presence != Stamp.ZERO && it.arrivals.isEmpty() && it.removed == Stamp.ZERO }
                val changed = after.values.filter { items[it.id]?.broughtBy != it.broughtBy }
                println("Space $spaceId: ${items.size} items, $ofBefore of before, ${changed.size} brought by another device than kept, ${library.unexplainedItems(spaceId).size} not explained by the log")
                for (item in changed) println("  ${item.id} (${item.kind}): ${items[item.id]?.broughtBy} -> ${item.broughtBy}, arrivals ${item.arrivals}")
                for (id in library.unexplainedItems(spaceId)) println("  not explained: $id")
            }
        } finally {
            scope.cancel()
            database.close()
        }
    }
}
