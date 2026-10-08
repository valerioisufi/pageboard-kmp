package io.github.valerioisufi.drawview.ui.command

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    private class Registration(val commands: List<Command>, val labels: List<String>)

    private val registrations = mutableStateListOf<Registration>()

    /** The commands registered, in the order of their registration. */
    val commands: List<Command> get() = registrations.flatMap { it.commands }

    /**
     * The commands registered with their labels in the language of the interface, for the menus
     * of the system asked outside of the composition (the list of the shortcuts of Android).
     */
    val labeledCommands: List<Pair<Command, String>>
        get() = registrations.flatMap { registration -> registration.commands.zip(registration.labels) }

    /** The titles of the menus in the language of the interface (given by [CommandRoot]). */
    var groupTitles: Map<CommandGroup, String> by mutableStateOf(emptyMap())
        internal set

    /** Adds [commands] (their [labels] written) until the returned function is called. */
    fun register(commands: List<Command>, labels: List<String> = emptyList()): () -> Unit {
        val registration = Registration(commands, labels)
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
    // Written here, in the language of the interface, for the menus of the system
    val labels = commands.map { it.label() }
    DisposableEffect(host, commands, labels) {
        val unregister = host.register(commands, labels)
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
    val groupTitles = CommandGroup.entries.associateWith { stringResource(it.title) }
    LaunchedEffect(host, groupTitles) { host.groupTitles = groupTitles }
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

/** The title of the menu of the group. */
internal val CommandGroup.title: StringResource
    get() = when (this) {
        CommandGroup.FILE -> Res.string.menu_file
        CommandGroup.EDIT -> Res.string.menu_edit
        CommandGroup.VIEW -> Res.string.menu_view
        CommandGroup.INSERT -> Res.string.menu_insert
        CommandGroup.FORMAT -> Res.string.menu_format
        CommandGroup.TOOLS -> Res.string.menu_tools
        CommandGroup.HELP -> Res.string.menu_help
    }
