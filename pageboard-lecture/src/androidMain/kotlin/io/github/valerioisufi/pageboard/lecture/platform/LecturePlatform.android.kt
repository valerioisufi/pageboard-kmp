package io.github.valerioisufi.pageboard.lecture.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import io.github.valerioisufi.pageboard.platform.PageboardAndroid

public actual val isComputer: Boolean = false

internal actual fun environmentApiKey(): String? = null

// The files of the application are private to it
internal actual fun restrictToOwner(path: String) {}

internal actual fun showNotification(channel: String, title: String, text: String) {
    val context = PageboardAndroid.context
    // Without the permission (Android 13+), nothing is shown
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(ARRIVALS_CHANNEL, channel, NotificationManager.IMPORTANCE_DEFAULT))
    val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
        PendingIntent.getActivity(context, 1, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    val notification = Notification.Builder(context, ARRIVALS_CHANNEL)
        .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
        .setContentTitle(title)
        .setContentText(text)
        .setAutoCancel(true)
        .setContentIntent(open)
        .build()
    manager.notify((title + text).hashCode(), notification)
}

private const val ARRIVALS_CHANNEL = "lecture-arrivals"
