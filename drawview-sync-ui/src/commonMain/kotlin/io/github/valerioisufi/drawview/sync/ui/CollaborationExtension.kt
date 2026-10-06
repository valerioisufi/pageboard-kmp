package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.core.controller.DrawViewController
import io.github.valerioisufi.drawview.core.controller.EditorActivity
import io.github.valerioisufi.drawview.core.util.currentTimeMillis
import io.github.valerioisufi.drawview.sync.Invitation
import io.github.valerioisufi.drawview.sync.Presence
import io.github.valerioisufi.drawview.sync.ui.generated.resources.Res
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_cancel
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_close
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_copy_link
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_link_copied
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_no_network
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_nobody
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_participants
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_share
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_share_explanation
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_share_link
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_share_title
import io.github.valerioisufi.drawview.sync.ui.generated.resources.sync_stop_sharing
import io.github.valerioisufi.drawview.ui.components.ToolButton
import io.github.valerioisufi.drawview.ui.session.EditorExtension
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs

/**
 * The collaboration in the editor: the action sharing the document (with the number of the other
 * devices on it), and over the canvas where the others are and what they are drawing.
 */
fun Collaboration.editorExtension(): EditorExtension = EditorExtension(
    toolbarActions = { session, controller -> ShareAction(this@editorExtension, session.documentId, controller) },
    canvasOverlay = { session, controller -> PresenceLayer(this@editorExtension, session.documentId, controller) }
)

