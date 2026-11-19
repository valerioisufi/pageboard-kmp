package io.github.valerioisufi.drawview.explorer.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel
import io.github.valerioisufi.drawview.explorer.FileExplorerViewModel.FileItem
import io.github.valerioisufi.drawview.explorer.generated.resources.Res
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_selected_count
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_drag_move_to
import io.github.valerioisufi.drawview.explorer.generated.resources.explorer_root_folder
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The items of the library dragged with a precise pointer onto a folder (the root, a folder of
 * the list, of the grid or of the pane of the folders), which moves them there. The folders
 * register where they are; the drag knows the one under the pointer.
 */
@Stable
internal class LibraryDrag {
    /** A folder the items can be dropped onto: `null` is the root. */
    private class Target(val folderId: String?)

    /** The items dragged, none when nothing is dragged. */
    var items by mutableStateOf(emptyList<FileItem>())
        private set

    /** Where the pointer is, in the coordinates of the root of the window. */
    var position by mutableStateOf(Offset.Zero)
        private set

    private val targets = mutableMapOf<Any, Pair<Target, Rect>>()

    /** The folder under the pointer, `null` when none; [hoveredRoot] tells the root apart. */
    var hovered by mutableStateOf<String?>(null)
        private set
    var hoveredRoot by mutableStateOf(false)
        private set

    val isDragging: Boolean get() = items.isNotEmpty()

    fun start(dragged: List<FileItem>, at: Offset) {
        items = dragged
        move(at)
    }

    fun move(at: Offset) {
        position = at
        val target = targets.values.lastOrNull { (_, bounds) -> bounds.contains(at) }?.first
        hovered = target?.folderId
        hoveredRoot = target != null && target.folderId == null
    }

    /** Drops the items: [viewModel] moves them into the folder under the pointer, if they can go. */
    fun end(viewModel: FileExplorerViewModel) {
        val dragged = items
        val target = hovered
        val onTarget = target != null || hoveredRoot
        items = emptyList()
        hovered = null
        hoveredRoot = false
        if (onTarget && isValidDrop(viewModel, target, dragged)) viewModel.moveFiles(dragged, target)
    }

    /** Whether [items] can be dropped into [folderId]: not into themselves nor where they are. */
    fun isValidDrop(viewModel: FileExplorerViewModel, folderId: String?, items: List<FileItem> = this.items): Boolean =
        items.isNotEmpty() && items.none { it.id == folderId } && viewModel.isValidMove(folderId, items)

    fun register(key: Any, folderId: String?, bounds: Rect) {
        targets[key] = Target(folderId) to bounds
    }

    fun unregister(key: Any) {
        targets.remove(key)
    }
}

/** The drag of the library shown, `null` outside of one. */
internal val LocalLibraryDrag = compositionLocalOf<LibraryDrag?> { null }

/** Holds the coordinates of a layout (read by its gestures). */
private class Coordinates {
    var value: LayoutCoordinates? = null
}

/**
 * Drags [item] with the primary button of a mouse (the selection, when it is selected): beyond the
 * slop of a touch the click is left and the items follow the pointer until dropped. A finger or a
 * stylus keep scrolling and the long press selecting.
 */
@Composable
internal fun Modifier.draggableItem(item: FileItem, viewModel: FileExplorerViewModel): Modifier {
    val drag = LocalLibraryDrag.current ?: return this
    val coordinates = remember { Coordinates() }
    return this
        .onGloballyPositioned { coordinates.value = it }
        .pointerInput(item, drag) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (down.type != PointerType.Mouse || !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                var dragging = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val root = coordinates.value?.takeIf { it.isAttached }?.localToRoot(change.position) ?: change.position
                    if (!change.pressed) {
                        if (dragging) {
                            change.consume()
                            drag.end(viewModel)
                        }
                        break
                    }
                    if (!dragging && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        dragging = true
                        val items = if (viewModel.isSelected(item)) viewModel.targetItems else listOf(item)
                        drag.start(items, root)
                    }
                    if (dragging) {
                        drag.move(root)
                        change.consume()
                    }
                }
            }
        }
}

/**
 * A folder ([folderId], `null`: the root) the dragged items can be dropped onto, ringed while
 * they are above it and can go there.
 */
@Composable
internal fun Modifier.dropTarget(viewModel: FileExplorerViewModel, folderId: String?): Modifier {
    val drag = LocalLibraryDrag.current ?: return this
    val key = remember { Any() }
    DisposableEffect(drag, key) { onDispose { drag.unregister(key) } }
    val hovered = drag.isDragging && (if (folderId == null) drag.hoveredRoot else drag.hovered == folderId) &&
        drag.isValidDrop(viewModel, folderId)
    return this
        .onGloballyPositioned { drag.register(key, folderId, it.boundsInRoot()) }
        .then(if (hovered) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)) else Modifier)
}

/**
 * What follows the pointer while items are dragged: their name or their number, and the folder
 * they would go to. Put it over the whole library.
 */
@Composable
internal fun LibraryDragShadow(drag: LibraryDrag, viewModel: FileExplorerViewModel) {
    if (!drag.isDragging) return
    val origin = remember { Coordinates() }
    Box(modifier = Modifier.onGloballyPositioned { origin.value = it }) {
        val offset = drag.position - (origin.value?.takeIf { it.isAttached }?.positionInRoot() ?: Offset.Zero)
        val items = drag.items
        val what = items.singleOrNull()?.name ?: stringResource(Res.string.explorer_selected_count, items.size)
        val target = drag.hovered?.let { viewModel.folderName(it) }
            ?: if (drag.hoveredRoot) stringResource(Res.string.explorer_root_folder) else null
        val valid = (drag.hovered != null || drag.hoveredRoot) && drag.isValidDrop(viewModel, drag.hovered)
        Box(modifier = Modifier.offset { IntOffset(offset.x.roundToInt() + 12, offset.y.roundToInt() + 12) }) {
            DragLabel(if (valid && target != null) stringResource(Res.string.explorer_drag_move_to, target) else what)
        }
    }
}
