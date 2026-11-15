package io.github.valerioisufi.drawview.ui.theme

import androidx.compose.ui.graphics.Color

// The palette of the design (docs/ui-design.md): a green seed, olive and blue beside it; every role
// of Material 3, so that the containers never take the neutrals of its baseline.

internal val md_theme_light_primary = Color(0xFF3E691A)
internal val md_theme_light_onPrimary = Color(0xFFFFFFFF)
internal val md_theme_light_primaryContainer = Color(0xFFBDF292)
internal val md_theme_light_onPrimaryContainer = Color(0xFF0C2000)
internal val md_theme_light_secondary = Color(0xFF636100)
internal val md_theme_light_onSecondary = Color(0xFFFFFFFF)
internal val md_theme_light_secondaryContainer = Color(0xFFEBE865)
internal val md_theme_light_onSecondaryContainer = Color(0xFF1D1D00)
internal val md_theme_light_tertiary = Color(0xFF0061A6)
internal val md_theme_light_onTertiary = Color(0xFFFFFFFF)
internal val md_theme_light_tertiaryContainer = Color(0xFFD2E4FF)
internal val md_theme_light_onTertiaryContainer = Color(0xFF001C37)
internal val md_theme_light_error = Color(0xFFBA1A1A)
internal val md_theme_light_errorContainer = Color(0xFFFFDAD6)
internal val md_theme_light_onError = Color(0xFFFFFFFF)
internal val md_theme_light_onErrorContainer = Color(0xFF410002)
internal val md_theme_light_background = Color(0xFFFDFDF5)
internal val md_theme_light_onBackground = Color(0xFF1A1C18)
internal val md_theme_light_surface = Color(0xFFFDFDF5)
internal val md_theme_light_onSurface = Color(0xFF1A1C18)
internal val md_theme_light_surfaceVariant = Color(0xFFE0E4D6)
internal val md_theme_light_onSurfaceVariant = Color(0xFF44483E)
internal val md_theme_light_outline = Color(0xFF74796D)
internal val md_theme_light_inverseOnSurface = Color(0xFFF1F1EA)
internal val md_theme_light_inverseSurface = Color(0xFF2F312C)
internal val md_theme_light_inversePrimary = Color(0xFFA2D579)
internal val md_theme_light_surfaceTint = Color(0xFF3E691A)
internal val md_theme_light_outlineVariant = Color(0xFFC4C8BA)
internal val md_theme_light_scrim = Color(0xFF000000)

internal val md_theme_dark_primary = Color(0xFFA2D579)
internal val md_theme_dark_onPrimary = Color(0xFF193800)
internal val md_theme_dark_primaryContainer = Color(0xFF275100)
internal val md_theme_dark_onPrimaryContainer = Color(0xFFBDF292)
internal val md_theme_dark_secondary = Color(0xFFCECC4C)
internal val md_theme_dark_onSecondary = Color(0xFF333200)
internal val md_theme_dark_secondaryContainer = Color(0xFF4A4900)
internal val md_theme_dark_onSecondaryContainer = Color(0xFFEBE865)
internal val md_theme_dark_tertiary = Color(0xFFA0C9FF)
internal val md_theme_dark_onTertiary = Color(0xFF00325A)
internal val md_theme_dark_tertiaryContainer = Color(0xFF00497F)
internal val md_theme_dark_onTertiaryContainer = Color(0xFFD2E4FF)
internal val md_theme_dark_error = Color(0xFFFFB4AB)
internal val md_theme_dark_errorContainer = Color(0xFF93000A)
internal val md_theme_dark_onError = Color(0xFF690005)
internal val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)
internal val md_theme_dark_background = Color(0xFF1A1C18)
internal val md_theme_dark_onBackground = Color(0xFFE3E3DC)
internal val md_theme_dark_surface = Color(0xFF1A1C18)
internal val md_theme_dark_onSurface = Color(0xFFE3E3DC)
internal val md_theme_dark_surfaceVariant = Color(0xFF44483E)
internal val md_theme_dark_onSurfaceVariant = Color(0xFFC4C8BA)
internal val md_theme_dark_outline = Color(0xFF8E9286)
internal val md_theme_dark_inverseOnSurface = Color(0xFF1A1C18)
internal val md_theme_dark_inverseSurface = Color(0xFFE3E3DC)
internal val md_theme_dark_inversePrimary = Color(0xFF3E691A)
internal val md_theme_dark_surfaceTint = Color(0xFFA2D579)
internal val md_theme_dark_outlineVariant = Color(0xFF44483E)
internal val md_theme_dark_scrim = Color(0xFF000000)

// Surfaces: tones of the neutral palette (hue of the seed, chroma 4, as the colors above).
// Containers of dialogs, menus, sheets, cards and bars; without them Material falls back to its
// baseline neutrals, purple beside this palette
internal val md_theme_light_surfaceDim = Color(0xFFDADAD3) // N87
internal val md_theme_light_surfaceBright = Color(0xFFFDFDF5) // N99, as the surface
internal val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF) // N100
internal val md_theme_light_surfaceContainerLow = Color(0xFFF4F4ED) // N96
internal val md_theme_light_surfaceContainer = Color(0xFFEFEEE7) // N94
internal val md_theme_light_surfaceContainerHigh = Color(0xFFE9E8E1) // N92
internal val md_theme_light_surfaceContainerHighest = Color(0xFFE3E3DC) // N90
internal val md_theme_dark_surfaceDim = Color(0xFF121410) // N6
internal val md_theme_dark_surfaceBright = Color(0xFF383A35) // N24
internal val md_theme_dark_surfaceContainerLowest = Color(0xFF0D0F0B) // N4
// The dark containers start four tones above the surface (N10), as the light ones start three tones
// under it: a card on surfaceContainerLow is told apart from the background in both themes
internal val md_theme_dark_surfaceContainerLow = Color(0xFF222420) // N14
internal val md_theme_dark_surfaceContainer = Color(0xFF272925) // N16
internal val md_theme_dark_surfaceContainerHigh = Color(0xFF2F312C) // N20
internal val md_theme_dark_surfaceContainerHighest = Color(0xFF383A35) // N24

// Fixed: the same in the light and dark themes (tones 90, 80, 10, 30 of each palette)
internal val md_theme_primaryFixed = md_theme_light_primaryContainer
internal val md_theme_primaryFixedDim = md_theme_dark_primary
internal val md_theme_onPrimaryFixed = md_theme_light_onPrimaryContainer
internal val md_theme_onPrimaryFixedVariant = md_theme_dark_primaryContainer
internal val md_theme_secondaryFixed = md_theme_light_secondaryContainer
internal val md_theme_secondaryFixedDim = md_theme_dark_secondary
internal val md_theme_onSecondaryFixed = md_theme_light_onSecondaryContainer
internal val md_theme_onSecondaryFixedVariant = md_theme_dark_secondaryContainer
internal val md_theme_tertiaryFixed = md_theme_light_tertiaryContainer
internal val md_theme_tertiaryFixedDim = md_theme_dark_tertiary
internal val md_theme_onTertiaryFixed = md_theme_light_onTertiaryContainer
internal val md_theme_onTertiaryFixedVariant = md_theme_dark_tertiaryContainer

/** The color the dynamic schemes of the theme are made from. */
public val seed: Color = Color(0xFF1A3504)
