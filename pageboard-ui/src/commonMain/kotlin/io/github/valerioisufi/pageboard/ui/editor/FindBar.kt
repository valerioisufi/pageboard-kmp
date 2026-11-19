package io.github.valerioisufi.pageboard.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.canvas.controller.PageboardController
import io.github.valerioisufi.pageboard.ui.components.DesignIconButton
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.common_action_close
import io.github.valerioisufi.pageboard.ui.generated.resources.find_count
import io.github.valerioisufi.pageboard.ui.generated.resources.find_next
import io.github.valerioisufi.pageboard.ui.generated.resources.find_none
import io.github.valerioisufi.pageboard.ui.generated.resources.find_placeholder
import io.github.valerioisufi.pageboard.ui.generated.resources.find_previous
import io.github.valerioisufi.pageboard.ui.theme.PageboardIcons
import org.jetbrains.compose.resources.stringResource

/**
 * The bar of the search in the document (see `DocumentFind`): the text looked for, how many places
 * were found and which one is shown, the previous and the next (Shift+Enter and Enter), Close
 * (Escape). It takes the focus when it opens, with its text selected.
 */
@Composable
internal fun FindBar(controller: PageboardController, modifier: Modifier = Modifier) {
    val find = controller.find
    if (!find.isOpen) return
    val colors = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    var field by remember { mutableStateOf(TextFieldValue(find.query, TextRange(0, find.query.length))) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    ActionBar(modifier = modifier.padding(top = 8.dp, end = 12.dp), scrolls = false) {
        Icon(PageboardIcons.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, end = 4.dp).size(18.dp))
        Box(modifier = Modifier.widthIn(min = 140.dp, max = 220.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            if (field.text.isEmpty()) {
                Text(stringResource(Res.string.find_placeholder), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1)
            }
            BasicTextField(
                value = field,
                onValueChange = {
                    val changed = it.text != field.text
                    field = it
                    if (changed) find.search(it.text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { find.next() }),
                modifier = Modifier
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                if (event.isShiftPressed) find.previous() else find.next()
                                true
                            }
                            Key.Escape -> {
                                find.close()
                                true
                            }
                            else -> false
                        }
                    }
            )
        }
        val count = find.matches.size
        val label = when {
            field.text.isBlank() -> ""
            count == 0 && !find.isSearching -> stringResource(Res.string.find_none)
            count == 0 -> ""
            else -> stringResource(Res.string.find_count, find.current + 1, count)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.widthIn(min = 56.dp).padding(horizontal = 4.dp))
        DesignIconButton(PageboardIcons.ChevronUp, stringResource(Res.string.find_previous), { find.previous() }, enabled = count > 0, tint = colors.onSurface)
        DesignIconButton(PageboardIcons.ChevronDown, stringResource(Res.string.find_next), { find.next() }, enabled = count > 0, tint = colors.onSurface)
        DesignIconButton(PageboardIcons.Close, stringResource(Res.string.common_action_close), { find.close() }, tint = colors.onSurface)
    }
}
