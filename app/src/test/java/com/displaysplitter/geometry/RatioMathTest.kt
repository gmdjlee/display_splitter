package com.displaysplitter.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Geometry is exercised against the Galaxy Z Fold7 inner display in both orientations
 * (2184×1968 landscape, 1968×2184 portrait) and the Pixel 9 Pro Fold emulator (2076×2152).
 * Planning is top/bottom-split only: the video pane spans the full width, so its height
 * alone decides the ratio — the one layout with zero letterbox.
 */
class RatioMathTest {

    private val landW = 2184
    private val landH = 1968
    private val portW = 1968
    private val portH = 2184
    private val divider = 24

    /** Fold7 inner camera: punch hole along the top edge (landscape coords). */
    private val topHole = listOf(Box(2020, 30, 2080, 90))

    @Test
    fun `16to9 landscape hits exact ratio`() {
        val plan = RatioMath.plan(landW, landH, divider, AspectRatio.R16_9, topHole, PositionPref.AUTO)
        assertTrue(plan.exactRatio)
        assertEquals(1229, plan.videoPaneLengthPx) // 2184 / (16/9)
        // hole is in the top half → video goes to the bottom pane
        assertEquals(PaneSide.SECOND, plan.videoSide)
        // divider sits just above the video pane
        assertEquals(landH - 1229 - divider / 2, plan.dividerCenterPx)
    }

    @Test
    fun `16to9 portrait hits exact ratio too`() {
        val plan = RatioMath.plan(portW, portH, divider, AspectRatio.R16_9, emptyList(), PositionPref.AUTO)
        assertTrue(plan.exactRatio)
        assertEquals(1107, plan.videoPaneLengthPx) // 1968 / (16/9)
    }

    /** Why a rotation has to re-apply the split rather than just be re-measured. */
    @Test
    fun `a portrait plan no longer holds the ratio after rotating to landscape`() {
        val portrait = RatioMath.plan(portW, portH, divider, AspectRatio.R16_9, emptyList(), PositionPref.AUTO)
        val landscape = RatioMath.plan(landW, landH, divider, AspectRatio.R16_9, emptyList(), PositionPref.AUTO)
        assertTrue(portrait.exactRatio && landscape.exactRatio)
        assertNotEquals(portrait.videoPaneLengthPx, landscape.videoPaneLengthPx)
        // Keeping the portrait pane height across the rotation letterboxes again.
        assertFalse(
            RatioMath.isWithinTolerance(
                RatioMath.achievedRatio(landW, portrait.videoPaneLengthPx), AspectRatio.R16_9,
            ),
        )
    }

    @Test
    fun `21to9 gives a shorter video pane`() {
        val plan = RatioMath.plan(landW, landH, divider, AspectRatio.R21_9, topHole, PositionPref.AUTO)
        assertTrue(plan.exactRatio)
        assertEquals(936, plan.videoPaneLengthPx)
    }

    @Test
    fun `4to3 still fits and stays exact`() {
        val plan = RatioMath.plan(landW, landH, divider, AspectRatio.R4_3, topHole, PositionPref.AUTO)
        assertTrue(plan.exactRatio)
        assertEquals(1638, plan.videoPaneLengthPx)
    }

    @Test
    fun `an impossible ratio clamps and reports non-exact`() {
        // 1:2 portrait content on a wide screen: ideal pane 4368 > usable → clamp.
        val plan = RatioMath.plan(landW, landH, divider, AspectRatio(1, 2), topHole, PositionPref.AUTO)
        assertFalse(plan.exactRatio)
        assertTrue(plan.videoPaneLengthPx <= landH - divider)
    }

    @Test
    fun `pane never shrinks below the system minimum`() {
        // Extremely wide content: ideal pane would be tiny — clamp to minPane.
        val plan = RatioMath.plan(landW, landH, divider, AspectRatio(100, 1), topHole, PositionPref.AUTO)
        val minPane = (landH * RatioMath.MIN_PANE_FRACTION).toInt()
        assertFalse(plan.exactRatio)
        assertTrue(plan.videoPaneLengthPx in minPane..(minPane + 1))
    }

