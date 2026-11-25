package io.github.valerioisufi.pageboard.model.shape

import kotlinx.serialization.Serializable

/**
 * Live in-progress shape being drawn or resized on [pageId] (millimeters of the page), streamed
 * via collaborator presence before commit.
 */
@Serializable
public data class ShapeDraftActivity(
    public val pageId: String,
    public val shape: ShapeElement,
    public val x: Float,
    public val y: Float,
    public val width: Float,
    public val height: Float,
    public val rotation: Float = 0f
) {
    /** Validates coordinates, dimensions, and identifiers received from an untrusted peer. */
    public fun isValid(): Boolean =
        pageId.isNotBlank() &&
            pageId.length <= MAX_PAGE_ID_LENGTH &&
            x.isFinite() && y.isFinite() &&
            width.isFinite() && height.isFinite() &&
            rotation.isFinite() &&
            x in -MAX_COORD_MM..MAX_COORD_MM &&
            y in -MAX_COORD_MM..MAX_COORD_MM &&
            width in MIN_EXTENT_MM..MAX_EXTENT_MM &&
            height in MIN_EXTENT_MM..MAX_EXTENT_MM

    public companion object {
        private const val MAX_PAGE_ID_LENGTH = 128
        private const val MIN_EXTENT_MM = 0.01f
        private const val MAX_EXTENT_MM = 10_000f
        private const val MAX_COORD_MM = 100_000f
    }
}
