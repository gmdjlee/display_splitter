package com.displaysplitter.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.IBinder
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import com.displaysplitter.App
import com.displaysplitter.R
import com.displaysplitter.split.DividerAccessibilityService
import com.displaysplitter.split.Posture
import com.displaysplitter.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Foreground service that owns the floating bubble window.
 * The bubble appears only while [EngagementController.bubbleVisible] says so.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = App.from(this)
    private val controller get() = app.controller
    private val settings get() = app.settings

    private lateinit var overlayContext: Context
    private lateinit var windowManager: WindowManager
    private var overlayRoot: OverlayRoot? = null
    private var overlayLifecycle: OverlayWindowLifecycle? = null
    private var snapAnimator: ValueAnimator? = null
    private var hideJob: Job? = null
    private val collapseRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Drag state: positions come from raw screen coordinates captured at drag start,
    // never from window-local deltas (the window moves under the finger, which turns
    // local deltas into a feedback loop).
    private var dragOriginRawX = 0f
    private var dragOriginRawY = 0f
    private var dragOriginX = 0
    private var dragOriginY = 0
    private var dragBounds: Rect? = null

    // Bubble position saved while the expanded panel temporarily repositions the window.
    private var preExpandX = -1
    private var preExpandY = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // A window context bound to the default display: correct metrics and config
        // for overlay windows on every API 30+ build, and no non-UI-context warnings.
        val display = getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
        overlayContext = createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        windowManager = overlayContext.getSystemService(WindowManager::class.java)
        // A START_STICKY restart after the a11y service was disabled can be denied
        // FGS start on API 31+: fail quietly instead of crashing.
        runCatching { startForeground(NOTIFICATION_ID, buildNotification()) }
            .onFailure {
                stopSelf()
                return
            }

        scope.launch {
            combine(
                controller.bubbleVisible, controller.onInnerDisplay, controller.posture,
            ) { visible, inner, posture -> Triple(visible, inner, posture) }
                .collect { (visible, inner, posture) ->
                    hideJob?.cancel()
                    when {
                        // Cover screen / Flex mode: vanish NOW — no debounce. The cover
                        // display is none of our business, ever.
                        !inner || posture == Posture.HALF_OPENED -> detachOverlay()
                        visible -> attachOverlay()
                        else -> {
                            // Debounce ordinary hides: transient foreground flips (recents,
                            // switching between two video apps) must not blink the bubble.
                            hideJob = scope.launch {
                                delay(HIDE_DEBOUNCE_MS)
                                detachOverlay()
                            }
                        }
                    }
                }
        }
        scope.launch {
            settings.state.collect { s ->
                if (!s.bubbleEnabled) stopSelf()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch {
                settings.setBubbleEnabled(false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Display size or uiMode changed (fold, rotation, dark mode): recreate the
        // window so theme and position are correct for the new environment. Never
        // re-attach on the cover screen — bubbleVisible can lag the fold by an event.
        if (overlayRoot != null) {
            detachOverlay()
            if (newConfig.smallestScreenWidthDp >= DividerAccessibilityService.INNER_DISPLAY_MIN_SW_DP &&
                controller.bubbleVisible.value
            ) {
                attachOverlay()
            }
        }
    }

    override fun onDestroy() {
        detachOverlay()
        scope.cancel()
        super.onDestroy()
    }

    // ---- window management -------------------------------------------------------------------

    private fun attachOverlay() {
        if (overlayRoot != null) return
        if (!android.provider.Settings.canDrawOverlays(this)) return
        // Backstop for every race: nothing ever attaches off the inner display.
        if (overlayContext.resources.configuration.smallestScreenWidthDp <
            DividerAccessibilityService.INNER_DISPLAY_MIN_SW_DP
        ) {
            return
        }

        val lifecycle = OverlayWindowLifecycle()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            windowAnimations = android.R.style.Animation_Dialog
            val saved = settings.state.value
            val display = windowManager.currentWindowMetrics.bounds
            // Saved positions were measured against whatever display the bubble was
            // on last — always clamp to the current bounds.
            val bubble = dp(BUBBLE_SIZE_DP)
            x = (if (saved.bubbleX >= 0) saved.bubbleX else display.width() - dp(72))
                .coerceIn(0, (display.width() - bubble).coerceAtLeast(0))
            y = (if (saved.bubbleY >= 0) saved.bubbleY else display.height() / 3)
                .coerceIn(0, (display.height() - bubble).coerceAtLeast(0))
        }

        val root = OverlayRoot(
            overlayContext,
            onOutsideTouch = { collapsePanel() },
            // A second finger landing/lifting mid-drag shifts pointer index 0:
            // re-anchor so the window doesn't jump by the inter-finger distance.
            onPointerChange = { if (dragBounds != null) startDrag() },
        )
        // Hidden status bar = the app under us is immersive fullscreen; the split entry
        // must reveal the bars before the two-finger gesture (see SplitEntryDriver).
        // This overlay window is the app's only live view of bar visibility.
        root.setOnApplyWindowInsetsListener { _, insets ->
            controller.statusBarsVisible =
                insets.isVisible(android.view.WindowInsets.Type.statusBars())
            insets
        }
        val composeView = ComposeView(overlayContext).apply {
            setContent {
                OverlayContent(
                    controller = controller,
                    settings = settings,
                    collapseRequests = collapseRequests,
                    onDragStart = { startDrag() },
                    onDragMove = { dragMove() },
                    onDragEnd = { snapToEdge() },
                    onExpandedChange = { expanded -> repositionForPanel(expanded) },
                    onOpenSettings = {
                        collapsePanel()
                        startActivity(
                            Intent(this@OverlayService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                )
            }
        }
        // Owners must live on the view handed to WindowManager: Compose resolves the
        // window recomposer from the root view, so a ComposeView-only attachment
        // throws "ViewTreeLifecycleOwner not found" the moment it attaches.
        root.addView(composeView)
        lifecycle.attachTo(root)
        lifecycle.create()

        runCatching { windowManager.addView(root, params) }
            .onSuccess {
                overlayRoot = root
                overlayLifecycle = lifecycle
            }
            .onFailure { lifecycle.destroy() }
    }

    private fun detachOverlay() {
        snapAnimator?.cancel()
        val hadWindow = overlayRoot != null
        overlayRoot?.let { runCatching { windowManager.removeViewImmediate(it) } }
        overlayLifecycle?.destroy()
        overlayRoot = null
        overlayLifecycle = null
        // Persist only the true bubble anchor: snapToEdge already saves the settled
        // position after every drag, so the only value worth saving here is the
        // pre-expand anchor while the panel was open. Saving the live p.x would
        // persist a panel-shifted or mid-snap-animation coordinate. The write runs
        // on the app scope — the service scope may be cancelled right after this.
        if (hadWindow && preExpandX >= 0) {
            val x = preExpandX
            val y = preExpandY
            app.appScope.launch { settings.setBubblePosition(x, y) }
        }
        preExpandX = -1
    }

    private fun params(): WindowManager.LayoutParams? =
        overlayRoot?.layoutParams as? WindowManager.LayoutParams

    private fun startDrag() {
        val root = overlayRoot ?: return
        val p = params() ?: return
        snapAnimator?.cancel()
        dragOriginRawX = root.lastRawX
        dragOriginRawY = root.lastRawY
        dragOriginX = p.x
        dragOriginY = p.y
        // Display bounds cannot change mid-drag: cache them once per drag.
        dragBounds = windowManager.currentWindowMetrics.bounds
    }

    private fun dragMove() {
        val root = overlayRoot ?: return
        val p = params() ?: return
        val bounds = dragBounds ?: return
        p.x = (dragOriginX + (root.lastRawX - dragOriginRawX)).roundToInt()
            .coerceIn(0, (bounds.width() - root.width).coerceAtLeast(0))
        p.y = (dragOriginY + (root.lastRawY - dragOriginRawY)).roundToInt()
            .coerceIn(0, (bounds.height() - root.height).coerceAtLeast(0))
        windowManager.updateViewLayout(root, p)
    }

    /** One UI-style: the bubble rests on the nearest horizontal edge. */
    private fun snapToEdge() {
        val root = overlayRoot ?: return
        val p = params() ?: return
        val display = dragBounds ?: windowManager.currentWindowMetrics.bounds
        dragBounds = null
        val targetX = if (p.x + root.width / 2 < display.width() / 2) {
            dp(8)
        } else {
            display.width() - root.width - dp(8)
        }
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 250
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener { anim ->
                val live = params() ?: return@addUpdateListener
                live.x = anim.animatedValue as Int
                overlayRoot?.let { windowManager.updateViewLayout(it, live) }
            }
            start()
        }
        scope.launch { settings.setBubblePosition(targetX, p.y) }
    }

    /**
     * The expanded panel is wider than the edge-snapped bubble position allows:
     * shift the window so the panel stays fully on screen, and restore the bubble
     * position on collapse. The window stays non-focusable throughout — the panel
     * is touch-only, so Back and key focus remain with the video app.
     */
    private fun repositionForPanel(expanded: Boolean) {
        val root = overlayRoot ?: return
        val p = params() ?: return
        val display = windowManager.currentWindowMetrics.bounds
        if (expanded) {
            preExpandX = p.x
            preExpandY = p.y
            // ponytail: fixed panel-size estimate; measure the composed panel if designs change.
            val panelW = dp(PANEL_WIDTH_DP + 12)
            val panelH = dp(PANEL_HEIGHT_ESTIMATE_DP)
            p.x = p.x.coerceAtMost((display.width() - panelW).coerceAtLeast(0))
            p.y = p.y.coerceAtMost((display.height() - panelH).coerceAtLeast(0))
        } else if (preExpandX >= 0) {
            p.x = preExpandX
            p.y = preExpandY
            preExpandX = -1
        } else {
            return
        }
        windowManager.updateViewLayout(root, p)
    }

    private fun collapsePanel() {
        collapseRequests.tryEmit(Unit)
    }

    // ---- notification ------------------------------------------------------------------------

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_stat_splitter)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun dp(value: Int): Int =
        (value * overlayContext.resources.displayMetrics.density).roundToInt()

    companion object {
        private const val NOTIFICATION_ID = 10
        private const val ACTION_STOP = "com.displaysplitter.overlay.STOP"
        private const val HIDE_DEBOUNCE_MS = 250L
        private const val BUBBLE_SIZE_DP = 58
        private const val PANEL_WIDTH_DP = 316
        private const val PANEL_HEIGHT_ESTIMATE_DP = 420

        fun start(context: Context) {
            val app = App.from(context)
            if (!app.settings.state.value.bubbleEnabled) return
            if (!android.provider.Settings.canDrawOverlays(context)) return
            context.startForegroundService(Intent(context, OverlayService::class.java))
        }
    }
}

/** Container that reports outside-touches and records raw screen coordinates for drags. */
class OverlayRoot(
    context: Context,
    private val onOutsideTouch: () -> Unit,
    private val onPointerChange: () -> Unit = {},
) : FrameLayout(context) {

    var lastRawX = 0f
        private set
    var lastRawY = 0f
        private set

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        lastRawX = event.rawX
        lastRawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_OUTSIDE -> onOutsideTouch()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> onPointerChange()
        }
        return super.dispatchTouchEvent(event)
    }
}
