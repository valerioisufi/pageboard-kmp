package io.github.valerioisufi.pageboard.sync.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.valerioisufi.pageboard.canvas.controller.Collaborator
import io.github.valerioisufi.pageboard.canvas.controller.CollaboratorPresence
import io.github.valerioisufi.pageboard.canvas.controller.EditorActivity
import io.github.valerioisufi.pageboard.canvas.port.EditorDocument
import io.github.valerioisufi.pageboard.ink.InkStrokeEngine
import io.github.valerioisufi.pageboard.model.PageElement
import io.github.valerioisufi.pageboard.model.TextElement
import io.github.valerioisufi.pageboard.model.InputToolType
import io.github.valerioisufi.pageboard.model.StrokeMesh
import io.github.valerioisufi.pageboard.model.StrokePoint
import io.github.valerioisufi.pageboard.model.StrokeTool
import io.github.valerioisufi.pageboard.model.change.ChangeObserver
import io.github.valerioisufi.pageboard.model.change.ChangeOrigin
import io.github.valerioisufi.pageboard.model.change.TextData
import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import io.github.valerioisufi.pageboard.model.remodelingBrush
import io.github.valerioisufi.pageboard.model.shape.ShapeDraftActivity
import io.github.valerioisufi.pageboard.platform.currentTimeMillis
import io.github.valerioisufi.pageboard.sync.Presence
import kotlin.math.abs
import kotlin.math.exp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** How often this device tells where it is: at most (while it changes), and at least. */
private const val PRESENCE_INTERVAL_MS = 33L
private const val PRESENCE_HEARTBEAT_MS = 3_000L

/** A device that has not told where it is for so long is gone. */
private const val PRESENCE_TIMEOUT_MS = 10_000L

/** How quickly the pointers reach where they are (time constant), how long a stroke drawn fades. */
private const val POINTER_SMOOTHING_MS = 45f
private const val INK_FADE_MS = 300f

/**
 * Tells the others where this device is on the document [documentId] (its pointer, the stroke it
 * is drawing), and shows on the canvas where they are (see [io.github.valerioisufi.pageboard.canvas.controller.CollaboratorPresence.collaborators]):
 * their pointers glide to where they are, their strokes are modeled as they come like the strokes
 * of this device, and fade away once drawn (the stroke of the document takes their place).
 */
@Composable
internal fun CollaboratorsLayer(collaboration: Collaboration, documentId: String, document: EditorDocument, presence: CollaboratorPresence) {
    // This device
    LaunchedEffect(collaboration, documentId, document, presence) {
        var sent: EditorActivity? = null
        var sentAt = 0L
        val ink = InkValues()
        suspend fun send(activity: EditorActivity?) {
            collaboration.engine.updatePresence(activity.toPresence(documentId, ink))
            sent = activity
            sentAt = currentTimeMillis()
        }
        launch {
            while (true) {
                delay(PRESENCE_HEARTBEAT_MS)
                if (currentTimeMillis() - sentAt >= PRESENCE_HEARTBEAT_MS) send(sent)
            }
        }
        // As it changes, at most every PRESENCE_INTERVAL_MS (meanwhile the state keeps the last one)
        presence.activity.collect { activity ->
            send(activity)
            delay(PRESENCE_INTERVAL_MS)
        }
    }

    // The others
    LaunchedEffect(collaboration, documentId, document, presence) {
        val drawing = CollaboratorsDrawing(document, presence, documentId)
        try {
            coroutineScope {
                launch { collaboration.presences.collect(drawing::update) }
                // The ones gone silent
                launch {
                    while (true) {
                        delay(1_000)
                        drawing.update(collaboration.presences.value)
                    }
                }
                while (true) {
                    drawing.moving.first { it }
                    withFrameNanos(drawing::frame)
                }
            }
        } finally {
            drawing.release()
        }
    }
}

/** The presence of this device for `activity` (`null`: nothing), the values of its stroke kept by [ink]. */
private fun EditorActivity?.toPresence(documentId: String, ink: InkValues): Presence {
    if (this == null) return Presence(documentId)
    val brush = brush
    val work = Presence(documentId, pageId, x, y, inkCancelled = strokeCancelled, text = text, textPageId = textPageId, selection = selection, shapeDraft = shapeDraft)
    if (stroke == 0L || brush == null || this.ink.isEmpty()) return work
    return work.copy(
        stroke = stroke,
        ink = ink.of(stroke, this.ink),
        inkTool = brush.tool.name,
        inkColor = brush.color,
        inkSize = brush.size,
        inkInput = inputType.name
    )
}

