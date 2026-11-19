package io.github.valerioisufi.pageboard.ui.command

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.adaptive.currentAdaptiveInfo
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the pointer rests on a control before its tooltip appears. */
private const val TooltipDelayMillis = 500L

/**
 * [content] with the tooltip of [command] (its label and its shortcut) shown while a precise
 * pointer rests on it.
 */
@Composable
internal fun CommandTooltip(command: Command, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shortcut = command.shortcutLabel(currentAdaptiveInfo().conventions)
    HoverTooltip(text = command.label(), shortcut = shortcut, modifier = modifier, content = content)
}

/**
 * [content] with the tooltip [text] (and [shortcut], if any) shown while a precise pointer (a
 * mouse, a trackpad) rests on it. Without such a pointer it shows [content] only: on a touch
 * screen the long press belongs to the controls (the settings of a tool, the selection).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HoverTooltip(text: String, modifier: Modifier = Modifier, shortcut: String? = null, content: @Composable () -> Unit) {
    if (!currentAdaptiveInfo().input.canHover) {
        Box(modifier = modifier, propagateMinConstraints = true) { content() }
        return
    }
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            PlainTooltip(
                shape = RoundedCornerShape(8.dp),
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text, style = MaterialTheme.typography.labelMedium)
                    if (shortcut != null) {
                        Text(shortcut, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.7f))
                    }
                }
            }
        },
        state = state,
        enableUserInput = false,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.pointerInput(state) {
                var showing: Job? = null
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val mouse = event.changes.any { it.type == PointerType.Mouse }
                        when (event.type) {
                            PointerEventType.Enter -> if (mouse) {
                                showing?.cancel()
                                showing = scope.launch {
                                    delay(TooltipDelayMillis)
                                    state.show()
                                }
                            }
                            // Gone or clicked: the tooltip leaves the control to its use
                            PointerEventType.Exit, PointerEventType.Press -> {
                                showing?.cancel()
                                state.dismiss()
                            }
                        }
                    }
                }
            },
            propagateMinConstraints = true
        ) {
            content()
        }
    }
}
