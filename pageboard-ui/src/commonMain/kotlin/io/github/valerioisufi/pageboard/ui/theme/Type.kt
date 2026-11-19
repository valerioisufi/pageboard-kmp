package io.github.valerioisufi.pageboard.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.pageboard.ui.generated.resources.Res
import io.github.valerioisufi.pageboard.ui.generated.resources.plus_jakarta_sans_bold
import io.github.valerioisufi.pageboard.ui.generated.resources.plus_jakarta_sans_extrabold
import io.github.valerioisufi.pageboard.ui.generated.resources.plus_jakarta_sans_medium
import io.github.valerioisufi.pageboard.ui.generated.resources.plus_jakarta_sans_regular
import io.github.valerioisufi.pageboard.ui.generated.resources.plus_jakarta_sans_semibold
import org.jetbrains.compose.resources.Font

/**
 * Plus Jakarta Sans, the typeface of the interface (bundled: the downloadable fonts of Google are
 * available on Android only).
 */
@Composable
internal fun plusJakartaSansFontFamily(): FontFamily {
    val regular = Font(Res.font.plus_jakarta_sans_regular, FontWeight.Normal)
    val medium = Font(Res.font.plus_jakarta_sans_medium, FontWeight.Medium)
    val semiBold = Font(Res.font.plus_jakarta_sans_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.plus_jakarta_sans_bold, FontWeight.Bold)
    val extraBold = Font(Res.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold)
    return remember(regular, medium, semiBold, bold, extraBold) { FontFamily(regular, medium, semiBold, bold, extraBold) }
}

/**
 * The type scale of the design: headlines in bold, titles and labels in semibold, the body
 * regular, all in Plus Jakarta Sans.
 */
@Composable
internal fun pageboardTypography(): Typography {
    val family = plusJakartaSansFontFamily()
    return remember(family) { pageboardTypography(family) }
}

/** The type scale of the design in [family]. */
internal fun pageboardTypography(family: FontFamily): Typography {
    fun style(weight: FontWeight, size: Int, lineHeight: Int, tracking: Double = 0.0) = TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.em
    )
    return Typography(
        displayLarge = style(FontWeight.ExtraBold, 57, 64, -0.02),
        displayMedium = style(FontWeight.ExtraBold, 45, 52, -0.015),
        displaySmall = style(FontWeight.ExtraBold, 36, 44, -0.01),
        headlineLarge = style(FontWeight.ExtraBold, 32, 40, -0.01),
        headlineMedium = style(FontWeight.Bold, 26, 32, -0.005),
        headlineSmall = style(FontWeight.Bold, 24, 32),
        titleLarge = style(FontWeight.Bold, 22, 28),
        titleMedium = style(FontWeight.SemiBold, 16, 24),
        titleSmall = style(FontWeight.SemiBold, 14, 20),
        bodyLarge = style(FontWeight.Normal, 16, 24),
        bodyMedium = style(FontWeight.Normal, 14, 20),
        bodySmall = style(FontWeight.Normal, 12, 16),
        labelLarge = style(FontWeight.SemiBold, 14, 20),
        labelMedium = style(FontWeight.SemiBold, 12, 16),
        labelSmall = style(FontWeight.SemiBold, 11, 16, 0.02)
    )
}
