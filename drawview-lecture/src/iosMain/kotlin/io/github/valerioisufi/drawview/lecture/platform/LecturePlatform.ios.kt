package io.github.valerioisufi.drawview.lecture.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioApplication
import platform.AVFAudio.AVAudioApplicationRecordPermissionDenied
import platform.AVFAudio.AVAudioApplicationRecordPermissionGranted
import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter

internal actual val isComputer: Boolean = false

@Composable
internal actual fun rememberMicrophoneAccess(): suspend () -> Boolean = remember {
    suspend {
        when (AVAudioApplication.sharedInstance().recordPermission) {
            AVAudioApplicationRecordPermissionGranted -> true
            AVAudioApplicationRecordPermissionDenied -> false
            else -> suspendCancellableCoroutine { continuation ->
                AVAudioApplication.requestRecordPermissionWithCompletionHandler { granted -> continuation.resume(granted) }
            }
        }
    }
}

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
