package io.github.valerioisufi.drawview.platform

import platform.UIKit.UIDevice

actual fun localDeviceName(): String = UIDevice.currentDevice.name
