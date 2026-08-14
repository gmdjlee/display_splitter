package com.displaysplitter.spacer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import com.displaysplitter.App
import com.displaysplitter.R
import com.displaysplitter.settings.SPACER_MEMO_MAX_CHARS
import com.displaysplitter.settings.SettingsRepository
import com.displaysplitter.ui.AppIcons
import com.displaysplitter.ui.theme.OneUiPalette
import com.displaysplitter.ui.theme.OneUiTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Which ambient widget the spacer draws — a display preference, nothing else observes
 * it. Unknown/blank stored values fall back to BLACK, the spacer's founding purpose
 * (read as bezel), which also keeps the pre-widget behavior for existing installs.
 */
enum class SpacerWidgetMode {
    CLOCK, MEMO, BLACK;

    companion object {
        fun fromStorage(raw: String?): SpacerWidgetMode = when (raw) {
            "CLOCK" -> CLOCK
            "MEMO" -> MEMO
            else -> BLACK
        }
    }
}

// ── Ambient palette ─────────────────────────────────────────────────────────────────
// The spacer stays pure black in any system theme (letterbox filler: a non-black
// surface reads as a gray band on OLED), so content colors are pinned white/accent
// alphas, never theme reads. Luminance contract (contrast on black, alpha-composited):
// each mode's PERSISTENT primary content outshines every other persistent element in
// that mode; the control tray is brighter but transient (auto-hides in 4s — the
// existing spacer control design, kept).
//   memo ink (focused)   White 0.56 ≈ 6.5:1  ← MEMO primary: a reading surface
//   memo ink (unfocused) White 0.47 ≈ 4.7:1  (functional text: kept above 4.5:1)
//   clock time           White 0.35 ≈ 3.0:1  ← CLOCK primary: ambient, glow-capped
//   save indicator       White 0.35 (FAILED/cap notice: Warning 0.8 — functional
//                        warnings must be noticed; FAILED never auto-dismisses, the
//                        next keystroke is the retry — and both announce politely
//                        to TalkBack, which cannot see luminance at all)
//   date                 White 0.22 ≈ 1.8:1  ← glanceable secondary, below primary
//   placeholder          White 0.20
//   memo field fill      White 0.05 (a surface, not text)
// BLACK draws nothing at all — a watermark would be glow/burn-in next to the video.
private val TrayColor = Color(0xFF17171B)

/** M3 derives selection colors from colorScheme.primary; under a light system theme
 *  that is the light-scheme blue, near-invisible on this always-black window — so the
 *  selection colors are pinned to the dark-scheme accent like everything else here. */
private val SpacerSelectionColors = TextSelectionColors(
    handleColor = OneUiPalette.BlueDark,
    backgroundColor = OneUiPalette.BlueDark.copy(alpha = 0.4f),
)

/** Auto-hide for the control tray — the pre-widget spacer's timing, kept. Pure display
 *  timing: no state transition waits on it. */
private const val CONTROLS_AUTO_HIDE_MS = 4_000L

/** Memo input debounce — input-IO thrift, not an orchestration wait. */
private const val MEMO_DEBOUNCE_MS = 500L

/** The save-confirmed line removes itself: "nothing visible = saved" is the stable state. */
private const val MEMO_SAVED_LINGER_MS = 1_000L

/** Clock size band (dp): keeps the clock from doubling in size just because the
 *  letterbox happened to pile onto one side of the divider. */
private const val CLOCK_BAND_MIN_DP = 64f
private const val CLOCK_BAND_MAX_DP = 96f

/** Below this pane height the date line is dropped entirely — small panes need the time only. */
private const val CLOCK_DATE_MIN_PANE_DP = 170f

/** Reference size for measuring the time string's width: glyph width is linear in font
 *  size, so one measurement at 100dp lets the width-fitting size be solved directly. */
private const val CLOCK_MEASURE_REF_DP = 100f

/** Minimum pane height the memo's IME-overlap padding must leave visible. Plain
 *  imePadding() subtracts the whole inset and collapsed the editor to 0dp when the
 *  spacer is the bottom pane under a ~300dp keyboard (measured in FoldWindow). */
private const val MEMO_MIN_VISIBLE_DP = 120f

/** Memo save indicator. IDLE (= disk matches the buffer, nothing shown) is stable. */
private enum class MemoSaveState { IDLE, SAVING, SAVED, FAILED }

