package io.github.valerioisufi.pageboard.ui.command

import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import androidx.compose.ui.input.key.nativeKeyCode

/**
 * The shortcuts of the commands of the screen shown, a group per menu, for the list Android shows
 * with Meta + / (or from the menu of the keyboard): give them from
 * `Activity.onProvideKeyboardShortcuts`. The commands without a shortcut are left out.
 */
public fun CommandHost.keyboardShortcutGroups(): List<KeyboardShortcutGroup> =
    labeledCommands
        .filter { (command, _) -> command.shortcuts.isNotEmpty() }
        .groupBy { (command, _) -> command.group }
        .toSortedMap()
        .map { (group, commands) ->
            KeyboardShortcutGroup(
                groupTitles[group] ?: group.name,
                commands.map { (command, label) ->
                    val shortcut = command.shortcuts.first()
                    KeyboardShortcutInfo(label, shortcut.key.nativeKeyCode, shortcut.metaState())
                }
            )
        }

/** The modifiers of the shortcut as Android writes them: Ctrl for the primary one. */
private fun Shortcut.metaState(): Int =
    (if (primary) KeyEvent.META_CTRL_ON else 0) or
        (if (shift) KeyEvent.META_SHIFT_ON else 0) or
        (if (alt) KeyEvent.META_ALT_ON else 0)
