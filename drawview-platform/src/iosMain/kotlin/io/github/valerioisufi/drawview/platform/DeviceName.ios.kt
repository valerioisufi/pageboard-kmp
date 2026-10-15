package io.github.valerioisufi.drawview.platform

import platform.UIKit.UIDevice

internal actual fun localDeviceName(): String = UIDevice.currentDevice.name
