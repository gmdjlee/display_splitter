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
 * Enters a split with [the video app, our spacer] by injecting One UI's "swipe up with
 * two fingers" split-screen gesture through the accessibility service, then tapping our
 * spacer in the partner picker. The user must have the One UI multi-window two-finger
 * swipe gesture enabled (Settings → Advanced features → Multi window). The swipe
 * coordinates derive from the CURRENT display bounds, so the gesture is always visually
 * bottom→top no matter how the device is rotated.
 *
 * (`GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` was removed from the framework and
 * `FLAG_ACTIVITY_LAUNCH_ADJACENT` is ignored for background callers; both were verified
 * dead on a real Fold7, here and independently in FoldWindow's device facts.)
 *
 * Every step follows measure-don't-sleep: perform the action, then poll the step's
 * success condition with a deadline. Each step first re-checks its success condition
 * so a previous attempt's late settle is absorbed instead of re-triggering the action.
 *
 * The floating bubble/panel MUST be detached while this runs — injected swipes/taps take
 * the same hit-test path as a finger, and our own touchable overlay would swallow them
 * (measured failure class in FoldWindow). EngagementController hides the overlay for
 * the whole Engaging state.
 */
class SplitEntryDriver(private val service: DividerAccessibilityService) {

    private data class EntryContext(
        val targetPackage: String,
        val targetLabel: String?,
        val selfPackage: String,
        val panelLabel: String,
        val revealBarsFirst: Boolean,
    )

    /** Runs the two-step entry. False = a step failed; the driver has already backed
     *  out of any transient UI it opened — the caller only owns the fail UX.
     *  [revealBarsFirst]: the target is immersive fullscreen (bars hidden), where One UI
     *  ignores the two-finger split gesture — reveal the bars before swiping. */
    suspend fun enterSplit(targetPackage: String, revealBarsFirst: Boolean = false): Boolean {
        val pm = service.packageManager
        val ctx = EntryContext(
            targetPackage = targetPackage,
            targetLabel = runCatching {
                pm.getApplicationInfo(targetPackage, 0).loadLabel(pm).toString()
            }.getOrNull(),
            selfPackage = service.packageName,
            panelLabel = service.getString(R.string.spacer_label),
            revealBarsFirst = revealBarsFirst,
        )
        Log.i(TAG, "enterSplit: pkg=$targetPackage label=${ctx.targetLabel} (two-finger swipe)")
        for (step in 1..STEP_COUNT) {
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
                Log.w(TAG, "enterSplit: step $step attempt $attempt failed")
            }
            if (!ok) {
                // A step-1 failure changed nothing on screen (the swipe was ignored) —
                // injecting BACK there would poke the user's fullscreen app. Only later
                // steps leave split-select/picker UI up that one BACK dismisses.
                if (step > 1) {
                    runCatching {
                        service.performGlobalAction(
                            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK,
                        )
                    }
                }
                return false
            }
        }
        return true
    }

    /**
     * A pre-existing split can be LEFT/RIGHT (manual entry, or the swipe gesture docking
     * to a side): tap the divider handle, then the "Rotate clockwise" popup item.
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

        // Tap the handle, then give the popup one slice to produce a clickable item.
        // A swallowed handle tap leaves no popup to ever find — a single tap followed
        // by a full-budget poll burned the whole timeout on nothing (recorded flake),
        // so re-tap once per slice instead; popup-open is near-instant when the tap
        // lands. If the popup IS open but the item eludes the search, the re-tap may
        // toggle it closed/open — bounded by the same budget, never worse than before.
        var clicked = false
        while (!clicked && remaining() > 0) {
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
            clicked = clickWhenFound(minOf(POPUP_FIND_SLICE_MS, remaining()), what, find)
        }
        if (!clicked) return false
        return pollUntil(remaining(), settled)
    }

    // ══════════════════════════════════════════════════════════
    // Steps
    // ══════════════════════════════════════════════════════════

    private suspend fun performStep(step: Int, ctx: EntryContext): Boolean = when (step) {
        1 -> stepTwoFingerSwipeUp(ctx)
        2 -> stepTapPanelInPicker(ctx) { isSplitPairPresent(ctx) }
        else -> false
    }

    /**
     * Step 1: inject the two-finger bottom→top swipe on the foreground target. The
     * gesture splits the CURRENT app, so the target must hold the foreground first.
     * Success = the target lands in ANY split-select state; a side-docked (left/right)
     * result is fine — EngagementController rotates it to top/bottom afterwards.
     */
    private suspend fun stepTwoFingerSwipeUp(ctx: EntryContext): Boolean {
        val deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MS
        fun remaining() = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)

        if (isTargetInSplitSelect(ctx)) return true // previous attempt's late settle
        // Short budget: nothing here can bring the target forward, so a target that is
        // visible but never focused must fail fast, not burn the whole step timeout.
        // ponytail: no bring-to-front — add a pane tap-to-focus if that path matters.
        if (!pollUntil(minOf(FOREGROUND_WAIT_MS, remaining())) {
                service.activeAppPackage() == ctx.targetPackage
            }
        ) {
            Log.w(TAG, "twoFingerSwipeUp: target never held the foreground")
            return false
        }
        // Immersive fullscreen: One UI does not recognize the two-finger split gesture
        // while the system bars are hidden — both swipe attempts die silently (measured
        // on Fold7/One UI 8.5, fullscreen YouTube). A single-finger bottom-edge swipe
        // is consumed by sticky-immersive as "reveal bars" and ONLY sent when the bars
        // are hidden, so it can never register as the HOME gesture; with the bars
        // transiently visible the split gesture is recognized first try (validated).
        if (ctx.revealBarsFirst) {
            val s = screenBox()
            Log.i(TAG, "revealBars: single-finger edge swipe before split gesture")
            service.singleFingerSwipe(
                android.graphics.Point(s.centerX, s.bottom - REVEAL_EDGE_MARGIN_PX),
                android.graphics.Point(s.centerX, s.bottom - REVEAL_TRAVEL_PX),
                REVEAL_SWIPE_MS,
            )
            delay(REVEAL_SETTLE_MS)
        }
        // Bounds are read at DISPATCH time, not enterSplit() start: the foreground wait
        // can span a rotation, and on the near-square panel stale bounds would put the
        // swipe off-display. Current-rotation bounds keep the gesture visually
        // bottom→top in any orientation.
        val screen = screenBox()
        val from = android.graphics.Point(
            screen.centerX, screen.bottom - SWIPE_EDGE_MARGIN_PX,
        )
        val to = android.graphics.Point(
            screen.centerX, from.y - (screen.height * SWIPE_TRAVEL_FRACTION).toInt(),
        )
        Log.i(TAG, "twoFingerSwipeUp: (${from.x},${from.y}) -> (${to.x},${to.y})")
        if (!service.twoFingerSwipe(from, to, SWIPE_FINGER_GAP_PX, SWIPE_MOVE_MS)) {
            Log.w(TAG, "twoFingerSwipeUp: gesture callback=false — polling state anyway")
        }
        return pollUntil(remaining()) { isTargetInSplitSelect(ctx) }
    }

    /**
     * Picker step (step 2): tap our spacer's label in the partner picker.
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
        val screen = screenBox()
        return windows.any { w ->
            runCatching {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@runCatching false
                if (w.root?.packageName?.toString() != ctx.targetPackage) return@runCatching false
                val bounds = Rect().also { w.getBoundsInScreen(it) }
                predicate(bounds.toBox(), screen)
            }.getOrDefault(false)
        }
    }

    /**
     * Which edge One UI docks the swiped app to can vary by build/orientation: accept
     * any split-select docking — the pane side is corrected downstream. A COMMITTED
     * split pane passes the same geometry checks (~50% edge-docked, full cross-axis),
     * so the divider window — which only exists once a split is committed, never in
     * split-select — must be absent; otherwise re-entry over an existing
     * [video, other-app] split would false-positive here and skip the swipe entirely.
     */
    private fun isTargetInSplitSelect(ctx: EntryContext): Boolean =
        dividerBounds() == null && targetWindowMatches(ctx) { pane, screen ->
            PaneGeometry.isSplitSelectTopPane(pane, screen) ||
                PaneGeometry.isSplitSelectBottomPane(pane, screen) ||
                PaneGeometry.isSplitSelectSidePane(pane, screen)
        }

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

    /** Entry success: both panes committed, top/bottom OR left/right — the caller
     *  rotates a left/right result to top/bottom via the divider popup. */
    private fun isSplitPairPresent(ctx: EntryContext): Boolean {
        val (hasTarget, hasSelf, panes) = collectPairState(ctx)
        val screen = screenBox()
        return hasTarget && hasSelf &&
            (
                PaneGeometry.isTopBottomSplit(panes, screen) ||
                    PaneGeometry.isLeftRightSplit(panes, screen)
                )
    }

    /** Display bounds in the CURRENT rotation — read fresh, never cached across waits. */
    private fun screenBox(): Box {
        val d = service.displayBounds()
        return Box(d.left, d.top, d.right, d.bottom)
    }

    // ══════════════════════════════════════════════════════════
    // Node search
    // ══════════════════════════════════════════════════════════

    private fun launcherRoots(): List<AccessibilityNodeInfo> =
        runCatching { service.windows }.getOrDefault(emptyList())
            .filter { runCatching { it.root?.packageName?.toString() }.getOrNull() == LAUNCHER_PACKAGE }
            .mapNotNull { runCatching { it.root }.getOrNull() }

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

        const val STEP_COUNT = 2
        const val POLL_INTERVAL_MS = 75L
        // 4s was measured too tight: split-select transitions can settle after the
        // remaining budget. 6s × 2 attempts (with success-precheck absorption)
        // matches FoldWindow's retry design.
        const val STEP_TIMEOUT_MS = 6_000L
        const val STEP_ATTEMPTS = 2
        // Transient focus-stealers (dialogs, IME) settle well inside this; a target
        // that is visible but never focused should fail in ~3s total, not ~12s.
        const val FOREGROUND_WAIT_MS = 1_500L

        // Two-finger swipe geometry — device-calibration knobs (Fold7 inner display):
        // start hugging the bottom edge, travel most of the screen height at
        // real-finger speed, fingers ~180px (~69dp) apart.
        // Measured (Fold7, One UI 8.5): starting 40px up landed INSIDE the app's bottom
        // nav row and tap-through opened YouTube's Shorts camera mid-entry; the very
        // edge (system gesture zone, like a real finger) avoids app button delivery.
        const val SWIPE_EDGE_MARGIN_PX = 8
        const val SWIPE_TRAVEL_FRACTION = 0.55f
        const val SWIPE_FINGER_GAP_PX = 180
        const val SWIPE_MOVE_MS = 350L
        // Immersive bar-reveal nudge — exact params validated on device (Fold7):
        // bottom-4px → 180px up in 120ms revealed the bars; the split gesture
        // dispatched ~450ms later was recognized first try.
        const val REVEAL_EDGE_MARGIN_PX = 4
        const val REVEAL_TRAVEL_PX = 180
        const val REVEAL_SWIPE_MS = 120L
        const val REVEAL_SETTLE_MS = 450L
        // One slice of "find + click the divider-popup item" before re-tapping the
        // handle: the popup opens near-instantly when the tap lands, so a dry slice
        // means the tap was swallowed.
        const val POPUP_FIND_SLICE_MS = 800L
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
        const val ROTATE_DESC_KO = "시계 방향으로 회전"
        const val ROTATE_DESC_EN = "rotate"
        const val SWITCH_DESC_KO = "창 전환"
        const val SWITCH_DESC_EN = "switch"
        const val SEARCH_DESC_KO = "검색"
        const val SEARCH_DESC_EN = "search"
    }
}
