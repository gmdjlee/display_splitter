package com.displaysplitter.geometry

import kotlin.math.abs
import kotlin.math.roundToInt

/** HORIZONTAL: the divider line is horizontal → panes stacked top/bottom.
 *  VERTICAL: the divider line is vertical → panes side by side.
 *  Only HORIZONTAL is plannable — a VERTICAL divider is rotated before planning. */
enum class SplitAxis { HORIZONTAL, VERTICAL }

/** FIRST = top pane, SECOND = bottom pane. */
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

/** Plan for a top/bottom split: the video pane spans the full display width, so its
 *  height alone decides the aspect ratio — the one layout with zero letterbox. */
data class SplitPlan(
    val videoSide: PaneSide,
    /** Desired video pane height, px. */
    val videoPaneLengthPx: Int,
    /** Desired divider center Y, px in display coords. */
    val dividerCenterPx: Int,
    /** True when the video pane hits the target ratio exactly (zero letterbox). */
    val exactRatio: Boolean,
)

object RatioMath {

    /** Panes can't shrink below roughly this fraction of the axis on One UI / AOSP. */
    const val MIN_PANE_FRACTION = 0.10f

    /** Achieved-vs-target tolerance when verifying a drag result. */
    const val RATIO_TOLERANCE = 0.02f

    /** Top/bottom split: video fills the pane width; pane height = width / ratio. */
    fun plan(
        displayWidth: Int,
        displayHeight: Int,
        dividerThicknessPx: Int,
        target: AspectRatio,
        cutouts: List<Box>,
        positionPref: PositionPref,
    ): SplitPlan {
        val usable = (displayHeight - dividerThicknessPx).coerceAtLeast(1)
        val minPane = (displayHeight * MIN_PANE_FRACTION).roundToInt()
        val videoSide = resolveVideoSide(displayHeight, cutouts, positionPref)

        val ideal = (displayWidth / target.value).roundToInt()
        val paneLen = ideal.coerceIn(minPane, (usable - minPane).coerceAtLeast(minPane))

        val dividerCenter = if (videoSide == PaneSide.FIRST) {
            paneLen + dividerThicknessPx / 2
        } else {
            displayHeight - paneLen - dividerThicknessPx / 2
        }
        return SplitPlan(videoSide, paneLen, dividerCenter, exactRatio = paneLen == ideal)
    }

    /** AUTO: put the video in the pane the camera hole is NOT in. Without cutout data
     *  (One UI hides it from third parties) default to the bottom pane — every current
     *  fold's inner camera sits along the top edge. */
    fun resolveVideoSide(
        displayHeight: Int,
        cutouts: List<Box>,
        pref: PositionPref,
    ): PaneSide = when (pref) {
        PositionPref.FIRST -> PaneSide.FIRST
        PositionPref.SECOND -> PaneSide.SECOND
        PositionPref.AUTO -> {
            val hole = union(cutouts) ?: return PaneSide.SECOND
            if (hole.centerY < displayHeight / 2) PaneSide.SECOND else PaneSide.FIRST
        }
    }

    /**
     * Rotate [box] out of the display's NATURAL frame into the frame for [rotation]
     * (`Surface.ROTATION_0..3`). [naturalWidth]/[naturalHeight] are the unrotated display
     * dimensions. Mirrors `android.util.RotationUtils.rotateBounds`, which is what the
     * framework itself applies to the natural DisplayCutout for each rotation
     * (`DisplayContent.calculateDisplayCutoutForRotation`) — so a hole rotated here lands
     * exactly where a platform-reported cutout would.
     */
    fun rotateBox(box: Box, naturalWidth: Int, naturalHeight: Int, rotation: Int): Box =
        when (rotation) {
            1 -> Box(box.top, naturalWidth - box.right, box.bottom, naturalWidth - box.left)
            2 -> Box(
                naturalWidth - box.right, naturalHeight - box.bottom,
                naturalWidth - box.left, naturalHeight - box.top,
            )
            3 -> Box(naturalHeight - box.bottom, box.left, naturalHeight - box.top, box.right)
            else -> box
        }

    fun achievedRatio(width: Int, height: Int): Float =
        if (height <= 0) 0f else width.toFloat() / height

    fun isWithinTolerance(achieved: Float, target: AspectRatio): Boolean =
        abs(achieved - target.value) / target.value <= RATIO_TOLERANCE

    private fun union(rects: List<Box>): Box? =
        rects.reduceOrNull { acc, r -> acc.union(r) }
}

fun PaneSide.opposite(): PaneSide = if (this == PaneSide.FIRST) PaneSide.SECOND else PaneSide.FIRST
