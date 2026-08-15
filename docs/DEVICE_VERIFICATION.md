# On-device verification — Galaxy Z Fold7 / Fold8

## Results — 2026-08-15 (4): rotation re-apply VERIFIED (SM-F976N, One UI 9.0)

Rotating the device while engaged now re-plans and re-drags the divider for the new
geometry. **Every size-changing rotation landed `exact=true` — better than the entry
path, which still lands 2–4% off on this device (open finding below).**

| Rotation | Display | Video pane | achieved |
|---|---|---|---|
| entry (portrait) | 2256×2504 | 2256×1224 | 1.8431 `exact=false` |
| → landscape | 2504×2256 | 2504×1408 | **1.7784 `exact=true`** |
| → portrait | 2256×2504 | 2256×1268 | **1.7792 `exact=true`** |
| ×4 more (1→0→3→2→0) | — | — | all `exact=true` |
| 4 rapid rotations, 1.5s apart | — | — | all 4 handled, all `exact=true` |

Detection→re-engaged latency is a consistent **1.36s**. A **180° flip is correctly a
no-op** (`user_rotation` 0↔2 keeps the display size, so the pane still holds the ratio
and nothing is touched). Split survived every rotation; 전체 화면으로 still dissolves it
cleanly afterwards. On this device One UI 9 keeps the split **top/bottom across the
rotation** (`StageCoordinator: mChangeToHorizontalSplitLayout=false`), so the
rotate-to-top-bottom popup step never runs — it is retained for builds that do re-lay
the split side-by-side.

### Defect 3 — planning against mid-rotation window bounds (fixed)

The first implementation tore a healthy split down on every rotation
(`ADJUST_FAILED`). Root cause, from the instrumented read at T+250ms:

```
display=Rect(0,0-2504,2256)        ← config already landscape
divider=Rect(1176,1012-1362,1265)  ← 186w × 253h, reads as a VERTICAL divider
video=Rect(633,68-2863,2782)       ← x=2863 past the 2504 display width
spacer=Rect(-359,-526-1905,2209)   ← negative origin
```

The display configuration flips to the new orientation **instantly**, but the a11y
window bounds keep reporting **animation-frame coordinates for ~0.5s**. The axis check
read the transformed divider handle as vertical and "corrected" a perfectly good
top/bottom split via the divider popup, which then could not satisfy its settle
predicate → `ADJUST_FAILED` → spacer teardown.

Fix: `EngagementController.settledPanes` no longer polls only for divider *presence*.
A snapshot is plannable only when the divider is present, **every pane is inside the
display**, and **the bounds are unchanged since the previous poll**. Both new tests are
needed — an intermediate frame can sit inside the display, and bounds can hold still
for one poll while still being stale. Costs one extra 75ms poll in the steady state;
the entry path measured identical before/after (`achieved=1.843 exact=false`).

## Results — 2026-08-15 (3): FIRST PASS ON **Fold8** (SM-F976N, Android 17 / One UI 9.0)

New hardware and a new OS major: inner display **2256×2504** (Fold7 was 1968×2184),
density 480 (override 420), inner display id `4630946722019192211` (screencap `-d`),
cover 1080×2520. One UI 9 offers **top/bottom split directly** (`ShellSplitScreen:
AppsVertical? true`) — no rotate step. Everything below was ADB-driven on a fresh
install; the app had never run on this device.

**Verdict: the whole pipeline works on Fold8/One UI 9, and two real defects were found
and fixed here.**

| # | Check | Result |
|---|---|---|
| 1 | Bubble over a video app | ✅ YouTube + Netflix both attach |
| 2 | Apply → split, no letterbox | ✅ video pane fills edge-to-edge (see below for ratio accuracy) |
| 4 | 위치 전환 flip | ✅ 0.47s via 창 전환, ratio preserved; achieved side written back to the chip (자동→위) |
| 5 | Live ratio change while engaged | ✅ 16:9→21:9 in ~2s landing **2.3354 exact=true**; 4:3 **1.3341 exact**; 16:9 **1.7792 exact** |
| 6 | Restore 전체 화면 | ✅ split dissolves, spacer window count 0, video fullscreen |
| 7 | Netflix | ✅ engages from **immersive DRM playback** (revealBars path) in 3.63s |
| — | Card-first picker discovery | ✅ `picker: cycle=0 dispatched=true`, engage **3.3–3.7s** |
| — | Fresh-install search escalation | ✅ after the fix below, engage **5.09s** |
| — | Settings screen over the split | ✅ opens fullscreen without tearing the split down |
| 8–10 | fold / flex / cover screen | ⏳ still needs hands |

