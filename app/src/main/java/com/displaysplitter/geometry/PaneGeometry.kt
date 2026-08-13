package com.displaysplitter.geometry

/**
 * Screen/window geometry predicates for the split-entry recipes. Pure Kotlin —
 * everything here is verifiable without a device.
 *
 * Ported from FoldWindow's device-verified PaneGeometry (Fold7 / One UI 8):
 * when the divider is at a minimum snap point One UI SLIDES the shrunken pane
 * off-screen instead of resizing it (getBoundsInScreen returns negative left/top),
 * so every predicate works on the pane's VISIBLE intersection with the screen.
 */
object PaneGeometry {

    /** A real split pane spans most of the screen width (rejects popups/freeform). */
    private const val MIN_PANE_WIDTH_FRACTION = 0.6f

    /** Two stacked panes must together cover most of the screen height. */
    private const val MIN_COMBINED_HEIGHT_FRACTION = 0.7f

    /** Split-select state: the docked window's visible-height fraction (measured 15–75%). */
    const val SPLIT_SELECT_MIN_RATIO = 0.15f
    const val SPLIT_SELECT_MAX_RATIO = 0.75f

    /** Split-select must span (almost) the full cross axis — blocks popup/freeform false
     *  positives that pass the visible-ratio test alone (measured on device). */
    private const val FULL_AXIS_FRACTION = 0.9f

    /** Edge-docking tolerance, px. */
    const val EDGE_DOCK_TOLERANCE_PX = 40

    /** Visible intersection of a window with the screen; null when fully off-screen. */
    fun visibleRect(r: Box, screen: Box): Box? {
        val left = maxOf(r.left, screen.left)
        val top = maxOf(r.top, screen.top)
        val right = minOf(r.right, screen.right)
        val bottom = minOf(r.bottom, screen.bottom)
        if (right <= left || bottom <= top) return null
        return Box(left, top, right, bottom)
    }

    /**
     * Top/bottom split layout: two non-overlapping panes stacked vertically, each
     * visibly ≥60% of the screen width, together covering ≥70% of the screen height.
     */
    fun isTopBottomSplit(panes: List<Box>, screen: Box): Boolean {
        val candidates = visibleWideCandidates(panes, screen)
        if (candidates.size < 2) return false

        val sorted = candidates.sortedBy { it.top }
        val minCombinedHeight = screen.height * MIN_COMBINED_HEIGHT_FRACTION
        for (i in 0 until sorted.size - 1) {
            val upper = sorted[i]
            val lower = sorted[i + 1]
            if (lower.top < upper.bottom) continue // vertical overlap → not stacked
            if (upper.height + lower.height >= minCombinedHeight) return true
        }
        return false
    }

    /** Left/right split layout: the horizontal mirror of [isTopBottomSplit]. */
    fun isLeftRightSplit(panes: List<Box>, screen: Box): Boolean {
        val minHeight = screen.height * MIN_PANE_WIDTH_FRACTION
        val candidates = panes.mapNotNull { visibleRect(it, screen) }
            .filter { it.height >= minHeight }
        if (candidates.size < 2) return false

        val sorted = candidates.sortedBy { it.left }
        val minCombinedWidth = screen.width * MIN_COMBINED_HEIGHT_FRACTION
        for (i in 0 until sorted.size - 1) {
            val left = sorted[i]
            val right = sorted[i + 1]
            if (right.left < left.right) continue // horizontal overlap → not side by side
            if (left.width + right.width >= minCombinedWidth) return true
        }
        return false
    }

    /**
     * DRAG-recipe step 2 success: the target entered the TOP/BOTTOM split-select state.
     * All three measured conditions are required — full width (≥90%), top-docked, and
     * visible height in 15–75% — to reject popup/freeform windows.
     */
    fun isSplitSelectTopPane(pane: Box, screen: Box): Boolean {
        val visible = visibleRect(pane, screen) ?: return false
        val fullWidthOk = visible.width >= screen.width * FULL_AXIS_FRACTION
        val topDockedOk = visible.top <= screen.top + EDGE_DOCK_TOLERANCE_PX
        val ratio = visible.height.toFloat() / screen.height.toFloat()
        return fullWidthOk && topDockedOk && ratio in SPLIT_SELECT_MIN_RATIO..SPLIT_SELECT_MAX_RATIO
    }

    /** The bottom-docked mirror of [isSplitSelectTopPane] (video-on-bottom entry). */
    fun isSplitSelectBottomPane(pane: Box, screen: Box): Boolean {
        val visible = visibleRect(pane, screen) ?: return false
        val fullWidthOk = visible.width >= screen.width * FULL_AXIS_FRACTION
        val bottomDockedOk = visible.bottom >= screen.bottom - EDGE_DOCK_TOLERANCE_PX
        val ratio = visible.height.toFloat() / screen.height.toFloat()
        return fullWidthOk && bottomDockedOk && ratio in SPLIT_SELECT_MIN_RATIO..SPLIT_SELECT_MAX_RATIO
    }

    /** MENU-recipe step 3 success: the left/right split-select mirror of [isSplitSelectTopPane]. */
    fun isSplitSelectSidePane(pane: Box, screen: Box): Boolean {
        val visible = visibleRect(pane, screen) ?: return false
        val fullHeightOk = visible.height >= screen.height * FULL_AXIS_FRACTION
        val edgeDockedOk = visible.left <= screen.left + EDGE_DOCK_TOLERANCE_PX ||
            visible.right >= screen.right - EDGE_DOCK_TOLERANCE_PX
        val ratio = visible.width.toFloat() / screen.width.toFloat()
        return fullHeightOk && edgeDockedOk && ratio in SPLIT_SELECT_MIN_RATIO..SPLIT_SELECT_MAX_RATIO
    }

    private fun visibleWideCandidates(panes: List<Box>, screen: Box): List<Box> {
        val minWidth = screen.width * MIN_PANE_WIDTH_FRACTION
        return panes.mapNotNull { visibleRect(it, screen) }.filter { it.width >= minWidth }
    }
}
