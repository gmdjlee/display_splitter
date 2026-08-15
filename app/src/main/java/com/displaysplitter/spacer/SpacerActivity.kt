package com.displaysplitter.spacer

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.displaysplitter.App
import com.displaysplitter.split.DividerAccessibilityService
import com.displaysplitter.split.EngageState
import kotlinx.coroutines.launch

/**
 * The black companion pane. It exists to occupy split-screen space — visually it reads
 * as bezel — with an optional ambient widget (clock/memo) on top ([SpacerContent]).
 * A tap reveals the mode switcher and split controls for a few seconds.
 *
 * Every exit is a plain finish(), never finishAndRemoveTask(): the leftover recents
 * card is what the partner picker's recent-tasks section lists on the next engage, so
 * the entry recipe taps the spacer directly instead of running the picker-search
 * escalation. A dead card lands correctly back in the split pane when tapped — same
 * task reused, no fullscreen steal (measured in FoldWindow, DESIGN_27 G1/G3). If the
 * card IS gone (fresh install, user swiped it away), the search escalation in
 * SplitEntryDriver still covers discovery.
 */
class SpacerActivity : ComponentActivity() {

    private val controller get() = App.from(this).controller

    /** Set once this instance's death has been reported to (or was caused by) the
     *  controller. Activity destroy is asynchronous — it can land seconds after
     *  finish(), inside the NEXT engagement — and an unconditional re-report from
     *  onDestroy reset a live Engaging run (stale teardown killing a fresh entry) and
     *  clobbered the fold re-engage bookkeeping with a fresh, now-wrong foldedShut.
     *  onDestroy stays the catch-all only for genuinely unreported deaths (e.g. the
     *  task swiped away with no multi-window callback delivered). */
    private var stopReported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Zero cover-screen footprint: if we are ever (re)created off the inner
        // display — process-death restore on the cover screen — vanish immediately.
        if (onCoverDisplay()) {
            finish()
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        // The recents card is deliberately kept (see the manifest), but its thumbnail
        // must not be: the pane can hold a user-written memo, and the snapshot would
        // quietly display it in recents on BOTH displays and in the partner picker.
        // The thumbnail of a near-black pane carries no value anyway.
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)

        // The controller's state is the single authority: whenever it is not
        // engaged/engaging (disengage, failure, service loss, process-death restore
        // against a fresh Idle controller), this window has no reason to exist. This
        // also covers a recents-card tap outside an engagement: the fullscreen
        // intruder finishes on its first frame. A StateFlow makes late subscription
        // safe — nothing can be missed. The controller is already in the state this
        // finish reflects, so the death needs no report — flag it before finishing.
        lifecycleScope.launch {
            controller.state.collect { st ->
                if (st is EngageState.Idle || st is EngageState.Failed) {
                    stopReported = true
                    finish()
                }
            }
        }

        setContent {
            SpacerContent(
                settings = App.from(this).settings,
                onFlip = { controller.flipVideoSide() },
                onExit = { controller.disengage() },
            )
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Folding the device shut: the cover screen is none of our business. Vanish silently.
        // Display-based check: this window is a SPLIT PANE, so its own configuration's
        // smallestScreenWidthDp is pane-sized (<600dp on the inner display too) — using it
        // here killed the spacer on every pane resize (measured).
        if (onCoverDisplay()) {
            stopReported = true
            controller.onSpacerStopped(foldedShut = true)
            finish()
            return
        }
        controller.onGeometryChanged()
    }

    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean, newConfig: Configuration) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig)
        // The split was dismissed (divider flung to the edge, app-pair closed, or the
        // device was folded shut and the split dissolved on the way to the cover
        // screen). foldedShut comes from newConfig — callback ordering between this
        // and onConfigurationChanged during a fold is not guaranteed.
        if (!isInMultiWindowMode) {
            stopReported = true
            controller.onSpacerStopped(
                foldedShut = newConfig.smallestScreenWidthDp <
                    DividerAccessibilityService.INNER_DISPLAY_MIN_SW_DP
            )
            finish()
        }
    }

    override fun onDestroy() {
        // A pure configuration recreation (locale, font scale) must not tear down a
        // live engagement — the recreated instance re-binds to controller.state.
        // Already-reported deaths stay silent: this destroy can arrive DURING the
        // next engagement, and a stale report here tore that engagement down.
        if (!isChangingConfigurations && !stopReported) {
            controller.onSpacerStopped(foldedShut = onCoverDisplay())
        }
        super.onDestroy()
    }

    /** Cover-screen detection from the DISPLAY size, never this window's configuration —
     *  as a split pane this window is always narrower than 600dp, even on the inner display. */
    private fun onCoverDisplay(): Boolean {
        val display = getSystemService(android.view.WindowManager::class.java)
            .maximumWindowMetrics.bounds
        val smallestDp = minOf(display.width(), display.height()) / resources.displayMetrics.density
        return smallestDp < DividerAccessibilityService.INNER_DISPLAY_MIN_SW_DP
    }

    private fun hideSystemBars() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
