package com.displaysplitter.geometry

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bottom-edge split-select mirror (video-on-bottom entry), Fold7 portrait screen. */
class PaneGeometryBottomTest {

    private val screen = Box(0, 0, 1968, 2184)

    @Test
    fun `bottom-docked full-width half-height window is split-select bottom`() {
        assertTrue(PaneGeometry.isSplitSelectBottomPane(Box(0, 1099, 1968, 2184), screen))
    }

    @Test
    fun `top-docked window is not split-select bottom`() {
        assertFalse(PaneGeometry.isSplitSelectBottomPane(Box(0, 0, 1968, 1000), screen))
    }

    @Test
    fun `freeform popup is rejected by the full-width requirement`() {
        assertFalse(PaneGeometry.isSplitSelectBottomPane(Box(541, 1200, 1427, 2184), screen))
    }

    @Test
    fun `fullscreen window is not split-select bottom`() {
        assertFalse(PaneGeometry.isSplitSelectBottomPane(Box(0, 0, 1968, 2184), screen))
    }
}
