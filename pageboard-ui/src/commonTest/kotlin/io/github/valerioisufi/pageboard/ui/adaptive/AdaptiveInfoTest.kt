package io.github.valerioisufi.pageboard.ui.adaptive

import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveInfoTest {

    @Test
    fun widthClassesFollowTheBreakpointsOfMaterial() {
        assertEquals(WidthClass.COMPACT, WindowLayout.of(599.dp, 800.dp).widthClass)
        assertEquals(WidthClass.MEDIUM, WindowLayout.of(600.dp, 800.dp).widthClass)
        assertEquals(WidthClass.MEDIUM, WindowLayout.of(839.dp, 800.dp).widthClass)
        assertEquals(WidthClass.EXPANDED, WindowLayout.of(840.dp, 800.dp).widthClass)
    }

    @Test
    fun heightClassesFollowTheBreakpointsOfMaterial() {
        assertEquals(HeightClass.COMPACT, WindowLayout.of(800.dp, 479.dp).heightClass)
        assertEquals(HeightClass.MEDIUM, WindowLayout.of(800.dp, 480.dp).heightClass)
        assertEquals(HeightClass.MEDIUM, WindowLayout.of(800.dp, 899.dp).heightClass)
        assertEquals(HeightClass.EXPANDED, WindowLayout.of(800.dp, 900.dp).heightClass)
    }

    @Test
    fun referenceWindowsHaveTheClassesOfTheirDevices() {
        val classes = ReferenceWindow.entries.associateWith { it.layout.widthClass to it.layout.heightClass }
        assertEquals(WidthClass.COMPACT to HeightClass.MEDIUM, classes[ReferenceWindow.PHONE])
        assertEquals(WidthClass.EXPANDED to HeightClass.COMPACT, classes[ReferenceWindow.PHONE_LANDSCAPE])
        assertEquals(WidthClass.MEDIUM to HeightClass.EXPANDED, classes[ReferenceWindow.TABLET_PORTRAIT])
        assertEquals(WidthClass.MEDIUM to HeightClass.MEDIUM, classes[ReferenceWindow.TABLET_SPLIT_SCREEN])
        assertEquals(WidthClass.EXPANDED to HeightClass.MEDIUM, classes[ReferenceWindow.TABLET_LANDSCAPE])
        assertEquals(WidthClass.MEDIUM to HeightClass.MEDIUM, classes[ReferenceWindow.DESKTOP_NARROW])
        assertEquals(WidthClass.EXPANDED to HeightClass.EXPANDED, classes[ReferenceWindow.DESKTOP])
    }

    @Test
    fun capabilitiesStartFromTheHintsOfThePlatform() {
        val computer = InputCapabilities(InputHints.PointerAndKeyboard)
        assertTrue(computer.hasPrecisePointer)
        assertTrue(computer.hasKeyboard)
        assertTrue(computer.canHover)
        assertFalse(computer.hasTouch)

        val tablet = InputCapabilities(InputHints.Stylus)
        assertTrue(tablet.hasTouch)
        assertTrue(tablet.hasStylus)
        assertFalse(tablet.canHover)
    }

    @Test
    fun capabilitiesAreLearnedFromTheEventsAndKept() {
        val input = InputCapabilities(InputHints.Touch)
        assertFalse(input.hasStylus)

        input.onPointer(PointerType.Stylus)
        input.onPointer(PointerType.Mouse)
        input.onKey()
        assertTrue(input.hasStylus)
        assertTrue(input.hasPrecisePointer)
        assertTrue(input.hasKeyboard)

        // A keyboard unplugged: the hints no longer tell it, the capability stays
        input.learn(InputHints.Touch)
        assertTrue(input.hasKeyboard)
        assertTrue(input.hasTouch)
    }

    @Test
    fun theControlsAreLargeWhereAFingerCanTouchThem() {
        val computer = InputCapabilities(InputHints.PointerAndKeyboard)
        assertEquals(40.dp, computer.minimumInteractiveSize)

        // A touch screen of a computer, used once
        computer.onPointer(PointerType.Touch)
        assertEquals(48.dp, computer.minimumInteractiveSize)

        assertEquals(48.dp, InputCapabilities(InputHints.Stylus).minimumInteractiveSize)
    }
}
