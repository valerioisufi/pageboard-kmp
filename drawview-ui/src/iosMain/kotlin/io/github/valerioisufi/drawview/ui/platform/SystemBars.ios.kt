package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import platform.UIKit.UIViewController
import platform.objc.class_addMethod
import platform.objc.class_replaceMethod
import platform.objc.object_getClass
import platform.objc.sel_registerName

/** The tallest top of the safe area with the status bar and no notch or island (an iPad: 24 pt). */
private const val StatusBarOnlyHeight = 24

/** Whether the status bar is hidden, answered to UIKit by the view controller of the application. */
private var statusBarHidden: Boolean = false

/** Whether the home indicator hides itself, answered to UIKit by the view controller of the application. */
private var homeIndicatorHidden: Boolean = false

@OptIn(ExperimentalForeignApi::class)
private val prefersStatusBarHiddenImp = staticCFunction { _: COpaquePointer?, _: COpaquePointer? -> statusBarHidden }

@OptIn(ExperimentalForeignApi::class)
private val prefersHomeIndicatorAutoHiddenImp = staticCFunction { _: COpaquePointer?, _: COpaquePointer? -> homeIndicatorHidden }

private var overridesInstalled = false

/** Makes the view controller of Compose answer UIKit with the two flags above (once). */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun installOverrides(viewController: UIViewController) {
    if (overridesInstalled) return
    val cls = object_getClass(viewController) ?: return
    val types = "B@:"
    for ((name, imp) in listOf("prefersStatusBarHidden" to prefersStatusBarHiddenImp, "prefersHomeIndicatorAutoHidden" to prefersHomeIndicatorAutoHiddenImp)) {
        val selector = sel_registerName(name)
        val function = imp.reinterpret<CFunction<() -> Unit>>()
        if (!class_addMethod(cls, selector, function, types)) class_replaceMethod(cls, selector, function, types)
    }
    overridesInstalled = true
}

@Composable
internal actual fun ImmersiveSystemBarsEffect() {
    val viewController = LocalUIViewController.current
    // With a notch or the Dynamic Island the safe area keeps its height without the status bar:
    // it stays, with its clock. Without them, the top is the status bar (or nothing, hidden)
    val density = LocalDensity.current
    val islandOnTop = WindowInsets.safeDrawing.getTop(density) > with(density) { StatusBarOnlyHeight.dp.roundToPx() }
    DisposableEffect(viewController, islandOnTop) {
        installOverrides(viewController)
        statusBarHidden = !islandOnTop
        homeIndicatorHidden = true
        viewController.setNeedsStatusBarAppearanceUpdate()
        onDispose {
            statusBarHidden = false
            homeIndicatorHidden = false
            viewController.setNeedsStatusBarAppearanceUpdate()
        }
    }
}
