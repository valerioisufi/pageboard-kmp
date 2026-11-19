package io.github.valerioisufi.pageboard.platform

import android.os.Build
import android.provider.Settings

internal actual fun localDeviceName(): String =
    Settings.Global.getString(DrawViewAndroid.context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
