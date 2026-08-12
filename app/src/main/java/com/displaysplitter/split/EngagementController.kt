package com.displaysplitter.split

import android.content.Context
import android.content.Intent
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import com.displaysplitter.geometry.AspectRatio
import com.displaysplitter.geometry.Box
import com.displaysplitter.geometry.PaneSide
import com.displaysplitter.geometry.PositionPref
import com.displaysplitter.geometry.RatioMath
import com.displaysplitter.geometry.SplitAxis
import com.displaysplitter.geometry.SplitPlan
import com.displaysplitter.geometry.opposite
import com.displaysplitter.settings.SettingsRepository
import com.displaysplitter.spacer.SpacerActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

sealed interface EngageState {
    data object Idle : EngageState
    data class Engaging(val packageName: String) : EngageState
    data class Engaged(
        val packageName: String,
        val plan: SplitPlan,
        val achievedRatio: Float,
        val videoPane: Rect,
    ) : EngageState

    data class Failed(val reason: FailReason) : EngageState
}

enum class FailReason {
    NO_SERVICE, NO_TARGET_APP, NOT_INNER_DISPLAY, FLEX_MODE, RATIO_OFF,
    SPLIT_TIMEOUT, SPLIT_UNAVAILABLE, DIVIDER_LOST, HOLE_UNCOVERED, ADJUST_FAILED,
}

enum class Posture { FLAT, HALF_OPENED, UNKNOWN }

/**
 * Single source of truth for the split engagement lifecycle.
 * All mutation happens on the main dispatcher via [scope].
 *
 * The spacer window observes [state]: any Idle/Failed transition makes it finish
 * itself, so every failure/reset path converges on the same teardown.
 */
