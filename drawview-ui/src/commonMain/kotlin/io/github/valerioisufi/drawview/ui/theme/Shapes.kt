package io.github.valerioisufi.drawview.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** The radii of the design, from the small marks to the sheets. */
object DrawViewRadius {
    /** The thumbnails, the marks. */
    val ExtraSmall = 4.dp

    /** The hints of the shortcuts, the small chips. */
    val Small = 8.dp

    /** The items of the menus. */
    val Medium = 12.dp

    /** The tool buttons, the wells of the thumbnails. */
    val Large = 16.dp

    /** The cards of the folders, the floating action button, the previews. */
    val ExtraLarge = 20.dp

    /** The cards of the notes, the popovers, the dialogs of the tools. */
    val ExtraLarge2 = 24.dp

    /** The sheets, the dialogs. */
    val ExtraLarge3 = 28.dp
}

/** The shapes of Material with the radii of the design. */
val DrawViewShapes = Shapes(
    extraSmall = RoundedCornerShape(DrawViewRadius.ExtraSmall),
    small = RoundedCornerShape(DrawViewRadius.Small),
    medium = RoundedCornerShape(DrawViewRadius.Medium),
    large = RoundedCornerShape(DrawViewRadius.Large),
    extraLarge = RoundedCornerShape(DrawViewRadius.ExtraLarge3)
)
