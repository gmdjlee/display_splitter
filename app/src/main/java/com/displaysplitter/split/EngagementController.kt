package com.displaysplitter.split

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.os.SystemClock
import com.displaysplitter.geometry.AspectRatio
import com.displaysplitter.geometry.Box
import com.displaysplitter.geometry.PaneSide
import com.displaysplitter.geometry.PositionPref
import com.displaysplitter.geometry.RatioMath
import com.displaysplitter.geometry.SplitPlan
import com.displaysplitter.geometry.opposite
import com.displaysplitter.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    SPLIT_UNAVAILABLE, DIVIDER_LOST, ADJUST_FAILED,
}

enum class Posture { FLAT, HALF_OPENED, UNKNOWN }

/**
 * Single source of truth for the split engagement lifecycle.
 * All mutation happens on the main dispatcher via [scope].
 *
 * Engagement starts ONLY from the user's explicit Apply (or the opt-in re-apply after
 * unfolding of a split the user had applied) — never automatically on app launch.
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

    /** True while OverlayService has a window attached; flipped false only AFTER
     *  removeViewImmediate returns, so injections gated on it can't hit our window. */
    val overlayAttached = MutableStateFlow(false)

    /** The bubble shows only when an enabled video app is visible, on the inner display,
     *  outside Flex mode. It is force-hidden while Engaging: the entry injects the split
     *  swipe and picker taps, which take the finger's hit-test path — our own touchable
     *  overlay would swallow them (SplitEntryDriver). */
    val bubbleVisible: StateFlow<Boolean> = combine(
        settings.state, _visiblePackages, _state, _posture,
        combine(_serviceConnected, _onInnerDisplay) { c, i -> c && i },
    ) { s, visible, st, posture, ready ->
        s.bubbleEnabled && ready && posture != Posture.HALF_OPENED &&
            st !is EngageState.Engaging &&
            (visible.any { it in s.enabledApps } || st is EngageState.Engaged)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var engageJob: Job? = null
    private var reengageJob: Job? = null
    private var disengageGraceJob: Job? = null
    private var boundsJob: Job? = null

    /** A settings change was skipped because the split wasn't visibly intact (e.g. the
     *  settings screen fullscreen over it); re-applied on the next video-app foreground
     *  event instead of being silently dropped. */
    private var pendingAdjust = false

    /**
     * Status-bar visibility sampled from the overlay window's insets (OverlayService).
     * Hidden bars = the foreground app is immersive fullscreen, where One UI ignores
     * the two-finger split gesture (measured on device) — the entry must reveal the
     * bars first. Last-known value: the overlay is detached while Engaging, but the
     * user's Apply tap guarantees a fresh sample moments before engage() runs.
     */
    var statusBarsVisible: Boolean = true

    init {
        // Target ratio or video position changed while engaged (quick panel or settings
        // screen): re-adjust to the new target. Observed from the settings flow so every
        // entry point gets this without wiring.
        scope.launch {
            var last: Pair<AspectRatio?, PositionPref>? = null
            settings.state.map { it.ratio to it.positionPref }.distinctUntilChanged().collect { cur ->
                val (ratio, pref) = cur
                // Ratio off: nothing can be applied — leave the change unconsumed
                // (don't advance `last`) so re-enabling the ratio re-delivers it.
                if (ratio == null) return@collect
                val prev = last
                last = cur
                // prev == null only for the seed emission, which is never a change.
                val ratioChanged = prev != null && prev.first != ratio
                val prefChanged = prev != null && prev.second != pref
                // Changed mid-engagement: let the in-flight run land first, then correct
                // to the newest target (StateFlow conflation keeps only the latest value).
                engageJob?.join()
                // Taps during the join are conflated behind us: bail and let that newer
                // emission drive, rather than first adjusting to a superseded target.
                if (settings.state.value.let { it.ratio to it.positionPref } != cur) return@collect
                val st = _state.value as? EngageState.Engaged ?: return@collect
                val service = DividerAccessibilityService.instance ?: return@collect
                if (engageJob?.isActive == true) return@collect
                val needRatio = ratioChanged && !RatioMath.isWithinTolerance(st.achievedRatio, ratio)
                // A pref already satisfied by the current side is a no-op — this also
                // swallows the pref emissions flipVideoSide and the reconciliation in
                // launchAdjust persist. AUTO is resolved on the spot from the same
                // inputs RatioMath.plan uses, so it can no-op symmetrically.
                val needPref = prefChanged && st.plan.videoSide != resolvedSide(service, pref)
                if (!needRatio && !needPref) return@collect
                launchAdjust(st, service, ratio, pref)
            }
        }
    }

    /** Engaging → overlay-detach → verify-intact → adjustToPlan, shared by the settings
     *  observer and the pending-change retry. [ratio]/[pref] are passed straight through,
     *  never re-read via the StateFlow mid-flight (same ordering caveat as flipVideoSide). */
    private fun launchAdjust(
        st: EngageState.Engaged,
        service: DividerAccessibilityService,
        ratio: AspectRatio,
        pref: PositionPref,
    ) {
        engageJob = scope.launch {
            // Engaging detaches the overlay immediately (OverlayService skips its
            // hide debounce for this state); handshake on the actual detach — the
            // drag takes the finger's hit-test path and a still-attached quick
            // panel would swallow it (see bubbleVisible).
            _state.value = EngageState.Engaging(st.packageName)
            awaitOverlayDetached()
            // Only adjust a split that is visibly intact (e.g. the settings screen
            // fullscreen over the split hides the divider): failing would tear the
            // healthy split down. Skip, but keep the change pending — it re-applies
            // on the next video-app foreground event (retryPendingAdjust).
            val snap = settledPanes(service, st.packageName)
            if (snap?.divider == null || snap.video == null) {
                pendingAdjust = true
                _state.value = st
                // st predates the suspensions above and bounds events are suppressed
                // while Engaging: re-measure in case the divider moved meanwhile.
                onSpacerBoundsChanged()
                return@launch
            }
            pendingAdjust = false
            // ponytail: failures past this point keep engage-path semantics
            // (Failed → spacer teardown); fail-soft needs adjustToPlan surgery.
            adjustToPlan(service, st.packageName, ratio, retriesLeft = 1, positionPrefOverride = pref)
            // The chips persist the pref BEFORE this adjust proves it achievable; if the
            // swap was unavailable, adjustToPlan re-planned for the side the video really
            // occupies — write that side back so the stored pref never disagrees with
            // reality (and the chip stays re-tappable). Explicit FIRST/SECOND only:
            // AUTO stays AUTO. The echo emission is swallowed by the observer's guard.
            val end = _state.value as? EngageState.Engaged ?: return@launch
            if (pref != PositionPref.AUTO && end.plan.videoSide != resolvedSide(service, pref)) {
                settings.setPositionPref(
                    if (end.plan.videoSide == PaneSide.FIRST) PositionPref.FIRST else PositionPref.SECOND,
                )
            }
        }
    }

    /** Re-applies a settings change that was skipped while the split was covered,
     *  now that a video app is back in the foreground. */
    private fun retryPendingAdjust(st: EngageState.Engaged) {
        if (!pendingAdjust || engageJob?.isActive == true) return
        val service = DividerAccessibilityService.instance ?: return
        val s = settings.state.value
        val ratio = s.ratio ?: return
        launchAdjust(st, service, ratio, s.positionPref)
    }

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
            reengageJob?.cancel()
            engageJob?.cancel()
            if (_state.value is EngageState.Engaging) resetToIdle()
        } else if (posture == Posture.FLAT && previous != Posture.FLAT) {
            // Physically unfolding passes through HALF_OPENED, which suppresses (or
            // cancels) the re-engage — re-evaluate now that the device is flat,
            // regardless of which event (posture vs. foreground app) arrived last.
            _foregroundPackage.value?.let { maybeReengage(it) }
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
        if (pkg == context.packageName) {
            // Closing our own settings screen re-reveals the split, but the pane that
            // takes focus is the SPACER, not the video (measured: mCurrentFocus =
            // SpacerActivity) — so the isVideo branch below never runs and a change
            // skipped while the divider was covered stayed pending until the user
            // happened to touch the video pane. This is that missing signal.
            (_state.value as? EngageState.Engaged)?.let { retryPendingAdjust(it) }
            return
        }
        _foregroundPackage.value = pkg
        val s = settings.state.value
        val isVideo = pkg in s.enabledApps

        when (val st = _state.value) {
            is EngageState.Engaged -> {
                if (isVideo || pkg == SYSTEM_UI) {
                    disengageGraceJob?.cancel()
                    if (isVideo) retryPendingAdjust(st)
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
                    maybeReengage(pkg)
                } else if (pkg != SYSTEM_UI) {
                    // Transient system-UI windows (shade, recents peek) are neutral:
                    // they must not cancel a pending re-engage.
                    reengageJob?.cancel()
                }
            }

            is EngageState.Engaging -> Unit
        }
    }

    /** Schedules the debounced re-apply after unfolding — the ONLY non-explicit trigger,
     *  and it only restores a split the user had applied before folding (opt-in setting). */
    private fun maybeReengage(pkg: String) {
        if (_state.value !is EngageState.Idle && _state.value !is EngageState.Failed) return
        val s = settings.state.value
        val reengageValid = pkg == reengagePackage &&
            SystemClock.elapsedRealtime() - reengageAtMs < REENGAGE_WINDOW_MS
        if (!(s.autoReengage && reengageValid && pkg in s.enabledApps &&
                _onInnerDisplay.value && _posture.value != Posture.HALF_OPENED)
        ) {
            return
        }
        reengageJob?.cancel()
        reengageJob = scope.launch {
            delay(REENGAGE_DEBOUNCE_MS)
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
                // The user may have swapped panes via the system's own divider popup:
                // videoSide must track the measured side or every side-dependent
                // consumer (needPref guard, flip direction, diagram) goes stale.
                plan = snap.divider?.let { d -> st.plan.copy(videoSide = sideOf(video, d)) }
                    ?: st.plan,
                achievedRatio = RatioMath.achievedRatio(video.width(), video.height()),
                videoPane = video,
            )
        }
    }

    // ---- user actions ------------------------------------------------------------------------

    fun engage() {
        if (engageJob?.isActive == true) return
        android.util.Log.i(TAG, "engage() requested (state=${_state.value})")
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
            val ratio = settings.state.value.ratio ?: return@launch
            // Same overlay discipline as every other injection path: Engaging detaches
            // the bubble immediately; handshake before tapping the divider.
            _state.value = EngageState.Engaging(st.packageName)
            awaitOverlayDetached()
            // The fresh pref is passed directly — never round-tripped through the
            // DataStore StateFlow, whose propagation is not ordered with this coroutine.
            adjustToPlan(service, st.packageName, ratio, retriesLeft = 1, positionPrefOverride = newPref)
            // Persist only after the flip actually took effect: a failed flip must not
            // poison future engagements with an unfulfilled preference. "Took effect"
            // means the achieved side matches — the swap-unavailable fallback still
            // lands Engaged, on the original side, after re-planning honestly.
            if ((_state.value as? EngageState.Engaged)?.plan?.videoSide == newSide) {
                settings.setPositionPref(newPref)
            }
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
        // Engaging detaches the overlay immediately (OverlayService); handshake on the
        // actual detach before ANY injection below — our own touchable window on the
        // finger's hit-test path would swallow the gestures.
        awaitOverlayDetached()

        // 1. Ensure a split containing the video and our spacer exists. Initiation =
        //    injected two-finger bottom→top swipe (One UI's multi-window split gesture)
        //    on the foreground video app, then a picker tap (SplitEntryDriver) —
        //    TOGGLE_SPLIT_SCREEN was removed from the framework and LAUNCH_ADJACENT is
        //    ignored for background callers (verified on device).
        //    Settled read only when our spacer is already up: One UI's embedded divider
        //    window flickers out of the a11y windows list during animations, and a single
        //    missed read would re-run the whole entry over a perfectly good split. On a
        //    fresh engage (no spacer window) the divider cannot exist — polling for it
        //    would burn the whole settle budget before the swipe even starts.
        var snap = service.panes(pkg)
        if (snap?.spacer != null && snap.divider == null) snap = settledPanes(service, pkg)
        if (snap?.divider == null || snap.spacer == null || snap.video == null) {
            val entered = SplitEntryDriver(service)
                .enterSplit(pkg, revealBarsFirst = !statusBarsVisible)
            if (abortRequested(service)) return
            // The driver has already backed out of any transient UI it opened; a
            // step-1 (swipe) failure leaves the user's app untouched on purpose.
            if (!entered) return fail(FailReason.SPLIT_UNAVAILABLE)
            // The entry verified the pane PAIR, but the divider window lags the commit
            // animation — give it a real settle budget before declaring it lost.
            val deadline = SystemClock.uptimeMillis() + POST_ENTRY_SETTLE_MS
            snap = service.panes(pkg)
            while (snap?.divider == null && SystemClock.uptimeMillis() < deadline) {
                delay(POLL_MS)
                snap = service.panes(pkg)
            }
            android.util.Log.i(
                TAG,
                "post-entry snap: divider=${snap?.divider} spacer=${snap?.spacer} video=${snap?.video}",
            )
        }

        // 2. Planning needs a horizontal divider (top/bottom panes — the only layout
        //    where the full-width video pane can hit the exact ratio). A left/right
        //    split (manual entry, or the swipe gesture docking to a side) is rotated
        //    once via the divider popup.
        val divider = snap?.divider ?: return fail(FailReason.DIVIDER_LOST)
        if (divider.width() < divider.height()) {
            val rotated = SplitEntryDriver(service).rotateToTopBottom(ROTATE_TIMEOUT_MS) {
                service.panes(pkg)?.divider?.let { it.width() >= it.height() } == true
            }
            if (abortRequested(service)) return
            if (!rotated) return fail(FailReason.ADJUST_FAILED)
        }

        // 3. Plan from *measured* geometry and drive the divider.
        adjustToPlan(service, pkg, ratio, retriesLeft = 1)
    }

    /**
     * Measure, plan, swap panes if needed (verified), drag the divider (with error
     * compensation on retry), and verify the achieved ratio honestly.
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

        // Prefer the measured inter-pane gap over the divider window bounds: some
        // builds report the divider window inflated by its touch-target extension.
        val thickness = measuredGap(settled.video, settled.spacer) ?: divider.height()
        val cutouts = service.displayCutoutRects().map { Box(it.left, it.top, it.right, it.bottom) }
        var plan = RatioMath.plan(
            displayWidth = display.width(),
            displayHeight = display.height(),
            dividerThicknessPx = thickness,
            target = ratio,
            cutouts = cutouts,
            positionPref = positionPrefOverride ?: settings.state.value.positionPref,
        )

        // Put the video pane on the planned side. The only swap that works on One UI 8+
        // is the divider-handle popup's "Switch windows" item (a bare double-tap merely
        // opens and mis-taps that popup — measured). Verify the swap actually happened;
        // retry once if it was swallowed.
        var current: PaneSnapshot = settled
        if (current.video != null && sideOf(current.video!!, divider) != plan.videoSide) {
            val driver = SplitEntryDriver(service)
            var attempts = 2
            while (attempts-- > 0) {
                driver.swapPanes(SWAP_POPUP_TIMEOUT_MS) {
                    service.panes(pkg)?.let { s2 ->
                        val v2 = s2.video
                        val d2 = s2.divider
                        v2 != null && d2 != null && sideOf(v2, d2) == plan.videoSide
                    } == true
                }
                // No fixed settle: settledPanes' divider-present poll below absorbs the
                // swap/popup-dismiss animation (the divider leaves the windows list for it).
                if (abortRequested(service)) return
                current = settledPanes(service, pkg) ?: return fail(FailReason.DIVIDER_LOST)
                val v = current.video
                val dd = current.divider
                if (v != null && dd != null && sideOf(v, dd) == plan.videoSide) break
            }
            // Swap unavailable (popup item missing on this build): re-plan honestly for
            // the side the video actually occupies instead of dragging the wrong pane
            // to the planned length.
            val v = current.video
            val dd = current.divider
            if (v != null && dd != null && sideOf(v, dd) != plan.videoSide) {
                val actualPref =
                    if (sideOf(v, dd) == PaneSide.FIRST) PositionPref.FIRST else PositionPref.SECOND
                plan = RatioMath.plan(
                    display.width(), display.height(), thickness, ratio, cutouts, actualPref,
                )
            }
        }

        // Drag the divider to the planned position (adjusted by any measured snap
        // error from a previous attempt so retries converge). Never drag from a
        // stale rect: if the divider vanished, the split may be gone and the
        // gesture would land inside the video app.
        val fresh = current.divider
            ?: settledPanes(service, pkg)?.divider
            ?: return fail(FailReason.DIVIDER_LOST)
        // Already converged (re-apply of the same target, or a retry whose previous
        // drag actually landed despite a false dispatch result): don't replay a ~1s
        // no-op hold+drag+settle. The acceptance test mirrors the post-drag verify.
        current.video?.let { v ->
            val converged = sideOf(v, fresh) == plan.videoSide &&
                if (plan.exactRatio) {
                    RatioMath.isWithinTolerance(RatioMath.achievedRatio(v.width(), v.height()), ratio)
                } else {
                    v.height() == plan.videoPaneLengthPx
                }
            if (converged) {
                coroutineContext.ensureActive()
                setEngaged(pkg, plan, RatioMath.achievedRatio(v.width(), v.height()), v)
                return
            }
        }
        val from = Point(fresh.centerX(), fresh.centerY())
        val to = Point(fresh.centerX(), plan.dividerCenterPx + compensationPx)
        val dragged = service.dragDivider(from, to)
        awaitDragSettle(service, pkg, current.video)
        if (abortRequested(service)) return

        // Verify against what the system actually gave us (snap points may differ).
        // Settled read: the divider leaves the windows list for whole animation
        // durations, and the side verdict below needs it present to mean anything.
        val result = settledPanes(service, pkg)
        val videoPane = result?.video
        if (result == null || videoPane == null || !dragged) {
            if (retriesLeft > 0) {
                return adjustToPlan(service, pkg, ratio, retriesLeft - 1, compensationPx, positionPrefOverride)
            }
            return fail(FailReason.ADJUST_FAILED)
        }

        // The video must sit on the planned side — a converged-but-wrong-side result
        // would put the video under the camera hole silently. Divider still absent
        // after the settled read = no verdict: retry (whose own settled re-read
        // decides) instead of recording the planned side as fact.
        val dividerNow = result.divider
        val sideOk = dividerNow != null && sideOf(videoPane, dividerNow) == plan.videoSide
        if (!sideOk) {
            if (retriesLeft > 0) {
                return adjustToPlan(service, pkg, ratio, retriesLeft - 1, compensationPx, positionPrefOverride)
            }
            return fail(FailReason.ADJUST_FAILED)
        }

        val achieved = RatioMath.achievedRatio(videoPane.width(), videoPane.height())
        if (plan.exactRatio && !RatioMath.isWithinTolerance(achieved, ratio)) {
            if (retriesLeft > 0) {
                // Compensate the systematic snap error instead of replaying the same drag.
                val err = videoPane.height() - plan.videoPaneLengthPx
                val delta = if (plan.videoSide == PaneSide.FIRST) -err else err
                return adjustToPlan(service, pkg, ratio, retriesLeft - 1, compensationPx + delta, positionPrefOverride)
            }
            // Still off after compensation: report honestly, never claim exact.
            coroutineContext.ensureActive()
            setEngaged(pkg, plan.copy(exactRatio = false), achieved, videoPane)
            return
        }
        coroutineContext.ensureActive()
        setEngaged(pkg, plan, achieved, videoPane)
    }

    /** The one success log on the engage path — everything else only logs failures. */
    private fun setEngaged(pkg: String, plan: SplitPlan, achieved: Float, video: Rect) {
        android.util.Log.i(
            TAG, "engaged: pkg=$pkg achieved=$achieved exact=${plan.exactRatio} pane=$video",
        )
        _state.value = EngageState.Engaged(pkg, plan, achieved, video)
    }

    /** Waits for OverlayService's detach handshake. Bounded: the service may not be
     *  running at all (bubble disabled, permission revoked), in which case the flag
     *  is already false and this returns immediately. */
    private suspend fun awaitOverlayDetached() {
        withTimeoutOrNull(OVERLAY_DETACH_TIMEOUT_MS) { overlayAttached.first { !it } }
    }

    /** Post-drag settle: floor for the snap animation, then exit as soon as the video
     *  pane bounds have moved off [before] and held still for one poll. Bounds that
     *  never move (no-op drag, or a11y bounds that only update at animation end) fall
     *  back to the full fixed budget — an early stale read would feed the retry a
     *  wrong compensation value. */
    private suspend fun awaitDragSettle(
        service: DividerAccessibilityService,
        pkg: String,
        before: Rect?,
    ) {
        delay(DRAG_SETTLE_FLOOR_MS)
        val deadline = SystemClock.uptimeMillis() + DRAG_SETTLE_MS - DRAG_SETTLE_FLOOR_MS
        var prev: Rect? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val cur = service.panes(pkg)?.video
            if (cur != null && cur != before && cur == prev) return
            prev = cur
            delay(POLL_MS)
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

    /** Environment changed under an in-flight engagement: stop touching the screen. */
    private fun abortRequested(service: DividerAccessibilityService): Boolean =
        DividerAccessibilityService.instance !== service ||
            !service.isOnInnerDisplay() ||
            _posture.value == Posture.HALF_OPENED

    /** FIRST = top pane. Horizontal divider only — engageInternal rotates before planning. */
    private fun sideOf(pane: Rect, divider: Rect): PaneSide =
        if (pane.centerY() < divider.centerY()) PaneSide.FIRST else PaneSide.SECOND

    /** The pane side [pref] denotes on the current geometry. AUTO is resolved from the
     *  same display/cutout inputs RatioMath.plan uses — cheap, non-suspending reads. */
    private fun resolvedSide(service: DividerAccessibilityService, pref: PositionPref): PaneSide =
        RatioMath.resolveVideoSide(
            service.displayBounds().height(),
            service.displayCutoutRects().map { Box(it.left, it.top, it.right, it.bottom) },
            pref,
        )

    /** Effective inter-pane gap measured from the panes themselves, when available. */
    private fun measuredGap(video: Rect?, spacer: Rect?): Int? {
        if (video == null || spacer == null) return null
        val gap = maxOf(video.top, spacer.top) - minOf(video.bottom, spacer.bottom)
        return gap.takeIf { it > 0 }
    }

    private fun rememberReengage(st: EngageState) {
        reengagePackage = (st as? EngageState.Engaged)?.packageName
            ?: (st as? EngageState.Engaging)?.packageName
        reengageAtMs = SystemClock.elapsedRealtime()
    }

    private fun fail(reason: FailReason) {
        android.util.Log.w(TAG, "engagement failed: $reason")
        // Failed is observed by the spacer window, which finishes itself.
        _state.value = EngageState.Failed(reason)
    }

    private fun resetToIdle() {
        engageJob?.cancel()
        reengageJob?.cancel()
        disengageGraceJob?.cancel()
        boundsJob?.cancel()
        pendingAdjust = false
        _state.value = EngageState.Idle
    }

    private companion object {
        const val TAG = "DisplaySplitter"
        const val SYSTEM_UI = "com.android.systemui"
        const val POLL_MS = 75L
        const val POST_ENTRY_SETTLE_MS = 3_000L
        const val SWAP_POPUP_TIMEOUT_MS = 4_000L
        const val ROTATE_TIMEOUT_MS = 5_000L
        // Cap for the post-drag settle poll (awaitDragSettle); the floor guards against
        // reading bounds before the snap animation starts reporting.
        const val DRAG_SETTLE_MS = 600L
        const val DRAG_SETTLE_FLOOR_MS = 150L
        const val BOUNDS_SETTLE_MS = 250L
        // Safety cap on the overlay-detach handshake (normally resolves in ~0-50ms).
        const val OVERLAY_DETACH_TIMEOUT_MS = 600L
        const val REENGAGE_DEBOUNCE_MS = 800L
        const val DISENGAGE_GRACE_MS = 1_500L
        const val REENGAGE_WINDOW_MS = 60_000L
        // 1.5s worth of polls: the divider window leaves the a11y windows list for the
        // whole duration of swap/commit animations, not just a frame (measured).
        const val DIVIDER_SETTLE_POLLS = 20
    }
}