class EngagementController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<EngageState>(EngageState.Idle)
    val state: StateFlow<EngageState> = _state.asStateFlow()

    private val _foregroundPackage = MutableStateFlow<String?>(null)
    val foregroundPackage: StateFlow<String?> = _foregroundPackage.asStateFlow()

    private val _serviceConnected = MutableStateFlow(false)
    val serviceConnected: StateFlow<Boolean> = _serviceConnected.asStateFlow()

    private val _posture = MutableStateFlow(Posture.UNKNOWN)
    val posture: StateFlow<Posture> = _posture.asStateFlow()

    private val _onInnerDisplay = MutableStateFlow(true)
    val onInnerDisplay: StateFlow<Boolean> = _onInnerDisplay.asStateFlow()

    private val _visiblePackages = MutableStateFlow<Set<String>>(emptySet())

    /** The bubble shows only when an enabled video app is visible, on the inner display,
     *  outside Flex mode. "Visible" covers a video app occupying one split pane. */
    val bubbleVisible: StateFlow<Boolean> = combine(
        settings.state, _visiblePackages, _state, _posture,
        combine(_serviceConnected, _onInnerDisplay) { c, i -> c && i },
    ) { s, visible, st, posture, ready ->
        s.bubbleEnabled && ready && posture != Posture.HALF_OPENED &&
            (visible.any { it in s.enabledApps } ||
                st is EngageState.Engaged || st is EngageState.Engaging)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var engageJob: Job? = null
    private var autoEngageJob: Job? = null
    private var disengageGraceJob: Job? = null
    private var boundsJob: Job? = null

    /** Package engaged when the device was folded shut, for auto re-engage on unfold. */
    private var reengagePackage: String? = null
    private var reengageAtMs: Long = 0L

    // ---- events from the accessibility service ----------------------------------------------

    fun onServiceConnected(connected: Boolean) {
        _serviceConnected.value = connected
        if (!connected) resetToIdle()
    }

    fun onPosture(posture: Posture) {
        val previous = _posture.value
        _posture.value = posture
        if (posture == Posture.HALF_OPENED) {
            // Flex mode: never interfere. Abort any in-flight automation immediately.
            autoEngageJob?.cancel()
            engageJob?.cancel()
            if (_state.value is EngageState.Engaging) resetToIdle()
        } else if (posture == Posture.FLAT && previous != Posture.FLAT) {
            // Physically unfolding passes through HALF_OPENED, which suppresses (or
            // cancels) auto-engagement — re-evaluate now that the device is flat,
            // regardless of which event (posture vs. foreground app) arrived last.
            _foregroundPackage.value?.let { maybeAutoEngage(it) }
        }
    }

    fun onVisiblePackages(packages: Set<String>) {
        _visiblePackages.value = packages
    }

    fun onInnerDisplayChanged(inner: Boolean) {
        _onInnerDisplay.value = inner
        if (!inner) {
            // Folded shut (or otherwise off the inner display): the cover screen is
            // none of our business. Remember the app, tear everything down silently.
            val st = _state.value
            if (st is EngageState.Engaged || st is EngageState.Engaging) {
                rememberReengage(st)
                resetToIdle()
            }
        }
    }

    fun onForegroundPackage(pkg: String) {
        if (pkg == context.packageName) return
        _foregroundPackage.value = pkg
        val s = settings.state.value
        val isVideo = pkg in s.enabledApps

        when (val st = _state.value) {
            is EngageState.Engaged -> {
                if (isVideo || pkg == SYSTEM_UI) {
                    disengageGraceJob?.cancel()
                } else {
                    // A non-video app took over: restore full screen after a short grace period.
                    if (disengageGraceJob?.isActive != true) {
                        disengageGraceJob = scope.launch {
                            delay(DISENGAGE_GRACE_MS)
                            // Re-query the live focused app window: the last event's package
                            // can be a stale transient (dialog dismissed, IME hidden).
                            val live = DividerAccessibilityService.instance?.activeAppPackage()
                                ?: _foregroundPackage.value
                            if (_state.value is EngageState.Engaged &&
                                live != context.packageName &&
                                live !in settings.state.value.enabledApps
                            ) {
                                disengage()
                            }
                        }
                    }
                }
            }

            is EngageState.Idle, is EngageState.Failed -> {
                if (isVideo) {
                    maybeAutoEngage(pkg)
                } else if (pkg != SYSTEM_UI) {
                    // Transient system-UI windows (shade, recents peek) are neutral:
                    // they must not cancel a pending auto-engage.
                    autoEngageJob?.cancel()
                }
            }

            is EngageState.Engaging -> Unit
        }
    }

    /** Schedules a debounced auto-engage if settings and device state allow it right now. */
    private fun maybeAutoEngage(pkg: String) {
        if (_state.value !is EngageState.Idle && _state.value !is EngageState.Failed) return
        val s = settings.state.value
        val reengageValid = pkg == reengagePackage &&
            SystemClock.elapsedRealtime() - reengageAtMs < REENGAGE_WINDOW_MS
        val shouldAuto = pkg in s.enabledApps && _onInnerDisplay.value &&
            _posture.value != Posture.HALF_OPENED &&
            (s.autoEngage || (s.autoReengage && reengageValid))
        if (!shouldAuto) return
        autoEngageJob?.cancel()
        autoEngageJob = scope.launch {
            delay(AUTO_ENGAGE_DEBOUNCE_MS)
            reengagePackage = null
            engage()
        }
    }

    // ---- events from the spacer window -------------------------------------------------------

    fun onSpacerStopped(foldedShut: Boolean) {
        val st = _state.value
        if (st is EngageState.Engaged || st is EngageState.Engaging) {
            if (foldedShut) rememberReengage(st)
            resetToIdle()
        }
    }

    fun onSpacerBoundsChanged() {
        // The user (or the system) moved the divider while engaged: re-measure and
        // report honestly instead of fighting the user's drag.
        if (_state.value !is EngageState.Engaged) return
        boundsJob?.cancel()
        boundsJob = scope.launch {
            delay(BOUNDS_SETTLE_MS)
            // Re-read after the delay: a disengage/reset in the window must win.
            val st = _state.value as? EngageState.Engaged ?: return@launch
            val service = DividerAccessibilityService.instance ?: return@launch
            val snap = service.panes(st.packageName) ?: return@launch
            val video = snap.video ?: return@launch
            _state.value = st.copy(
                achievedRatio = RatioMath.achievedRatio(video.width(), video.height()),
                videoPane = video,
            )
        }
    }

    // ---- user actions ------------------------------------------------------------------------

    fun engage() {
        if (engageJob?.isActive == true) return
        engageJob = scope.launch { engageInternal() }
    }

    fun disengage() {
        engageJob?.cancel()
        reengagePackage = null
        resetToIdle()
    }

    /** User override: swap sides, re-drag to keep the target, then persist the choice. */
    fun flipVideoSide() {
        val service = DividerAccessibilityService.instance ?: return
        val st = _state.value as? EngageState.Engaged ?: return
        if (engageJob?.isActive == true) return
        engageJob = scope.launch {
            val newSide = st.plan.videoSide.opposite()
            val newPref = if (newSide == PaneSide.FIRST) PositionPref.FIRST else PositionPref.SECOND
            // Hole-avoid planning ignores the ratio; exact mode needs a real one.
            val ratio = settings.state.value.ratio
                ?: if (st.plan.holeAvoidMode) AspectRatio.R16_9 else return@launch
            // The fresh pref is passed directly — never round-tripped through the
            // DataStore StateFlow, whose propagation is not ordered with this coroutine.
            adjustToPlan(service, st.packageName, ratio, retriesLeft = 1, positionPrefOverride = newPref)
            // Persist only after the flip actually took effect: a failed flip must not
            // poison future engagements with an unfulfilled preference.
            if (_state.value is EngageState.Engaged) settings.setPositionPref(newPref)
        }
    }

    // ---- engagement sequence -----------------------------------------------------------------

    private suspend fun engageInternal() {
        val service = DividerAccessibilityService.instance
        if (service == null) return fail(FailReason.NO_SERVICE)
        if (!service.isOnInnerDisplay()) return fail(FailReason.NOT_INNER_DISPLAY)
        if (_posture.value == Posture.HALF_OPENED) return fail(FailReason.FLEX_MODE)

        val s = settings.state.value
        val ratio = s.ratio ?: return fail(FailReason.RATIO_OFF)
        // Target the focused video app, else any visible one (it may be a split pane),
        // else the app we were already engaged with.
        val pkg = _foregroundPackage.value?.takeIf { it in s.enabledApps }
            ?: _visiblePackages.value.firstOrNull { it in s.enabledApps }
            ?: (_state.value as? EngageState.Engaged)?.packageName
            ?: return fail(FailReason.NO_TARGET_APP)

        _state.value = EngageState.Engaging(pkg)

        // 1. Ensure a split containing our spacer exists.
        //    PRIMARY: FLAG_ACTIVITY_LAUNCH_ADJACENT initiates split from a fullscreen
        //    source on Android 12L+ (Samsung One UI 11+). GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
        //    was *removed* from the framework in Android 13, so it is only a legacy
        //    fallback for API <= 32, feature-detected — never called blind.
        val snap = service.panes(pkg)
        if (snap?.divider == null || snap.spacer == null) {
            var ready = attemptSplit(service, pkg)
            if (ready != true && service.tryLegacyToggle()) {
                delay(TOGGLE_SETTLE_MS)
                ready = attemptSplit(service, pkg)
            }
            if (ready != true) return fail(FailReason.SPLIT_UNAVAILABLE)
        }

        // 2. Plan from *measured* geometry — never assume the split axis.
        adjustToPlan(service, pkg, ratio, retriesLeft = 1)
    }

    /**
     * Measure, plan, swap panes if needed (verified), drag the divider (with error
     * compensation on retry), and verify the outcome — ratio in exact mode, hole
     * coverage and side in hole-avoid mode.
     */
    private suspend fun adjustToPlan(
        service: DividerAccessibilityService,
        pkg: String,
        ratio: AspectRatio,
        retriesLeft: Int,
        compensationPx: Int = 0,
        positionPrefOverride: PositionPref? = null,
    ) {
        val settled = settledPanes(service, pkg)
        val divider = settled?.divider ?: return fail(FailReason.DIVIDER_LOST)
        val display = settled.display
        if (abortRequested(service)) return

        val axis = if (divider.width() >= divider.height()) SplitAxis.HORIZONTAL else SplitAxis.VERTICAL
        // Prefer the measured inter-pane gap over the divider window bounds: some
        // builds report the divider window inflated by its touch-target extension.
        val windowThickness = if (axis == SplitAxis.HORIZONTAL) divider.height() else divider.width()
        val thickness = measuredGap(settled.video, settled.spacer, axis) ?: windowThickness
        val cutouts = service.displayCutoutRects().map { Box(it.left, it.top, it.right, it.bottom) }
        var plan = RatioMath.plan(
            axis = axis,
            displayWidth = display.width(),
            displayHeight = display.height(),
            dividerThicknessPx = thickness,
            target = ratio,
            cutouts = cutouts,
            positionPref = positionPrefOverride ?: settings.state.value.positionPref,
        )
        if (plan.noOp) {
            // Nothing to avoid on this axis — splitting would be pure interference.
            _state.value = EngageState.Idle
            return
        }

        // 3. Put the video pane on the planned side (double-tap on the divider swaps
        //    panes). Verify the swap actually happened; retry once if it was swallowed.
        var current: PaneSnapshot = settled
        if (current.video != null && sideOf(current.video!!, divider, axis) != plan.videoSide) {
            var attempts = 2
            while (attempts-- > 0) {
                val d = current.divider ?: break
                service.doubleTap(Point(d.centerX(), d.centerY()))
                delay(SWAP_SETTLE_MS)
                if (abortRequested(service)) return
                current = settledPanes(service, pkg) ?: return fail(FailReason.DIVIDER_LOST)
                val v = current.video
                val dd = current.divider
                if (v != null && dd != null && sideOf(v, dd, axis) == plan.videoSide) break
            }
            // Swap unsupported on this build (plain AOSP dividers have no double-tap
            // swap): re-plan honestly for the side the video actually occupies instead
            // of dragging the wrong pane to the planned length.
            val v = current.video
            val dd = current.divider
            if (v != null && dd != null && sideOf(v, dd, axis) != plan.videoSide) {
                val actualPref =
                    if (sideOf(v, dd, axis) == PaneSide.FIRST) PositionPref.FIRST else PositionPref.SECOND
                plan = RatioMath.plan(
                    axis, display.width(), display.height(), thickness, ratio, cutouts, actualPref,
                )
                if (plan.noOp) {
                    _state.value = EngageState.Idle
                    return
                }
            }
        }

        // 4. Drag the divider to the planned position (adjusted by any measured
        //    snap error from a previous attempt so retries converge). Never drag
        //    from a stale rect: if the divider vanished, the split may be gone and
        //    the gesture would land inside the video app.
        val fresh = current.divider
            ?: settledPanes(service, pkg)?.divider
            ?: return fail(FailReason.DIVIDER_LOST)
        val from = Point(fresh.centerX(), fresh.centerY())
        val targetCenter = plan.dividerCenterPx + compensationPx
        val to = if (axis == SplitAxis.HORIZONTAL) {
            Point(fresh.centerX(), targetCenter)
        } else {
            Point(targetCenter, fresh.centerY())
        }
        val dragged = service.dragDivider(from, to)
        delay(DRAG_SETTLE_MS)
        if (abortRequested(service)) return

        // 5. Verify against what the system actually gave us (snap points may differ).
        val result = service.panes(pkg)
        val videoPane = result?.video
        if (videoPane == null || !dragged) {
            if (retriesLeft > 0) {
                return adjustToPlan(service, pkg, ratio, retriesLeft - 1, compensationPx, positionPrefOverride)
            }
            return fail(FailReason.ADJUST_FAILED)
        }

        // The video must sit on the planned side in every mode — a converged-but-
        // wrong-side result would defeat the hole avoidance silently.
        val sideOk = result.divider == null || sideOf(videoPane, result.divider, axis) == plan.videoSide
        if (!sideOk) {
            if (retriesLeft > 0) {
                return adjustToPlan(service, pkg, ratio, retriesLeft - 1, compensationPx, positionPrefOverride)
            }
            return fail(FailReason.ADJUST_FAILED)
        }

        if (plan.holeAvoidMode && !plan.holeExposedByChoice) {
            // The whole point of this mode: the spacer must actually cover the hole.
            val spacer = result.spacer
            val covered = spacer != null &&
                RatioMath.holeCovered(Box(spacer.left, spacer.top, spacer.right, spacer.bottom), cutouts, axis)
            if (!covered) {
                if (retriesLeft > 0) {
                    return adjustToPlan(service, pkg, ratio, retriesLeft - 1, compensationPx, positionPrefOverride)
                }
                return fail(FailReason.HOLE_UNCOVERED)
            }
        }

        val achieved = RatioMath.achievedRatio(videoPane.width(), videoPane.height())
        if (plan.exactRatio && !RatioMath.isWithinTolerance(achieved, ratio)) {
            if (retriesLeft > 0) {
                // Compensate the systematic snap error instead of replaying the same drag.
                val measuredLen = if (axis == SplitAxis.HORIZONTAL) videoPane.height() else videoPane.width()
                val err = measuredLen - plan.videoPaneLengthPx
                val delta = if (plan.videoSide == PaneSide.FIRST) -err else err
                return adjustToPlan(service, pkg, ratio, retriesLeft - 1, compensationPx + delta, positionPrefOverride)
            }
            // Still off after compensation: report honestly, never claim exact.
            coroutineContext.ensureActive()
            _state.value = EngageState.Engaged(pkg, plan.copy(exactRatio = false), achieved, videoPane)
            return
        }
        coroutineContext.ensureActive()
        _state.value = EngageState.Engaged(pkg, plan, achieved, videoPane)
    }

    /**
     * Launch the spacer into the adjacent pane and wait for the split (divider + our
     * spacer) to materialize. On a fullscreen source this *initiates* the split.
     * Returns true on success, false/null on timeout.
     */
    private suspend fun attemptSplit(service: DividerAccessibilityService, pkg: String): Boolean? {
        launchSpacerAdjacent()
        return withTimeoutOrNull(SPLIT_TIMEOUT_MS) {
            var elapsed = 0L
            var relaunched = false
            while (true) {
                if (abortRequested(service)) return@withTimeoutOrNull false
                val snap = service.panes(pkg)
                // Success requires the VIDEO to still be present: launching adjacent into
                // a pre-existing foreign split can evict the video pane, and a split
                // without the video is not a success — never claim it.
                if (snap?.divider != null && snap.spacer != null && snap.video != null) break
                if (snap?.video == null && snap?.spacer != null) {
                    // The video was evicted (foreign-split replacement went the wrong way).
                    return@withTimeoutOrNull false
                }
                delay(POLL_MS)
                elapsed += POLL_MS
                // One mid-wait relaunch covers the race where LAUNCH_ADJACENT fired before
                // the shell was ready. Re-check spacer/divider *immediately before* the
                // relaunch — MULTIPLE_TASK would otherwise spawn a duplicate spacer task
                // whenever the first spacer lands after this check but before the launch.
                if (!relaunched && elapsed >= RELAUNCH_AFTER_MS) {
                    relaunched = true
                    val now = service.panes(pkg)
                    if (now?.spacer == null && now?.divider == null) launchSpacerAdjacent()
                }
            }
            true
        }
    }

    /** Polls briefly for a snapshot whose divider is present — the divider window can
     *  transiently disappear right after swap/drag animations. */
    private suspend fun settledPanes(service: DividerAccessibilityService, pkg: String): PaneSnapshot? {
        var snap: PaneSnapshot? = service.panes(pkg)
        var polls = DIVIDER_SETTLE_POLLS
        while (snap?.divider == null && polls-- > 0) {
            delay(POLL_MS)
            snap = service.panes(pkg)
        }
        return snap
    }

    // ---- helpers -----------------------------------------------------------------------------

    /** Environment changed under an in-flight adjustment: stop touching the divider. */
    private fun abortRequested(service: DividerAccessibilityService): Boolean =
        DividerAccessibilityService.instance !== service ||
            !service.isOnInnerDisplay() ||
            _posture.value == Posture.HALF_OPENED

    private fun sideOf(pane: Rect, divider: Rect, axis: SplitAxis): PaneSide {
        val paneCenter = if (axis == SplitAxis.HORIZONTAL) pane.centerY() else pane.centerX()
        val dividerCenter = if (axis == SplitAxis.HORIZONTAL) divider.centerY() else divider.centerX()
        return if (paneCenter < dividerCenter) PaneSide.FIRST else PaneSide.SECOND
    }

    /** Effective inter-pane gap measured from the panes themselves, when available. */
    private fun measuredGap(video: Rect?, spacer: Rect?, axis: SplitAxis): Int? {
        if (video == null || spacer == null) return null
        val gap = if (axis == SplitAxis.HORIZONTAL) {
            maxOf(video.top, spacer.top) - minOf(video.bottom, spacer.bottom)
        } else {
            maxOf(video.left, spacer.left) - minOf(video.right, spacer.right)
        }
        return gap.takeIf { it > 0 }
    }

    private fun rememberReengage(st: EngageState) {
        reengagePackage = (st as? EngageState.Engaged)?.packageName
            ?: (st as? EngageState.Engaging)?.packageName
        reengageAtMs = SystemClock.elapsedRealtime()
    }

    private fun launchSpacerAdjacent() {
        // NEW_TASK is mandatory for LAUNCH_ADJACENT; the pair initiates split from a
        // fullscreen source (Android 12L+). The launch is BAL-permitted because our
        // overlay bubble is a visible window whenever engagement can be triggered.
        context.startActivity(
            Intent(context, SpacerActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
        )
    }

    private fun fail(reason: FailReason) {
        // Failed is observed by the spacer window, which finishes itself.
        _state.value = EngageState.Failed(reason)
    }

    private fun resetToIdle() {
        engageJob?.cancel()
        autoEngageJob?.cancel()
        disengageGraceJob?.cancel()
        boundsJob?.cancel()
        _state.value = EngageState.Idle
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val TOGGLE_SETTLE_MS = 350L
        const val POLL_MS = 150L
        const val RELAUNCH_AFTER_MS = 600L
        const val SPLIT_TIMEOUT_MS = 5_000L
        const val SWAP_SETTLE_MS = 650L
        const val DRAG_SETTLE_MS = 600L
        const val BOUNDS_SETTLE_MS = 250L
        const val AUTO_ENGAGE_DEBOUNCE_MS = 800L
        const val DISENGAGE_GRACE_MS = 1_500L
        const val REENGAGE_WINDOW_MS = 60_000L
        const val DIVIDER_SETTLE_POLLS = 3
    }
}
