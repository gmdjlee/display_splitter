package com.displaysplitter.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry is exercised against the Galaxy Z Fold7 inner display (2184×1968)
 * and the Pixel 9 Pro Fold inner display (2076×2152) used by the emulator.
 */
class RatioMathTest {

    private val fold7W = 2184
    private val fold7H = 1968
    private val divider = 24

    /** Fold7 inner camera: punch hole near the top-right corner. */
    private val fold7Hole = listOf(Box(2020, 30, 2080, 90))

    // ---- horizontal divider (top/bottom panes): exact-ratio mode ---------------------------

    @Test
    fun `16to9 on fold7 top-bottom split hits exact ratio`() {
        val plan = RatioMath.plan(
            SplitAxis.HORIZONTAL, fold7W, fold7H, divider,
            AspectRatio.R16_9, fold7Hole, PositionPref.AUTO,
        )
        assertTrue(plan.exactRatio)
        assertFalse(plan.holeAvoidMode)
        // pane height = width / (16/9)
        assertEquals(1229, plan.videoPaneLengthPx)
        // hole is in the top half → video goes to the bottom pane
        assertEquals(PaneSide.SECOND, plan.videoSide)
        // divider sits just above the video pane
        assertEquals(fold7H - 1229 - divider / 2, plan.dividerCenterPx)
    }

    @Test
    fun `21to9 on fold7 gives a shorter video pane`() {
        val plan = RatioMath.plan(
            SplitAxis.HORIZONTAL, fold7W, fold7H, divider,
            AspectRatio.R21_9, fold7Hole, PositionPref.AUTO,
        )
        assertTrue(plan.exactRatio)
        assertEquals(936, plan.videoPaneLengthPx)
    }

    @Test
    fun `4to3 is clamped when the ideal pane would exceed the usable range`() {
        // 2184 / (4/3) = 1638 which still fits: expect exact.
        val plan = RatioMath.plan(
            SplitAxis.HORIZONTAL, fold7W, fold7H, divider,
            AspectRatio.R4_3, fold7Hole, PositionPref.AUTO,
        )
        assertTrue(plan.exactRatio)
        assertEquals(1638, plan.videoPaneLengthPx)
    }

    @Test
    fun `an impossible ratio clamps and reports non-exact`() {
        // 1:2 portrait content on a wide screen: ideal pane 4368 > usable → clamp.
        val plan = RatioMath.plan(
            SplitAxis.HORIZONTAL, fold7W, fold7H, divider,
            AspectRatio(1, 2), fold7Hole, PositionPref.AUTO,
        )
        assertFalse(plan.exactRatio)
        assertTrue(plan.videoPaneLengthPx <= fold7H - divider)
    }

    @Test
    fun `user override forces the video to the chosen pane`() {
        val plan = RatioMath.plan(
            SplitAxis.HORIZONTAL, fold7W, fold7H, divider,
            AspectRatio.R16_9, fold7Hole, PositionPref.FIRST,
        )
        assertEquals(PaneSide.FIRST, plan.videoSide)
        assertEquals(plan.videoPaneLengthPx + divider / 2, plan.dividerCenterPx)
    }

    // ---- vertical divider (left/right panes): hole-avoid mode ------------------------------

    @Test
    fun `side-by-side split falls back to hole avoidance`() {
        val plan = RatioMath.plan(
            SplitAxis.VERTICAL, fold7W, fold7H, divider,
            AspectRatio.R16_9, fold7Hole, PositionPref.AUTO,
        )
        assertTrue(plan.holeAvoidMode)
        assertFalse(plan.exactRatio)
        assertFalse(plan.noOp)
        // hole on the right → video keeps the left pane
        assertEquals(PaneSide.FIRST, plan.videoSide)
        // spacer must cover the hole column: video pane cannot extend past hole.left
        assertTrue(plan.videoPaneLengthPx <= fold7Hole[0].left)
        // divider-center formula verified on the VERTICAL axis too (video FIRST)
        assertEquals(plan.videoPaneLengthPx + divider / 2, plan.dividerCenterPx)
    }

    @Test
    fun `no cutout means no-op in hole-avoid mode`() {
        // Nothing to avoid: splitting would only steal screen — never interfere.
        val plan = RatioMath.plan(
            SplitAxis.VERTICAL, fold7W, fold7H, divider,
            AspectRatio.R16_9, emptyList(), PositionPref.AUTO,
        )
        assertTrue(plan.holeAvoidMode)
        assertTrue(plan.noOp)
    }

    @Test
    fun `left-side hole puts spacer on the left and covers the hole column`() {
        // 180° rotation case: hole bottom-left → video SECOND (right), spacer FIRST (left).
        val hole = listOf(Box(100, 30, 160, 90))
        val plan = RatioMath.plan(
            SplitAxis.VERTICAL, fold7W, fold7H, divider,
            AspectRatio.R16_9, hole, PositionPref.AUTO,
        )
        assertEquals(PaneSide.SECOND, plan.videoSide)
        assertTrue(plan.holeAvoidMode)
        assertFalse(plan.noOp)
        // video SECOND → divider center mirrors from the far edge
        assertEquals(fold7W - plan.videoPaneLengthPx - divider / 2, plan.dividerCenterPx)
        // spacer pane spans [0, spacerRight]: it must contain the hole column
        val spacerRight = fold7W - plan.videoPaneLengthPx - divider
        assertTrue(RatioMath.holeCovered(Box(0, 0, spacerRight, fold7H), hole, SplitAxis.VERTICAL))
    }

