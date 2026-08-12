package com.displaysplitter.split

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.view.WindowManager
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.displaysplitter.App
import com.displaysplitter.overlay.OverlayService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

data class PaneSnapshot(
    val video: Rect?,
    val spacer: Rect?,
    val divider: Rect?,
    val display: Rect,
)

/**
 * Watches the foreground app, enters split screen, and drives the split divider
 * with dispatched gestures. It never reads window *content* — only window bounds
 * and package names, as promised in the service description.
 */
class DividerAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller get() = App.from(this).controller

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        controller.onServiceConnected(true)
        controller.onInnerDisplayChanged(isOnInnerDisplay())
        observePosture()
        OverlayService.start(this)
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            controller.onServiceConnected(false)
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        controller.onInnerDisplayChanged(isOnInnerDisplay())
        // Recompute on both event types: TYPE_WINDOWS_CHANGED covers window *removals*
        // (a pane closing) that never fire a STATE_CHANGED, so the bubble can't get
        // stuck visible after the video pane goes away. In split screen a video app can
        // be the side pane while another app holds focus, so the bubble keys off "video
        // app visible", not just "video app focused".
        controller.onVisiblePackages(visibleAppPackages())
        // Foreground attribution only makes sense for a state change: attribute to the
        // active APP window, never the event's source package (IMEs, permission dialogs
        // and other system windows fire these events too and must not masquerade as an
        // app switch).
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = activeAppPackage() ?: return
        if (pkg == packageName) return
        controller.onForegroundPackage(pkg)
    }

    override fun onInterrupt() = Unit

    /** Package of the active (input-focused) application window, or null in transitions. */
    fun activeAppPackage(): String? {
        val all = windows ?: return null
        val appWindow = all.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: all.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isFocused
        } ?: return null
        return appWindow.root?.packageName?.toString()
    }

    /**
     * Packages of real fullscreen/split-pane application windows, excluding our own and
     * excluding Picture-in-Picture (a PiP window is a TYPE_APPLICATION window too, but a
     * tiny floating video must NOT make the bubble appear over home/launcher).
     */
    fun visibleAppPackages(): Set<String> {
        val all = windows ?: return emptySet()
        val display = displayBounds()
        val minSpan = 0.6f // a genuine pane spans most of one display dimension; PiP spans neither
        val bounds = Rect()
        return all.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .filter {
                it.getBoundsInScreen(bounds)
                bounds.width() >= display.width() * minSpan ||
                    bounds.height() >= display.height() * minSpan
            }
            .mapNotNull { it.root?.packageName?.toString() }
            .filter { it != packageName }
            .toSet()
    }

    // ---- geometry ----------------------------------------------------------------------------

    /** True when the app is rendering on the large inner display (vs. the cover screen). */
    fun isOnInnerDisplay(): Boolean =
        resources.configuration.smallestScreenWidthDp >= INNER_DISPLAY_MIN_SW_DP

    fun displayBounds(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        return wm.maximumWindowMetrics.bounds
    }

    fun displayCutoutRects(): List<Rect> {
        val wm = getSystemService(WindowManager::class.java)
        return wm.maximumWindowMetrics.windowInsets.displayCutout?.boundingRects ?: emptyList()
    }

    /** Snapshot of the current split: video pane, our spacer pane, and the divider. */
    fun panes(videoPackage: String): PaneSnapshot? {
        val all = windows ?: return null
        var video: Rect? = null
        var spacer: Rect? = null
        var divider: Rect? = null
        for (w in all) {
            when (w.type) {
                AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> divider = boundsOf(w)
                AccessibilityWindowInfo.TYPE_APPLICATION -> {
                    val root = w.root ?: continue
                    val pkg = root.packageName?.toString()
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        @Suppress("DEPRECATION")
                        root.recycle()
                    }
                    // A package can own several windows (dialogs, trampolines):
                    // the pane is the largest one.
                    when (pkg) {
                        videoPackage -> video = largest(video, boundsOf(w))
                        packageName -> spacer = largest(spacer, boundsOf(w))
                    }
                }
            }
        }
        return PaneSnapshot(video, spacer, divider, displayBounds())
    }

    private fun largest(a: Rect?, b: Rect): Rect =
        if (a == null || b.width().toLong() * b.height() > a.width().toLong() * a.height()) b else a

    private fun boundsOf(w: AccessibilityWindowInfo): Rect = Rect().also { w.getBoundsInScreen(it) }

    // ---- actions -----------------------------------------------------------------------------

    /**
     * Legacy split entry for Android <= 12 only. GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN was
     * removed from the framework in Android 13, so we feature-detect it via
     * [getSystemActions] and never call a removed action blind.
     */
    fun tryLegacyToggle(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) return false
        val available = systemActions.any { it.id == GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN }
        return available && performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
    }

    /** Press-hold, then drag the divider to the target. Suspends until the gesture completes. */
    suspend fun dragDivider(from: Point, to: Point): Boolean {
        // A short initial hold lets the divider register the grab before movement starts.
        val holdStroke = GestureDescription.StrokeDescription(
            Path().apply { moveTo(from.x.toFloat(), from.y.toFloat()) },
            0, HOLD_MS, true,
        )
        val dragStroke = holdStroke.continueStroke(
            Path().apply {
                moveTo(from.x.toFloat(), from.y.toFloat())
                lineTo(to.x.toFloat(), to.y.toFloat())
            },
            0, DRAG_MS, false,
        )
        // If the caller is cancelled while the hold is in flight, the injected pointer
        // must be released — otherwise the divider stays "grabbed" by a phantom finger.
        if (!dispatchStroke(holdStroke, releaseOnCancelAt = from)) return false
        return dispatchStroke(dragStroke)
    }

    suspend fun doubleTap(at: Point): Boolean {
        val tap = {
            GestureDescription.StrokeDescription(
                Path().apply { moveTo(at.x.toFloat(), at.y.toFloat()) }, 0, TAP_MS, false,
            )
        }
        if (!dispatchStroke(tap())) return false
        delay(TAP_GAP_MS)
        return dispatchStroke(tap())
    }

    private suspend fun dispatchStroke(
        stroke: GestureDescription.StrokeDescription,
        releaseOnCancelAt: Point? = null,
    ): Boolean {
        // A dead service connection may never deliver the callback: bound the wait
        // so a caller can't hang forever on a gesture that will not complete.
        val result = withTimeoutOrNull(GESTURE_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val dispatched = dispatchGesture(
                    gesture,
                    object : GestureResultCallback() {
                        override fun onCompleted(g: GestureDescription?) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onCancelled(g: GestureDescription?) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                    null,
                )
                if (!dispatched && cont.isActive) cont.resume(false)
                cont.invokeOnCancellation {
                    // The stroke was dispatched with willContinue=true and its
                    // continuation will never come: send a zero-length terminating
                    // continuation so the system lifts the injected pointer.
                    if (releaseOnCancelAt != null && stroke.willContinue()) {
                        runCatching {
                            val release = stroke.continueStroke(
                                Path().apply {
                                    moveTo(releaseOnCancelAt.x.toFloat(), releaseOnCancelAt.y.toFloat())
                                },
                                0, 1, false,
                            )
                            dispatchGesture(
                                GestureDescription.Builder().addStroke(release).build(), null, null,
                            )
                        }
                    }
                }
            }
        }
        return result ?: false
    }

    // ---- posture -----------------------------------------------------------------------------

    private fun observePosture() {
        scope.launch {
            try {
                WindowInfoTracker.getOrCreate(this@DividerAccessibilityService)
                    .windowLayoutInfo(this@DividerAccessibilityService)
                    .collect { info ->
                        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                        controller.onPosture(
                            when (fold?.state) {
                                FoldingFeature.State.HALF_OPENED -> Posture.HALF_OPENED
                                FoldingFeature.State.FLAT -> Posture.FLAT
                                else -> Posture.UNKNOWN
                            }
                        )
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not a UiContext on this OEM build — posture stays UNKNOWN, which only
                // disables the Flex-mode suppression, never core behavior.
                controller.onPosture(Posture.UNKNOWN)
            }
        }
    }

    companion object {
        @Volatile
        var instance: DividerAccessibilityService? = null
            private set

        const val INNER_DISPLAY_MIN_SW_DP = 600

        private const val HOLD_MS = 150L
        private const val DRAG_MS = 350L
        private const val GESTURE_TIMEOUT_MS = 3_000L
        private const val TAP_MS = 60L
        private const val TAP_GAP_MS = 90L

        fun isEnabled(context: Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { it.contains(context.packageName) }
        }
    }
}
