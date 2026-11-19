package io.github.valerioisufi.pageboard.ui.screen

import androidx.compose.ui.unit.dp
import io.github.valerioisufi.pageboard.ui.adaptive.ReferenceWindow
import kotlin.test.Test
import kotlin.test.assertEquals

class PanelPlacementTest {

    @Test
    fun aPanelGoesBesideTheCanvasWhenTheCanvasKeepsItsMinimumWidth() {
        val limit = PanelPlacement.PanelWidth + PanelPlacement.CanvasMinWidth
        assertEquals(PanelPlacement.BESIDE, PanelPlacement.of(limit, 1))
        assertEquals(PanelPlacement.BOTTOM, PanelPlacement.of(limit - 1.dp, 1))
        // None open: where one would go
        assertEquals(PanelPlacement.BESIDE, PanelPlacement.of(limit, 0))
    }

    @Test
    fun severalPanelsGoSideBySideOrInTabs() {
        val two = PanelPlacement.PanelWidth * 2 + PanelPlacement.CanvasMinWidth
        assertEquals(PanelPlacement.BESIDE, PanelPlacement.of(two, 2))
        assertEquals(PanelPlacement.BESIDE_TABS, PanelPlacement.of(two - 1.dp, 2))
        assertEquals(PanelPlacement.BOTTOM, PanelPlacement.of(500.dp, 2))
    }

    @Test
    fun thePanelsOfTheReferenceWindows() {
        val placements = ReferenceWindow.entries.associateWith { PanelPlacement.of(it.width, 1) }
        assertEquals(PanelPlacement.BOTTOM, placements[ReferenceWindow.PHONE])
        assertEquals(PanelPlacement.BESIDE, placements[ReferenceWindow.PHONE_LANDSCAPE])
        assertEquals(PanelPlacement.BESIDE, placements[ReferenceWindow.TABLET_PORTRAIT])
        assertEquals(PanelPlacement.BOTTOM, placements[ReferenceWindow.TABLET_SPLIT_SCREEN])
        assertEquals(PanelPlacement.BESIDE, placements[ReferenceWindow.TABLET_LANDSCAPE])
        assertEquals(PanelPlacement.BOTTOM, placements[ReferenceWindow.DESKTOP_NARROW])
        assertEquals(PanelPlacement.BESIDE, placements[ReferenceWindow.DESKTOP])
        // Two panels on a tablet in portrait: in tabs
        assertEquals(PanelPlacement.BESIDE_TABS, PanelPlacement.of(ReferenceWindow.TABLET_PORTRAIT.width, 2))
    }
}