private fun MemoSaveState.labelResOrNull(): Int? = when (this) {
    MemoSaveState.IDLE -> null
    MemoSaveState.SAVING -> R.string.spacer_memo_saving
    MemoSaveState.SAVED -> R.string.spacer_memo_saved
    MemoSaveState.FAILED -> R.string.spacer_memo_save_failed
}

/**
 * The spacer pane's content: an ambient widget (CLOCK / MEMO / BLACK) with the split
 * controls (flip / full screen) and the mode switcher in one transient tray.
 *
 * Layout is a Box OVERLAY — content always fills the window; the tray fades in above
 * it. As a layout sibling the tray would hand its space back to the content every time
 * it auto-hides, sliding and resizing the clock next to a playing video (measured in
 * FoldWindow — the reason for this invariant). Fade-only transitions for the same
 * reason: nothing in this window may move.
 *
 * A tap anywhere toggles the tray (the pre-widget spacer's interaction, kept). In MEMO
 * the text field consumes its own taps, but the field's margins, the status line and —
 * with the IME open — closing the keyboard all remain as escape paths, so no state
 * strands the tray hidden.
 */
@Composable
fun SpacerContent(
    settings: SettingsRepository,
    onFlip: () -> Unit,
    onExit: () -> Unit,
) = OneUiTheme {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as ComponentActivity

    // BLACK as the collect initial keeps the first frames pure black (invisible against
    // the window background) while DataStore replays the stored mode.
    val mode by remember(settings) { settings.spacerWidgetMode.map(SpacerWidgetMode::fromStorage) }
        .collectAsState(initial = SpacerWidgetMode.BLACK)

    var memoText by remember { mutableStateOf("") }
    var memoSaveJob by remember { mutableStateOf<Job?>(null) }
    // Reports "does disk match the buffer" honestly: SAVED only after the write
    // actually committed (saveSpacerMemo returns real success).
    var saveState by remember { mutableStateOf(MemoSaveState.IDLE) }
    // Monotonic edit stamp. Every writer captures it at launch and only publishes its
    // verdict if no newer edit claimed the stamp meanwhile — otherwise a slow ON_PAUSE
    // flush landing SAVED could bury a newer edit's FAILED, and "IDLE = disk matches
    // buffer" (the flush's own firing condition) would silently break.
    var memoEpoch by remember { mutableIntStateOf(0) }

    // Seed the edit buffer once; afterwards the local state is the source of truth —
    // a live DataStore subscription could replay a stale value over in-flight typing.
    // The guard keeps even the SEED from winning over input: a keystroke that beats
    // this async read moves saveState off IDLE, and then the stored value must lose.
    LaunchedEffect(settings) {
        val stored = settings.spacerMemo.first()
        if (saveState == MemoSaveState.IDLE && memoText.isEmpty()) memoText = stored
    }

    LaunchedEffect(saveState) {
        if (saveState == MemoSaveState.SAVED) {
            delay(MEMO_SAVED_LINGER_MS)
            saveState = MemoSaveState.IDLE
        }
    }

    fun onMemoTextChange(raw: String) {
        // Cap at input time so the buffer, the disk, and the cap notice all agree —
        // a silent write-time truncation would let the buffer lie about what's saved.
        val text = if (raw.length > SPACER_MEMO_MAX_CHARS) raw.take(SPACER_MEMO_MAX_CHARS) else raw
        memoText = text
        saveState = MemoSaveState.SAVING
        val epoch = ++memoEpoch
        memoSaveJob?.cancel()
        memoSaveJob = scope.launch {
            delay(MEMO_DEBOUNCE_MS)
            val ok = settings.saveSpacerMemo(text)
            if (memoEpoch == epoch) {
                saveState = if (ok) MemoSaveState.SAVED else MemoSaveState.FAILED
            }
        }
    }

    // ON_PAUSE flush: the debounce window can still be open when the split dissolves
    // (finish() arrives with onPause+onDestroy in one transaction). The composition
    // scope queues on the UI dispatcher and is cancelled before a block launched here
    // could even start (measured in FoldWindow), so the flush runs on the process-wide
    // appScope — Main.immediate starts it inline, and saveSpacerMemo's NonCancellable
    // write finishes even after this window is gone.
    val appScope = App.from(activity).appScope
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE && saveState != MemoSaveState.IDLE) {
                val pending = memoText
                val epoch = ++memoEpoch
                memoSaveJob?.cancel()
                memoSaveJob = null
                appScope.launch {
                    val ok = settings.saveSpacerMemo(pending)
                    if (memoEpoch == epoch) {
                        saveState = if (ok) MemoSaveState.SAVED else MemoSaveState.FAILED
                    }
                }
            }
        }
        activity.lifecycle.addObserver(observer)
        onDispose { activity.lifecycle.removeObserver(observer) }
    }

    // ── tray visibility ────────────────────────────────────────────────────────────
    var trayVisible by remember { mutableStateOf(false) }
    // Bumped on every tray interaction so the auto-hide timer restarts even when the
    // visibility value itself doesn't change.
    var revealTick by remember { mutableIntStateOf(0) }

    // With the IME up the tray would sit behind/under the keyboard — fold it away
    // entirely; it returns (with a fresh timer) when the keyboard closes.
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val trayShown = trayVisible && !imeVisible

    LaunchedEffect(trayShown, revealTick, mode) {
        if (!trayShown) return@LaunchedEffect
        // Under touch exploration (TalkBack) the tray must not vanish mid-traversal —
        // a screen-reader pass through five chips easily outlives any timer, so the
        // tray stays until explicitly toggled. Queried at reveal time, not cached:
        // the service can be switched on between engagements.
        val a11y = activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (a11y?.isTouchExplorationEnabled == true) return@LaunchedEffect
        delay(CONTROLS_AUTO_HIDE_MS)
        trayVisible = false
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // The whole window is the tray toggle. Nodes that consume their own taps
            // (chips, the memo field) never let events reach this. indication = null:
            // a full-window ripple would be a flash of light next to the video.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                // The label must match what the tap will DO — announcing "show" while
                // the tray is up would promise the opposite of the outcome.
                onClickLabel = stringResource(
                    if (trayVisible) R.string.spacer_hide_controls else R.string.spacer_reveal_controls
                ),
            ) {
                trayVisible = !trayVisible
                revealTick++
            },
    ) {
        val paneHeight = maxHeight

        Crossfade(
            targetState = mode,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(durationMillis = 200),
            label = "spacerWidget",
        ) { shown ->
            when (shown) {
                SpacerWidgetMode.CLOCK -> ClockWidget(paneHeight = paneHeight)
                SpacerWidgetMode.MEMO -> MemoWidget(
                    paneHeight = paneHeight,
                    text = memoText,
                    saveState = saveState,
                    onTextChange = ::onMemoTextChange,
                )
                // BLACK draws nothing — the parent's pure black is the whole point.
                SpacerWidgetMode.BLACK -> Unit
            }
        }

        AnimatedVisibility(
            visible = trayShown,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(320)),
            label = "spacerTray",
        ) {
            ControlTray(
                selected = mode,
                onSelect = { newMode ->
                    revealTick++
                    // Process scope, not the composition scope: a chip tap racing the
                    // split's dissolution (composition disposal cancels queued UI-
                    // dispatcher launches before they start — the measured failure
                    // mode the ON_PAUSE flush comment documents) must still persist.
                    appScope.launch { settings.setSpacerWidgetMode(newMode.name) }
                },
                onFlip = onFlip,
                onExit = onExit,
                onKeepAlive = { revealTick++ },
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

/**
 * One transient tray for everything: widget modes on the left (selectable, Tab role so
 * TalkBack reads the selected state), split actions on the right. Bottom-center so it
 * never overlaps the centered clock. Styling continues the pre-widget spacer controls:
 * dark pill, white content, full brightness excused by the 4s auto-hide.
 */
@Composable
private fun ControlTray(
    selected: SpacerWidgetMode,
    onSelect: (SpacerWidgetMode) -> Unit,
    onFlip: () -> Unit,
    onExit: () -> Unit,
    onKeepAlive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = TrayColor,
        contentColor = Color.White,
        // Near-miss taps (chip gaps, the pill's padding ring) must NOT fall through
        // to the whole-window toggle underneath — dismissing the very tray the user
        // was aiming at. detectTapGestures consumes them without adding a TalkBack
        // node (unlike an unlabeled clickable), and restarts the auto-hide timer:
        // touching the tray is interacting with it.
        modifier = modifier.pointerInput(Unit) { detectTapGestures { onKeepAlive() } },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Own row + selectableGroup: gives the Tab-role chips their "tab 1 of 3"
            // context in TalkBack and keeps the action chips out of the count.
            Row(
                modifier = Modifier.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeChip(AppIcons.Clock, R.string.spacer_mode_clock, selected == SpacerWidgetMode.CLOCK) {
                    onSelect(SpacerWidgetMode.CLOCK)
                }
                ModeChip(AppIcons.Memo, R.string.spacer_mode_memo, selected == SpacerWidgetMode.MEMO) {
                    onSelect(SpacerWidgetMode.MEMO)
                }
                ModeChip(AppIcons.Blank, R.string.spacer_mode_black, selected == SpacerWidgetMode.BLACK) {
                    onSelect(SpacerWidgetMode.BLACK)
                }
            }
            Box(
                Modifier
                    .padding(horizontal = 6.dp)
                    .width(1.dp)
                    .height(30.dp)
                    .background(Color.White.copy(alpha = 0.14f)),
            )
            ActionChip(AppIcons.Swap, R.string.spacer_flip, onFlip)
            ActionChip(AppIcons.Expand, R.string.spacer_exit, onExit)
        }
    }
}

