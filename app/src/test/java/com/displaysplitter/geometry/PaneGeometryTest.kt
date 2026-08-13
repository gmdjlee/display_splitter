package com.displaysplitter.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split-entry geometry predicates against the Fold7 inner display (1968×2184 portrait).
 * The off-screen cases mirror the measured One UI trap: a pane at a minimum snap point
 * SLIDES off-screen (negative coordinates) instead of resizing.
 */
class PaneGeometryTest {

    private val screen = Box(0, 0, 1968, 2184)

    // ---- visibleRect -------------------------------------------------------------------------

    @Test
    fun `visibleRect clamps an off-screen slide to the visible part`() {
        // Measured: bounds=[-1592,0][181,2184] for a minimum-snapped pane.
        val visible = PaneGeometry.visibleRect(Box(-1592, 0, 181, 2184), screen)
        assertEquals(Box(0, 0, 181, 2184), visible)
    }

    @Test
    fun `visibleRect is null for a fully off-screen window`() {
        assertNull(PaneGeometry.visibleRect(Box(-500, 0, -10, 2184), screen))
    }

    // ---- isTopBottomSplit ----------------------------------------------------------------------

    @Test
    fun `stacked full-width panes are a top-bottom split`() {
        val panes = listOf(Box(0, 0, 1968, 1060), Box(0, 1074, 1968, 2184))
        assertTrue(PaneGeometry.isTopBottomSplit(panes, screen))
    }

    @Test
    fun `side-by-side panes are not a top-bottom split`() {
        val panes = listOf(Box(0, 0, 975, 2184), Box(989, 0, 1968, 2184))
        assertFalse(PaneGeometry.isTopBottomSplit(panes, screen))
        assertTrue(PaneGeometry.isLeftRightSplit(panes, screen))
    }

    @Test
    fun `a popup next to one pane is not a split`() {
        val panes = listOf(Box(0, 0, 1968, 2184), Box(500, 600, 1400, 1600))
        assertFalse(PaneGeometry.isTopBottomSplit(panes, screen))
    }

    @Test
    fun `minimum-snapped pane that slid off-screen still counts via its visible part`() {
        // Top pane visible sliver + big bottom pane: combined visible height ≥ 70%.
        val panes = listOf(Box(0, -1900, 1968, 260), Box(0, 274, 1968, 2184))
        assertTrue(PaneGeometry.isTopBottomSplit(panes, screen))
    }

    // ---- split-select predicates ---------------------------------------------------------------

    @Test
    fun `top-docked full-width half-height window is split-select top`() {
        assertTrue(PaneGeometry.isSplitSelectTopPane(Box(0, 0, 1968, 1000), screen))
    }

    @Test
    fun `freeform popup is rejected by the full-width requirement`() {
        // Measured trap: a pop-up window passes the height-ratio test alone.
        assertFalse(PaneGeometry.isSplitSelectTopPane(Box(541, 645, 1427, 1628), screen))
    }

    @Test
    fun `bottom-docked window is not split-select top`() {
        assertFalse(PaneGeometry.isSplitSelectTopPane(Box(0, 1184, 1968, 2184), screen))
    }

    @Test
    fun `fullscreen window is not split-select (ratio above 75 percent)`() {
        assertFalse(PaneGeometry.isSplitSelectTopPane(Box(0, 0, 1968, 2184), screen))
    }

    @Test
    fun `edge-docked full-height half-width window is split-select side`() {
        assertTrue(PaneGeometry.isSplitSelectSidePane(Box(0, 0, 900, 2184), screen))
        assertTrue(PaneGeometry.isSplitSelectSidePane(Box(1068, 0, 1968, 2184), screen))
    }

    @Test
    fun `centered window is not split-select side`() {
        assertFalse(PaneGeometry.isSplitSelectSidePane(Box(500, 0, 1400, 2184), screen))
    }
}
