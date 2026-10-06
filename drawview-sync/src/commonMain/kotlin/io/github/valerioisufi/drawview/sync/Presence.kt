package io.github.valerioisufi.drawview.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Where a device is on the document [documentId], for the others working on it: who ([replica],
 * [name]), the point ([x], [y]) in the millimeters of the page [pageId] (`null`: on no page), the
 * stroke being drawn ([ink]: the x and y of its inputs, with [inkColor] and [inkSize]).
 *
 * Never stored: a device sends it when it changes and again every few seconds ([sequence] orders
 * the presences of a device); [receivedAt] is when this device received it.
 */
@Serializable
data class Presence(
    val documentId: String,
    val pageId: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val ink: List<Float> = emptyList(),
    val inkColor: Int = 0,
    val inkSize: Float = 0f,
    val replica: String = "",
    val name: String = "",
    val sequence: Long = 0,
    @Transient val receivedAt: Long = 0
)