/** Selectable segment: icon + label. The icon is decorative (label carries meaning —
 *  both would make TalkBack say it twice); Role.Tab + selected reads the state. */
@Composable
private fun ModeChip(
    icon: ImageVector,
    @StringRes labelRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val content = Color.White.copy(alpha = if (selected) 0.95f else 0.55f)
    ChipColumn(
        icon = icon,
        labelRes = labelRes,
        content = content,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            .selectable(
                selected = selected,
                interactionSource = remember { MutableInteractionSource() },
                // The default indication is a black overlay — invisible on this pill.
                indication = ripple(color = Color.White),
                role = Role.Tab,
                onClick = onClick,
            ),
    )
}

/** Action segment: same geometry as a mode chip so the tray reads as one instrument. */
@Composable
private fun ActionChip(icon: ImageVector, @StringRes labelRes: Int, onClick: () -> Unit) {
    ChipColumn(
        icon = icon,
        labelRes = labelRes,
        content = Color.White.copy(alpha = 0.85f),
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(color = Color.White),
                onClick = onClick,
            ),
    )
}

@Composable
private fun ChipColumn(
    icon: ImageVector,
    @StringRes labelRes: Int,
    content: Color,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .defaultMinSize(minWidth = 56.dp, minHeight = 52.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/**
 * CLOCK — ambient clock: time (primary) + date/weekday (secondary), nothing else.
 *
 * Sizing solves three constraints at once (no fixed pane size may be assumed — this
 * pane is born at any height the divider leaves it):
 * 1. A narrow BAND (pane height × 0.26, clamped 64–96dp): a purely proportional size
 *    would double between a 16:9 and a 2.39:1 letterbox — which side of the divider
 *    the letterbox piled onto is not the user's concern, so the clock holds its size.
 * 2. WIDTH fit, measured with a TextMeasurer at a reference size and solved linearly —
 *    an em-per-glyph heuristic under-counts CJK and clipped in narrow panes (measured
 *    in FoldWindow).
 * 3. HEIGHT fit for the time+date stack; below [CLOCK_DATE_MIN_PANE_DP] the date is
 *    dropped entirely.
 * All math in dp, converted to sp only at the end — computing in sp would let a large
 * system font scale silently break the fits.
 */
@Composable
private fun ClockWidget(paneHeight: Dp, modifier: Modifier = Modifier) {
    val activity = LocalContext.current as ComponentActivity

    // Timezone/clock-set changes must show immediately, not at the next minute tick —
    // the first thing seen after a flight should not be the departure city's time.
    var timeInvalidation by remember { mutableIntStateOf(0) }
    DisposableEffect(activity) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                timeInvalidation++
            }
        }
        // Protected system broadcasts — NOT_EXPORTED still receives them. ContextCompat
        // because the flagged registerReceiver overload is API 33+ and minSdk is 30.
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { activity.unregisterReceiver(receiver) }
    }

    // The manifest routes locale changes through onConfigurationChanged (no
    // recreation), so the formatters must re-key on the LIVE locale. The Configuration
    // OBJECT cannot be that key: the framework mutates the activity's instance in
    // place — Compose backs LocalConfiguration with neverEqualPolicy for exactly this
    // reason — so remember would compare the mutated object against itself and never
    // invalidate. Key on the locale VALUE instead (the .current read still makes this
    // recompose on every configuration change).
    val localeTags = LocalConfiguration.current.locales.toLanguageTags()
    val timeFormat = remember(localeTags, timeInvalidation) { spacerTimeFormat(activity) }
    val dateFormat = remember(localeTags, timeInvalidation) { spacerDateFormat() }
    var now by remember { mutableStateOf(Date()) }

    // Display granularity is minutes: sleep to the next minute boundary instead of
    // ticking every second; dormant outside STARTED, refreshed on re-entry. A display
    // refresh loop, not an orchestration wait.
    LaunchedEffect(activity, timeInvalidation) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                now = Date()
                delay(60_000L - System.currentTimeMillis() % 60_000L)
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        val density = LocalDensity.current
        val fontScale = density.fontScale
        val formatted = timeFormat.format(now)
        val paneDp = paneHeight.value

        val band = (paneDp * 0.26f).coerceIn(CLOCK_BAND_MIN_DP, CLOCK_BAND_MAX_DP)

        // Width fit: measure once at the reference size in the RENDER style (only
        // fontSize differs), so "measured one thing, drew another" can't regress.
        // tnum keeps minute changes from re-centering the clock (proportional digits
        // change the string width every minute).
        val refStyle = MaterialTheme.typography.displayLarge.copy(
            fontSize = (CLOCK_MEASURE_REF_DP / fontScale).sp,
            lineHeight = (CLOCK_MEASURE_REF_DP * 1.04f / fontScale).sp,
            fontWeight = FontWeight.Light,
            letterSpacing = (-0.02).em,
            fontFeatureSettings = "tnum",
        )
        val measurer = rememberTextMeasurer()
        val refWidthDp = remember(formatted, refStyle, measurer) {
            measurer.measure(
                text = formatted,
                style = refStyle,
                softWrap = false,
                maxLines = 1,
            ).size.width / density.density
        }
        val widthFit = if (refWidthDp > 0f) {
            CLOCK_MEASURE_REF_DP * maxWidth.value / refWidthDp
        } else {
            band // measurement failure: give up only the width cap, never silently
        }

        val showDate = paneDp >= CLOCK_DATE_MIN_PANE_DP
        // Stack = time line (1.04) + gap (0.08) + date line (0.19 × 1.4); 0.86 breathes.
        val stackFactor = if (showDate) 1.386f else 1.04f
        val heightFit = paneDp * 0.86f / stackFactor

        val timeDp = minOf(band, widthFit, heightFit).coerceAtLeast(16f)
        val dateDp = (timeDp * 0.19f).coerceIn(11f, 20f)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = formatted,
                style = refStyle.copy(
                    fontSize = (timeDp / fontScale).sp,
                    lineHeight = (timeDp * 1.04f / fontScale).sp,
                ),
                color = Color.White.copy(alpha = 0.35f),
                maxLines = 1,
            )
            if (showDate) {
                Text(
                    text = dateFormat.format(now),
                    // Hierarchy by size and alpha only — a second COLOR on a two-line
                    // screen reads as noise, not hierarchy.
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontSize = (dateDp / fontScale).sp,
                        lineHeight = (dateDp * 1.4f / fontScale).sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = 0.08.em,
                    ),
                    color = Color.White.copy(alpha = 0.22f),
                    maxLines = 1,
                    modifier = Modifier.padding(top = (timeDp * 0.08f).dp),
                )
            }
        }
    }
}