/**
 * The values of the stroke being drawn as a presence carries them ([Presence.INK_VALUES] by input:
 * its position, its pressure, its time from the first input), kept from one presence to the next:
 * the inputs of a stroke only grow, so only the ones that came are added (a presence goes up to 30
 * times a second on the main thread, see docs/plans/ios-stability.md).
 */
private class InkValues {
    private var stroke = 0L
    private var start = 0L
    private var inputs = 0
    private val values = ArrayList<Float>()

    /** The values of the inputs [ink] of the stroke [stroke]: a copy, the next inputs added apart. */
    fun of(stroke: Long, ink: List<StrokePoint>): List<Float> {
        if (stroke != this.stroke || ink.size < inputs) {
            this.stroke = stroke
            // The times from the first input (from 1: 0 is no time)
            start = ink.first().timestampMs - 1
            inputs = 0
            values.clear()
        }
        for (index in inputs until ink.size) {
            val input = ink[index]
            values.add(input.x)
            values.add(input.y)
            values.add(input.pressure)
            values.add((input.timestampMs - start).toFloat())
        }
        inputs = ink.size
        return ArrayList(values)
    }
}

/**
 * The other devices on the document [documentId] as drawn over it, updated by their presences
 * and moved on every [frame] while [moving].
 */
private class CollaboratorsDrawing(private val document: EditorDocument, private val presence: CollaboratorPresence, private val documentId: String) {

    private class Peer(val replica: String) {
        var name = ""
        var pageId: String? = null
        var shown = Offset.Zero
        var target = Offset.Zero

        // The stroke being drawn, modeled as its inputs come
        var stroke = 0L
        var engine: InkStrokeEngine? = null
        var fed = 0
        var ink: StrokeMesh? = null
        var inkPageId: String? = null

        /** When the stroke drawn started fading (frame time; -1: at the next frame, 0: not fading). */
        var fadeStart = 0L
        var inkAlpha = 1f

        // The text being written
        var textData: TextData? = null
        var text: TextElement? = null
        var textPageId: String? = null

        // The elements selected, and how they are being moved (shown: gliding to the target)
        var selectionIds: List<String> = emptyList()
        var selection: List<PageElement> = emptyList()
        var selectionPageId: String? = null
        var moveTarget: AffineTransform? = null
        var moveShown: AffineTransform? = null

        // The live shape being drawn or resized
        var shapeDraft: ShapeDraftActivity? = null
    }

    private val peers = mutableMapOf<String, Peer>()
    private var lastFrame = 0L

    val moving = MutableStateFlow(false)

    /**
     * A move of another device received: its elements are where it put them, no more drawn moved
     * (its next presence says so too).
     */
    private val moves = ChangeObserver { committed ->
        if (committed.origin != ChangeOrigin.REMOTE) return@ChangeObserver
        val changed = committed.effects.savedElements.keys
        for (peer in peers.values) {
            if (peer.moveTarget != null && peer.selection.any { it in changed }) {
                peer.moveTarget = null
                peer.moveShown = null
                moving.value = true
            }
        }
    }

    init {
        document.addObserver(moves)
    }

    fun update(presences: List<Presence>) {
        val now = currentTimeMillis()
        val present = presences.filter { it.documentId == documentId && now - it.receivedAt < PRESENCE_TIMEOUT_MS }
        val replicas = present.mapTo(HashSet()) { it.replica }
        for (gone in peers.keys.filter { it !in replicas }) peers.remove(gone)?.engine?.release()
        for (presence in present) {
            val peer = peers.getOrPut(presence.replica) { Peer(presence.replica) }
            peer.name = presence.name
            val target = Offset(presence.x, presence.y)
            // On another page: there at once
            if (presence.pageId != peer.pageId) {
                peer.pageId = presence.pageId
                peer.shown = target
            }
            peer.target = target
            updateInk(peer, presence)
            updateText(peer, presence)
            updateSelection(peer, presence)
            peer.shapeDraft = presence.shapeDraft?.takeIf { it.isValid() }
        }
        moving.value = true
    }

    private fun updateText(peer: Peer, presence: Presence) {
        val data = presence.text
        peer.textPageId = presence.textPageId
        if (data == null) {
            peer.textData = null
            peer.text = null
        } else if (data != peer.textData) {
            // A new text each time, for the canvas to draw it again
            peer.text = TextElement(data.id).also { data.applyTo(it) }
            peer.textData = data
        }
    }

    private fun updateSelection(peer: Peer, presence: Presence) {
        val selection = presence.selection
        if (selection == null || selection.ids.isEmpty()) {
            peer.selectionIds = emptyList()
            peer.selection = emptyList()
            peer.moveTarget = null
            peer.moveShown = null
            return
        }
        // Found again while some are missing (not received yet)
        if (selection.ids != peer.selectionIds || peer.selection.size < selection.ids.size) {
            peer.selectionIds = selection.ids
            peer.selection = selection.ids.mapNotNull { document.document.findElement(it)?.second }
        }
        peer.selectionPageId = selection.pageId
        if (selection.isMoving) {
            if (peer.moveShown == null) peer.moveShown = selection.transform
            peer.moveTarget = selection.transform
        } else {
            peer.moveTarget = null
            peer.moveShown = null
        }
    }

