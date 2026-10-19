package io.github.valerioisufi.drawview.explorer.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.uikit.LocalUIViewController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import platform.UIKit.UIViewController
import platform.objc.class_addMethod
import platform.objc.class_replaceMethod
import platform.objc.object_getClass
import platform.objc.sel_registerName

private var isSystemBarsHiddenState: Boolean = false

@OptIn(ExperimentalForeignApi::class)
private val prefersStatusBarHiddenImp = staticCFunction { _: COpaquePointer?, _: COpaquePointer? ->
    isSystemBarsHiddenState
}

@OptIn(ExperimentalForeignApi::class)
private val prefersHomeIndicatorAutoHiddenImp = staticCFunction { _: COpaquePointer?, _: COpaquePointer? ->
    isSystemBarsHiddenState
}

private var overridesInstalled = false

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ensureOverridesInstalled(viewController: UIViewController) {
    if (overridesInstalled) return
    val cls = object_getClass(viewController) ?: return
    val selStatusBar = sel_registerName("prefersStatusBarHidden")
    val selHomeIndicator = sel_registerName("prefersHomeIndicatorAutoHidden")
    val types = "B@:"
    val statusImp = prefersStatusBarHiddenImp.reinterpret<kotlinx.cinterop.CFunction<() -> Unit>>()
    val homeImp = prefersHomeIndicatorAutoHiddenImp.reinterpret<kotlinx.cinterop.CFunction<() -> Unit>>()
    if (!class_addMethod(cls, selStatusBar, statusImp, types)) {
        class_replaceMethod(cls, selStatusBar, statusImp, types)
    }
    if (!class_addMethod(cls, selHomeIndicator, homeImp, types)) {
        class_replaceMethod(cls, selHomeIndicator, homeImp, types)
    }
    overridesInstalled = true
}

@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun SystemBarsHidden(hidden: Boolean) {
    val viewController = LocalUIViewController.current
    DisposableEffect(viewController, hidden) {
        ensureOverridesInstalled(viewController)
        isSystemBarsHiddenState = hidden
        viewController.setNeedsStatusBarAppearanceUpdate()
        val selUpdateHome = sel_registerName("setNeedsUpdateOfHomeIndicatorAutoHidden")
        if (viewController.respondsToSelector(selUpdateHome)) {
            viewController.performSelector(selUpdateHome)
        }
        onDispose {
            if (hidden) {
                isSystemBarsHiddenState = false
                viewController.setNeedsStatusBarAppearanceUpdate()
                if (viewController.respondsToSelector(selUpdateHome)) {
                    viewController.performSelector(selUpdateHome)
                }
            }
        }
    }
}