/**
 * MEMO — free-form input on black: a barely-there input surface, readable ink, a
 * visible caret/selection, and an honest one-line save status. No save button — the
 * debounce plus the ON_PAUSE flush own persistence.
 *
 * IME avoidance is overlap-aware, not [androidx.compose.foundation.layout.imePadding]:
 * edge-to-edge means the window never resizes for the IME, and subtracting the whole
 * inset collapsed the editor to 0dp when this is the BOTTOM pane under a ~300dp
 * keyboard (measured in FoldWindow). Instead only min(overlap, pane − 120dp) is
 * subtracted: as the TOP pane the overlap is 0 and inline editing is untouched; as the
 * bottom pane the surviving strip holds the status line and first text line (the
 * status line sits ABOVE the field for exactly that reason — and to stay clear of the
 * bottom tray).
 */
@Composable
private fun MemoWidget(
    paneHeight: Dp,
    text: String,
    saveState: MemoSaveState,
    onTextChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val imeOverlapPx = WindowInsets.ime.getBottom(density)
    val imeAvoidance = with(density) {
        val floorPx = (paneHeight.roundToPx() - MEMO_MIN_VISIBLE_DP.dp.roundToPx()).coerceAtLeast(0)
        imeOverlapPx.coerceAtMost(floorPx).toDp()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = imeAvoidance)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        MemoStatusLine(text = text, saveState = saveState)
        CompositionLocalProvider(LocalTextSelectionColors provides SpacerSelectionColors) {
            TextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.05f)),
                // Raised line height: CJK at the default leading sets dense on black.
                textStyle = MaterialTheme.typography.bodyMedium.copy(lineHeight = 24.sp),
                placeholder = {
                    Text(
                        text = stringResource(R.string.spacer_memo_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = Color.White.copy(alpha = 0.56f),
                    unfocusedTextColor = Color.White.copy(alpha = 0.47f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = OneUiPalette.BlueDark.copy(alpha = 0.9f),
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedPlaceholderColor = Color.White.copy(alpha = 0.20f),
                    unfocusedPlaceholderColor = Color.White.copy(alpha = 0.20f),
                ),
            )
        }
    }
}

