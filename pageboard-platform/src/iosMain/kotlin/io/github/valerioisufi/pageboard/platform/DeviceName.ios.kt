package io.github.valerioisufi.pageboard.platform

import platform.UIKit.UIDevice

internal actual fun localDeviceName(): String = UIDevice.currentDevice.name
