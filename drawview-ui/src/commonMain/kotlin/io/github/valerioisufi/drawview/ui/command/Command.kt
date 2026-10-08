package io.github.valerioisufi.drawview.ui.command

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.painter.Painter
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions

/** The menu listing a command (the menu bar of the desktop, the menus of the platforms). */
enum class CommandGroup { FILE, EDIT, VIEW, INSERT, FORMAT, TOOLS, HELP }

/**
 * An action of the interface, defined once and shown in many ways: a button of a bar (with its
 * tooltip), an item of the menu bar, a keyboard shortcut, an entry of a context menu.
 *
 * The command asks its expert whether it can run ([isEnabled]: the history for undo, the selection
 * for copy) and whether it is on ([isChecked], for the tools and the toggles): the presenters read
 * them in composition, so that they follow the state.
 *
 * @param id a name unique among the commands of a screen.
 * @param label what the command does, as the menus write it.
 * @param group the menu listing it.
 * @param section commands of a group with different sections are separated in the menus.
 * @param icon its icon in the menus and the bars, if any.
 * @param shortcuts the keys running it; the first is the one shown.
 * @param isChecked whether it is on, for a command that is a state (a tool, a toggle); `null` for
 *   an action.
 */
@Stable
class Command(
    val id: String,
    val label: @Composable () -> String,
    val group: CommandGroup,
    val section: Int = 0,
    val icon: (@Composable () -> Painter)? = null,
    val shortcuts: List<Shortcut> = emptyList(),
    val isEnabled: () -> Boolean = { true },
    val isChecked: (() -> Boolean)? = null,
    val perform: () -> Unit
) {
    /** The shortcut shown beside the command, as the platform writes it. */
    fun shortcutLabel(conventions: PlatformConventions): String? = shortcuts.firstOrNull()?.label(conventions)

    /** Runs the command if it can run; whether it did. */
    fun performIfEnabled(): Boolean {
        if (!isEnabled()) return false
        perform()
        return true
    }

    override fun toString(): String = "Command($id)"
}
