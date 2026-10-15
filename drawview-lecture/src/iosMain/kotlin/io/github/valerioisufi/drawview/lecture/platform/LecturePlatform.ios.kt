package io.github.valerioisufi.drawview.lecture.platform

import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

public actual val isComputer: Boolean = false

internal actual fun environmentApiKey(): String? = null

// The files of the application are private to it
internal actual fun restrictToOwner(path: String) {}

/** A notification of iOS (the permission asked the first time). */
internal actual fun showNotification(channel: String, title: String, text: String) {
    val center = UNUserNotificationCenter.currentNotificationCenter()
    center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, _ ->
        if (!granted) return@requestAuthorizationWithOptions
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(text)
            setThreadIdentifier(channel)
        }
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(NSUUID().UUIDString, content, null), null)
    }
}
