package com.displaysplitter.geometry

import kotlin.math.abs
import kotlin.math.roundToInt

/** HORIZONTAL: the divider line is horizontal → panes stacked top/bottom.
 *  VERTICAL: the divider line is vertical → panes side by side. */
enum class SplitAxis { HORIZONTAL, VERTICAL }

/** FIRST = top (HORIZONTAL axis) or left (VERTICAL axis). */
enum class PaneSide { FIRST, SECOND }

enum class PositionPref { AUTO, FIRST, SECOND }

/** Pure-Kotlin rect so the geometry core stays unit-testable off-device. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun union(other: Box): Box = Box(
        minOf(left, other.left), minOf(top, other.top),
        maxOf(right, other.right), maxOf(bottom, other.bottom),
    )
}

data class AspectRatio(val w: Int, val h: Int) {
    val value: Float get() = w.toFloat() / h
    val label: String get() = if (this == R235) "2.35:1" else "$w:$h"

    companion object {
        val R16_9 = AspectRatio(16, 9)
        val R21_9 = AspectRatio(21, 9)
        val R235 = AspectRatio(47, 20)
        val R4_3 = AspectRatio(4, 3)
        val PRESETS = listOf(R16_9, R21_9, R235, R4_3)
    }
}

data class SplitPlan(
    val axis: SplitAxis,
    val videoSide: PaneSide,
    /** Desired length of the video pane along the split axis, px. */
    val videoPaneLengthPx: Int,
    /** Desired divider center coordinate along the split axis, px in display coords. */
    val dividerCenterPx: Int,
    /** True when the video pane hits the target ratio exactly (zero letterbox). */
    val exactRatio: Boolean,
    /** VERTICAL-axis fallback: exact wide ratios are impossible, spacer only covers the camera hole. */
    val holeAvoidMode: Boolean,
    /** True when splitting would provide zero benefit (e.g. hole-avoid with no cutout): skip engagement. */
    val noOp: Boolean = false,
    /** The user explicitly placed the video on the hole side: minimal spacer, coverage not enforced. */
    val holeExposedByChoice: Boolean = false,
)

object RatioMath {

    /** Panes can't shrink below roughly this fraction of the axis on One UI / AOSP. */
    const val MIN_PANE_FRACTION = 0.10f

    /** Extra margin past the camera hole in hole-avoid mode, px. */
    const val HOLE_MARGIN_PX = 12

    /** Achieved-vs-target tolerance when verifying a drag result. */
    const val RATIO_TOLERANCE = 0.02f

    fun plan(
        axis: SplitAxis,
        displayWidth: Int,
        displayHeight: Int,
        dividerThicknessPx: Int,
        target: AspectRatio,
        cutouts: List<Box>,
        positionPref: PositionPref,
    ): SplitPlan {
        val axisLength = if (axis == SplitAxis.HORIZONTAL) displayHeight else displayWidth
        val crossLength = if (axis == SplitAxis.HORIZONTAL) displayWidth else displayHeight
        val usable = (axisLength - dividerThicknessPx).coerceAtLeast(1)
        val minPane = (axisLength * MIN_PANE_FRACTION).roundToInt()

        val videoSide = resolveVideoSide(axis, axisLength, cutouts, positionPref)

        val paneLen: Int
        val exact: Boolean
        val holeAvoid: Boolean
        var holeExposed = false
        when (axis) {
            SplitAxis.HORIZONTAL -> {
                // Video fills the pane width; pane height = width / ratio → zero letterbox.
                val ideal = (crossLength / target.value).roundToInt()
                paneLen = ideal.coerceIn(minPane, (usable - minPane).coerceAtLeast(minPane))
                exact = paneLen == ideal
                holeAvoid = false
            }
            SplitAxis.VERTICAL -> {
                // A near-square screen can't reach wide ratios by shrinking pane width.
                // Fall back: the spacer covers just the camera-hole strip on its side.
                val hole = union(cutouts)
                    ?: // Nothing to avoid — splitting would only steal screen. Never interfere.
                    return SplitPlan(axis, videoSide, usable, axisLength, exactRatio = false, holeAvoidMode = true, noOp = true)
                val spacerSide = videoSide.opposite()
                val holeSide = if (hole.centerX < axisLength / 2) PaneSide.FIRST else PaneSide.SECOND
                val spacerLen = if (holeSide == spacerSide) {
                    spacerLengthForHole(axis, axisLength, spacerSide, cutouts).coerceAtLeast(minPane)
                } else {
                    // Only reachable with a manual position pref: the user chose the hole
                    // side for the video. Minimal spacer; the hole stays exposed by choice —
                    // never plan an impossible "cover the hole from the far edge" spacer.
                    holeExposed = true
                    minPane
                }
                paneLen = (usable - spacerLen).coerceIn(minPane, (usable - minPane).coerceAtLeast(minPane))
                exact = false
                holeAvoid = true
            }
        }

        val dividerCenter = if (videoSide == PaneSide.FIRST) {
            paneLen + dividerThicknessPx / 2
        } else {
            axisLength - paneLen - dividerThicknessPx / 2
        }
        return SplitPlan(axis, videoSide, paneLen, dividerCenter, exact, holeAvoid, holeExposedByChoice = holeExposed)
    }

    /** AUTO: put the video in the pane the camera hole is NOT in. */
    fun resolveVideoSide(
        axis: SplitAxis,
        axisLength: Int,
        cutouts: List<Box>,
        pref: PositionPref,
    ): PaneSide = when (pref) {
        PositionPref.FIRST -> PaneSide.FIRST
        PositionPref.SECOND -> PaneSide.SECOND
        PositionPref.AUTO -> {
            val hole = union(cutouts) ?: return PaneSide.SECOND
            val center = if (axis == SplitAxis.HORIZONTAL) hole.centerY else hole.centerX
            if (center < axisLength / 2) PaneSide.SECOND else PaneSide.FIRST
        }
    }

    /** Axis-aware spacer length: distance from the spacer-side display edge past the hole. */
    fun spacerLengthForHole(
        axis: SplitAxis,
        axisLength: Int,
        spacerSide: PaneSide,
        cutouts: List<Box>,
    ): Int {
        val hole = union(cutouts) ?: return 0
        val (lo, hi) = if (axis == SplitAxis.HORIZONTAL) hole.top to hole.bottom else hole.left to hole.right
        return when (spacerSide) {
            PaneSide.FIRST -> (hi + HOLE_MARGIN_PX).coerceAtLeast(0)
            PaneSide.SECOND -> (axisLength - lo + HOLE_MARGIN_PX).coerceAtLeast(0)
        }
    }

    /** Verifies the measured spacer pane actually covers the camera hole along the split axis. */
    fun holeCovered(spacer: Box, cutouts: List<Box>, axis: SplitAxis): Boolean {
        val hole = union(cutouts) ?: return true
        return if (axis == SplitAxis.VERTICAL) {
            hole.left >= spacer.left && hole.right <= spacer.right
        } else {
            hole.top >= spacer.top && hole.bottom <= spacer.bottom
        }
    }

    fun achievedRatio(width: Int, height: Int): Float =
        if (height <= 0) 0f else width.toFloat() / height

    fun isWithinTolerance(achieved: Float, target: AspectRatio): Boolean =
        abs(achieved - target.value) / target.value <= RATIO_TOLERANCE

    private fun union(rects: List<Box>): Box? =
        rects.reduceOrNull { acc, r -> acc.union(r) }
}

fun PaneSide.opposite(): PaneSide = if (this == PaneSide.FIRST) PaneSide.SECOND else PaneSide.FIRST
