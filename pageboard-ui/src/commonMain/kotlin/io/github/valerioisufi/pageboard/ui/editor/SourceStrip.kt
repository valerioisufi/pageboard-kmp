package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.layout
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.text_open_link
import kotlin.math.roundToInt

/**
 * The source the page hides of what the caret of the text being written is in (see [HiddenSource]):
 * the LaTeX of a formula, the address of a link, shown by it so that the text does not move. The
 * keys go to the one field of the text: the strip draws its [selection] (the caret, blinking as the
 * caret of the field when [caretShown]) and its [composition], in the source shown; `null` when the
 * caret is not in it (in the text of a link). A tap in it puts the caret on the character tapped
 * ([onCaret], an offset of the source shown).
 *
 * It stands above [anchor] (what it belongs to, on the screen), below it when there is no room above
 * or the formatting bar ([avoid]) is there.
 *
 * @param error why the source cannot be rendered, shown under it.
 */
@Composable
internal fun SourceStrip(
    source: String,
    selection: TextRange?,
    composition: TextRange?,
    caretShown: Boolean,
    anchor: () -> Rect?,
    avoid: () -> Rect?,
    error: String?,
    onCaret: (Int) -> Unit,
    monospace: FontFamily,
    onOpenLink: (() -> Unit)? = null
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val colors = MaterialTheme.colorScheme
    val selectionColor = LocalTextSelectionColors.current.backgroundColor
    Surface(
        modifier = Modifier
            .layout { measurable, constraints ->
                val gap = GAP.roundToPx()
                val width = (constraints.maxWidth - 2 * gap).coerceAtLeast(0)
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = width))
                layout(placeable.width, placeable.height) {
                    val at = anchor() ?: return@layout
                    val x = at.left.roundToInt().coerceIn(gap, (constraints.maxWidth - placeable.width - gap).coerceAtLeast(gap))
                    val above = (at.top - gap - placeable.height).roundToInt()
                    val below = (at.bottom + gap).roundToInt()
                    val bar = avoid()
                    fun clear(y: Int) = bar == null || !Rect(x.toFloat(), y.toFloat(), (x + placeable.width).toFloat(), (y + placeable.height).toFloat()).overlaps(bar)
                    val y = if (above >= gap && clear(above)) above else if (clear(below)) below else above.coerceAtLeast(gap)
                    placeable.place(x, y)
                }
            }
            .widthIn(max = MAX_WIDTH)
            .testTag("source-strip"),
        shape = RoundedCornerShape(8.dp),
        shadowElevation = 4.dp,
        color = colors.surface
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                BasicText(
                    text = source.ifEmpty { " " },
                    style = TextStyle(color = colors.onSurface, fontFamily = monospace, fontSize = 14.sp),
                    onTextLayout = { layout = it },
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .pointerInput(source) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                down.consume()
                                val up = waitForUpOrCancellation() ?: return@awaitEachGesture
                                up.consume()
                                val current = layout ?: return@awaitEachGesture
                                onCaret(current.getOffsetForPosition(up.position).coerceIn(0, source.length))
                            }
                        }
                        .drawWithContent {
                            val current = layout
                            val range = selection?.let { TextRange(it.start.coerceIn(0, source.length), it.end.coerceIn(0, source.length)) }
                            if (current != null && range != null && !range.collapsed) {
                                drawPath(current.getPathForRange(range.min, range.max), selectionColor)
                            }
                            drawContent()
                            if (current == null) return@drawWithContent
                            composition?.let { c ->
                                val from = c.min.coerceIn(0, source.length)
                                val to = c.max.coerceIn(0, source.length)
                                if (to > from) {
                                    val start = current.getCursorRect(from)
                                    val end = current.getCursorRect(to)
                                    drawLine(colors.onSurface, Offset(start.left, start.bottom), Offset(end.left, end.bottom), strokeWidth = 1.dp.toPx())
                                }
                            }
                            if (range != null && range.collapsed && caretShown) {
                                with(TextCaret) { drawCaret(current.getCursorRect(range.start), TextCaret.CARET_WIDTH.toPx(), colors.primary) }
                            }
                        }
                )
                if (onOpenLink != null) {
                    IconButton(
                        onClick = onOpenLink,
                        modifier = Modifier.padding(start = 4.dp).size(28.dp).keepsTextFocus()
                    ) {
                        Icon(PageboardIcons.ExternalLink, contentDescription = stringResource(Res.string.text_open_link), modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (error != null) {
                Text(error, style = MaterialTheme.typography.labelMedium, color = colors.error)
            }
        }
    }
}

private val GAP = 6.dp
private val MAX_WIDTH = 480.dp