    private fun updateInk(peer: Peer, presence: Presence) {
        val inputs = presence.inkInputs
        if (presence.stroke == 0L || inputs == 0) {
            if (peer.stroke != 0L) {
                peer.stroke = 0
                peer.engine?.cancelStroke()
                if (presence.inkCancelled) {
                    // Given up: gone at once
                    peer.ink = null
                    peer.fadeStart = 0
                    peer.inkAlpha = 1f
                } else {
                    // Drawn: it fades while the stroke of the document takes its place
                    peer.fadeStart = -1
                }
            }
            return
        }
        val engine = peer.engine ?: InkStrokeEngine().also { peer.engine = it }
        if (presence.stroke != peer.stroke || inputs < peer.fed) {
            val tool = StrokeTool.fromName(presence.inkTool) ?: StrokeTool.PRESSURE_PEN
            engine.startStroke(remodelingBrush(tool, presence.inkSize, presence.inkColor))
            peer.stroke = presence.stroke
            peer.fed = 0
            peer.inkPageId = presence.pageId
            peer.fadeStart = 0
            peer.inkAlpha = 1f
        }
        val type = InputToolType.fromName(presence.inkInput)
        for (i in peer.fed until inputs) {
            val at = i * Presence.INK_VALUES
            engine.addInput(presence.ink[at], presence.ink[at + 1], presence.ink[at + 2], presence.ink[at + 3].toLong(), type)
        }
        peer.fed = inputs
        peer.ink = engine.currentMesh()
    }

    fun frame(nanos: Long) {
        val elapsedMs = if (lastFrame == 0L) 16f else ((nanos - lastFrame) / 1_000_000f).coerceIn(0f, 100f)
        lastFrame = nanos
        val follow = 1f - exp(-elapsedMs / POINTER_SMOOTHING_MS)
        var animating = false
        for (peer in peers.values) {
            val distance = peer.target - peer.shown
            if (distance.getDistance() < 0.05f) {
                peer.shown = peer.target
            } else {
                peer.shown += distance * follow
                animating = true
            }
            val moveTarget = peer.moveTarget
            val moveShown = peer.moveShown
            if (moveTarget != null && moveShown != null && moveShown != moveTarget) {
                val glided = moveShown.toward(moveTarget, follow)
                peer.moveShown = if (glided.closeTo(moveTarget)) moveTarget else glided.also { animating = true }
            }
            if (peer.fadeStart == -1L) peer.fadeStart = nanos
            if (peer.fadeStart > 0L) {
                peer.inkAlpha = 1f - (nanos - peer.fadeStart) / 1_000_000f / INK_FADE_MS
                if (peer.inkAlpha > 0f) {
                    animating = true
                } else {
                    peer.ink = null
                    peer.fadeStart = 0
                    peer.inkAlpha = 1f
                }
            }
        }
        presence.collaborators = peers.values.map {
            Collaborator(
                id = it.replica,
                name = it.name,
                color = colorOf(it.replica),
                pageId = it.pageId,
                position = it.shown,
                ink = it.ink,
                inkPageId = it.inkPageId,
                inkAlpha = it.inkAlpha,
                text = it.text,
                textPageId = it.textPageId,
                selection = it.selection,
                selectionPageId = it.selectionPageId,
                selectionTransform = it.moveShown,
                shapeDraft = it.shapeDraft
            )
        }
        moving.value = animating
        if (!animating) lastFrame = 0L
    }

    fun release() {
        document.removeObserver(moves)
        peers.values.forEach { it.engine?.release() }
        peers.clear()
        presence.collaborators = emptyList()
    }
}

/** This transformation moved toward [target] by the fraction [amount]. */
private fun AffineTransform.toward(target: AffineTransform, amount: Float) = AffineTransform(
    a + (target.a - a) * amount,
    b + (target.b - b) * amount,
    c + (target.c - c) * amount,
    d + (target.d - d) * amount,
    tx + (target.tx - tx) * amount,
    ty + (target.ty - ty) * amount
)

private fun AffineTransform.closeTo(other: AffineTransform): Boolean =
    abs(a - other.a) < 0.001f && abs(b - other.b) < 0.001f && abs(c - other.c) < 0.001f && abs(d - other.d) < 0.001f &&
        abs(tx - other.tx) < 0.05f && abs(ty - other.ty) < 0.05f

