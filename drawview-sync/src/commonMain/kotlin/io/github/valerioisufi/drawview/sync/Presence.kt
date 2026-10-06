package io.github.valerioisufi.drawview.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Where a device is on the document [documentId], for the others working on it: who ([replica],
 * [name]), the point ([x], [y]) in the millimeters of the page [pageId] (`null`: on no page), the
 * stroke it is drawing ([stroke], `0`: none) with its inputs ([ink]: x, y, pressure and time of
 * each one, see [INK_VALUES]) and its brush ([inkTool], [inkColor], [inkSize], [inkInput]: the
 * names of a `StrokeTool` and an `InputToolType`).
 *
 * Never stored: a device sends it when it changes and again every few seconds ([sequence] orders
 * the presences of a device; the inputs of a stroke travel a few at a time, see
 * [SyncEngine.updatePresence]); [receivedAt] is when this device received it.
 */
@Serializable
data class Presence(
    val documentId: String,
    val pageId: String? = null,
    val x: Float = 0f,
    val y: Float = 0f,
    val stroke: Long = 0,
    val ink: List<Float> = emptyList(),
    val inkTool: String = "",
    val inkColor: Int = 0,
    val inkSize: Float = 0f,
    val inkInput: String = "",
    val replica: String = "",
    val name: String = "",
    val sequence: Long = 0,
    @Transient val receivedAt: Long = 0
) {
    /** The number of inputs of the stroke being drawn. */
    val inkInputs: Int get() = ink.size / INK_VALUES

    companion object {
        /** The values of an input in [ink]: x, y, pressure, time. */
        const val INK_VALUES = 4
    }
}