    @Test
    fun `user override forces the video to the chosen pane`() {
        val plan = RatioMath.plan(landW, landH, divider, AspectRatio.R16_9, topHole, PositionPref.FIRST)
        assertEquals(PaneSide.FIRST, plan.videoSide)
        assertEquals(plan.videoPaneLengthPx + divider / 2, plan.dividerCenterPx)
    }

    // ---- side resolution -------------------------------------------------------------------

    @Test
    fun `auto side puts video opposite the hole`() {
        // hole top → video bottom
        assertEquals(PaneSide.SECOND, RatioMath.resolveVideoSide(landH, topHole, PositionPref.AUTO))
        // hole bottom (180° rotation) → video top
        val bottomHole = listOf(Box(50, 1900, 110, 1960))
        assertEquals(PaneSide.FIRST, RatioMath.resolveVideoSide(landH, bottomHole, PositionPref.AUTO))
    }

    @Test
    fun `auto side without cutout data defaults to bottom`() {
        // One UI hides the cutout from third parties; every current fold's inner camera
        // is along the top edge, so the safe default is video-on-bottom.
        assertEquals(PaneSide.SECOND, RatioMath.resolveVideoSide(landH, emptyList(), PositionPref.AUTO))
    }

    @Test
    fun `the hole follows the display rotation`() {
        // Fold8 inner panel, natural (portrait) 2256×2504, hole measured off
        // `dumpsys window displays` udcCutout. Rotating it must land the hole where the
        // framework would have put a real cutout, and flip the video pane with it.
        val natural = Box(1665, 22, 1747, 104)
        val (w, h) = 2256 to 2504

        val at0 = RatioMath.rotateBox(natural, w, h, 0)
        assertEquals(natural, at0)
        assertEquals(PaneSide.SECOND, RatioMath.resolveVideoSide(h, listOf(at0), PositionPref.AUTO))

        // 180°: same display size, hole now along the BOTTOM edge — the case the old
        // "no cutout data → hole on top" default got wrong.
        val at180 = RatioMath.rotateBox(natural, w, h, 2)
        assertEquals(Box(509, 2400, 591, 2482), at180)
        assertEquals(PaneSide.FIRST, RatioMath.resolveVideoSide(h, listOf(at180), PositionPref.AUTO))

        // 90°/270°: display is now w=2504 h=2256 and the hole sits on a side edge, so
        // which half it lands in depends on its offset along the natural top edge.
        val at90 = RatioMath.rotateBox(natural, w, h, 1)
        assertEquals(Box(22, 509, 104, 591), at90)
        assertEquals(PaneSide.SECOND, RatioMath.resolveVideoSide(w, listOf(at90), PositionPref.AUTO))

        val at270 = RatioMath.rotateBox(natural, w, h, 3)
        assertEquals(Box(2400, 1665, 2482, 1747), at270)
        assertEquals(PaneSide.FIRST, RatioMath.resolveVideoSide(w, listOf(at270), PositionPref.AUTO))

        // Every rotation stays inside the rotated display.
        listOf(at0 to (w to h), at90 to (h to w), at180 to (w to h), at270 to (h to w))
            .forEach { (box, size) ->
                assertTrue(
                    "$box outside $size",
                    box.left in 0..size.first && box.right in 0..size.first &&
                        box.top in 0..size.second && box.bottom in 0..size.second &&
                        box.left < box.right && box.top < box.bottom,
                )
            }
    }

    @Test
    fun `explicit prefs override the hole`() {
        assertEquals(PaneSide.FIRST, RatioMath.resolveVideoSide(landH, topHole, PositionPref.FIRST))
        assertEquals(PaneSide.SECOND, RatioMath.resolveVideoSide(landH, topHole, PositionPref.SECOND))
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
        val plan = RatioMath.plan(2076, 2152, 24, AspectRatio.R16_9, emptyList(), PositionPref.AUTO)
        assertTrue(plan.exactRatio)
        assertEquals(1168, plan.videoPaneLengthPx)
    }
}
