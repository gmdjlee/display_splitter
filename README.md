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
AccessibilityService launches the black SpacerActivity with
  FLAG_ACTIVITY_LAUNCH_ADJACENT | NEW_TASK | MULTIPLE_TASK   → split screen forms
        ▼
service measures the divider window (TYPE_SPLIT_SCREEN_DIVIDER) — axis is MEASURED,
never assumed — and RatioMath computes the target divider position:
  • horizontal divider (top/bottom): pane height = paneWidth / ratio  → zero letterbox
  • vertical divider (left/right):    exact wide ratios are geometrically impossible on a
                                      ~1.1:1 screen → spacer just covers the camera-hole column
        ▼
service drags the divider (dispatchGesture) to that position; the result is re-measured
and corrected once. The black spacer sits on the camera-hole side (auto-detected from
DisplayCutout; user-overridable).
```

## Split-screen initiation — important

`GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` was **removed from the Android framework in API 33**
and is dead on all modern Android (AOSP 13+ and Samsung One UI). The app therefore uses a
three-tier strategy:

1. **Primary — `FLAG_ACTIVITY_LAUNCH_ADJACENT | NEW_TASK | MULTIPLE_TASK`.** Initiates
   split from a fullscreen source on Android 12L+ (Samsung One UI 11+). The launch is
   permitted from the background because the app has a visible overlay window
   (`BAL_ALLOW_VISIBLE_WINDOW`).
2. **Legacy fallback — feature-detected toggle** via `getSystemActions()` for Android ≤12.
3. **Guided fallback.** If no split forms, the app shows: *"Start split screen from
   Recents, then tap Apply."* Applying then drops the spacer into the already-open split
   (adjacent-launch into an existing split is reliable everywhere) and adjusts the divider.

## Modules

| File | Purpose |
|------|---------|
| `geometry/RatioMath.kt` | Pure-Kotlin split geometry (unit-tested, no Android deps) |
| `split/EngagementController.kt` | Lifecycle state machine; single source of truth |
| `split/DividerAccessibilityService.kt` | Foreground/visible-app watch, split entry, divider gestures |
| `spacer/SpacerActivity.kt` | The pitch-black companion pane |
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
— all confirmed. Geometry has 16 passing unit tests. See `docs/DEVICE_VERIFICATION.md` for
the on-device Fold7 checklist (the one step the emulator cannot exercise:
programmatic split *initiation*).
