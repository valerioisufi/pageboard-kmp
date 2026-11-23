package io.github.valerioisufi.pageboard.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.theme.PageboardTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The rows of the licenses keep their description wide and put the license under it where the
 * two do not fit side by side (docs/plans/small-screen-modals.md).
 */
@OptIn(ExperimentalTestApi::class)
class LicenseRowTest {

    private val freeType = ThirdPartyLicense(
        name = "The FreeType Project",
        license = "The FreeType Project License (FTL)",
        copyright = "",
        purpose = "Font rasterization engine statically incorporated inside PDFium.",
        notice = ""
    )

    private fun bounds(rowWidth: Dp): Pair<DpRect, DpRect> {
        var purpose = DpRect(0.dp, 0.dp, 0.dp, 0.dp)
        var license = purpose
        runDesktopComposeUiTest(width = 800, height = 400) {
            setContent {
                PageboardTheme(dynamicColor = false) {
                    Box(Modifier.width(rowWidth)) { LicenseRow(freeType) }
                }
            }
            purpose = onNodeWithText(freeType.purpose, useUnmergedTree = true).getBoundsInRoot()
            license = onNodeWithText(freeType.license, useUnmergedTree = true).getBoundsInRoot()
        }
        return purpose to license
    }

    @Test
    fun inANarrowRowTheLicenseGoesUnderTheDescription() {
        val (purpose, license) = bounds(264.dp)
        assertTrue(purpose.right - purpose.left >= 160.dp, "description ${purpose.right - purpose.left} wide")
        assertTrue(license.top >= purpose.bottom, "license at ${license.top}, description ending at ${purpose.bottom}")
        assertTrue(license.right <= 264.dp, "license ending at ${license.right}")
    }

    @Test
    fun inAWideRowTheLicenseIsBesideTheDescription() {
        val (purpose, license) = bounds(600.dp)
        assertTrue(license.left >= purpose.right, "license at ${license.left}, description ending at ${purpose.right}")
        assertTrue(license.top < purpose.bottom)
    }
}
