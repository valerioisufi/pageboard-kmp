package io.github.valerioisufi.drawview.sync

import io.github.valerioisufi.drawview.model.SelectionActivity
import io.github.valerioisufi.drawview.model.change.TextData
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Where a device is on the document [documentId], for the others working on it: who ([replica],
 * [name]), the point ([x], [y]) in the millimeters of the page [pageId] (`null`: on no page), the
 * stroke it is drawing ([stroke], `0`: none) with its inputs ([ink]: x, y, pressure and time of
 * each one, see [INK_VALUES]) and its brush ([inkTool], [inkColor], [inkSize], [inkInput]: the
 * names of a `StrokeTool` and an `InputToolType`; [inkCancelled]: the stroke it was drawing was
 * given up, not added to the document), the text it is writing ([text], on the page
 * [textPageId]), the elements it selected and how it is moving them ([selection]).
 *
 * Never stored: a device sends it when it changes and again every few seconds ([sequence] orders
 * the presences of a device; the inputs of a stroke travel a few at a time, the text and the
 * selection when they change, see [SyncEngine.updatePresence]); [receivedAt] is when this device
 * received it.
 */
@Serializable
public data class Presence(
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
    val inkCancelled: Boolean = false,
    val text: TextData? = null,
    val textPageId: String? = null,
    val selection: SelectionActivity? = null,
    val replica: String = "",
    val name: String = "",
    val sequence: Long = 0,
    @Transient val receivedAt: Long = 0
) {
    /** The number of inputs of the stroke being drawn. */
    val inkInputs: Int get() = ink.size / INK_VALUES

    public companion object {
        /** The values of an input in [ink]: x, y, pressure, time. */
        public const val INK_VALUES: Int = 4
    }
}
