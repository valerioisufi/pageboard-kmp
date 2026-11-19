package io.github.valerioisufi.pageboard.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** The radii of the design, from the small marks to the sheets. */
internal object PageboardRadius {
    /** The thumbnails, the marks. */
    val ExtraSmall = 4.dp

    /** The hints of the shortcuts, the small chips. */
    val Small = 8.dp

    /** The items of the menus. */
    val Medium = 12.dp

    /** The tool buttons, the wells of the thumbnails. */
    val Large = 16.dp

    /** The sheets, the dialogs. */
    val ExtraLarge3 = 28.dp
}

/** The shapes of Material with the radii of the design. */
internal val PageboardShapes = Shapes(
    extraSmall = RoundedCornerShape(PageboardRadius.ExtraSmall),
    small = RoundedCornerShape(PageboardRadius.Small),
    medium = RoundedCornerShape(PageboardRadius.Medium),
    large = RoundedCornerShape(PageboardRadius.Large),
    extraLarge = RoundedCornerShape(PageboardRadius.ExtraLarge3)
)
