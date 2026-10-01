package io.github.valerioisufi.drawview.data.db

import kotlinx.serialization.Serializable

/**
 * Persisted form of a tool preset (size, color and brush family).
 */
@Serializable
data class BrushSettingsData(
    val sizeMm: Float,
    val color: Int,
    val family: String
)

/** Wall-clock time in milliseconds since the Unix epoch. */
internal fun currentTimeMillis(): Long = io.github.valerioisufi.drawview.core.util.currentTimeMillis()
