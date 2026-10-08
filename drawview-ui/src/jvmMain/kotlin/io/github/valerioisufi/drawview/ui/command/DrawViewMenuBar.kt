package io.github.valerioisufi.drawview.ui.command

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier
import io.github.valerioisufi.drawview.ui.adaptive.platformConventions
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.menu_edit
import io.github.valerioisufi.drawview.ui.generated.resources.menu_file
import io.github.valerioisufi.drawview.ui.generated.resources.menu_format
import io.github.valerioisufi.drawview.ui.generated.resources.menu_help
import io.github.valerioisufi.drawview.ui.generated.resources.menu_insert
import io.github.valerioisufi.drawview.ui.generated.resources.menu_tools
import io.github.valerioisufi.drawview.ui.generated.resources.menu_view
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The menu bar of the window (the bar of the system on macOS) with the commands of [host]: a menu
 * per [CommandGroup], the sections separated, the shortcuts shown.
 *
 * The window runs the shortcuts itself (`Window(onKeyEvent = { host.dispatch(it) })`): a key it
 * handles is consumed, so that the menu does not run the command a second time.
 */
@Composable
fun FrameWindowScope.DrawViewMenuBar(host: CommandHost) {
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

private val CommandGroup.title: StringResource
    get() = when (this) {
        CommandGroup.FILE -> Res.string.menu_file
        CommandGroup.EDIT -> Res.string.menu_edit
        CommandGroup.VIEW -> Res.string.menu_view
        CommandGroup.INSERT -> Res.string.menu_insert
        CommandGroup.FORMAT -> Res.string.menu_format
        CommandGroup.TOOLS -> Res.string.menu_tools
        CommandGroup.HELP -> Res.string.menu_help
    }

/** The accelerator of a menu item showing this shortcut. */
private fun Shortcut.toKeyShortcut(conventions: PlatformConventions): KeyShortcut {
    val command = conventions.shortcutModifier == ShortcutModifier.COMMAND
    return KeyShortcut(key = key, ctrl = primary && !command, meta = primary && command, alt = alt, shift = shift)
}