### Defect 1 — the picker docked the WRONG APP (fixed)

On the fresh-install search path the driver typed `DS 스페이서`, matched a node 170ms
later and gesture-tapped it — and One UI docked the **calculator**. Evidence:
`HoneySpace.FromRecent.ViewModel: onItemClick cn=…popupcalculator` 60ms after our tap.
The search overlay's result item is not touchable yet while it animates in, so the
gesture falls **through** to the app grid underneath, which still holds the suggested
apps (`FilteredItemProvider: [youtube, popupcalculator, displaysplitter, settings]`).

Fix: `SplitEntryDriver.stepTapPanelInPicker` uses **a11y ACTION_CLICK first once
`searchUsed`** (node-identity routing cannot land on a neighbour); gesture-first is kept
for the recents-card path where ACTION_CLICK was measured to no-op. Re-verified after
`pm clear`: `onItemClick cn=…SpacerActivity` on the first post-search cycle.

### Defect 2 — settings-screen change never re-applied (fixed)

Changing 영상 위치 on the settings screen while engaged skipped correctly (divider
covered → `pendingAdjust`), but returning to the video **never applied it** — the pref
sat stored as `SECOND` while the video stayed on top, until the user happened to tap the
video pane. Root cause: closing the settings screen focuses **our own spacer**
(`mCurrentFocus=SpacerActivity`), and `DividerAccessibilityService` dropped every
own-package event before the controller could see it, so `retryPendingAdjust` had no
trigger.

Fix: the service no longer filters its own package (the controller already owns that
policy), and `EngagementController.onForegroundPackage` retries a pending adjust on an
own-package event. Re-verified: BACK from settings, no touch on the video → swap +
`engaged: achieved=2.3354037 exact=true` in ~3.5s (including one divider-popup re-tap).

### Open finding — entry lands 2–4% off — **retry branch identified 2026-08-15 (4)**

The §1 question below is **answered**: only `retry[ratio]` fires on entry — never
`retry[side]`/`retry[unsettled]` — so the shared-budget hypothesis is **disproved**.
Three entries, all identical in shape:

```
adjust: retry[ratio] achieved=1.716895  err=45px delta=45   → engaged 1.8446 exact=false
adjust: retry[ratio] achieved=1.716895  err=45px delta=45   → engaged 1.8446 exact=false
adjust: retry[ratio] achieved=1.7182026 err=44px delta=44   → engaged 1.8431 exact=false
```

