package io.github.valerioisufi.pageboard.sync.lan

import io.github.valerioisufi.pageboard.model.BrushConfig
import io.github.valerioisufi.pageboard.model.StrokeElement
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.KotlinStrokeModeller
import io.github.valerioisufi.pageboard.model.SelectionActivity
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.AddElements
import io.github.valerioisufi.pageboard.model.change.PlaceElements
import io.github.valerioisufi.pageboard.model.change.StrokePlacement
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.model.newElementId
import io.github.valerioisufi.pageboard.store.InMemoryKeyValueStorage
import io.github.valerioisufi.pageboard.store.KeyValueDocumentStore
import io.github.valerioisufi.pageboard.store.OpenDocuments
import io.github.valerioisufi.pageboard.sync.InMemorySharedDocuments
import io.github.valerioisufi.pageboard.sync.Presence
import io.github.valerioisufi.pageboard.sync.ResourceFiles
import io.github.valerioisufi.pageboard.sync.SyncEngine
import io.github.valerioisufi.pageboard.sync.protocol.Invitation
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * This machine as another device working on a document shared by an application (e.g. on a
 * simulator): run with `PAGEBOARD_PEER_LINK` set to the invitation, with the address of the device
 * (`&a=host%3Aport`). It receives the document, draws a wave on its first page, writes a text,
 * moves the wave, then prints what the other device does for `PAGEBOARD_PEER_WATCH_MS`.
 */
class RemotePeerTest {

    @Test
    fun joinsTheDocumentOfAnotherDevice() {
        val link = System.getenv("PAGEBOARD_PEER_LINK") ?: return
        val invitation = assertNotNull(Invitation.parse(link))
        val address = assertNotNull(invitation.addresses[LanTransport.NAME], "the invitation needs the address of the device")
        runBlocking(Dispatchers.Default) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val store = KeyValueDocumentStore(InMemoryKeyValueStorage(), scope = scope)
                val documents = OpenDocuments(store, Dispatchers.Default.limitedParallelism(1), scope)
                val noFiles = object : ResourceFiles {
                    override fun read(path: String): ByteArray? = null
                    override fun write(bytes: ByteArray, extension: String): String? = null
                }
                val engine = SyncEngine(documents, InMemorySharedDocuments(), scope, { "JVM peer" }, noFiles)
                engine.join(invitation)
                engine.connect(LanTransport(scope).connect(address), invitation.documentId, invitation.key)

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

                val waveY = System.getenv("PAGEBOARD_PEER_WAVE_Y")?.toFloatOrNull() ?: 150f
                // A wave drawn as by hand: the pointer and the stroke go along, then the stroke is added
                val points = (0..150).map { i ->
                    StrokePoint(20f + i * 1.1f, waveY + 20f * sin(i / 12f), 0.3f + 0.5f * abs(sin(i / 25f)), i * 16L)
                }
                val brush = BrushConfig(tool = StrokeTool.PRESSURE_PEN, size = 1f, color = 0xFF1E88E5.toInt())
                val pageId = open.edit { session -> session.document.pages[0].id }
                println("PEER drawing")
                for (n in 1..points.size) {
                    val last = points[n - 1]
                    val ink = points.take(n).flatMap { listOf(it.x, it.y, it.pressure, it.timestampMs + 1f) }
                    engine.updatePresence(
                        Presence(
                            invitation.documentId, pageId, last.x, last.y,
                            stroke = 1, ink = ink, inkTool = brush.tool.name, inkColor = brush.color, inkSize = brush.size, inkInput = "STYLUS"
                        )
                    )
                    delay(32)
                }
                val mesh = assertNotNull(KotlinStrokeModeller.buildMesh(points, brush, InputToolType.STYLUS))
                val wave = StrokeElement(mesh = mesh, brushConfig = brush)
                open.edit { session -> session.submit(AddElements.of(session.document.pages[0], listOf(wave))) }
                println("PEER drawn")

                // A text written a letter at a time, then added
                val written = "Ciao! Sto scrivendo anch'io su questa pagina."
                val textId = newElementId()
                fun draft(length: Int) = TextData(
                    id = textId, text = written.take(length), x = 25f, y = waveY + 30f, width = 150f,
                    color = 0xFF6D4C41.toInt(), fontSize = 16f
                )
                println("PEER writing")
                for (n in 1..written.length) {
                    engine.updatePresence(Presence(invitation.documentId, text = draft(n), textPageId = pageId))
                    delay(110)
                }
                delay(800)
                open.edit { session -> session.submit(AddElements.of(session.document.pages[0], listOf(draft(written.length).toElement()))) }
                engine.updatePresence(Presence(invitation.documentId, pageId, 25f, waveY + 34f))
                println("PEER written")
                delay(1_000)

                // The wave selected, moved up a little at a time, then placed
                println("PEER moving")
                for (i in 0..60) {
                    val transform = AffineTransform(tx = i * 0.5f, ty = -i * 0.6f)
                    engine.updatePresence(Presence(invitation.documentId, pageId, 120f + i * 0.5f, waveY - i * 0.6f, selection = SelectionActivity(pageId, listOf(wave.id), transform)))
                    delay(33)
                }
                val moved = AffineTransform(tx = 30f, ty = -36f)
                open.edit { session -> session.submit(PlaceElements(pageId, listOf(StrokePlacement(wave.id, wave.transform.then(moved.toMatrix()))))) }
                engine.updatePresence(Presence(invitation.documentId, pageId, 150f, waveY - 36f, selection = SelectionActivity(pageId, listOf(wave.id))))
                println("PEER moved")
                delay(1_500)
                engine.updatePresence(Presence(invitation.documentId, pageId, 150f, waveY - 36f))
                delay(500)
                println("PEER peers: ${engine.peers.value.map { it.name }}")

                // What the other device does, for a while
                val watchMs = System.getenv("PAGEBOARD_PEER_WATCH_MS")?.toLongOrNull() ?: 0
                if (watchMs > 0) {
                    println("PEER watching")
                    withTimeoutOrNull(watchMs) {
                        engine.presences
                            .map { all -> all.map { "${it.name}: ink=${it.inkInputs} cancelled=${it.inkCancelled} text=${it.text?.text?.let { t -> "\"$t\"" }} selection=${it.selection}" } }
                            .distinctUntilChanged()
                            .collect { println("PEER sees $it") }
                    }
                }
                documents.close(open)
                engine.stop()
            } finally {
                scope.cancel()
            }
        }
    }
}
