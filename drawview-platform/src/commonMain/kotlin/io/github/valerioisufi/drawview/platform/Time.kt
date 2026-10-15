package io.github.valerioisufi.drawview.platform

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Wall-clock time in milliseconds since the Unix epoch.
 */
@OptIn(ExperimentalTime::class)
public fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
