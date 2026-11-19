package io.github.valerioisufi.pageboard.model

import io.github.valerioisufi.pageboard.model.geometry.AffineTransform
import kotlinx.serialization.Serializable

/**
 * The elements [ids] of the page [pageId] selected, moved by [transform] (page millimeters;
 * [AffineTransform.IDENTITY] while they are not being moved).
 */
@Serializable
public data class SelectionActivity(
    val pageId: String,
    val ids: List<String>,
    val transform: AffineTransform = AffineTransform.IDENTITY
) {
    val isMoving: Boolean get() = transform != AffineTransform.IDENTITY
}
