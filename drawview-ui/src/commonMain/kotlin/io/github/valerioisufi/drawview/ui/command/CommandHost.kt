package io.github.valerioisufi.drawview.ui.command

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import io.github.valerioisufi.drawview.ui.adaptive.PlatformConventions
import io.github.valerioisufi.drawview.ui.adaptive.currentAdaptiveInfo
import io.github.valerioisufi.drawview.ui.adaptive.platformConventions

/**
 * The commands of the screens shown, for what presents them outside of the screens: the menu bar
 * of the window, the keyboard shortcuts. The screens register their commands while they are shown
 * ([RegisterCommands]); the last registered come first (the screen in front, then the
 * application).
 */
@Stable
class CommandHost {

    private class Registration(val commands: List<Command>)

    private val registrations = mutableStateListOf<Registration>()

    /** The commands registered, in the order of their registration. */
    val commands: List<Command> get() = registrations.flatMap { it.commands }

    /** Adds [commands] until the returned function is called. */
    fun register(commands: List<Command>): () -> Unit {
        val registration = Registration(commands)
        registrations += registration
        return { registrations.remove(registration) }
    }

    /**
     * Runs the enabled command whose shortcut is [event] (a key pressed); whether there was one.
     * The window of the desktop calls it with the keys its content leaves (`Window(onKeyEvent)`),
     * [CommandRoot] with those of the focused element.
     */
    fun dispatch(event: KeyEvent, conventions: PlatformConventions = platformConventions): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        return registrations.asReversed().asSequence()
            .flatMap { it.commands.asSequence() }
            .filter { command -> command.shortcuts.any { it.matches(event, conventions) } }
            .any { it.performIfEnabled() }
    }
}

/** The [CommandHost] given by the nearest [CommandRoot], `null` outside of one. */
val LocalCommandHost = compositionLocalOf<CommandHost?> { null }

/** Registers [commands] in the [CommandHost] of the composition while this is composed. */
@Composable
fun RegisterCommands(commands: List<Command>) {
    val host = LocalCommandHost.current ?: return
    DisposableEffect(host, commands) {
        val unregister = host.register(commands)
        onDispose { unregister() }
    }
}

/**
 * The root of the commands of an interface: gives [host] to [content] and runs the shortcuts of
 * its commands with the keys the focused element leaves (a text field keeps its own editing keys).
 * The root takes the focus when nothing in it has it, at first and when it is touched, so that a
 * hardware keyboard reaches the shortcuts (an iPad, a tablet with a keyboard). Inside another root
 * it only shows [content].
 *
 * @param host the commands, also given by the application to its menu bar.
 */
@Composable
fun CommandRoot(
    modifier: Modifier = Modifier,
    host: CommandHost = remember { CommandHost() },
    content: @Composable () -> Unit
) {
    if (LocalCommandHost.current != null) {
        Box(modifier = modifier, propagateMinConstraints = true) { content() }
        return
    }
    val focus = remember { FocusRequester() }
    val focusState = remember { RootFocus() }
    val conventions = currentAdaptiveInfo().conventions
    LaunchedEffect(focus) { focus.requestFocus() }
    CompositionLocalProvider(LocalCommandHost provides host) {
        Box(
            modifier = modifier
                .pointerInput(focus) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!focusState.hasFocus) focus.requestFocus()
                    }
                }
                .focusRequester(focus)
                .onFocusChanged { focusState.hasFocus = it.hasFocus }
                .onKeyEvent { host.dispatch(it, conventions) }
                .focusable(),
            propagateMinConstraints = true
        ) {
            content()
        }
    }
}

/** Whether the root or an element in it has the focus (read by the pointer, not by the composition). */
private class RootFocus {
    var hasFocus = false
}
