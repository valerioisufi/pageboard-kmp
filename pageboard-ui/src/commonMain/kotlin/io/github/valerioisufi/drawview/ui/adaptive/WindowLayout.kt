package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Size class of a width, with the breakpoints of Material 3. */
public enum class WidthClass {
    /** Under 600 dp: a phone, an iPad in Slide Over, a narrow desktop window. */
    COMPACT,

    /** From 600 to 840 dp: a tablet in portrait, a tablet in split screen. */
    MEDIUM,

    /** From 840 dp: a tablet in landscape, a desktop window. */
    EXPANDED
}

/** Size class of a height, with the breakpoints of Material 3. */
public enum class HeightClass {
    /** Under 480 dp: a phone in landscape. */
    COMPACT,

    /** From 480 to 900 dp. */
    MEDIUM,

    /** From 900 dp. */
    EXPANDED
}

/**
 * The size classes of a window, which decide the structure of a screen (one pane or two, a sheet
 * or a side panel). A component inside a screen follows its own constraints instead: a side panel
 * can make the canvas narrow in a wide window.
 *
 * Only the classes are kept, so that the screens reading them change when a class changes, not at
 * every pixel of a window being resized.
 */
@Immutable
public data class WindowLayout(val widthClass: WidthClass, val heightClass: HeightClass) {

    /** Whether the width is compact: one pane, the panels rising from the bottom. */
    val isCompact: Boolean get() = widthClass == WidthClass.COMPACT

    public companion object {
        /** Lower bound of [WidthClass.MEDIUM]. */
        internal val MediumWidth: Dp = 600.dp

        /** Lower bound of [WidthClass.EXPANDED]. */
        internal val ExpandedWidth: Dp = 840.dp

        /** Lower bound of [HeightClass.MEDIUM]. */
        internal val MediumHeight: Dp = 480.dp

        /** Lower bound of [HeightClass.EXPANDED]. */
        internal val ExpandedHeight: Dp = 900.dp

        /** The classes of a window [width] wide and [height] high. */
        public fun of(width: Dp, height: Dp): WindowLayout = WindowLayout(
            widthClass = when {
                width < MediumWidth -> WidthClass.COMPACT
                width < ExpandedWidth -> WidthClass.MEDIUM
                else -> WidthClass.EXPANDED
            },
            heightClass = when {
                height < MediumHeight -> HeightClass.COMPACT
                height < ExpandedHeight -> HeightClass.MEDIUM
                else -> HeightClass.EXPANDED
            }
        )
    }
}

/** The size classes of the window showing the composition, following it when it is resized. */
@Composable
internal fun currentWindowLayout(): WindowLayout {
    val size = LocalWindowInfo.current.containerSize
    return with(LocalDensity.current) { WindowLayout.of(size.width.toDp(), size.height.toDp()) }
}
