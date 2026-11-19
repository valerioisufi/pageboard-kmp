package io.github.valerioisufi.pageboard.ui.platform

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
import kotlinx.cinterop.toKString
import platform.UIKit.UIDevice
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.UIKit.UIViewController
import platform.objc.class_addMethod
import platform.objc.class_replaceMethod
import platform.objc.object_getClass
import platform.objc.sel_registerName

/** The tallest top of the safe area of an iPhone with the status bar and no notch or island (20 pt). */
private const val StatusBarOnlyHeight = 24

/** Whether the status bar is hidden, answered to UIKit by the view controllers of the application. */
private var statusBarHidden: Boolean = false

/** Whether the home indicator hides itself, answered to UIKit by the view controller of Compose. */
private var homeIndicatorHidden: Boolean = false

@OptIn(ExperimentalForeignApi::class)
private val prefersStatusBarHiddenImp = staticCFunction { _: COpaquePointer?, _: COpaquePointer? -> statusBarHidden }

@OptIn(ExperimentalForeignApi::class)
private val prefersHomeIndicatorAutoHiddenImp = staticCFunction { _: COpaquePointer?, _: COpaquePointer? -> homeIndicatorHidden }

/** The classes of view controllers answering UIKit with the flags above. */
private val classesWithOverrides = mutableSetOf<String>()

/**
 * Makes the view controller of Compose answer UIKit with the two flags above, and those holding it
 * (a `UIHostingController` of SwiftUI, asked by UIKit for the status bar) with the status bar
 * flag: once per class. The home indicator of those holding it stays as the application set it
 * (e.g. `persistentSystemOverlays`).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun installOverrides(viewController: UIViewController) {
    var current: UIViewController? = viewController
    while (current != null) {
        val cls = object_getClass(current)
        val name = cls?.let { platform.objc.class_getName(it)?.toKString() }
        if (cls != null && name != null && classesWithOverrides.add(name)) {
            val overrides = buildList {
                add("prefersStatusBarHidden" to prefersStatusBarHiddenImp)
                if (current === viewController) add("prefersHomeIndicatorAutoHidden" to prefersHomeIndicatorAutoHiddenImp)
            }
            for ((selectorName, imp) in overrides) {
                val selector = sel_registerName(selectorName)
                val function = imp.reinterpret<CFunction<() -> Unit>>()
                if (!class_addMethod(cls, selector, function, "B@:")) class_replaceMethod(cls, selector, function, "B@:")
            }
        }
        current = current.parentViewController
    }
}

/** The view controller holding [viewController] at the root, asked by UIKit for the status bar. */
private fun rootOf(viewController: UIViewController): UIViewController {
    var root = viewController
    while (true) root = root.parentViewController ?: return root
}

@Composable
internal actual fun immersiveSystemBars(): HiddenSystemBars {
    val viewController = LocalUIViewController.current
    // An iPhone with a notch or the Dynamic Island keeps the height of the safe area without the
    // status bar: it stays, with its clock. No iPad has them (its status bar is 24 to 32 pt)
    val density = LocalDensity.current
    val islandOnTop = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone &&
        WindowInsets.safeDrawing.getTop(density) > with(density) { StatusBarOnlyHeight.dp.roundToPx() }
    DisposableEffect(viewController, islandOnTop) {
        installOverrides(viewController)
        statusBarHidden = !islandOnTop
        homeIndicatorHidden = true
        rootOf(viewController).setNeedsStatusBarAppearanceUpdate()
        onDispose {
            statusBarHidden = false
            homeIndicatorHidden = false
            rootOf(viewController).setNeedsStatusBarAppearanceUpdate()
        }
    }
    return if (islandOnTop) HiddenSystemBars.NAVIGATION else HiddenSystemBars.ALL
}

/** The windows over the editor take the status bar of its view controller: nothing to keep. */
@Composable
internal actual fun PopupSystemBarsEffect() {
}

@Composable
internal actual fun popupTakesFocus(): Boolean = true
