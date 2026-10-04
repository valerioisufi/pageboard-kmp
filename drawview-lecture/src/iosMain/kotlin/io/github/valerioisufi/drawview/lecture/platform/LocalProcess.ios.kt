package io.github.valerioisufi.drawview.lecture.platform

// The application cannot start programs on this platform
actual fun startLocalProcess(command: List<String>): LocalProcess? = null
