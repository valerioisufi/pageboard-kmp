package io.github.valerioisufi.pageboard.canvas.camera

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a document is looked at: a point of one of its pages at the center of the canvas, and the
 * zoom (see [Viewport.currentPlace] and [Viewport.showPlace]). Written as text ([encode]) by
 * whoever keeps it, e.g. to open a document where it was left.
 */
@Serializable
public data class DocumentPlace(
    /** The id of the page. */
    val pageId: String,
    /** The number of the page (1-based), used when no page has [pageId] any more. */
    val pageNumber: Int,
    /** The point of the page at the center of the canvas, as a fraction (`0..1`) of its width. */
    val x: Float,
    /** The point of the page at the center of the canvas, as a fraction (`0..1`) of its height. */
    val y: Float,
    /** The zoom of the camera: 1 when the pages are as wide as the canvas. */
    val scale: Float,
    /** The version of the format of this text. */
    @SerialName("v") val version: Int = FORMAT_VERSION
) {
    /** This place as text (JSON), read again by [decode]. */
    public fun encode(): String = json.encodeToString(serializer(), this)

    public companion object {
        /** The version of the format [encode] writes. */
        public const val FORMAT_VERSION: Int = 1

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * The place written by [encode], or `null` when [text] cannot be read: another version, a
         * value missing or out of its range (the point is clamped to the page).
         */
        public fun decode(text: String): DocumentPlace? {
            val place = try {
                json.decodeFromString(serializer(), text)
            } catch (_: Exception) {
                return null
            }
            if (place.version != FORMAT_VERSION) return null
            if (place.pageNumber < 1) return null
            if (!place.x.isFinite() || !place.y.isFinite()) return null
            if (!place.scale.isFinite() || place.scale <= 0f) return null
            return place.copy(x = place.x.coerceIn(0f, 1f), y = place.y.coerceIn(0f, 1f))
        }
    }
}
