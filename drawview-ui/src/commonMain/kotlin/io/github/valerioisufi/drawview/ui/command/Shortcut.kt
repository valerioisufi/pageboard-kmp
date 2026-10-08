package io.github.valerioisufi.drawview.ui.command

import androidx.compose.runtime.Immutable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.ShortcutModifier

/**
 * A combination of keys running a [Command], written once for every platform: [primary] is ⌘ on
 * the Apple systems and Ctrl elsewhere ([PlatformConventions.shortcutModifier]), so that
 * `Shortcut(Key.Z, primary = true)` is ⌘Z on a Mac and an iPad, Ctrl+Z on Windows, Linux and
 * Android.
 */
@Immutable
data class Shortcut(
    val key: Key,
    val primary: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false
) {
    /** Whether [event] is this combination, with no other modifier. */
    fun matches(event: KeyEvent, conventions: PlatformConventions): Boolean {
        if (event.key != key) return false
        val command = conventions.shortcutModifier == ShortcutModifier.COMMAND
        val primaryPressed = if (command) event.isMetaPressed else event.isCtrlPressed
        val otherPressed = if (command) event.isCtrlPressed else event.isMetaPressed
        return primaryPressed == primary && !otherPressed && event.isShiftPressed == shift && event.isAltPressed == alt
    }

    /** The combination as the platform writes it: ⇧⌘Z on Apple systems, Ctrl+Shift+Z elsewhere. */
    fun label(conventions: PlatformConventions): String {
        val apple = conventions.shortcutModifier == ShortcutModifier.COMMAND
        val name = keyName(key, apple)
        return if (apple) {
            buildString {
                if (alt) append('⌥')
                if (shift) append('⇧')
                if (primary) append('⌘')
                append(name)
            }
        } else {
            listOfNotNull("Ctrl".takeIf { primary }, "Shift".takeIf { shift }, "Alt".takeIf { alt }, name).joinToString("+")
        }
    }
}

private val Letters = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z
)

private val Digits = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/** The name of [key] on the keys of a keyboard, with the symbols of the Apple keyboards when [apple]. */
internal fun keyName(key: Key, apple: Boolean): String {
    Letters.indexOf(key).takeIf { it >= 0 }?.let { return ('A' + it).toString() }
    Digits.indexOf(key).takeIf { it >= 0 }?.let { return ('0' + it).toString() }
    return when (key) {
        Key.Backspace -> if (apple) "⌫" else "Backspace"
        Key.Delete -> if (apple) "⌦" else "Del"
        Key.Enter -> if (apple) "↩" else "Enter"
        Key.Escape -> "Esc"
        Key.Spacebar -> "Space"
        Key.Tab -> if (apple) "⇥" else "Tab"
        Key.Plus, Key.NumPadAdd -> "+"
        Key.Minus, Key.NumPadSubtract -> "-"
        Key.Equals -> "="
        Key.Period -> "."
        Key.Comma -> ","
        Key.DirectionUp -> "↑"
        Key.DirectionDown -> "↓"
        Key.DirectionLeft -> "←"
        Key.DirectionRight -> "→"
        Key.PageUp -> if (apple) "⇞" else "PgUp"
        Key.PageDown -> if (apple) "⇟" else "PgDn"
        Key.F2 -> "F2"
        // A key without a name here (the tests check those of the commands)
        else -> key.toString()
    }
}
