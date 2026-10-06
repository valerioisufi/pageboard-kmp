package io.github.valerioisufi.drawview.sync.lan

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.drawview.core.change.AddElements
import io.github.valerioisufi.drawview.core.engine.InkStrokeEngine
import io.github.valerioisufi.drawview.core.model.BrushConfig
import io.github.valerioisufi.drawview.core.model.DrawStroke
import io.github.valerioisufi.drawview.core.model.InputToolType
import io.github.valerioisufi.drawview.core.model.StrokePoint
import io.github.valerioisufi.drawview.core.model.StrokeTool
import io.github.valerioisufi.drawview.data.InMemoryKeyValueStorage
import io.github.valerioisufi.drawview.data.KeyValueDocumentStore
import io.github.valerioisufi.drawview.data.OpenDocuments
import io.github.valerioisufi.drawview.sync.InMemorySharedDocuments
import io.github.valerioisufi.drawview.sync.Invitation
import io.github.valerioisufi.drawview.sync.Presence
import io.github.valerioisufi.drawview.sync.ResourceFiles
import io.github.valerioisufi.drawview.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * This machine as another device working on a document shared by an application (e.g. on a
 * simulator): run with `DRAWVIEW_PEER_LINK` set to the invitation, with the address of the device
 * (`&a=host%3Aport`). It receives the document, draws a wave on its first page and shows where it
 * is for a while.
 */
class RemotePeerTest {

    @Test
    fun joinsTheDocumentOfAnotherDevice() {
        val link = System.getenv("DRAWVIEW_PEER_LINK") ?: return
        val invitation = assertNotNull(Invitation.parse(link))
        val address = assertNotNull(invitation.address, "the invitation needs the address of the device")
        runBlocking(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
                val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope)
                val noFiles = object : ResourceFiles {
                    override fun read(path: String): ByteArray? = null
                    override fun write(bytes: ByteArray, extension: String): String? = null
                }
                val engine = SyncEngine(documents, InMemorySharedDocuments(), scope, "JVM peer", noFiles)
                engine.join(LanTransport(scope).connect(address), invitation)

                val open = withTimeout(20_000) {
                    var found = documents.open(invitation.documentId)
                    while (found == null) {
                        delay(100)
                        found = documents.open(invitation.documentId)
                    }
                    found
                }
                val strokes = open.edit { session -> session.document.pages.sumOf { it.strokes.size } }
                println("PEER received \"${open.document.title}\" with $strokes strokes")

                val waveY = System.getenv("DRAWVIEW_PEER_WAVE_Y")?.toFloatOrNull() ?: 150f
                // A wave drawn as by hand: the pointer and the stroke go along, then the stroke is added
                val points = (0..150).map { i ->
                    StrokePoint(20f + i * 1.1f, waveY + 20f * sin(i / 12f), 0.3f + 0.5f * abs(sin(i / 25f)), i * 16L)
                }
                val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 1f, color = Color(0xFF1E88E5))
                val pageId = open.edit { session -> session.document.pages[0].id }
                println("PEER drawing")
                for (n in 1..points.size) {
                    val last = points[n - 1]
                    val ink = points.take(n).flatMap { listOf(it.x, it.y, it.pressure, it.timestampMs + 1f) }
                    engine.updatePresence(
                        Presence(
                            invitation.documentId, pageId, last.x, last.y,
                            stroke = 1, ink = ink, inkTool = brush.tool.name, inkColor = brush.color.toArgb(), inkSize = brush.size, inkInput = "STYLUS"
                        )
                    )
                    delay(32)
                }
                val mesh = assertNotNull(InkStrokeEngine.buildMesh(points, brush, InputToolType.STYLUS))
                open.edit { session -> session.submit(AddElements.of(session.document.pages[0], listOf(DrawStroke(mesh = mesh, brushConfig = brush)))) }
                println("PEER drawn")
                // Then the pointer wanders about
                repeat(120) { i ->
                    engine.updatePresence(Presence(invitation.documentId, pageId, 100f + 40f * sin(i / 15f), 220f + 30f * sin(i / 9f)))
                    delay(33)
                }
                println("PEER peers: ${engine.peers.value.map { it.name }}")
                documents.close(open)
                engine.stop()
            } finally {
                scope.cancel()
            }
        }
    }
}