The compensation **overshoots**: first drag lands the pane 45px too TALL (1314 vs the
1269 target), the +45px compensated retry lands it 46px too SHORT (1223) — so the
retry's drag hit its request almost exactly while the first one undershot by ~45px.
The snap grid is not the cause; the error is not systematic, so `err`-based
compensation cannot converge. Next step: measure whether the first drag's shortfall is
constant (a gesture/hold artifact — the drag starts at the divider's live centre) and
compensate the *first* drag instead of only the retry.

### Original open finding — entry lands 2–4% off, a live re-tap lands exact

Every *entry* on this device settled outside the 2% tolerance for 16:9 — 1.8135 /
1.8446 / 1.7488 (`exact=false`, reported honestly) — while a *live* ratio re-tap on the
same split converged to **1.7792 exact=true**. So the snap grid is not the limit. Most
likely `retriesLeft = 1` is shared: on entry the post-swap settle consumes the retry via
the `!sideOk` branch, leaving none for the ratio compensation (both branches are silent,
so this is unconfirmed). Next step: log which retry branch fires, then consider a
separate compensation budget for the entry path.

## Pending — 남은 검증 (재개용 절차, SM-F976N 기준)

세션을 새로 시작해도 이 절이면 바로 이어서 돌릴 수 있습니다. 좌표/ID는 전부
2026-08-15 Fold8 실측값입니다.

### 0. 준비 (재개 시 1회)

```bash
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug -q --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 재설치할 때마다 a11y 권한이 풀린다 — 매번 다시 넣을 것
adb shell settings put secure enabled_accessibility_services com.displaysplitter/com.displaysplitter.split.DividerAccessibilityService
adb shell settings put secure accessibility_enabled 1
adb shell appops set com.displaysplitter SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.displaysplitter android.permission.POST_NOTIFICATIONS
adb shell dumpsys deviceidle whitelist +com.displaysplitter
adb logcat -c && adb logcat -v time > log.txt &     # 앱 로그: grep -aE "/DisplaySplitter" log.txt
```

기기 상수 / 함정:
- 내부 화면 스크린샷은 `adb exec-out screencap -p -d 4630946722019192211` (`-d` 없으면 커버 화면).
- `adb shell pm list packages`는 이 기기에서 `--user 0`이 필요(보조 사용자 존재).
- 버블 좌표는 **찍기 전에 읽을 것**:
  `adb shell dumpsys window windows | grep -oE "[0-9a-f]+ com.displaysplitter, frame=\[Rect\([0-9, -]+\)\]" | head -1`
  실측 [2067,834][2220,987] → 중심 (2143,910). 넷플릭스 홈에서는 y가 947로 내려갔다.
- 패널 좌표(버블 y=834 기준): 분할 적용 / 전체 화면으로 **(1804,1809)**, 톱니 **(2016,924)**,
  화면비 칩 y=1209 — 16:9 (1505), 21:9 (1656), 4:3 (1956).
  **패널 버튼은 누르기 전에 항상 screencap** (실패 문구가 뜨면 패널이 늘어나 y가 밀린다).
- 설정 화면 영상 위치: 위 (500,1886), 아래 (500,2016).
- 스페이서 트레이는 4초 뒤 자동 숨김이고 탭이 **토글**이다 — 노출 탭과 칩 탭을 한 adb 명령에
  묶을 것: `adb shell "input tap <pane center>; sleep 0.5; input tap <chip>"`. 칩 y는 스페이서
  창 크기에 따라 달라지므로 매번 screencap으로 확인.
- **engage 중 `uiautomator dump` 금지** — a11y 연결이 끊겨 즉시 해제된다.
- 신규 설치 피커(검색) 경로를 다시 타려면 `adb shell pm clear com.displaysplitter` (권한 재부여 필요).

### 1. ADB로 가능 — 진입 시 비율 오차 원인 판정

진입은 항상 허용 오차 밖(16:9 → 1.81~1.84)인데 같은 분할에서 화면비를 다시 누르면
1.7792 exact로 수렴한다. 조용하던 재시도 분기 3곳에 로그를 넣어 뒀으므로(`adjust: retry[...]`)
**engage 한 번이면 어느 분기가 재시도 예산을 쓰는지 판정된다.**

```bash
# YouTube 16:9 영상에서 engage 후
grep -aE "adjust: retry|engaged:" log.txt | tail -5
```

판정:
- `retry[side]` 또는 `retry[unsettled]`가 먼저 찍히고 그 뒤 `engaged: exact=false` → 가설 확정
  (스왑 직후 정착에 예산을 써서 비율 보정 몫이 없음). 조치: 진입 경로만 `retriesLeft = 2`,
  또는 비율 보정에 별도 예산.
- `retry[ratio]`만 찍히고도 빗나감 → 보정식(`err`/`delta`) 문제. 조치: 실측 `err`/`delta` 값으로
  스냅 격자 확인 후 보정 로직 수정.
- 아무 로그도 없이 `exact=false` → 수렴 단축(`converged`) 분기가 잘못 통과시킨 것. 조치:
  `adjustToPlan`의 converged 판정 검토.

### 2. 손이 필요 (물리 조작) — #8~#10, 0 ms

각 동작 직후 아래 명령으로 판정한다. 접기 전에 반드시 engage 상태를 만들어 둘 것.

| # | 물리 동작 | 판정 명령 / 기준 |
|---|---|---|
| 8 | engage 상태에서 **플렉스(반접기)** | `grep -aE "/DisplaySplitter" log.txt \| tail -5` → 디바이더를 건드리는 로그(`switch-node`/`adjust`)가 **없어야** 함. 버블은 사라짐: `dumpsys window windows \| grep -c "com.displaysplitter,"` → 0 |
| 9 | **완전히 접어 커버 화면 사용** | `adb shell dumpsys window windows \| grep -c SpacerActivity` → **0** (즉시). 커버 화면에서 재생 계속되는지 눈으로 확인. 알려진 예외: 커버 recents에 "DS 스페이서" 카드가 보이는지 — **#9의 "zero footprint" 문구를 이 실측에 맞게 고칠 것** |
| 10 | 다시 **펼치기** ("펼친 후 재적용" ON 상태) | `grep -aE "engaged:" log.txt \| tail -1` → 새 `engaged:` 줄이 자동으로 찍혀야 함 |
| — | 0 ms 전환 | 오디오+비디오 재생 중 접었다 펴기 반복 — 오디오 끊김/영상 블랙 프레임 없어야. 앱 없이 같은 영상을 재생한 경우와 비교 |

### 3. 로케일 (아직 미검증)

`SplitEntryDriver`는 디바이더 팝업을 **'창 전환' / 'switch'로만** 매칭한다. 기기 언어를
제3언어(예: 일본어)로 바꾸고 engage → 스왑이 실패할 때 칩이 실제 면으로 정직하게
되돌아오는지(pref reconciliation) 확인. 실패 시 `ADJUST_FAILED` 대신 조용한 성공으로
보고되면 안 된다.

```bash
adb shell am start -a android.settings.LOCALE_SETTINGS   # 수동 변경 후 원복 필요
```

## Results — 2026-08-15 (2): live 영상 위치 change while engaged VERIFIED (SM-F966N)

Changing the panel's 영상 위치 chip mid-engagement now re-applies immediately (the
ratio observer was widened to a combined ratio+positionPref observer, per-element
change detection). Measured: 위 → swap to TOP in 2.4s, landing exact 1.7777778 —
the one real mid-engagement swap+drag measurement. The 아래 → 0.5s run cannot have
exercised a real swap+drag (the gesture constants alone — hold 150 + drag 350 +
settle floor 150 + polls — put a ~785ms floor under one); 0.5s measures the
converged no-op short-circuit, so don't calibrate swap latency against it.
Re-tapping the already-active chip is silent, but that comes from StateFlow
conflation upstream (an equal SettingsState never re-emits, so the observer never
runs for a re-tap); the observer's own side guard is exercised by a different
path — it swallows the pref emission flipVideoSide persists after a successful
tray flip, so no double-adjust. 자동 re-plans and converges without an unnecessary
swap when the side already matches. Unrelated known flake reproduced once during
setup: swap handle tap-through opened the Shorts camera → ADJUST_FAILED; retry
engaged fine.

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

Known trade-offs accepted with the recents card (qualifies #9's "zero cover-screen
footprint" wording): the "DS 스페이서" card is visible in recents on both displays;
tapping it outside an engagement launches-and-finishes on the first frame (state
collector; verified above). Tapping it inside the USER'S own manual split-select
while the app is Idle dissolves that half-built split — same dead-end class that
already existed via the picker's frequent-apps row, now merely more prominent.

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
| Divider drag to ratio | One UI snaps to a grid ~20px coarse: 16:9 target lands at 1.74:1 (2.1% off — just outside the 2% tolerance, so exact=false) — reported honestly in the UI |
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
| 9 | **Close** the phone (use cover screen) | Spacer vanishes instantly; **zero** cover-screen footprint (known exception: the "DS 스페이서" card in cover-screen recents — accepted trade-off, see the 2026-08-15 note); audio/video continues via system continuity |
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