@Composable
private fun ShareAction(collaboration: Collaboration, documentId: String, controller: DrawViewController) {
    var dialog by remember { mutableStateOf(false) }
    val peers by collaboration.peers.collectAsState()
    val others = peers.filter { it.documentId == documentId }.distinctBy { it.replica }
    Box {
        ToolButton(onClick = { dialog = true }) {
            Icon(imageVector = Icons.Outlined.PersonAdd, contentDescription = stringResource(Res.string.sync_share))
        }
        if (others.isNotEmpty()) {
            Badge(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(y = (-2).dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Text(others.size.toString(), maxLines = 1)
            }
        }
    }
    if (dialog) ShareDialog(collaboration, documentId, controller.document.title, onDismiss = { dialog = false })
}

/** Shares the document, or shows its invitation and who is on it. */
@Composable
private fun ShareDialog(collaboration: Collaboration, documentId: String, title: String, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var invitation by remember { mutableStateOf<Invitation?>(null) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(documentId) { invitation = collaboration.invitationOf(documentId, title) }
    val online by collaboration.isOnline.collectAsState()
    val peers by collaboration.peers.collectAsState()
    // The clipboard of every platform (the Clipboard API has no common plain text entry)
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val shown = invitation

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Group, contentDescription = null) },
        title = { Text(stringResource(Res.string.sync_share_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shown == null) {
                    Text(stringResource(Res.string.sync_share_explanation))
                } else {
                    Text(stringResource(Res.string.sync_share_link), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer {
                        Text(shown.toLink(), style = MaterialTheme.typography.bodySmall, maxLines = 4)
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(shown.toLink()))
                        copied = true
                    }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(
                            text = stringResource(if (copied) Res.string.sync_link_copied else Res.string.sync_copy_link),
                            modifier = Modifier.offset(x = 8.dp)
                        )
                    }
                    Text(stringResource(Res.string.sync_participants), style = MaterialTheme.typography.labelLarge)
                    val others = peers.filter { it.documentId == documentId }.distinctBy { it.replica }
                    if (others.isEmpty()) {
                        Text(stringResource(Res.string.sync_nobody), style = MaterialTheme.typography.bodyMedium)
                    }
                    for (peer in others) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Canvas(Modifier.size(12.dp)) { drawCircle(colorOf(peer.replica)) }
                            Text(peer.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (!online) {
                        Text(stringResource(Res.string.sync_no_network), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (shown == null) {
                TextButton(onClick = { scope.launch { invitation = collaboration.share(documentId, title) } }) {
                    Text(stringResource(Res.string.sync_share))
                }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_close)) }
            }
        },
        dismissButton = {
            if (shown == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(Res.string.sync_cancel)) }
            } else {
                TextButton(onClick = {
                    scope.launch {
                        collaboration.stopSharing(documentId)
                        invitation = null
                    }
                }) {
                    Text(stringResource(Res.string.sync_stop_sharing), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

// ==========================================================
// Presence
// ==========================================================

/** How often this device tells where it is (at most), and at least. */
private const val PRESENCE_INTERVAL_MS = 100L
private const val PRESENCE_HEARTBEAT_MS = 4_000L

/** A device that has not told where it is for so long is gone. */
private const val PRESENCE_TIMEOUT_MS = 12_000L

/** The most inputs of a stroke being drawn sent (the last ones). */
private const val MAX_INK_POINTS = 600

/**
 * Tells the others where this device is on the document, and draws where the others are: their
 * pointer with their name, the stroke they are drawing.
 */
@Composable
private fun BoxScope.PresenceLayer(collaboration: Collaboration, documentId: String, controller: DrawViewController) {
    LaunchedEffect(collaboration, documentId, controller) {
        var last: EditorActivity? = null
        var lastSent = 0L
        while (true) {
            val activity = controller.activity.value
            val now = currentTimeMillis()
            if (activity != last || now - lastSent >= PRESENCE_HEARTBEAT_MS) {
                collaboration.engine.updatePresence(activity.toPresence(documentId))
                last = activity
                lastSent = now
            }
            delay(PRESENCE_INTERVAL_MS)
        }
    }

    val presences by collaboration.presences.collectAsState()
    val now by produceState(currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = currentTimeMillis()
        }
    }
    val shown = presences.filter { it.documentId == documentId && it.pageId != null && now - it.receivedAt < PRESENCE_TIMEOUT_MS }
    if (shown.isEmpty()) return

    // Drawn again when the canvas is
    val version = controller.version
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    Canvas(modifier = Modifier.matchParentSize()) {
        check(version >= 0)
        for (presence in shown) {
            val pageIndex = controller.document.pages.indexOfFirst { it.id == presence.pageId }
            val mmToScreen = controller.pageMmToScreen(pageIndex) ?: continue
            val color = colorOf(presence.replica)

            // The stroke being drawn
            if (presence.ink.size >= 4) {
                val path = Path()
                for (i in 0 until presence.ink.size / 2) {
                    val point = mmToScreen.mapPoint(presence.ink[i * 2], presence.ink[i * 2 + 1])
                    if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                }
                val width = (presence.inkSize * controller.pixelsPerMm(pageIndex)).coerceAtLeast(1.dp.toPx())
                drawPath(
                    path = path,
                    color = Color(presence.inkColor).copy(alpha = 0.6f),
                    style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // The pointer and the name
            val point = mmToScreen.mapPoint(presence.x, presence.y)
            drawCircle(color = Color.White, radius = 7.dp.toPx(), center = point)
            drawCircle(color = color, radius = 5.dp.toPx(), center = point)
            val label = measurer.measure(presence.name, labelStyle)
            val padding = 4.dp.toPx()
            val topLeft = point + Offset(9.dp.toPx(), 7.dp.toPx())
            drawRoundRect(
                color = color,
                topLeft = topLeft,
                size = Size(label.size.width + padding * 2, label.size.height + padding),
                cornerRadius = CornerRadius(6.dp.toPx())
            )
            drawText(label, topLeft = topLeft + Offset(padding, padding / 2))
        }
    }
}

/** The presence of this device for [activity] (`null`: on no page). */
private fun EditorActivity?.toPresence(documentId: String): Presence {
    if (this == null) return Presence(documentId)
    val inputs = ink.takeLast(MAX_INK_POINTS)
    return Presence(
        documentId = documentId,
        pageId = pageId,
        x = x,
        y = y,
        ink = inputs.flatMap { listOf(it.x, it.y) },
        inkColor = color,
        inkSize = size
    )
}

private val palette = listOf(
    Color(0xFFE53935), Color(0xFF1E88E5), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00897B), Color(0xFFD81B60), Color(0xFF6D4C41)
)

/** The color of the device [replica], the same on every device. */
internal fun colorOf(replica: String): Color = palette[abs(replica.fold(0) { hash, char -> 31 * hash + char.code } % palette.size)]
