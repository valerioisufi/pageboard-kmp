package io.github.valerioisufi.drawview.sync

import androidx.room.Room
import io.github.valerioisufi.drawview.ink.InkStrokeModeller
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawPage
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.KotlinStrokeModeller
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.model.change.AddPage
import io.github.valerioisufi.drawview.model.change.PlaceElements
import io.github.valerioisufi.drawview.model.change.RemoveElements
import io.github.valerioisufi.drawview.model.change.StrokePlacement
import io.github.valerioisufi.drawview.model.change.snapshot
import io.github.valerioisufi.drawview.model.geometry.AffineTransform
import io.github.valerioisufi.drawview.store.OpenDocuments
import io.github.valerioisufi.drawview.store.room.RoomDocumentStore
import io.github.valerioisufi.drawview.store.room.db.DrawDatabase
import io.github.valerioisufi.drawview.store.room.db.createDrawDatabase
import io.github.valerioisufi.drawview.store.room.db.getInMemoryDatabaseBuilder
import io.github.valerioisufi.drawview.sync.protocol.InMemoryNetwork
import java.io.File
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.measureTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Two devices working apart on a shared document for long (`DRAWVIEW_BENCHMARK_STROKES` strokes
 * each, a few moved and erased), then back together: how long they take to be the same again
 * (Room, in the folder `DRAWVIEW_BENCHMARK_FILES` or in memory; encryption; real time). Run by
 * hand.
 */
class OfflineWorkBenchmark {

    private class Device(val name: String, scope: CoroutineScope, network: InMemoryNetwork) {
        // In a file (as on the devices) with DRAWVIEW_BENCHMARK_FILES, otherwise in memory
        val store = RoomDocumentStore(
            createDrawDatabase(
                System.getenv("DRAWVIEW_BENCHMARK_FILES")?.let { folder ->
                    File(folder, "$name.db").also { it.delete() }
                    Room.databaseBuilder<DrawDatabase>(name = File(folder, "$name.db").absolutePath)
                } ?: getInMemoryDatabaseBuilder()
            )
        , InkStrokeModeller)
        val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope)
        val engine = SyncEngine(documents, StoreSharedDocuments(store), scope, { name }, NoFiles)
        val transport = network.transport(name)

        init {
            scope.launch { transport.incoming.collect { engine.accept(it) } }
        }
    }

    private object NoFiles : ResourceFiles {
        override fun read(path: String): ByteArray? = null
        override fun write(bytes: ByteArray, extension: String): String? = null
    }

    @Test
    fun devicesWorkingApartForLongComeTogetherAgain() {
        val strokes = System.getenv("DRAWVIEW_BENCHMARK_STROKES")?.toIntOrNull() ?: return
        runBlocking(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val network = InMemoryNetwork()
                val ada = Device("ada", scope, network)
                val bob = Device("bob", scope, network)
                val document = ada.store.createDocument("Notes")
                val invitation = ada.engine.invite(document.id, "Notes")!!
                bob.engine.join(invitation)
                bob.engine.connect(bob.transport.connect("ada"), document.id, invitation.key)
                awaitSame(document.id, ada, bob)
                bob.engine.stop()
                delay(200)

                // Apart: each one draws on its pages, moves and erases some of its strokes
                val random = Random(1)
                for ((device, offset) in listOf(ada to 0f, bob to 100f)) {
                    val open = device.documents.open(document.id)!!
                    val made = measureTime {
                        open.edit { session -> session.submit(AddPage.of(DrawPage(orderKey = session.document.orderKeyAt(session.document.pages.size)))) }
                        repeat(strokes) { i ->
                            val stroke = stroke(offset + random.nextFloat() * 100f, random.nextFloat() * 280f)
                            open.edit { session ->
                                val page = session.document.pages[i % session.document.pages.size]
                                session.submit(AddElements.of(page, listOf(stroke)))
                                if (i % 10 == 9) {
                                    val mine = page.strokes.last()
                                    session.submit(PlaceElements(page.id, listOf(StrokePlacement(mine.id, AffineTransform(tx = 1f)))))
                                }
                                if (i % 20 == 19) session.submit(RemoveElements.of(page, listOf(page.strokes.first())))
                            }
                        }
                    }
                    // Stored meanwhile (it is drawn by hand for long)
                    val stored = measureTime { open.flush() }
                    device.documents.close(open)
                    println("BENCH ${device.name}: $strokes strokes drawn apart in $made, stored $stored later")
                }
                val log = measureTime { ada.store.operationsAfter(document.id, emptyMap()) }
                println("BENCH the whole log of ada read in $log")

                // Together again
                var applied = Duration.ZERO
                val together = measureTime {
                    bob.engine.connect(bob.transport.connect("ada"), document.id, bob.engine.connectionKeys().first().second)
                    applied = measureTime { awaitApplied(document.id, ada, bob) }
                    awaitSame(document.id, ada, bob)
                }
                println("BENCH applied in $applied")
                val nothing = measureTime { ada.store.operationsAfter(document.id, ada.store.versionVector(document.id)) }
                println("BENCH nothing missing found in $nothing")
                val count = ada.documents.open(document.id)!!.let { open -> open.edit { s -> s.document.pages.sumOf { it.strokes.size } }.also { ada.documents.close(open) } }
                println("BENCH the same again in $together ($count strokes on each one)")
            } finally {
                scope.cancel()
            }
        }
    }

    /** Until both applied the same operations (stored or not yet). */
    private suspend fun awaitApplied(documentId: String, a: Device, b: Device) {
        suspend fun Device.vector() = documents.open(documentId)!!.let { open -> open.edit { it.versionVector }.also { documents.close(open) } }
        while (a.vector() != b.vector()) delay(20)
    }

    private suspend fun awaitSame(documentId: String, a: Device, b: Device) {
        // The same operations first (cheap), then the same document
        while (a.store.versionVector(documentId) != b.store.versionVector(documentId)) delay(20)
        while (true) {
            val first = a.documents.open(documentId)?.let { open -> open.edit { it.document.snapshot() }.also { a.documents.close(open) } }
            val second = b.documents.open(documentId)?.let { open -> open.edit { it.document.snapshot() }.also { b.documents.close(open) } }
            if (first != null && first == second) return
            delay(100)
        }
    }

    /** A stroke as drawn by hand: 150 inputs. */
    private fun stroke(x: Float, y: Float): DrawStroke {
        val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 1f, color = 0xFF000000.toInt())
        val points = (0 until 150).map { i -> StrokePoint(x + i * 0.2f, y + 3f * sin(i / 10f), 0.5f, i * 8L) }
        return DrawStroke(mesh = KotlinStrokeModeller.buildMesh(points, brush, InputToolType.STYLUS)!!, brushConfig = brush)
    }
}
