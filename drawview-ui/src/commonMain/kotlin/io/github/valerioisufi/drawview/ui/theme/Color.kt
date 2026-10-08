package io.github.valerioisufi.drawview.ui.theme

import androidx.compose.ui.graphics.Color

// --- LIGHT THEME ---
val md_theme_light_primary = Color(0xFF00639B) // Blu Oceano
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFCEE5FF)
val md_theme_light_onPrimaryContainer = Color(0xFF001D33)

val md_theme_light_secondary = Color(0xFF526070) // Grigio Ardesia
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFD5E4F7)
val md_theme_light_onSecondaryContainer = Color(0xFF0E1D2A)

val md_theme_light_tertiary = Color(0xFF97484B) // Corallo scuro
val md_theme_light_onTertiary = Color(0xFFFFFFFF)
val md_theme_light_tertiaryContainer = Color(0xFFFFDAD8)
val md_theme_light_onTertiaryContainer = Color(0xFF3E000A)

val md_theme_light_error = Color(0xFFBA1A1A)
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onError = Color(0xFFFFFFFF)
val md_theme_light_onErrorContainer = Color(0xFF410002)

val md_theme_light_background = Color(0xFFFCFCFF) // Bianco ghiaccio
val md_theme_light_onBackground = Color(0xFF1A1C1E)
val md_theme_light_surface = Color(0xFFFCFCFF)
val md_theme_light_onSurface = Color(0xFF1A1C1E)
val md_theme_light_surfaceVariant = Color(0xFFDFE2E6)
val md_theme_light_onSurfaceVariant = Color(0xFF42474E)
val md_theme_light_outline = Color(0xFF73777F)
val md_theme_light_inverseOnSurface = Color(0xFFF1F0F4)
val md_theme_light_inverseSurface = Color(0xFF2F3033)
val md_theme_light_inversePrimary = Color(0xFF97CBFF)
val md_theme_light_shadow = Color(0xFF000000)
val md_theme_light_surfaceTint = Color(0xFF00639B)
val md_theme_light_outlineVariant = Color(0xFFC3C7CF)
val md_theme_light_scrim = Color(0xFF000000)

// --- DARK THEME ---
val md_theme_dark_primary = Color(0xFF97CBFF) // Azzurro cielo
val md_theme_dark_onPrimary = Color(0xFF003353)
val md_theme_dark_primaryContainer = Color(0xFF004A76)
val md_theme_dark_onPrimaryContainer = Color(0xFFCEE5FF)

val md_theme_dark_secondary = Color(0xFFB9C8DA) // Ardesia chiaro
val md_theme_dark_onSecondary = Color(0xFF243240)
val md_theme_dark_secondaryContainer = Color(0xFF3B4857)
val md_theme_dark_onSecondaryContainer = Color(0xFFD5E4F7)

val md_theme_dark_tertiary = Color(0xFFFFB3B3) // Corallo chiaro
val md_theme_dark_onTertiary = Color(0xFF5C1A20)
val md_theme_dark_tertiaryContainer = Color(0xFF7A3035)
val md_theme_dark_onTertiaryContainer = Color(0xFFFFDAD8)

val md_theme_dark_error = Color(0xFFFFB4AB)
val md_theme_dark_errorContainer = Color(0xFF93000A)
val md_theme_dark_onError = Color(0xFF690005)
val md_theme_dark_onErrorContainer = Color(0xFFFFDAD6)

val md_theme_dark_background = Color(0xFF1A1C1E) // Grigio quasi nero
val md_theme_dark_onBackground = Color(0xFFE2E2E5)
val md_theme_dark_surface = Color(0xFF1A1C1E)
val md_theme_dark_onSurface = Color(0xFFE2E2E5)
val md_theme_dark_surfaceVariant = Color(0xFF42474E)
val md_theme_dark_onSurfaceVariant = Color(0xFFC3C7CF)
val md_theme_dark_outline = Color(0xFF8D9199)
val md_theme_dark_inverseOnSurface = Color(0xFF1A1C1E)
val md_theme_dark_inverseSurface = Color(0xFFE2E2E5)
val md_theme_dark_inversePrimary = Color(0xFF00639B)
val md_theme_dark_shadow = Color(0xFF000000)
val md_theme_dark_surfaceTint = Color(0xFF97CBFF)
val md_theme_dark_outlineVariant = Color(0xFF42474E)
val md_theme_dark_scrim = Color(0xFF000000)

// --- SURFACES (tones of the neutral palette: hue of the seed, chroma 4, as the colors above) ---
// Containers of dialogs, menus, sheets, cards and bars; without them Material falls back to its
// baseline neutrals, purple beside this palette
val md_theme_light_surfaceDim = Color(0xFFDADADD) // N87
val md_theme_light_surfaceBright = Color(0xFFFCFCFF) // N99, as the surface
val md_theme_light_surfaceContainerLowest = Color(0xFFFFFFFF) // N100
val md_theme_light_surfaceContainerLow = Color(0xFFF3F3F7) // N96
val md_theme_light_surfaceContainer = Color(0xFFEEEDF1) // N94
val md_theme_light_surfaceContainerHigh = Color(0xFFE8E8EB) // N92
val md_theme_light_surfaceContainerHighest = Color(0xFFE2E2E5) // N90

val md_theme_dark_surfaceDim = Color(0xFF121416) // N6
val md_theme_dark_surfaceBright = Color(0xFF37393C) // N24
val md_theme_dark_surfaceContainerLowest = Color(0xFF0C0E11) // N4
val md_theme_dark_surfaceContainerLow = Color(0xFF1A1C1E) // N10, as the surface
val md_theme_dark_surfaceContainer = Color(0xFF1E2022) // N12
val md_theme_dark_surfaceContainerHigh = Color(0xFF282A2D) // N17
val md_theme_dark_surfaceContainerHighest = Color(0xFF333538) // N22

// --- FIXED (the same in the light and dark themes: tones 90, 80, 10, 30 of each palette) ---
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

val seed = Color(0xFF00639B)