/**
 * Memo status line: (left) character-cap notice · (right) save indicator. Stable state
 * is NOTHING VISIBLE — a permanent "saved" badge both glows next to the video and lies
 * whenever a write fails; only FAILED stays until the next keystroke retries.
 *
 * "Not visible" must hold for the accessibility tree too: alpha 0 still leaves a node
 * TalkBack reads ("saved" before anything was ever saved). So the indicator leaves the
 * composition once its fade completes — alive while alpha > 0 (the fade shows fully),
 * gone at exactly 0 (tween lands on the exact end value, so no near-zero remnant keeps
 * the node forever). The left text keeps its layout slot (empty text pins the row
 * height) but drops its semantics, since an empty Text still carries a Text semantic
 * that makes TalkBack pause on nothing.
 */
@Composable
private fun MemoStatusLine(text: String, saveState: MemoSaveState) {
    val labelRes = saveState.labelResOrNull()
    // Hold the last shown label through the fade-out — snapping to "" would blink.
    var shownLabel by remember { mutableIntStateOf(R.string.spacer_memo_saved) }
    LaunchedEffect(labelRes) { if (labelRes != null) shownLabel = labelRes }
    val indicatorAlpha by animateFloatAsState(
        targetValue = if (labelRes == null) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "spacerMemoSaveIndicator",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val capReached = text.length >= SPACER_MEMO_MAX_CHARS
        Text(
            text = if (capReached) stringResource(R.string.spacer_memo_cap_reached) else "",
            style = MaterialTheme.typography.labelSmall,
            color = OneUiPalette.Warning.copy(alpha = 0.8f),
            maxLines = 1,
            // Polite live region: hitting the cap must reach TalkBack too — visually
            // dropped input with no announcement is a silent failure.
            modifier = if (capReached) {
                Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            } else {
                Modifier.clearAndSetSemantics {}
            },
        )
        if (labelRes != null || indicatorAlpha > 0f) {
            val failed = saveState == MemoSaveState.FAILED
            Text(
                text = stringResource(shownLabel),
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) {
                    OneUiPalette.Warning.copy(alpha = 0.8f)
                } else {
                    Color.White.copy(alpha = 0.35f)
                },
                maxLines = 1,
                modifier = Modifier
                    .alpha(indicatorAlpha)
                    .padding(end = 4.dp)
                    // Only FAILED is announced: SAVING→SAVED chatter on every typing
                    // pause would make TalkBack unbearable, and silence-as-success is
                    // this line's visual contract anyway. Failure may not be silent.
                    .then(
                        if (failed) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier
                    ),
            )
        }
    }
}

/** Locale-driven time pattern honoring the system 12/24-hour setting. */
private fun spacerTimeFormat(context: Context): SimpleDateFormat {
    val locale = Locale.getDefault()
    val skeleton = if (AndroidDateFormat.is24HourFormat(context)) "Hm" else "hm"
    return SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, skeleton), locale)
}

/** Locale-driven "month day + weekday" (ko: 8월 14일 금요일 / en: Friday, Aug 14). */
private fun spacerDateFormat(): SimpleDateFormat {
    val locale = Locale.getDefault()
    return SimpleDateFormat(AndroidDateFormat.getBestDateTimePattern(locale, "EEEEMMMd"), locale)
}
