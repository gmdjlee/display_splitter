# Display Splitter — Design Spec (2026-08-11)

## Goal
Galaxy Z Fold7/8 **inner display only**: remove video letterbox / camera-hole overlap for third-party video apps (YouTube, Netflix, …) without touching their rendering. Mechanism: Android split-screen + a black **Spacer window** (this app) as the second pane, divider driven to make the video pane hit the target aspect ratio, spacer placed on the camera-hole side.

## Non-goals (explicit user constraints)
- No standalone media player. No interference in Flex mode (HALF_OPENED). No cover-screen behavior — when folded, the spacer silently finishes; system continuity handles the video app (0ms perceived transition). Subtitles stay controlled by the video app. Gestures follow One UI system settings. Only split-ratio manipulation — nothing else.

## Core mechanism
1. User taps floating bubble (or auto mode) while a target video app is foreground on the inner display.
2. AccessibilityService: `GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN` → launch `SpacerActivity` with `FLAG_ACTIVITY_LAUNCH_ADJACENT`.
3. Spacer measures its window bounds → split axis + current divider fraction are *measured, not assumed*.
4. `RatioMath` (pure Kotlin, unit-tested) computes target divider position:
   - **Horizontal divider** (top/bottom panes): exact-ratio mode — video pane height = paneWidth / targetRatio → zero letterbox inside pane.
   - **Vertical divider** (left/right panes): exact wide ratios are geometrically impossible on a ~1.1:1 screen → hole-avoid mode — spacer just wide enough to cover the camera-hole column (+margin).
   - Spacer side: AUTO = side containing `DisplayCutout` bounding rect (Fold7: upper-right); user can override.
5. Accessibility `dispatchGesture` drags the divider handle to the target; result re-measured; one corrective retry (snap-point tolerant — AOSP snaps to 1/3‑1/2‑2/3, One UI 7+ is flexible).
6. Disengage: finish spacer → system restores video app fullscreen.

## Components
- `App` — Application, service locator (no DI framework).
- `settings/SettingsRepository` — DataStore: ratio preset, position (AUTO/A/B), bubble opacity, auto re-engage, enabled target apps.
- `geometry/RatioMath` — pure math (unit tests).
- `split/EngagementController` — state machine IDLE→SPLITTING→ADJUSTING→ENGAGED; single source of truth (StateFlow).
- `split/DividerAccessibilityService` — foreground-app watch, toggle split, gesture divider drag.
- `spacer/SpacerActivity` — pure-black resizeable pane; reports bounds; finishes when leaving multi-window or moving to cover display; tap = minimal quick controls.
- `overlay/OverlayService` — FGS bubble (Compose in overlay window), opacity 20–100%, drag + edge snap, expanded One UI-style quick panel; visible only while a target app is foreground on inner display, hidden in Flex mode.
- `fold/FoldObserver` — Jetpack WindowManager: FLAT/HALF_OPENED, inner-vs-cover via width class.
- `ui/MainActivity` — One UI-styled Compose settings + permission onboarding (overlay, accessibility).

## Toolchain
Gradle 8.9 · AGP 8.7.3 · Kotlin 2.1.0 · compileSdk 35 · minSdk 29 · Compose BOM 2025.01.01 · androidx.window 1.3.0 (all locally cached). JDK: Android Studio JBR 21.

## Verification order
Unit tests → gradle build → multi-agent strict critics (code + One UI fidelity) iterated to AAA → Pixel 9 Pro Fold emulator visual pass → real device (Fold7) instructions last.
