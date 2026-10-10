package io.github.valerioisufi.drawview.sync.lan

import io.github.valerioisufi.drawview.data.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.data.KeyValueDocumentStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import io.github.valerioisufi.drawview.ink.InkStrokeEngine
import io.github.valerioisufi.drawview.model.BrushConfig
import io.github.valerioisufi.drawview.model.DrawStroke
import io.github.valerioisufi.drawview.model.InputToolType
import io.github.valerioisufi.drawview.model.StrokePoint
import io.github.valerioisufi.drawview.model.StrokeTool
import io.github.valerioisufi.drawview.model.change.AddElements
import io.github.valerioisufi.drawview.sync.InMemorySharedDocuments
import io.github.valerioisufi.drawview.sync.ResourceFiles
import io.github.valerioisufi.drawview.sync.SyncEngine
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * This machine sharing a document with another device (e.g. an application on a simulator): run
 * with `DRAWVIEW_HOST_PORT` set to the port to listen on; the invitation (with the address on
 * this machine) is printed, the document is shared for `DRAWVIEW_HOST_SECONDS` (60 by default).
 */
class RemoteHostTest {

    @Test
    fun sharesADocumentWithAnotherDevice() {
        val port = System.getenv("DRAWVIEW_HOST_PORT")?.toIntOrNull() ?: return
        val seconds = System.getenv("DRAWVIEW_HOST_SECONDS")?.toIntOrNull() ?: 60
        runBlocking(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
                val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope)
                val noFiles = object : ResourceFiles {
                    override fun read(path: String): ByteArray? = null
                    override fun write(bytes: ByteArray, extension: String): String? = null
                }
                val engine = SyncEngine(documents, InMemorySharedDocuments(), scope, { "JVM host" }, noFiles)
                val transport = LanTransport(scope, port)
                transport.start()
                scope.launch { transport.incoming.collect { engine.accept(it) } }

                val document = store.createDocument("Appunti dal Mac")
                val open = assertNotNull(documents.open(document.id))
                val points = (0..60).map { i -> StrokePoint(30f + i * 2.5f, 100f + 25f * cos(i / 5f), 0.6f, i * 8L) }
                val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 1.2f, color = 0xFFE53935.toInt())
                val mesh = assertNotNull(InkStrokeEngine.buildMesh(points, brush, InputToolType.STYLUS))
                open.edit { session -> session.submit(AddElements.of(session.document.pages[0], listOf(DrawStroke(mesh = mesh, brushConfig = brush)))) }
                documents.close(open)

                val invitation = engine.invite(document.id, document.title, address = "127.0.0.1:$port")!!
                println("HOST invitation ${invitation.toLink()}")
                repeat(seconds) {
                    delay(1_000)
                    val peers = engine.peers.value.map { it.name }
                    if (peers.isNotEmpty()) println("HOST peers $peers")
                }
                engine.stop()
            } finally {
                scope.cancel()
            }
        }
    }
}
