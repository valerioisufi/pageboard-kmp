package io.github.valerioisufi.drawview.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * What the interface adapts to: the size classes of the window, what the user has at hand and the
 * conventions of the platform. The screens read it through [currentAdaptiveInfo] and never ask
 * which platform they run on.
 */
@Stable
data class AdaptiveInfo(
    val window: WindowLayout,
    val input: InputCapabilities,
    val conventions: PlatformConventions
)

/** The [AdaptiveInfo] given by the nearest [AdaptiveRoot], `null` outside of one. */
val LocalAdaptiveInfo = compositionLocalOf<AdaptiveInfo?> { null }

/**
 * The [AdaptiveInfo] of the composition. Outside of an [AdaptiveRoot] the input is only what the
 * platform tells, without the events.
 */
@Composable
fun currentAdaptiveInfo(): AdaptiveInfo {
    LocalAdaptiveInfo.current?.let { return it }
    val hints = platformInputHints()
    val input = remember { InputCapabilities(hints) }
    return AdaptiveInfo(currentWindowLayout(), input, platformConventions)
}

/**
 * The root of an adaptive interface: gives [AdaptiveInfo] to [content], learns the input from its
 * events and sizes the controls for it (the minimum interactive size of Material). Inside another
 * root it only shows [content], so that a library and the application around it can both declare
 * one.
 *
 * @param hints what the platform tells of the input; the screenshot tests give the hints of the
 *   device they draw.
 * @param conventions the conventions of the platform; the screenshot tests give the same on every
 *   system.
 */
@Composable
fun AdaptiveRoot(
    modifier: Modifier = Modifier,
    hints: InputHints = platformInputHints(),
    conventions: PlatformConventions = platformConventions,
    content: @Composable () -> Unit
) {
    if (LocalAdaptiveInfo.current != null) {
        Box(modifier = modifier, propagateMinConstraints = true) { content() }
        return
    }
    val input = remember { InputCapabilities(hints) }
    LaunchedEffect(hints) { input.learn(hints) }
    val info = AdaptiveInfo(currentWindowLayout(), input, conventions)
    CompositionLocalProvider(
        LocalAdaptiveInfo provides info,
        LocalMinimumInteractiveComponentSize provides input.minimumInteractiveSize
    ) {
        Box(modifier = modifier.observeInput(input), propagateMinConstraints = true) { content() }
    }
}
