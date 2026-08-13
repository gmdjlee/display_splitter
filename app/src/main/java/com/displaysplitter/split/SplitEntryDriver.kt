package com.displaysplitter.split

import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.displaysplitter.R
import com.displaysplitter.geometry.Box
import com.displaysplitter.geometry.PaneGeometry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Enters a TOP/BOTTOM split with [the video app, our spacer] by driving the One UI
 * Recents UI through the accessibility service — the only split-entry path that works
 * on One UI 8+ (`GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` was removed from the framework and
 * `FLAG_ACTIVITY_LAUNCH_ADJACENT` is ignored for background callers; both were verified
 * dead on a real Fold7, here and independently in FoldWindow's device facts).
 *
 * Ported from FoldWindow's device-verified SplitEntry (15/15 E2E on Fold7 / One UI 8),
 * specialised to the top/bottom layout this app needs:
 *
 *  - [EntryRecipe.DRAG] (resizeable target apps, default): open Recents → hold-drag the
 *    target's card icon to the TOP edge (top/bottom split-select) → tap our spacer in
 *    the partner picker.
 *  - [EntryRecipe.MENU] (unresizeable apps, e.g. Netflix — the drag drop gets routed to
 *    a pop-up window): open Recents → tap the card icon → tap "Open in split screen"
 *    (left/right split-select) → tap our spacer in the picker → tap the divider handle →
 *    "Rotate clockwise" popup converts left/right to top/bottom.
 *
 * Every step follows measure-don't-sleep: perform the action, then poll the step's
 * success condition with a deadline. Each step first re-checks its success condition
 * so a previous attempt's late settle is absorbed instead of re-triggering the action.
 *
 * The floating bubble/panel MUST be detached while this runs — gesture taps take the
 * same hit-test path as a finger, and our own touchable overlay would swallow them
 * (measured failure class in FoldWindow). EngagementController hides the overlay for
 * the whole Engaging state.
 */
class SplitEntryDriver(private val service: DividerAccessibilityService) {

    enum class EntryRecipe(val stepCount: Int) { DRAG(3), MENU(5) }

    private data class EntryContext(
        val targetPackage: String,
        val targetLabel: String?,
        val selfPackage: String,
        val panelLabel: String,
        val screen: Box,
        val recipe: EntryRecipe,
        /** DRAG recipe drop edge: the pane the video should END UP in — dropping on the
         *  matching edge makes the common case need no pane swap at all. */
        val videoOnTop: Boolean,
    )

    /** Runs the full recipe. False = a step failed; the caller owns cleanup/fail UX. */
    suspend fun enterSplit(targetPackage: String, videoOnTop: Boolean): Boolean {
        val pm = service.packageManager
        val recipe = if (ResizeMode.isActivitiesUnresizeable(pm, targetPackage) == true) {
            EntryRecipe.MENU
        } else {
            EntryRecipe.DRAG // resizeable confirmed OR undecidable — DRAG is the safe default
        }
        val display = service.displayBounds()
        val ctx = EntryContext(
            targetPackage = targetPackage,
            targetLabel = runCatching {
                pm.getApplicationInfo(targetPackage, 0).loadLabel(pm).toString()
            }.getOrNull(),
            selfPackage = service.packageName,
            panelLabel = service.getString(R.string.spacer_label),
            screen = Box(display.left, display.top, display.right, display.bottom),
            recipe = recipe,
            videoOnTop = videoOnTop,
        )
        Log.i(TAG, "enterSplit: pkg=$targetPackage label=${ctx.targetLabel} recipe=$recipe videoOnTop=$videoOnTop")
        for (step in 1..recipe.stepCount) {
            var ok = false
            // Re-attempts are safe: every step first re-checks its own success condition,
            // so a late settle from the first attempt is absorbed instead of re-firing
            // the action (measured trap: split-select animations can outlive the budget).
            for (attempt in 1..STEP_ATTEMPTS) {
                ok = try {
                    performStep(step, ctx)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "enterSplit: step $step threw", e)
                    false
                }
                if (ok) break
                Log.w(TAG, "enterSplit: step $step attempt $attempt failed (recipe=$recipe)")
            }
            if (!ok) return false
        }
        return true
    }

    /**
     * A pre-existing split can be LEFT/RIGHT (manual entry, or MENU recipe before its
     * rotate step): tap the divider handle, then the "Rotate clockwise" popup item.
     * Succeeds when [settled] turns true within [timeoutMs].
     */
    suspend fun rotateToTopBottom(timeoutMs: Long, settled: () -> Boolean): Boolean =
        dividerPopupAction(timeoutMs, "rotate-node", settled) { findRotateNode() }

    /**
     * Swap the two panes via the divider handle's "Switch windows" popup item — the
     * only swap mechanism on One UI 8+: a plain double-tap on the handle just opens
     * the popup and mis-taps it (measured), leaving the divider hidden for seconds.
     */
    suspend fun swapPanes(timeoutMs: Long, settled: () -> Boolean): Boolean =
        dividerPopupAction(timeoutMs, "switch-node", settled) { findSwitchNode() }

    private suspend fun dividerPopupAction(
        timeoutMs: Long,
        what: String,
        settled: () -> Boolean,
        find: () -> AccessibilityNodeInfo?,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)

        val divider = dividerBounds()
        if (divider == null) {
            Log.w(TAG, "dividerPopupAction[$what]: divider window not found")
            return false
        }
        // The a11y divider bounds ARE the drag handle on One UI (measured 221×54):
        // tapping its center opens the handle popup.
        if (!service.tapPoint(divider.centerX(), divider.centerY())) {
            Log.w(TAG, "dividerPopupAction[$what]: handle tap rejected — polling for popup anyway")
        }
        if (!clickWhenFound(remaining(), what, find)) return false
        return pollUntil(remaining(), settled)
    }

    // ══════════════════════════════════════════════════════════
    // Steps
    // ══════════════════════════════════════════════════════════

    private suspend fun performStep(step: Int, ctx: EntryContext): Boolean = when (ctx.recipe) {
        EntryRecipe.DRAG -> when (step) {
            1 -> stepOpenRecents(ctx)
            2 -> dragStepToEdge(ctx)
            3 -> stepTapPanelInPicker(ctx) { isTopBottomPairPresent(ctx) }
            else -> false
        }
        EntryRecipe.MENU -> when (step) {
            1 -> stepOpenRecents(ctx)
            2 -> menuStepTapCardIcon(ctx)
            3 -> menuStepTapSplitMenu(ctx)
            4 -> stepTapPanelInPicker(ctx) { isLeftRightPairPresent(ctx) }
            5 -> rotateToTopBottom(STEP_TIMEOUT_MS) { isTopBottomPairPresent(ctx) }
            else -> false
        }
    }

    /** Open Recents; success = the target's card icon node appears in the launcher tree. */
    private suspend fun stepOpenRecents(ctx: EntryContext): Boolean {
        val accepted = runCatching {
            service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        }.getOrDefault(false)
        if (!accepted) Log.w(TAG, "stepOpenRecents: GLOBAL_ACTION_RECENTS returned false — polling anyway")
        return pollUntil(STEP_TIMEOUT_MS) { findCardIconNode(ctx) != null }
    }

    /**
     * DRAG step 2: hold-drag the card icon to the top or bottom screen edge — the
     * measured `input draganddrop` recipe that produces the TOP/BOTTOM split-select
     * state with the target docked at the chosen edge (both edges verified on device).
     */
    private suspend fun dragStepToEdge(ctx: EntryContext): Boolean {
        val deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)

        // One combined poll: already in split-select (a previous attempt's late settle),
        // or a card icon with usable bounds (a matched node can transiently report empty
        // bounds — a "ghost"; keep polling instead of failing the attempt).
        var already = false
        var iconBounds: Rect? = null
        val acquired = pollUntil(remaining()) {
            if (isTargetInSplitSelectEdge(ctx)) {
                already = true
                true
            } else {
                val node = findCardIconNode(ctx)
                val rect = Rect()
                if (node != null &&
                    runCatching { node.getBoundsInScreen(rect) }.isSuccess && !rect.isEmpty
                ) {
                    iconBounds = rect
                    true
                } else {
                    false
                }
            }
        }
        if (!acquired) return false
        if (already) return true

        val bounds = iconBounds ?: return false
        val from = android.graphics.Point(bounds.centerX(), bounds.centerY())
        val dropY = if (ctx.videoOnTop) {
            ctx.screen.top + DROP_MARGIN_PX
        } else {
            ctx.screen.bottom - DROP_MARGIN_PX
        }
        val to = android.graphics.Point(ctx.screen.centerX, dropY)
        Log.i(TAG, "dragStepToEdge: holdThenDrag (${from.x},${from.y}) -> (${to.x},${to.y})")
        if (!service.holdThenDrag(from, to, DRAG_HOLD_MS, DRAG_MOVE_MS)) {
            Log.w(TAG, "dragStepToEdge: gesture callback=false — polling state anyway")
        }
        return pollUntil(remaining()) { isTargetInSplitSelectEdge(ctx) }
    }

    /** MENU step 2: tap the card icon; success = the "split screen" menu item appears. */
    private suspend fun menuStepTapCardIcon(ctx: EntryContext): Boolean {
        val deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
        if (!clickWhenFound(remaining(), "card-icon") { findCardIconNode(ctx) }) return false
        return pollUntil(remaining()) { findSplitMenuNode() != null }
    }

    /** MENU step 3: tap "Open in split screen"; success = left/right split-select state. */
    private suspend fun menuStepTapSplitMenu(ctx: EntryContext): Boolean {
        if (isTargetInSplitSelectSide(ctx)) return true
        val deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
        if (!clickWhenFound(remaining(), "split-menu") { findSplitMenuNode() }) return false
        return pollUntil(remaining()) { isTargetInSplitSelectSide(ctx) }
    }

    /**
     * Picker step (DRAG 3 / MENU 4): tap our spacer's label in the partner picker.
     * Click-cycle escalation, gesture-first (FoldWindow's measured worst case: a11y
     * ACTION_CLICK returns true without the click ever taking effect, and its node-identity
     * routing can land on a neighboring non-picker node; gesture taps take the finger's
     * hit-test path). The last cycle falls back to ACTION_CLICK in case gestures are
     * rejected. Every cycle re-checks [pairPresent] first to absorb late settles.
     */
    private suspend fun stepTapPanelInPicker(ctx: EntryContext, pairPresent: () -> Boolean): Boolean {
        val deadline = SystemClock.uptimeMillis() + PICKER_TIMEOUT_MS
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)
        var searchUsed = false

        for (cycle in 0 until PICKER_CLICK_CYCLES) {
            if (remaining() <= 0) break
            if (pairPresent()) return true

            val node = pollForNode(minOf(PICKER_FIND_SLICE_MS, remaining())) { findPanelPickerNode(ctx) }
            if (node == null) {
                // The picker's immediately-visible sections (recent tasks, frequent
                // apps) won't contain our spacer on a fresh install, and the all-apps
                // grid is paginated with only the current page in the a11y tree —
                // page-hunting is unreliable. The picker's search is deterministic:
                // tap it, SET_TEXT the label, and the result list holds the node.
                if (!searchUsed) {
                    searchUsed = searchForPanel(ctx, minOf(SEARCH_BUDGET_MS, remaining()))
                    Log.i(TAG, "picker: cycle=$cycle search-escalation=$searchUsed")
                } else {
                    Log.w(TAG, "picker: cycle=$cycle node-not-found")
                }
                continue
            }
            if (!runCatching { node.refresh() }.getOrDefault(false)) {
                Log.w(TAG, "picker: cycle=$cycle stale node")
                continue
            }
            // gesture, gesture, then a11y-click as the final rung
            val dispatched = if (cycle < PICKER_CLICK_CYCLES - 1) {
                tapNodeCenter(node)
            } else {
                val clickable = clickableAncestorOrSelf(node)
                val clicked = clickable != null && runCatching {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
                if (clicked) true else tapNodeCenter(node)
            }
            Log.i(TAG, "picker: cycle=$cycle dispatched=$dispatched")
            if (!dispatched) continue
            if (pollUntil(minOf(PICKER_VERIFY_SLICE_MS, remaining()), pairPresent)) return true
        }
        // Spend whatever budget remains absorbing a late settle.
        return pollUntil(remaining(), pairPresent)
    }

    // ══════════════════════════════════════════════════════════
    // Success predicates
    // ══════════════════════════════════════════════════════════

    private fun targetWindowMatches(ctx: EntryContext, predicate: (Box, Box) -> Boolean): Boolean {
        val windows = runCatching { service.windows }.getOrDefault(emptyList())
        return windows.any { w ->
            runCatching {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@runCatching false
                if (w.root?.packageName?.toString() != ctx.targetPackage) return@runCatching false
                val bounds = Rect().also { w.getBoundsInScreen(it) }
                predicate(bounds.toBox(), ctx.screen)
            }.getOrDefault(false)
        }
    }

    private fun isTargetInSplitSelectEdge(ctx: EntryContext): Boolean =
        targetWindowMatches(ctx) { pane, screen ->
            if (ctx.videoOnTop) {
                PaneGeometry.isSplitSelectTopPane(pane, screen)
            } else {
                PaneGeometry.isSplitSelectBottomPane(pane, screen)
            }
        }

    private fun isTargetInSplitSelectSide(ctx: EntryContext): Boolean =
        targetWindowMatches(ctx) { pane, screen -> PaneGeometry.isSplitSelectSidePane(pane, screen) }

    private fun collectPairState(ctx: EntryContext): Triple<Boolean, Boolean, List<Box>> {
        val windows = runCatching { service.windows }.getOrDefault(emptyList())
        var hasTarget = false
        var hasSelf = false
        val panes = mutableListOf<Box>()
        for (w in windows) {
            if (runCatching { w.type }.getOrNull() != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = runCatching { w.root?.packageName?.toString() }.getOrNull() ?: continue
            if (pkg != ctx.targetPackage && pkg != ctx.selfPackage) continue
            if (pkg == ctx.targetPackage) hasTarget = true
            if (pkg == ctx.selfPackage) hasSelf = true
            runCatching {
                val bounds = Rect().also { w.getBoundsInScreen(it) }
                panes.add(bounds.toBox())
            }
        }
        return Triple(hasTarget, hasSelf, panes)
    }

    private fun isTopBottomPairPresent(ctx: EntryContext): Boolean {
        val (hasTarget, hasSelf, panes) = collectPairState(ctx)
        return hasTarget && hasSelf && PaneGeometry.isTopBottomSplit(panes, ctx.screen)
    }

    private fun isLeftRightPairPresent(ctx: EntryContext): Boolean {
        val (hasTarget, hasSelf, panes) = collectPairState(ctx)
        return hasTarget && hasSelf && PaneGeometry.isLeftRightSplit(panes, ctx.screen)
    }

    // ══════════════════════════════════════════════════════════
    // Node search
    // ══════════════════════════════════════════════════════════

    private fun launcherRoots(): List<AccessibilityNodeInfo> =
        runCatching { service.windows }.getOrDefault(emptyList())
            .filter { runCatching { it.root?.packageName?.toString() }.getOrNull() == LAUNCHER_PACKAGE }
            .mapNotNull { runCatching { it.root }.getOrNull() }

    /** The Recents card's small header icon (measured: content-desc "고급 옵션" + app label). */
    private fun findCardIconNode(ctx: EntryContext): AccessibilityNodeInfo? {
        val roots = launcherRoots()
        if (roots.isEmpty()) return null
        val label = ctx.targetLabel
        return firstMatch(
            roots,
            listOf(
                { node: AccessibilityNodeInfo ->
                    val desc = node.contentDescription?.toString().orEmpty()
                    desc.contains(CARD_ICON_DESC_KO) && (label == null || desc.contains(label))
                },
                { node ->
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    label != null && CARD_ICON_DESC_EN.any { desc.contains(it) } &&
                        desc.contains(label.lowercase())
                },
                // Structural fallback: a clickable node carrying the label. Recents card
                // BODIES inherit the label too (measured) — dragging the huge card body
                // destroys the Recents session, so only near-icon-sized nodes qualify
                // (≤ screen width / 10).
                { node ->
                    if (label == null || !node.isClickable ||
                        node.contentDescription?.toString()?.contains(label) != true
                    ) {
                        false
                    } else {
                        val rect = Rect()
                        val gotBounds = runCatching { node.getBoundsInScreen(rect) }.isSuccess
                        val maxDim = ctx.screen.width / 10
                        gotBounds && !rect.isEmpty && rect.width() <= maxDim && rect.height() <= maxDim
                    }
                },
            ),
        )
    }

    /** The card popup menu's "Open in split screen" item (MENU recipe). */
    private fun findSplitMenuNode(): AccessibilityNodeInfo? {
        val roots = launcherRoots()
        if (roots.isEmpty()) return null
        return firstMatch(
            roots,
            listOf(
                { node: AccessibilityNodeInfo ->
                    val text = node.text?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    text.contains(SPLIT_MENU_TEXT_KO) || desc.contains(SPLIT_MENU_TEXT_KO)
                },
                { node ->
                    val text = node.text?.toString()?.lowercase().orEmpty()
                    val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                    text.contains(SPLIT_MENU_TEXT_EN) || desc.contains(SPLIT_MENU_TEXT_EN)
                },
            ),
        )
    }

    /**
     * Our spacer's label in the partner picker. Labels are non-clickable text children
     * of the clickable card, so clickability is NOT required here — click resolution
     * walks up to a clickable ancestor. Zero-bounds ghost nodes (a stale recents card's
     * remnant appears before the visible node in DFS order — measured) are filtered in
     * the predicate so the traversal keeps going to the real node.
     */
    private fun findPanelPickerNode(ctx: EntryContext): AccessibilityNodeInfo? {
        val roots = launcherRoots()
        if (roots.isEmpty()) return null
        return firstMatch(
            roots,
            listOf(
                { node: AccessibilityNodeInfo ->
                    val text = node.text?.toString().orEmpty()
                    val desc = node.contentDescription?.toString().orEmpty()
                    if (text.contains(ctx.panelLabel) || desc.contains(ctx.panelLabel)) {
                        // Never the search field itself: after the search escalation its
                        // text IS the label, and it precedes the result item in DFS order.
                        val editable = runCatching { node.isEditable }.getOrDefault(false)
                        val rect = Rect()
                        !editable &&
                            runCatching { node.getBoundsInScreen(rect) }.isSuccess && !rect.isEmpty
                    } else {
                        false
                    }
                },
            ),
        )
    }

    /** Tap the picker's search affordance and type the panel label into the edit field. */
    private suspend fun searchForPanel(ctx: EntryContext, budgetMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + budgetMs
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)

        if (!clickWhenFound(remaining(), "picker-search") { findSearchNode() }) return false
        val edit = pollForNode(remaining()) { findEditField() }
        if (edit == null) {
            Log.w(TAG, "searchForPanel: no edit field after tapping search")
            return false
        }
        val args = android.os.Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ctx.panelLabel,
            )
        }
        return runCatching {
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        }.getOrDefault(false)
    }

    /** The picker's search button (top bar, content-desc "검색 버튼") or the grid's search field. */
    private fun findSearchNode(): AccessibilityNodeInfo? {
        val roots = launcherRoots()
        if (roots.isEmpty()) return null
        return firstMatch(
            roots,
            listOf(
                { node: AccessibilityNodeInfo ->
                    val desc = node.contentDescription?.toString().orEmpty()
                    val text = node.text?.toString().orEmpty()
                    desc.contains(SEARCH_DESC_KO) || text.contains(SEARCH_DESC_KO) ||
                        desc.lowercase().contains(SEARCH_DESC_EN) ||
                        text.lowercase().contains(SEARCH_DESC_EN)
                },
            ),
        )
    }

    private fun findEditField(): AccessibilityNodeInfo? {
        val roots = launcherRoots()
        if (roots.isEmpty()) return null
        // isEditable, not a class-name match: the picker's field is an
        // AutoCompleteTextView (measured), and OEMs swap input classes freely.
        return firstMatch(
            roots,
            listOf(
                { node: AccessibilityNodeInfo ->
                    runCatching { node.isEditable }.getOrDefault(false)
                },
            ),
        )
    }

    /** The divider-handle popup's rotate item ("시계 방향으로 회전"); system window, not launcher. */
    private fun findRotateNode(): AccessibilityNodeInfo? = findPopupNode(ROTATE_DESC_KO, ROTATE_DESC_EN)

    /** The divider-handle popup's switch-windows item ("창 전환"). */
    private fun findSwitchNode(): AccessibilityNodeInfo? = findPopupNode(SWITCH_DESC_KO, SWITCH_DESC_EN)

    private fun findPopupNode(descKo: String, descEn: String): AccessibilityNodeInfo? {
        val roots = runCatching { service.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return firstMatch(
            roots,
            listOf(
                { node: AccessibilityNodeInfo ->
                    val desc = node.contentDescription?.toString()
                    desc != null && (desc.contains(descKo) || desc.lowercase().contains(descEn))
                },
            ),
        )
    }

    /** Prioritized selector search: earlier selectors win; DFS pre-order within a selector. */
    private fun firstMatch(
        roots: List<AccessibilityNodeInfo>,
        selectors: List<(AccessibilityNodeInfo) -> Boolean>,
    ): AccessibilityNodeInfo? {
        for (selector in selectors) {
            for (root in roots) {
                var found: AccessibilityNodeInfo? = null
                var budget = MAX_NODES_VISITED
                walk(root, MAX_TREE_DEPTH) { node ->
                    if (budget-- <= 0) return@walk false
                    val match = runCatching { selector(node) }.getOrDefault(false)
                    if (match) found = node
                    !match
                }
                if (found != null) return found
            }
        }
        return null
    }

    /** Recursive pre-order DFS; [visit] returns "keep going?". Dead-node access is isolated. */
    private fun walk(
        root: AccessibilityNodeInfo,
        maxDepth: Int,
        visit: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        fun recurse(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (depth > maxDepth) return true
            if (!runCatching { visit(node) }.getOrDefault(true)) return false
            val count = runCatching { node.childCount }.getOrDefault(0)
            for (i in 0 until count) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                if (!recurse(child, depth + 1)) return false
            }
            return true
        }
        return recurse(root, 0)
    }

    // ══════════════════════════════════════════════════════════
    // Click / poll primitives
    // ══════════════════════════════════════════════════════════

    /** a11y ACTION_CLICK on the clickable ancestor, else a gesture tap on the node center. */
    private suspend fun clickWhenFound(
        budgetMs: Long,
        what: String,
        find: () -> AccessibilityNodeInfo?,
    ): Boolean {
        val attempt = attempt@{
            val node = find() ?: return@attempt false
            val clickable = clickableAncestorOrSelf(node)
            if (clickable != null && runCatching {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }.getOrDefault(false)
            ) {
                Log.i(TAG, "clickWhenFound: [$what] a11y-click")
                return@attempt true
            }
            val tapped = tapNodeCenter(node)
            if (tapped) Log.i(TAG, "clickWhenFound: [$what] gesture-tap")
            tapped
        }
        val ok = if (budgetMs <= 0) {
            attempt()
        } else {
            withTimeoutOrNull(budgetMs) {
                while (!attempt()) delay(POLL_INTERVAL_MS)
                true
            } ?: false
        }
        if (!ok) Log.w(TAG, "clickWhenFound: [$what] not found/clicked within ${budgetMs}ms")
        return ok
    }

    private fun clickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        repeat(11) {
            val n = cur ?: return null
            if (runCatching { n.isClickable }.getOrDefault(false)) return n
            cur = runCatching { n.parent }.getOrNull()
        }
        return null
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        if (bounds.isEmpty) return false
        return service.tapPoint(bounds.centerX(), bounds.centerY())
    }

    private suspend fun pollUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        if (timeoutMs <= 0) return condition()
        return withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(POLL_INTERVAL_MS)
            true
        } ?: false
    }

    private suspend fun pollForNode(
        timeoutMs: Long,
        find: () -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        if (timeoutMs <= 0) return find()
        return withTimeoutOrNull(timeoutMs) {
            var node = find()
            while (node == null) {
                delay(POLL_INTERVAL_MS)
                node = find()
            }
            node
        }
    }

    private fun dividerBounds(): Rect? {
        val windows = runCatching { service.windows }.getOrDefault(emptyList())
        val divider = runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER }
        }.getOrNull() ?: return null
        return runCatching { Rect().also { divider.getBoundsInScreen(it) } }.getOrNull()
    }

    private fun Rect.toBox() = Box(left, top, right, bottom)

    private companion object {
        const val TAG = "DisplaySplitter"

        const val POLL_INTERVAL_MS = 150L
        // 4s was measured too tight: the drag itself plays ~1.1s and the split-select
        // transition can settle after the remaining budget. 6s × 2 attempts (with
        // success-precheck absorption) matches FoldWindow's retry design.
        const val STEP_TIMEOUT_MS = 6_000L
        const val STEP_ATTEMPTS = 2
        const val PICKER_TIMEOUT_MS = 10_000L
        const val SEARCH_BUDGET_MS = 2_500L
        const val PICKER_CLICK_CYCLES = 3
        const val PICKER_FIND_SLICE_MS = 600L
        // Must exceed doubleTapTimeout (~300ms) so consecutive cycle taps are not
        // misread as a double tap; generous because a successful tap needs the spacer
        // activity to launch and lay out before the pair predicate can turn true.
        const val PICKER_VERIFY_SLICE_MS = 1_500L

        const val MAX_TREE_DEPTH = 50
        const val MAX_NODES_VISITED = 4_000

        const val LAUNCHER_PACKAGE = "com.sec.android.app.launcher"

        // Measured selectors (Korean, One UI 8); English candidates unverified.
        const val CARD_ICON_DESC_KO = "고급 옵션"
        val CARD_ICON_DESC_EN = listOf("more options", "advanced options")
        const val SPLIT_MENU_TEXT_KO = "분할 화면"
        const val SPLIT_MENU_TEXT_EN = "split screen"
        const val ROTATE_DESC_KO = "시계 방향으로 회전"
        const val ROTATE_DESC_EN = "rotate"
        const val SWITCH_DESC_KO = "창 전환"
        const val SWITCH_DESC_EN = "switch"
        const val SEARCH_DESC_KO = "검색"
        const val SEARCH_DESC_EN = "search"

        /** Measured drop recipe: card icon → top edge, y = screen.top + 150px. */
        const val DROP_MARGIN_PX = 150
        const val DRAG_HOLD_MS = 500L
        const val DRAG_MOVE_MS = 600L
    }
}