    @Test
    fun `huge hole clamps the video pane and reports uncoverable via holeCovered`() {
        // Hole so wide the spacer cannot cover it without shrinking the video below
        // the system minimum: the plan clamps to minPane, and the runtime coverage
        // check (holeCovered) must report false so engagement fails instead of
        // silently claiming success.
        val hole = listOf(Box(100, 0, 2100, 90))
        val plan = RatioMath.plan(
            SplitAxis.VERTICAL, fold7W, fold7H, divider,
            AspectRatio.R16_9, hole, PositionPref.AUTO,
        )
        val minPane = (fold7W * RatioMath.MIN_PANE_FRACTION).toInt()
        assertEquals(PaneSide.FIRST, plan.videoSide)
        assertTrue(plan.videoPaneLengthPx in minPane..(minPane + 1))
        val spacerLeft = plan.videoPaneLengthPx + divider
        assertFalse(RatioMath.holeCovered(Box(spacerLeft, 0, fold7W, fold7H), hole, SplitAxis.VERTICAL))
    }

    @Test
    fun `holeCovered checks axis containment`() {
        val hole = listOf(Box(2020, 30, 2080, 90))
        // right-column spacer containing the hole
        assertTrue(RatioMath.holeCovered(Box(1900, 0, 2184, 1968), hole, SplitAxis.VERTICAL))
        // spacer stops short of the hole
        assertFalse(RatioMath.holeCovered(Box(0, 0, 2000, 1968), hole, SplitAxis.VERTICAL))
        // no cutouts → trivially covered
        assertTrue(RatioMath.holeCovered(Box(0, 0, 100, 100), emptyList(), SplitAxis.VERTICAL))
        // horizontal axis: top-row hole inside a top spacer pane
        assertTrue(RatioMath.holeCovered(Box(0, 0, 2184, 200), hole, SplitAxis.HORIZONTAL))
        assertFalse(RatioMath.holeCovered(Box(0, 100, 2184, 1968), hole, SplitAxis.HORIZONTAL))
    }

    @Test
    fun `forcing the video onto the hole side keeps a minimal spacer and never bricks`() {
        // Fold7 default orientation (VERTICAL divider), hole upper-right, user taps Flip:
        // the video moves onto the hole side BY CHOICE. The plan must stay possible —
        // minimal spacer, hole exposed, coverage not enforced — never an impossible
        // "cover the far hole from the near edge" spacer that fails forever.
        val plan = RatioMath.plan(
            SplitAxis.VERTICAL, fold7W, fold7H, divider,
            AspectRatio.R16_9, fold7Hole, PositionPref.SECOND,
        )
        assertFalse(plan.noOp)
        assertTrue(plan.holeAvoidMode)
        assertTrue(plan.holeExposedByChoice)
        assertEquals(PaneSide.SECOND, plan.videoSide)
        val minPane = (fold7W * RatioMath.MIN_PANE_FRACTION).toInt()
        // Spacer collapses to the system minimum; the video keeps the rest.
        assertTrue(plan.videoPaneLengthPx >= fold7W - divider - minPane - 1)
    }

    @Test
    fun `auto side never exposes the hole`() {
        val plan = RatioMath.plan(
            SplitAxis.VERTICAL, fold7W, fold7H, divider,
            AspectRatio.R16_9, fold7Hole, PositionPref.AUTO,
        )
        assertFalse(plan.holeExposedByChoice)
    }

    // ---- side resolution -------------------------------------------------------------------

    @Test
    fun `auto side puts video opposite the hole`() {
        // hole top → video bottom
        assertEquals(
            PaneSide.SECOND,
            RatioMath.resolveVideoSide(SplitAxis.HORIZONTAL, fold7H, fold7Hole, PositionPref.AUTO),
        )
        // hole right → video left
        assertEquals(
            PaneSide.FIRST,
            RatioMath.resolveVideoSide(SplitAxis.VERTICAL, fold7W, fold7Hole, PositionPref.AUTO),
        )
        // hole bottom-left → flip
        val bottomLeftHole = listOf(Box(50, 1900, 110, 1960))
        assertEquals(
            PaneSide.FIRST,
            RatioMath.resolveVideoSide(SplitAxis.HORIZONTAL, fold7H, bottomLeftHole, PositionPref.AUTO),
        )
    }

    // ---- verification helpers --------------------------------------------------------------

    @Test
    fun `achieved ratio and tolerance`() {
        val achieved = RatioMath.achievedRatio(2184, 1229)
        assertTrue(RatioMath.isWithinTolerance(achieved, AspectRatio.R16_9))
        assertFalse(RatioMath.isWithinTolerance(achieved, AspectRatio.R21_9))
    }

    @Test
    fun `pixel 9 pro fold emulator geometry also plans exact 16to9`() {
        val plan = RatioMath.plan(
            SplitAxis.HORIZONTAL, 2076, 2152, 24,
            AspectRatio.R16_9, emptyList(), PositionPref.AUTO,
        )
        assertTrue(plan.exactRatio)
        assertEquals(1168, plan.videoPaneLengthPx)
    }
}
