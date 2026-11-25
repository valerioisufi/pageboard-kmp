package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.PageLayer.Companion.MAX_NAME_LENGTH
import kotlin.concurrent.Volatile
import kotlinx.serialization.Serializable

/**
 * A named, ordered organizational layer belonging to a single [Page]: controls visibility and
 * lock protection for all elements whose [PageElement.layerId] references [id].
 */
public class PageLayer(
    public val id: String = newElementId(),
    name: String = "",
    orderKey: String = "",
    visible: Boolean = true,
    locked: Boolean = false
) {
    @Volatile public var name: String = name.take(MAX_NAME_LENGTH)
    @Volatile public var orderKey: String = orderKey
    @Volatile public var visible: Boolean = visible
    @Volatile public var locked: Boolean = locked

    public var added: Stamp = Stamp.ZERO
    public var removed: Stamp? = null
    public var updated: Stamp = Stamp.ZERO

    public val isRemoved: Boolean get() = removed != null

    public fun toData(): PageLayerData = PageLayerData(
        id = id,
        name = name,
        orderKey = orderKey,
        visible = visible,
        locked = locked,
        added = added,
        removed = removed,
        updated = updated
    )

    public companion object {
        /** Maximum number of live layers allowed per page. */
        public const val MAX_LAYERS_PER_PAGE: Int = 64

        /** Maximum character length of a layer name. */
        public const val MAX_NAME_LENGTH: Int = 80

        /** Stacking order of layers on a page from bottom to top. */
        public val order: Comparator<PageLayer> = compareBy({ it.orderKey }, { it.id })
    }
}

/**
 * Serializable wire/snapshot representation of a [PageLayer].
 */
@Serializable
public data class PageLayerData(
    val id: String,
    val name: String,
    val orderKey: String,
    val visible: Boolean = true,
    val locked: Boolean = false,
    val added: Stamp = Stamp.ZERO,
    val removed: Stamp? = null,
    val updated: Stamp = Stamp.ZERO
) {
    /** Validates bounds for untrusted data received over sync. */
    public fun isValid(): Boolean =
        id.isNotBlank() &&
            id.length <= MAX_ID_LENGTH &&
            name.length <= MAX_NAME_LENGTH &&
            FractionalIndex.isValidKey(orderKey) &&
            orderKey.length <= MAX_ORDER_KEY_LENGTH

    public fun toLayer(): PageLayer = PageLayer(
        id = id,
        name = name.take(MAX_NAME_LENGTH),
        orderKey = orderKey,
        visible = visible,
        locked = locked
    ).also {
        it.added = added
        it.removed = removed
        it.updated = updated
    }

    private companion object {
        const val MAX_ID_LENGTH = 128
        const val MAX_ORDER_KEY_LENGTH = 256
    }
}
