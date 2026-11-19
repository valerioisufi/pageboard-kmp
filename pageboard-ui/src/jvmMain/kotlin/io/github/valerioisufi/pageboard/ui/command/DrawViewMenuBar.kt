package io.github.valerioisufi.pageboard.ui.command

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import io.github.valerioisufi.pageboard.ui.adaptive.PlatformConventions
import io.github.valerioisufi.pageboard.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.pageboard.ui.adaptive.platformConventions
import org.jetbrains.compose.resources.stringResource

/**
 * The menu bar of the window (the bar of the system on macOS) with the commands of [host]: a menu
 * per [CommandGroup], the sections separated, the shortcuts shown.
 *
 * The window runs the shortcuts itself (`Window(onKeyEvent = { host.dispatch(it) })`): a key it
 * handles is consumed, so that the menu does not run the command a second time.
 */
@Composable
public fun FrameWindowScope.DrawViewMenuBar(host: CommandHost) {
    val commands = host.commands
    MenuBar {
        for (group in CommandGroup.entries) {
            val items = commands.filter { it.group == group }
            if (items.isEmpty()) continue
            Menu(stringResource(group.title)) {
                items.forEachIndexed { index, command ->
                    if (index > 0 && items[index - 1].section != command.section) Separator()
                    val shortcut = command.shortcuts.firstOrNull()?.toKeyShortcut(platformConventions)
                    val checked = command.isChecked?.invoke()
                    if (checked != null) {
                        CheckboxItem(
                            text = command.label(),
                            checked = checked,
                            enabled = command.isEnabled(),
                            shortcut = shortcut,
                            onCheckedChange = { command.performIfEnabled() }
                        )
                    } else {
                        Item(
                            text = command.label(),
                            enabled = command.isEnabled(),
                            shortcut = shortcut,
                            onClick = { command.performIfEnabled() }
                        )
                    }
                }
            }
        }
    }
}

/** The accelerator of a menu item showing this shortcut. */
private fun Shortcut.toKeyShortcut(conventions: PlatformConventions): KeyShortcut {
    val command = conventions.shortcutModifier == ShortcutModifier.COMMAND
    return KeyShortcut(key = key, ctrl = primary && !command, meta = primary && command, alt = alt, shift = shift)
}
