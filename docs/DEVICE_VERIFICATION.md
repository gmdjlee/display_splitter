# On-device verification — Galaxy Z Fold7 / Fold8

## Results — 2026-08-15 (2): live 영상 위치 change while engaged VERIFIED (SM-F966N)

Changing the panel's 영상 위치 chip mid-engagement now re-applies immediately (the
ratio observer was widened to a combined ratio+positionPref observer, per-element
change detection). Measured: 위 → swap to TOP in 2.4s, 아래 → swap to BOTTOM in 0.5s,
both landing exact 1.7777778; re-tapping the already-active chip is a pure no-op
(zero controller activity — this same guard swallows the pref emission that
flipVideoSide persists after a successful tray flip, so no double-adjust); 자동
re-plans and converges without an unnecessary swap when the side already matches.
Unrelated known flake reproduced once during setup: swap handle tap-through opened
the Shorts camera → ADJUST_FAILED; retry engaged fine.

## Results — 2026-08-15: spacer recents card + ambient widgets VERIFIED (SM-F966N, One UI 8.5, ADB-driven)

Both pending changes pass on device. Three engage cycles run (YouTube feed, immersive
live video ×2), all committed successfully.

| Verified | Detail |
|---|---|
| Card-first picker discovery | Engage #1 (no card yet): `picker: cycle=0 search-escalation=true`, engage 5.98s. Engage #2 (dead card at MRU): `picker: cycle=0 dispatched=true` — **no search, 2.91s, >2× faster**. Card survives `finish()` exactly as designed (HoneySpace logs `DS 스페이서 [RecentApp]` after the spacer dies). |
| Search fallback when card absent | Engage #3 ran after the dead card was consumed (see below): search escalation kicked in automatically, engage 5.2s, committed. Fresh-install path stays healthy. |
| Dead-card tap outside engagement | Accidentally measured: tapping the dead card in recents while Idle launches-and-finishes on the first frame (`isRunning=true→false` in ~200ms, no stuck fullscreen spacer, lands back where you were). **Consuming the card this way removes the task from recents** — the next engage is search-path, then the new card re-seeds card-first. Documented trade-off behaves as designed. |
| Widget tray | Pane tap → one tray: [시계|메모|검정] + 위치 전환/전체 화면, 검정 default, 4s auto-hide. All five controls exercised. |
| CLOCK | Large ambient time + date (8월 15일 토요일), dim luminance ladder as designed, centered, no layout shift. |
| MEMO | Typed via ADB: field focus opens IME, tray folds away; "자동 저장됨" indicator appears only after the real write commits, then self-dismisses. Escape path from MEMO (field margins / status-line strip) works — the field consumes its own taps, so tray toggling needs the margins, as documented. |
| Memo + mode persistence | Restore → re-engage: new spacer instance restores MEMO mode AND the typed text from DataStore with zero input. ON_PAUSE flush + seed-once read verified end-to-end. |
| Recents thumbnail privacy | Spacer card face renders PLAIN DARK in recents — typed memo never appears (`setRecentsScreenshotEnabled(false)` works on One UI 8.5). Label renders (HoneySpace `label:DS 스페이서`); the picker matches the card (engage #2 cycle-0 hit). |
| 위치 전환 (flip chip) | Divider-popup 창 전환 driven; panes swapped video→TOP, spacer→BOTTOM; re-settled at **1.7777778 exact=true** (flip landed on the exact 16:9 snap). |
| 전체 화면 (restore chip) | Split dissolves, video app fullscreen, state Idle, spacer window gone (`dumpsys window` count 0). |

Snap-grid note: achieved ratios across entries were 1.814 / 1.716 / 1.740 / 1.7778 —
One UI's ~20px grid scatters around 16:9 and the app reports each honestly
(`exact` only at 1.7778).

ADB-driving traps learned this pass (for future sessions):
- **NEVER run `uiautomator dump` while engaged** — registering UiAutomation disrupts
  the a11y service connection and the engagement dissolves within a second (spacer
  finishes via the state collector; overlay churns detach/attach). Cost one engage.
- Spacer tray auto-hides in 4s: reveal-tap + chip-tap must be chained in ONE adb
  command. Remember taps TOGGLE — a stray pane tap between chains flips parity.
- Overlay panel Apply y drifts with bubble y (measured 1544 vs 1652); bubble idle y
  drifts too (795 vs 881). Always screencap before tapping panel buttons.
- In MEMO mode, tray reveal needs the field margins (top status strip ~y<85 in-pane,
  or side gutters); a field tap re-opens the IME instead.

Still pending (physical, user hands): cover-screen recents card visibility/wording
(#9), fold/flex transitions, Netflix pop-up-player quirk.

## Results — 2026-08-13: FWA-ported Recents entry VERIFIED WORKING (SM-F966N, One UI 8.5)

**Split initiation is solved.** The FoldWindow-style Recents automation was ported and
verified end-to-end on device: Apply → top/bottom split with the black spacer → divider
at the target ratio, in ~5s, repeatably.

| Verified | Detail |
|---|---|
| DRAG recipe (resizeable apps, YouTube ×3 runs) | Recents → card-icon hold-drag to the TOP or BOTTOM edge (both drop zones work in portrait; the edge is chosen so the video lands on its planned side → no swap needed) → partner picker → spacer tapped |
| MENU recipe (unresizeable apps, Netflix) | card icon → "분할 화면으로 열기" (L/R) → picker → divider handle → "시계 방향으로 회전" → T/B. `ResizeMode` privateFlags bit 1<<11 correctly classifies Netflix |
| Picker discovery | Fresh install: picker search escalation (search button → SET_TEXT "DS 스페이서" → result tap). After first use the spacer shows up in the picker's recent/frequent sections and is tapped directly |
| Pane swap (flip) | Divider-handle popup "창 전환" — a bare double-tap on the handle just opens/mis-taps that popup (measured); popup click is the only swap that works |
| Divider drag to ratio | One UI snaps to a grid ~20px coarse: 16:9 target lands at 1.74:1 (1.9% off, within the 2% tolerance) — reported honestly in the UI |
| Restore (전체 화면으로) | Spacer self-finishes → split dissolves → video app fullscreen |
| Measured traps fixed this session | ① hold stroke needs 1px drift AND the continuation must wait out holdMs (queued-continuation collapses the hold); ② the spacer's cover-screen guard must use DISPLAY size, not its own pane-sized configuration (it was self-destructing on landing); ③ divider window leaves the a11y list for whole animation durations (1.5s settle polls); ④ picker search field's own text matches the label — exclude editable nodes |

Still pending: physical checks below (fold/flex/0ms), plus the Netflix in-playback
pop-up-player quirk (FoldWindow device facts) when engaging mid-playback.

## Results — 2026-08-12, SM-F966N (Fold7), Android 16 / One UI 8.5 (ADB-driven)

**Verdict: the divider-driving pipeline works on real One UI; split *initiation* and
cutout *sourcing* are both blocked by One UI 8.5 and need product changes.**

| Finding | Status |
|---|---|
| Tier-1 `FLAG_ACTIVITY_LAUNCH_ADJACENT` from a background context | ❌ Ignored — spacer opens **fullscreen** (`windowing-mode=1`), from fullscreen source AND into an existing split. BAL itself is fine (`BAL_ALLOW_NON_APP_VISIBLE_WINDOW`). App detects it and fails gracefully (`SPLIT_UNAVAILABLE`, guidance shown). |
| Tier-2 legacy toggle | N/A (API 36, correctly feature-gated off) |
| Tier-3 as documented (split with *any* app, then Apply) | ❌ Also fails — spacer replacement relies on the same LAUNCH_ADJACENT. **Working variant: pick *Display Splitter itself* as the second split app**, then Apply — `panes()` accepts our pane as the spacer and the pipeline runs. |
| `TYPE_SPLIT_SCREEN_DIVIDER` detection (`Embedded{StageCoordinatorSplitDivider}`) | ✅ Works; divider **flickers out of the a11y windows list** transiently — first-check now uses `settledPanes` (fixed this session) |
| Axis measurement | ✅ Vertical divider (side-by-side) — the ONLY split layout One UI offers on the near-square inner display, so **hole-avoid is the only reachable mode**; exact-ratio (horizontal divider) appears unreachable on this device |
| Hole-avoid plan + `dispatchGesture` divider drag | ✅ Divider dragged x984→x1398; video pane [0,1391], spacer pane [1405,1968] fully covers the punch hole (x1450–1520); Engaged, honest 0.64:1 report |
| Double-tap pane swap | ✅ Video moved FIRST→SECOND on pref change + re-Apply; divider then dragged to minPane (~10%), `holeExposedByChoice` honored |
| Restore (전체 화면으로) | ✅ State returns to Idle (full collapse untestable in the bypass — the pane was MainActivity, which doesn't self-finish like SpacerActivity) |
| Fail/guidance UX (`fail_split_unavailable`), status card, achieved-ratio display | ✅ |
| **Display cutout sourcing** | ❌ One UI 8.5 hides the cutout from EVERY third-party API: service/window-context `WindowMetrics`, `Display.getCutout()`, overlay-window insets, and even **activity windows** (fullscreen + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS` still get `displayCutout=null` while the raw `InsetsState` in logcat clearly carries `Rect(1450,18–1520,88)`). `config_mainBuiltInDisplayCutout` resource is empty too. Verified this session with the value manually seeded into the app's cutout cache. |

Session code changes: `App.trackCutout` + per-display-size cutout cache (currently
unfillable on One UI 8.5 — see above), `settledPanes` on the engage first-check,
`displayCutoutRects` fallback chain.

**Open items**
1. Split initiation: launch-adjacent is dead on One UI 8.5. Candidates: update tier-3
   guidance to "pick Display Splitter as the second app"; have the in-pane MainActivity
   launch SpacerActivity into its own task (same-task launch stays in the pane); research
   Samsung app-pair APIs.
2. Cutout source: seed a small per-model table (Fold7 inner 1968×2184 → 1450,18,1520,88)
   or find a One UI-visible source; without it hole-avoid no-ops (graceful, but does nothing).
3. Physical checks below (fold/flex/0ms) still require hands on the device.


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
