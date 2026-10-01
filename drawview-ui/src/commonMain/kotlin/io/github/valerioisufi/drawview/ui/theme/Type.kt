package io.github.valerioisufi.drawview.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.valerioisufi.drawview.ui.generated.resources.Res
import io.github.valerioisufi.drawview.ui.generated.resources.nunito_bold
import io.github.valerioisufi.drawview.ui.generated.resources.nunito_regular
import io.github.valerioisufi.drawview.ui.generated.resources.nunito_semibold
import org.jetbrains.compose.resources.Font

/**
 * The Nunito font family of the original app (bundled: Google downloadable fonts are available
 * on Android only).
 */
@Composable
fun nunitoFontFamily(): FontFamily {
    val regular = Font(Res.font.nunito_regular, FontWeight.Normal)
    // Nunito Medium is not bundled: the regular file is the closest match
    val medium = Font(Res.font.nunito_regular, FontWeight.Medium)
    val semiBold = Font(Res.font.nunito_semibold, FontWeight.SemiBold)
    val bold = Font(Res.font.nunito_bold, FontWeight.Bold)
    return remember(regular, medium, semiBold, bold) { FontFamily(regular, medium, semiBold, bold) }
}

@Composable
fun drawViewTypography(): Typography {
    val nunito = nunitoFontFamily()
    return remember(nunito) {
        Typography(
            headlineLarge = TextStyle(
                fontFamily = nunito,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp
            ),
            titleLarge = TextStyle(
                fontFamily = nunito,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.15.sp
            ),
            titleMedium = TextStyle(
                fontFamily = nunito,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp
            ),
            bodyLarge = TextStyle(
                fontFamily = nunito,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.5.sp
            ),
            bodyMedium = TextStyle(
                fontFamily = nunito,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp
            ),
            labelMedium = TextStyle(
                fontFamily = nunito,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp
            )
        )
    }
}
