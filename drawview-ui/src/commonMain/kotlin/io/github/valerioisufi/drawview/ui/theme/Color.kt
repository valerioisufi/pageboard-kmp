package io.github.valerioisufi.drawview.ui.theme

import androidx.compose.ui.graphics.Color

// The palette of the design (docs/ui-design.md): a green seed, olive and blue beside it; every role
// of Material 3, so that the containers never take the neutrals of its baseline.

val md_theme_light_primary = Color(0xFF3E691A)
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFBDF292)
val md_theme_light_onPrimaryContainer = Color(0xFF0C2000)
val md_theme_light_secondary = Color(0xFF636100)
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFEBE865)
val md_theme_light_onSecondaryContainer = Color(0xFF1D1D00)
val md_theme_light_tertiary = Color(0xFF0061A6)
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFD2E4FF)
val md_theme_light_onTertiaryContainer = Color(0xFF001C37)
val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_onErrorContainer = Color(0xFF410002)
val md_theme_light_background = Color(0xFFFDFDF5)
val md_theme_light_onBackground = Color(0xFF1A1C18)
val md_theme_light_surface = Color(0xFFFDFDF5)
val md_theme_light_onSurface = Color(0xFF1A1C18)
val md_theme_light_surfaceVariant = Color(0xFFE0E4D6)
val md_theme_light_onSurfaceVariant = Color(0xFF44483E)
val md_theme_light_outline = Color(0xFF74796D)
val md_theme_light_inverseOnSurface = Color(0xFFF1F1EA)
val md_theme_light_inverseSurface = Color(0xFF2F312C)
val md_theme_light_inversePrimary = Color(0xFFA2D579)
val md_theme_light_shadow = Color(0xFF000000)
val md_theme_light_surfaceTint = Color(0xFF3E691A)
val md_theme_light_outlineVariant = Color(0xFFC4C8BA)
val md_theme_light_scrim = Color(0xFF000000)

val md_theme_dark_primary = Color(0xFFA2D579)
val md_theme_dark_onPrimary = Color(0xFF193800)
val md_theme_dark_primaryContainer = Color(0xFF275100)
val md_theme_dark_onPrimaryContainer = Color(0xFFBDF292)
val md_theme_dark_secondary = Color(0xFFCECC4C)
val md_theme_dark_onSecondary = Color(0xFF333200)
val md_theme_dark_secondaryContainer = Color(0xFF4A4900)
val md_theme_dark_onSecondaryContainer = Color(0xFFEBE865)
val md_theme_dark_tertiary = Color(0xFFA0C9FF)
val md_theme_dark_onTertiary = Color(0xFF00325A)
val md_theme_dark_tertiaryContainer = Color(0xFF00497F)
val md_theme_dark_onTertiaryContainer = Color(0xFFD2E4FF)
val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
val md_theme_dark_background = Color(0xFF1A1C18)
val md_theme_dark_onBackground = Color(0xFFE3E3DC)
val md_theme_dark_surface = Color(0xFF1A1C18)
val md_theme_dark_onSurface = Color(0xFFE3E3DC)
val md_theme_dark_surfaceVariant = Color(0xFF44483E)
val md_theme_dark_onSurfaceVariant = Color(0xFFC4C8BA)
val md_theme_dark_outline = Color(0xFF8E9286)
val md_theme_dark_inverseOnSurface = Color(0xFF1A1C18)
val md_theme_dark_inverseSurface = Color(0xFFE3E3DC)
val md_theme_dark_inversePrimary = Color(0xFF3E691A)
val md_theme_dark_shadow = Color(0xFF000000)
val md_theme_dark_surfaceTint = Color(0xFFA2D579)
val md_theme_dark_outlineVariant = Color(0xFF44483E)
val md_theme_dark_scrim = Color(0xFF000000)

// Surfaces: tones of the neutral palette (hue of the seed, chroma 4, as the colors above).
// Containers of dialogs, menus, sheets, cards and bars; without them Material falls back to its
// baseline neutrals, purple beside this palette
val md_theme_light_surfaceDim = Color(0xFFDADAD3) // N87
val md_theme_light_surfaceBright = Color(0xFFFDFDF5) // N99, as the surface
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF) // N100
val md_theme_light_surfaceContainerLow = Color(0xFFF4F4ED) // N96
val md_theme_light_surfaceContainer = Color(0xFFEFEEE7) // N94
val md_theme_light_surfaceContainerHigh = Color(0xFFE9E8E1) // N92
val md_theme_light_surfaceContainerHighest = Color(0xFFE3E3DC) // N90
val md_theme_dark_surfaceDim = Color(0xFF121410) // N6
val md_theme_dark_surfaceBright = Color(0xFF383A35) // N24
val md_theme_dark_surfaceContainerLowest = Color(0xFF0D0F0B) // N4
val md_theme_dark_surfaceContainerLow = Color(0xFF1A1C18) // N10, as the surface
val md_theme_dark_surfaceContainer = Color(0xFF1E201C) // N12
val md_theme_dark_surfaceContainerHigh = Color(0xFF292B26) // N17
val md_theme_dark_surfaceContainerHighest = Color(0xFF343531) // N22

// Fixed: the same in the light and dark themes (tones 90, 80, 10, 30 of each palette)
val md_theme_primaryFixed = md_theme_light_primaryContainer
val md_theme_primaryFixedDim = md_theme_dark_primary
val md_theme_onPrimaryFixed = md_theme_light_onPrimaryContainer
val md_theme_onPrimaryFixedVariant = md_theme_dark_primaryContainer
val md_theme_secondaryFixed = md_theme_light_secondaryContainer
val md_theme_secondaryFixedDim = md_theme_dark_secondary
val md_theme_onSecondaryFixed = md_theme_light_onSecondaryContainer
val md_theme_onSecondaryFixedVariant = md_theme_dark_secondaryContainer
val md_theme_tertiaryFixed = md_theme_light_tertiaryContainer
val md_theme_tertiaryFixedDim = md_theme_dark_tertiary
val md_theme_onTertiaryFixed = md_theme_light_onTertiaryContainer
val md_theme_onTertiaryFixedVariant = md_theme_dark_tertiaryContainer

val seed = Color(0xFF1A3504)
