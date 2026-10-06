package io.github.valerioisufi.drawview.core.platform

import android.os.Build
import android.provider.Settings

actual fun localDeviceName(): String =
    Settings.Global.getString(DrawViewAndroid.context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
        ?: Build.MODEL
