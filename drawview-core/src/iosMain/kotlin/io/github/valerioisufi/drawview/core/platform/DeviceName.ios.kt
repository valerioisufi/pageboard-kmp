package io.github.valerioisufi.drawview.core.platform

import platform.UIKit.UIDevice

actual fun localDeviceName(): String = UIDevice.currentDevice.name
