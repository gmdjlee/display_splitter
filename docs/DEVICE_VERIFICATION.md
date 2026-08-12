# On-device verification — Galaxy Z Fold7 / Fold8

Everything except programmatic split **initiation** was verified on the Pixel 9 Pro Fold
emulator (Android 16). Split initiation must be checked on a real Samsung device because
the AOSP emulator refuses all programmatic adjacent-split entry (it only offers the manual
Recents → Split flow), whereas Samsung One UI 11+ honors `FLAG_ACTIVITY_LAUNCH_ADJACENT`.

## Setup

```
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone, unfolded (inner display):

1. Open the app → **Allow** "Display over other apps".
2. **Allow** the Accessibility service (Settings → Accessibility → Display Splitter).
3. Allow the notification when prompted.
4. **Exclude the app from battery optimization** (Settings → Battery → Display Splitter →
   Unrestricted). One UI aggressively kills accessibility services otherwise.
5. Leave Target ratio = 16:9, Video position = Auto.

## Core checks

| # | Action | Pass criteria |
|---|--------|---------------|
| 1 | Open YouTube fullscreen, play any 16:9 video, unfolded | Floating bubble appears at the screen edge |
| 2 | Tap bubble → **Apply split** | Split forms automatically; the video pane resizes to fill 16:9 with **no letterbox**; the black spacer sits on the **camera-hole side** |
| 3 | Look at the camera hole | It is inside the black spacer, not over the video |
| 4 | Tap the spacer → **Flip video position** | Video and spacer swap sides; ratio preserved |
| 5 | Change ratio to 21:9 in the panel, Apply | Divider moves; video pane becomes 21:9, still no letterbox |
| 6 | Tap **Restore full screen** | Split collapses; video returns to fullscreen |
| 7 | Repeat with **Netflix** | Same behavior |

## Non-interference checks (must NOT act)

| # | Action | Pass criteria |
|---|--------|---------------|
| 8 | Fold to **Flex mode** (half-open) while engaged | App pauses; bubble hidden; does not touch the divider |
| 9 | **Close** the phone (use cover screen) | Spacer vanishes instantly; **zero** cover-screen footprint; audio/video continues via system continuity |
| 10 | **Re-open** to inner display | With "Re-apply after unfolding" on, the split is restored automatically |
| 11 | Open a video's subtitles | Subtitles behave exactly as the video app dictates; app does not move them |
| 12 | Use system gestures (back/home/recents) | Behave per One UI settings; app does not intercept |

## 0 ms transition check (the strict criterion)

Play audio+video, then repeatedly **fold and unfold**. Audio must not stutter and video
must not blank on the transition — the spacer's window has no enter/exit animation and
finishes without a visible frame. Compare side-by-side with the same video played without
the app: the folded/cover behavior must be identical (the app is out of the way when
closed).

## If split does not form on Apply (tier-3 fallback)

If a device blocks programmatic split, the panel shows *"This device blocked automatic
split screen. Start split screen from Recents, then tap Apply."* Do that: open Recents,
tap **Split**, pick any second app, then tap **Apply** on the bubble — the spacer replaces
the second pane and the ratio is applied. Report the device/One UI version if this path is
hit, so the primary mechanism can be tuned.

## Diagnostics

```
adb logcat | grep -iE "displaysplitter|BAL_|StageCoordinator|ActivityTaskManager: START.*displaysplitter"
```

Look for `START … cmp=com.displaysplitter/.spacer.SpacerActivity … (BAL_ALLOW_VISIBLE_WINDOW)`
followed by a `StageCoordinatorSplitDivider` window appearing — that confirms the primary
launch-adjacent path initiated the split.
