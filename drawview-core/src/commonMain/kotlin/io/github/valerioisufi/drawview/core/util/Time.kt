package io.github.valerioisufi.drawview.core.util

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Wall-clock time in milliseconds since the Unix epoch.
 */
@OptIn(ExperimentalTime::class)
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
