package io.github.valerioisufi.drawview.sync.ui

import androidx.compose.runtime.Composable
import io.github.valerioisufi.drawview.sync.lan.BonjourLanDiscovery
import io.github.valerioisufi.drawview.sync.lan.LanDiscovery
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationState
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIBackgroundTaskIdentifier
import platform.UIKit.UIBackgroundTaskInvalid
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

internal actual fun createLanDiscovery(): LanDiscovery = BonjourLanDiscovery()

// iOS asks for the permission of the local network itself, the first time it is used
@Composable
internal actual fun rememberLocalNetworkAccess(): suspend () -> Boolean = { true }

/**
 * Asks iOS for the time to go on once the application is in the background while connected to
 * other devices (until they are gone, or the system takes it back: about half a minute).
 */
internal actual object BackgroundSync {
    private var active = false
    private var task: UIBackgroundTaskIdentifier = UIBackgroundTaskInvalid
    private var observing = false

    actual fun update(active: Boolean, documents: Int) {
        dispatch_async(dispatch_get_main_queue()) {
            this.active = active
            if (!observing) {
                observing = true
                NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
                    if (this.active) begin()
                }
                NSNotificationCenter.defaultCenter.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ -> end() }
            }
            if (!active) end() else if (UIApplication.sharedApplication.applicationState == UIApplicationState.UIApplicationStateBackground) begin()
        }
    }

    private fun begin() {
        if (task != UIBackgroundTaskInvalid) return
        task = UIApplication.sharedApplication.beginBackgroundTaskWithName("pageboard-sync") { end() }
    }

    private fun end() {
        if (task == UIBackgroundTaskInvalid) return
        UIApplication.sharedApplication.endBackgroundTask(task)
        task = UIBackgroundTaskInvalid
    }
}
