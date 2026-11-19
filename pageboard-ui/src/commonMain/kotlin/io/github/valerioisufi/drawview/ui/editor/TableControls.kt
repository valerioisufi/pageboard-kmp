package io.github.valerioisufi.drawview.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.drawview.canvas.controller.TextFormatTarget
import io.github.valerioisufi.drawview.ui.command.CommandMenu
import io.github.valerioisufi.drawview.ui.components.ToolPopover
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.table_size
import io.github.valerioisufi.drawview.ui.generated.resources.text_editor_action_table
import io.github.valerioisufi.drawview.ui.screen.EditorCommands
import io.github.valerioisufi.drawview.ui.theme.DrawViewIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The table of the text being written: out of a table, a grid choosing the size of a table to
 * insert; in a table, the menu of its rows, columns and alignments (the commands of the table).
 */
@Composable
internal fun TableButton(target: TextFormatTarget, commands: EditorCommands) {
    var open by remember { mutableStateOf(false) }
    val inTable = target.inTable
    Box {
        FormatToggle(
            command = commands.table,
            checked = inTable,
            icon = DrawViewIcons.Grid,
            description = Res.string.text_editor_action_table,
            onClick = { open = true }
        )
        if (inTable) {
            CommandMenu(commands.tableActions, expanded = open, onDismissRequest = { open = false })
        } else {
            ToolPopover(expanded = open, onDismissRequest = { open = false }) {
                TableSizePicker { rows, columns ->
                    target.insertTable(rows, columns)
                    open = false
                }
            }
        }
    }
}

/**
 * A grid of [MAX_PICKED] x [MAX_PICKED] squares choosing the size of a table: the squares up to
 * the one pointed at (hovered, or touched) lit, its size written above; a tap inserts it (two rows
 * at least: its header and a row).
 */
@Composable
private fun TableSizePicker(onPick: (rows: Int, columns: Int) -> Unit) {
    var pointed by remember { mutableStateOf(3 to 3) }
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val (rows, columns) = pointed
        Text(
            stringResource(Res.string.table_size, columns, rows),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp).align(Alignment.CenterHorizontally)
        )
        for (row in 1..MAX_PICKED) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (column in 1..MAX_PICKED) {
                    val lit = row <= rows && column <= columns
                    val size = stringResource(Res.string.table_size, column, row.coerceAtLeast(2))
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(if (lit) colors.primaryContainer else colors.surface, RoundedCornerShape(3.dp))
                            .border(1.dp, if (lit) colors.primary else colors.outlineVariant, RoundedCornerShape(3.dp))
                            .pointerInput(row, column) {
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        if (event.type == PointerEventType.Enter || event.type == PointerEventType.Press) pointed = row.coerceAtLeast(2) to column
                                    }
                                }
                            }
                            .clickable { onPick(row.coerceAtLeast(2), column) }
                            .semantics { contentDescription = size }
                    )
                }
            }
        }
    }
}

/** The most rows and columns the grid offers (the commands add more). */
private const val MAX_PICKED = 8
