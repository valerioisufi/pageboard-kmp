package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.runtime.Immutable

/** The modifier of the keyboard shortcuts of a platform. */
public enum class ShortcutModifier {
    /** ⌘ (macOS, iPadOS). */
    COMMAND,

    /** Ctrl (Windows, Linux, Android). */
    CONTROL
}

/**
 * The conventions of the platform the interface follows, apart from the space and the input.
 *
 * @param shortcutModifier the modifier of the shortcuts: ⌘Z on Apple systems, Ctrl+Z elsewhere.
 */
@Immutable
public data class PlatformConventions(
    val shortcutModifier: ShortcutModifier
)

/** The conventions of the platform running the application. */
public expect val platformConventions: PlatformConventions
