# Display Splitter

Removes the video **letterbox / camera-hole overlap** on the Galaxy Z Fold7/8 **inner
display** for third-party video apps (YouTube, Netflix, …) — without touching the video
app's rendering. It puts a pitch-black companion pane into Android split-screen next to
the video app and drives the split divider so the video pane hits your target aspect
ratio (16:9, 21:9, 2.35:1, 4:3), placing the black pane over the camera hole.

Only the inner screen is ever adjusted. Flex mode and the cover screen are never touched.
Subtitles stay controlled by the video app; gestures follow One UI. The app does nothing
but manipulate the split ratio.

## How it works

```
video app (fullscreen, inner display)
        │  user taps floating bubble → Apply  (or Auto-apply)
        ▼
AccessibilityService injects One UI's TWO-FINGER bottom→top swipe (the multi-window
split gesture; must be enabled in Settings → Advanced features → Multi window)
  → the video app enters split-select, the partner picker opens
        ▼
service taps the black SpacerActivity's entry in the picker → split screen forms.
The spacer keeps its recents task card on exit (plain finish, not excluded from
recents), so from the second engage on the picker lists it directly at MRU; the
picker-search escalation (SET_TEXT the label) only covers a fresh install.
        ▼
service measures the divider window (TYPE_SPLIT_SCREEN_DIVIDER) — axis is MEASURED,
never assumed — rotating a side-by-side result to top/bottom via the divider popup,
and RatioMath computes the target divider position:
  • horizontal divider (top/bottom): pane height = paneWidth / ratio  → zero letterbox
        ▼
service drags the divider (dispatchGesture) to that position; the result is re-measured
and corrected once. The black spacer sits on the camera-hole side (auto-detected from
DisplayCutout; user-overridable) and can show an ambient clock or memo (SpacerContent).
```

## Split-screen initiation — important

`GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` was **removed from the Android framework in API 33**,
and `FLAG_ACTIVITY_LAUNCH_ADJACENT` is **ignored for background callers** — both verified
dead on a real Fold7 (and independently in FoldWindow's device facts). The only working
initiation is driving One UI's own UI: inject the two-finger split gesture on the
foreground video app, then tap the spacer in the partner picker (`SplitEntryDriver`).
Immersive-fullscreen apps get a bar-reveal edge swipe first — One UI ignores the split
gesture while the system bars are hidden (measured).

## Modules

| File | Purpose |
|------|---------|
| `geometry/RatioMath.kt` | Pure-Kotlin split geometry (unit-tested, no Android deps) |
| `split/EngagementController.kt` | Lifecycle state machine; single source of truth |
| `split/DividerAccessibilityService.kt` | Foreground/visible-app watch, split entry, divider gestures |
| `spacer/SpacerActivity.kt` + `SpacerContent.kt` | The pitch-black companion pane; ambient clock/memo widgets |
| `split/SplitEntryDriver.kt` | Two-finger split gesture + partner-picker automation |
| `overlay/OverlayService.kt` + `OverlayContent.kt` | Floating bubble + One UI quick panel |
| `settings/SettingsRepository.kt` | DataStore-backed preferences |
| `ui/HomeScreen.kt` + `theme/OneUiTheme.kt` | One UI-styled settings screen |

## Build

```
./gradlew assembleDebug        # JDK: Android Studio JBR 21
./gradlew testDebugUnitTest    # geometry unit tests
```

Toolchain: Gradle 8.9 · AGP 8.7.3 · Kotlin 2.1.0 · compileSdk 35 · minSdk 30 ·
Compose BOM 2025.01.01 · androidx.window 1.3.0.

## Verified on emulator (Pixel 9 Pro Fold, Android 16)

Overlay bubble over YouTube, One UI quick panel and settings screen, `panes()` reading the
real `StageCoordinatorSplitDivider`, and divider dragging at the exact gesture coordinates
— all confirmed. 30 passing unit tests. See `docs/DEVICE_VERIFICATION.md` for
the on-device Fold7 checklist (the one step the emulator cannot exercise:
programmatic split *initiation*).
