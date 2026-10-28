# v1 screen, copy and accessibility audit (lane H)

**Date:** 2026-10-28. **Baseline:** `integration/v1` at `ae1e2a1`; every `path:line` below refers
to that tree. **Inputs:** the Ember & Mist references `design/ember-mist/E00–E16`, `design/tokens.json`,
[ADR-012](../architecture/protection-contract.md) (authoritative for every protection claim; copy IDs
`C01`–`C28` are its §13 list) and the 2026-10-27 iOS §3 / Android §3, §8 rank 10 state audits.

This is a **source audit**, not a device run. "A11y" findings are read from layout code; each fix PR
records its own simulator/emulator evidence, and a human VoiceOver/TalkBack pass on hardware is still
required before release (see §6). No finding here is a claim that the current apps already behave as
ADR-012 requires.

**Owner lanes:** B iOS enforcement · C Android enforcement · E Android release/legal · F history
(Today/Complete/Insights, notifications) · G rules/limits/Sleep semantics (wave 2) · H this lane ·
J acceptance ledger/docs. H owns presentation only; it adds UI for an action only after B/C expose it.

## 1. Cross-cutting findings

| # | Finding | Evidence | Owner |
| --- | --- | --- | --- |
| X1 | **Palette contrast passes.** Every text token clears WCAG AA on night/ground/sheet: bone 15.6:1, mist 8.8:1, stone 6.5:1, faint 5.5:1, ember 8.9:1, danger 7.6:1; on-ember 7.0:1, on-bone 14.9:1. Hairlines/borders at bone 0.08–0.18 alpha are below 3:1 non-text contrast, so a border must never be the only cue for a control's bounds or state. | `design/tokens.json`; `ios-app/Flint/DesignSystem/FlintUI.swift:155,170,301-314`; Android `FlintPalette` in `core/core-common/.../theme/FlintTheme.kt:27-59` | H |
| X2 | **iOS type scales; Android type is `sp`.** iOS `FlintFont.display/text` use `relativeTo:` (`FlintUI.swift:28-43`), but UIKit chrome uses raw `UIFont(name:size:)` without `UIFontMetrics` (`FlintAppearance.swift:14-39`), and `flintNumber` shrinks with `lineLimit(1)`+`minimumScaleFactor(0.6)` instead of growing (`FlintUI.swift:65-72`). Android `FlintTypography` is all `sp` (`FlintTheme.kt:125-141`). | as cited | H |
| X3 | **Fixed control heights clip at 2× font (Android).** `EmberButton` `height(56.dp)`+`maxLines = 1`, compact `BoneButton` 36 dp, `GhostButton` 44 dp, `FlintChip` 40 dp, `FlintSegmented` 36 dp; several below the 48 dp touch target. | `core/core-common/.../ui/FlintControls.kt:99-108,131,162,189,229,291` | H |
| X4 | **Reduced motion is partial.** iOS gates the orb, mist, press scale and session rings (`FlintUI.swift:89-93,430-457,510-530`), but not page/sheet `withAnimation` calls (`OnboardingView.swift:14`; `SessionView.swift:54,90,344`). Android's `rememberReducedMotion()` (`FlintControls.kt:63-68`) is used only in `FlintScene.kt:171,226`; `flintPulse`, block-screen breath/entrance, Insights count-ups and the consent stagger are ungated (`FlintKit.kt:140-152`; `BlockScreen.kt:142-162`; `StatsScreen.kt:265-279,530-556,636-644`; `AccessibilityConsentScreen.kt:216-238`). | as cited | H |
| X5 | **Strings are not localizable as written.** iOS has no `.strings`/`.xcstrings`; `Text("…")` literals are keys, but `FlintRow(title: String)` uses the verbatim `Text(StringProtocol)` initializer (`FlintUI.swift:317-352`), as do tab titles (`FlintUI.swift:594-605`), computed footers, `Button(String)` and `+`-concatenated copy (e.g. `SleepModeView.swift:111-120,137-150`; `SettingsView.swift:149-151,203-205`). Android feature modules have **zero** `stringResource()` calls and no `res/values` (only `app` and `blocking-accessibility` have `strings.xml`). New H screens use localizable keys/resources; converting existing screens is tracked per screen. | as cited | H (new/owned screens); each owner for theirs |
| X6 | **Colour-only state.** Android `FlintStatusDot` has no description (`FlintKit.kt:200-203`, used `SettingsScreen.kt:225-231`, `BlocklistScreen.kt:680`); `FlintMeterBar` exposes no progress (`FlintKit.kt:261-286`); iOS Sleep day circles and schedule timeline blocks are colour-only (`SleepModeView.swift:94-109`; `SchedulesView.swift:259-260,281-282`). | as cited | H (kit, Sleep); G (timeline) |
| X7 | **Android semantics gaps in the kit.** `selectableRole()` sets `selected` without a `Role` (`FlintControls.kt:239`; `FlintScene.kt:78`); `flintClickable` has no role/merge (`FlintControls.kt:333`); skeletons announce nothing (`FlintKit.kt:158-192`). `FlintInfoPill` (`FlintKit.kt:237-254`) is the pattern to copy. | as cited | H |
| X8 | **No Help/limitations or app/site detail screen exists** on either platform. The closest are iOS `StrongerProtectionView` (`SettingsView.swift:198-217`), the Insights report-help sheet (`StatsView.swift:167-187`) and Android in-context copy (`SetupGuidance.kt:96-130`, `BlockingHealthUi.kt`). | as cited | H |
| X9 | **No screen-level UI test harness on Android** (JVM mapper tests only; `ui-tests/` is adb evidence scripts). iOS XCUITest harness is lane I's open PR #73. H follows the pure-mapper JVM test pattern for copy/state and records device screenshots as evidence. | `feature/*/build.gradle.kts`; `android-app/ui-tests/` | I (harness), H (tests for its screens) |

## 2. Screen matrix — iOS

| Ref | Current implementation | Gaps vs design / ADR-012 | Truthful-copy issues | Accessibility issues | Owner |
| --- | --- | --- | --- | --- | --- |
| E01 Welcome | `ios-app/Flint/Features/Onboarding/OnboardingView.swift:25-59` | Matches E01 composition. Not scrollable; fixed `.padding(.top, 110)` (`:54`). | "Every feature free" is true (all tiers/pass free per ADR-012 §1). | Claim row `lineLimit(1)`+`minimumScaleFactor(0.8)` shrinks instead of wrapping at AX5 (`:47-49`); page crossfade ungated by reduce motion (`:14`); no header trait on "Flint". | **H** |
| E02 Permissions | `OnboardingView.swift:61-128`; `Features/Authorization/AuthorizationControls.swift:15-54` | One required Screen Time row; design's optional "Tell me when a session ends" notification row and "Step 1 of 2" are absent. | "Let Flint guard your focus" is soft; fine. Must not promise that access cannot be revoked. | Fixed `Spacer().frame(height: 240)` (`:74`) pushes content off-screen at AX sizes; `"\n"` in the title (`:75`) forces a break at every size; status "Allowed/Required" is a verbatim `String` (`:93`). | **H** presentation; **F** owns the optional notification step's logic |
| E03 Today | `Features/Today/TodayView.swift:7-161` | Focused-today aggregate pending F's history store (#90). Current/next schedule derived from enabled windows, not arming (state audit §3). | Honest today. | `flintNumber` shrinks (X2); no loading state on `.task` reload (`:35-39`). | F (B for arming truth) |
| E04 Start focus | `Features/Today/StartFocusSheet.swift:6-249` | Allow-list toggle absent. | **C02**: "No way out" (`:238`); "Can’t be stopped, and Flint can’t be removed, until it ends. Your weekly Emergency Pass is the only exit." (`:246`). | Strong AX layout already; group chips lack label/value (`:186`); block row not combined (`:160-181`). | **B** (tier copy must match B's merged session semantics); H a11y with B's agreement |
| E05 App picker | Apple `familyActivityPicker` (`StartFocusSheet.swift:59`; `Schedules/SchedulesView.swift:469`; `Limits/LimitsView.swift:224`; `Limits/OpenLimitsView.swift:152`) | Platform substitution per E00. Host summary is counts only (`Sessions/SessionViewModel.swift:79-88`; `SchedulesView.swift:490-494`); categories are never expanded; only Open Limits explains it (`OpenLimitsView.swift:156-174`). Safety-floor exclusion explanation (ADR-012 §7) not shown. | None. | Summary rows not combined (`StartFocusSheet.swift:160-181`; `SchedulesView.swift:418-434`). | **H** summary presentation; B owns selection validation (#82) |
| E06 Session | `Features/Today/SessionView.swift:6-132` | No durable completion (F/B). | "Locked until it ends" (`:118`); VM "it can't be stopped until it ends" (`Sessions/SessionViewModel.swift:125`) — **C05/C10**. | Non-scrolling stack with 84 pt timer collides at AX5 (`:17-48`); ungated `withAnimation` (`:54,90`). | B (copy/semantics), F (completion); H layout with owner agreement |
| E07 Shield | `Extensions/ShieldConfigurationExtension/FlintShieldConfiguration.swift:16-110`; actions `Extensions/ShieldActionExtension/FlintShieldAction.swift:22-85` | Design shows the cause ("Laser Focus, until 12:00") and "Take a break"; current shield has neither. Showing a cause/end requires ADR-012 §3's contributor resolution, which is not yet exposed as a read API to the extension. | "N intentional opens left today" (`:25`) and "It opens again tomorrow" (`:32`) describe the baseline open model; **C16/C17/C19** apply when B lands bounded Open credits. All copy is a verbatim `String` (X5). | Apple owns layout; contrast fine. | **H** visuals once **B** exposes a read-only cause/end snapshot and the break action |
| E08 Complete | `SessionView.swift:369-394` | Today total / breaks pending F. | — | Non-scrolling (`:378-390`). | F |
| E09 Break wait | `SessionView.swift:196-265` | 150 s wait **ends** the session — **C09**; ADR-012 §4 needs persisted 60/300/900 s waits and separate break vs end. | C09. | Fixed 280×280 ring pushes "End session" off-screen at AX5 (`:225-242`). | B |
| E10 Emergency Pass | `SessionView.swift:268-357` | Ends only the Hardcore session; ADR-012 §6 requires global 60-minute recovery — **C08**. | **C06**: "1 pass left this week" / "No pass left this week" (`:302`), "Pass used this week" (`:89`) although eligibility is rolling (`Packages/FlintCore/Sources/FlintCore/FlintEmergencyPass.swift:8-14`). | Hold has label/hint/action but no progress value (`:351-355`); pill `.frame(height: 28)` clips (`:302-308`); `.isModal` without hiding the session beneath (`:324`). | B |
| E11 Sleep | `Features/Sleep/SleepModeView.swift:14-248`; `SleepDialHeader.swift:6-107` | Native `Form` styling with non-token colours (`Color.gray.opacity(0.15)`, `Color.primary`, `FlintBrand.spark` at `:103-104`; literals `SleepDialHeader.swift:62-64`). No separate pre-bed wind-down period (design) — semantics are lane G's. | **C20**: "Flint's free weekly Emergency Pass is the escape hatch" (`:146`); "turning Sleep Mode off also lifts the night shield" (`:147-148`) is **true at baseline** and must stay until G changes it. Saved tier not shown as a tier name. | Day circles are unlabeled single letters with colour-only selection (`:94-109`); dial `208×208` clips its 36 pt number at AX5 (`SleepDialHeader.swift:78`) and its value omits bedtime/wake (`:80-81`); footers verbatim (X5). | **H** presentation/copy accuracy; **G** semantics |
| E12 Insights | `Features/Stats/StatsView.swift:8-188`; `Extensions/DeviceActivityReportExtension/TotalActivityView.swift:21-161` | Focus metrics pending F. | "SCREEN TIME THIS WEEK" (`TotalActivityView.swift:55`) for a rolling 7-day range labelled "7 days" (`StatsView.swift:89`). | 11 pt stone axis labels; "today" marked by colour only (`TotalActivityView.swift:150-153`). | F |
| E13 Rules | `Features/Schedules/SchedulesView.swift:30-227` | Toggles/delete bypass tier (ADR-012 §2) — B/G. | — | Timeline is one `children: .ignore` element exposing only "Now: X" (`:281-282`); off rows shown by colour (`:170`); delete only via context menu (`:190-192`). | B/G; H a11y on request |
| E14 Schedule editor | `SchedulesView.swift:309-545` | No one-shot dates (G). | **C03**: "No early exit until it ends. Your weekly Emergency Pass is the only way out." (`:506`). | Name field fixed 44 pt height on 28 pt text (`:377-381`); unlabelled wheel `DatePicker` (`:563-565`); fixed `.height(320)` detent (`:570`); Save disabled without a reason (`:528-530`). | G (B for C03 until G starts); H a11y on request |
| E15 Limit editor | `Features/Limits/LimitsView.swift:93-244`; `OpenLimitsView.swift:88-188` | Unified Time/Open editor absent (G). | **C16**: "Cap how many times a day an app can be opened" (`OpenLimitsView.swift:64`). | Toolbar `+` unlabelled (`OpenLimitsView.swift:51`); empty-state icon not hidden (`:61-62`); ring 210×210 clips at AX5 (`LimitsView.swift:176`). | B (Open credits), G (editor); H a11y on request |
| E16 You | `Features/Settings/SettingsView.swift:6-217`; `WebRestrictionsView.swift`; PIN views; `AppLockWindow.swift` | Design rows: App groups, Website rules, Shortcuts and Focus, Notifications, About. Current adds App lock and Stronger protection; no App groups row, no Help. | **C06** "Emergency Pass ready · 1 this week" (`:147`); **C25** "Checked …" stamped on screen entry (`:111,179-183`); **C13** "true anti-uninstall … prevents deleting Flint or turning blocking off" (`:203-205`) and "Hardcore sessions already stop Flint from being deleted" (`:208`); **C15** "can't be evaded mid-session" (`WebRestrictionsView.swift:20`). | Health check icons not hidden (`:186-190`); whole card is one long element (`:160`); PIN gate `SecureField` fixed 180×52 and non-scrolling (`FlintApp.swift:137-142`); domain `+` unlabelled (`WebRestrictionsView.swift:113-115`); curtain "Flint is hidden" not localizable (`AppLockWindow.swift:90`). | **H** layout, Help, Stronger protection copy; **B** health card (#87, C06/C25) and website rules (#78, C15) |
| — Help & limitations (new) | none (X8) | ADR-012 requires users to learn tiers, pass, what cannot be prevented, and platform limits. | — | — | **H** |
| — App/site detail (new) | none | Needs read-only "which causes cover this target today" and per-target Open counts from B; F for opens/credits history. | — | — | **H**, blocked on B read API |

## 3. Screen matrix — Android

Paths are under `android-app/`.

| Ref | Current implementation | Gaps vs design / ADR-012 | Truthful-copy issues | Accessibility issues | Owner |
| --- | --- | --- | --- | --- | --- |
| E01 Welcome | `app/src/main/kotlin/com/flint/peakfocus/MainActivity.kt:360-397` | Matches E01. | True ("No account · No analytics · Every feature free"). | Non-scrolling `Column` with fixed `Spacer(110.dp)` and 14/18/12 dp spacers (`:371-394`) clips at 320 dp / 2×; `EmberButton` fixed 56 dp + one line (X3); no heading semantics. | **H** |
| E02 Permissions / consent | `feature/feature-onboarding/.../AccessibilityConsentScreen.kt:68-179`; `ConsentCopy.kt:5-18`; `SetupGuidance.kt:53-131` | Android-specific prominent disclosure instead of the iOS two-row mock (deliberate). | **C21**: "turn it off any time in Settings" (`ConsentCopy.kt:16`) while the release guard still intercepts during Hardcore; "Flint can't block anything until this is enabled" (`AccessibilityConsentScreen.kt:168`) contradicts the UsageStats path. Today's setup line truncates the guidance at the first sentence (`MainActivity.kt:306`). | Entrance stagger ungated (`:216-238`); pre-API 33 Back leaves mid-disclosure (`:246-261`). | **E** (consent, #75); C (guard removal); H layout/motion after #75 |
| E03 Today | `feature/feature-blocklist/.../TimerScreen.kt:279-372` | Focus total pending F (#95). | Honest. | `LinearProgressIndicator` unlabeled (`:335`). | F |
| E04 Start focus | `TimerScreen.kt:374-542` | Sites/allow-only absent here. | Break hints "No breaks" (`:686-689`) — C04 family. | Duration chips 44 dp tall in a 4-way row with duplicated descriptions (`:448-457`); disclosure 38 dp (`:512`). | C (tier copy); H a11y with C's agreement |
| E05 App picker | `feature/feature-blocklist/.../AppPickerScreen.kt:81-418`; `InstalledApps.kt:24-66` | No category chips / Block-Allow tabs / Save-as-group in the shared picker. `InstalledAppsUiState` is Loading/Ready only (`BlocklistViewModel.kt:54-57`): an enumeration failure looks like an empty device. Safety-floor exclusion is silent (ADR-012 §7 asks for a short explanation). | "No launchable apps found on this device." (`:335`) can be false on failure. | Confirm button default 40 dp (`:293-301`); single-select rows not merged (`:356-418`). | **H** UI; **C** sanitization/exclusion data |
| E06 Session | `TimerScreen.kt:546-622`; `TimerExitDialog.kt:43-138` | No in-session pass entry. | **C24**: "Hold to end early" (`:614`) is accurate for legacy Timers; new Harder/Hardcore must change with C's ADR-012 work. | Non-scrolling with weight spacers (`:567-573,599`); 84 sp countdown `maxLines = 1` (`:584-589`); `HoldToEndControl` is the best pattern in the app (`TimerExitDialog.kt:82-124`). | C (semantics/copy); H layout |
| E07 Shield / E09 / E10 | `feature/feature-blockscreen/.../BlockScreen.kt:126-572`; `BlockScreenState.kt:117-200` | Extra "Open Flint" and legacy hierarchy vs E07; no dedicated pass confirmation (E10) or pass detail. | **C07**: "Emergency Pass used — a new one arrives Monday." (`:561`); KDoc "one per week" (`:114`); guard line "while Deep Focus is on" (`BlockScreenState.kt:145`) — **C22**. | Non-scrolling with two weight spacers (`:219-234,274`) clips at 2×/320 dp; breath/entrance ungated (`:142-162`); hold-to-request lacks `stateDescription`/progress (`:605-629`). | **H** visuals/layout; **C** actions, pass/break semantics and their copy |
| E08 Complete | `TimerScreen.kt:626-655` | Totals pending F. | — | Non-scrolling (`:639-642`). | F |
| E11 Sleep | `feature/feature-blocklist/.../SleepEditorScreen.kt:58-320`; `SleepRules.kt` | No separate pre-bed wind-down field (G). | **C20**: Wind Down = Easy mapping prose (`SleepRules.kt:20-23`). | Two time fields in one fixed `Row` (`:168-184`); day pills ~35-40 dp wide (`RuleEditorScreen.kt:724-739`); assist radio rows colour-only (`:333-341`). | **H** presentation (after C's #84 lands); G semantics |
| E12 Insights | `feature/feature-stats/.../StatsScreen.kt:138-873` | Screen usage, not focus (F #95). | Count-up animation shows not-yet-true totals (`:353,361`). | Day bars ~39 dp wide, no role, description overwrites text (`:656-663`); "TYPICAL" baseline colour/position only (`:590-603`); no error state (`StatsUiState.kt:113-120`). | F |
| E13 Rules | `feature/feature-blocklist/.../BlocklistScreen.kt:154-819` | No app/site detail. | **C04**: "No breaks, no early stop — one Emergency Pass per week." (`Summaries.kt:106`); **C16**: "how many times — an app gets used" (`BlocklistScreen.kt:500`). | "New rule" 44 dp (`:360`); switch nested beside clickable card (`:707,776`); no error state (`BlocklistViewModel.kt:39-51`). | C/G; H a11y on request |
| E14 Schedule editor | `feature/feature-blocklist/.../RuleEditorScreen.kt:89-750` | No one-shot dates (G). | **C03**: "Your weekly Emergency Pass is the only exit." (`:523-524`). | Time fields clip at 2× (`:470-484`); 18 dp delete icon nested in a chip (`:405-414`). | G (C until G starts); H a11y on request |
| E15 Limit editor | `feature/feature-blocklist/.../LimitEditorScreen.kt:64-343` | No live remaining, per-limit tier, unified editor (G). | **C16**: "Block it after opening it this many times in a day." (`:275`). | Remove/Save 40 dp (`:311,328`). | C/G |
| E16 You | `feature/feature-settings/.../SettingsScreen.kt:79-520`; `BlockingHealthUi.kt`; `OemGuidance.kt`; `ExitDiagnosticsText.kt` | No pass summary, Help, About/support or app-lock surface. | Hedged and honest. | Expandable cards lack expanded state (`:304-308,374-379`); hand-off buttons 40 dp (`:258,262`). | **H** layout/Help (after E #75 and F #95 land); C health semantics |
| — Help & limitations (new) | none (X8) | as iOS | — | — | **H** |
| — App/site detail (new) | none | Needs C read API (covering causes, per-package Open counter) and F history. | — | — | **H**, blocked on C read API |

## 4. Lane H implementation plan

Each item is a focused PR into `integration/v1` with evidence under `docs/verification/h-*-2026-10-28/`.
Ordering avoids files open in other lanes' PRs (#75, #78, #84, #87, #91, #95).

1. **iOS onboarding (E01/E02):** AX5-safe scrolling welcome, no fixed 240 pt spacer, wrapping claim
   row, reduce-motion page change, header traits, localizable status. F's optional notification row
   slots in after; H leaves an insertion point and no notification logic.
2. **iOS Help & limitations + You rows:** new screen (tiers, Emergency Pass, what Flint cannot prevent,
   platform limits) linked from You; Stronger protection copy corrected (C13) without new semantics.
3. **iOS Sleep presentation (E11):** token colours, labelled day toggles with selected state, dial value
   with bedtime/wake, localizable footers, C20 pass wording; the "Sleep off lifts the shield" fact stays
   until G changes it.
4. **Android kit accessibility (X3/X4/X7):** min-height controls, roles, state descriptions, loading
   semantics, reduced-motion gating.
5. **Android E01/E05/E07 layouts:** scrollable Welcome, picker error/explanation presentation, block
   screen scroll at 2×/320 dp and reduced motion — no action or copy semantics.
6. **Android Help & limitations + You layout:** after #75/#95 and C's guard removal, so "you can always
   turn off Accessibility" is true when it ships.
7. **E07 iOS shield visuals and app/site detail (both):** after B/C expose read-only cause/end and
   per-target counters; until then H does not invent a contributor resolution.

## 5. Routed to other lanes

- **B:** C02 (`StartFocusSheet.swift:238,246`), C05/C10 (`SessionViewModel.swift:125`;
  `FlintShortcutsRunner.swift:22`), C06/C08/C09 (`SessionView.swift:89,296-302`), C06/C25 on the health
  card (`SettingsView.swift:111,147,179-183`, in #87), C15 (`WebRestrictionsView.swift:20,38`, in #78),
  C16/C17/C19 (`OpenLimitsView.swift:64`; shield subtitles `FlintShieldConfiguration.swift:25,32`) when
  bounded Open credits land; a read-only cause/end snapshot for the shield extension and an app/site
  detail read API.
- **C:** C03 (`RuleEditorScreen.kt:523-524`), C04 (`Summaries.kt:106`), C07 (`BlockScreen.kt:561`,
  KDoc `:114`), C16 (`LimitEditorScreen.kt:275`; `BlocklistScreen.kt:500`), C22 (`BlockScreenState.kt:145`),
  C24 for new Timers (`TimerScreen.kt:614`); picker load-error state in `BlocklistViewModel.kt:54-57`;
  a read-only covering-causes/Open-counter API for app detail; publish the supported-browser matrix
  for the Help screen.
- **E:** C21 (`ConsentCopy.kt:16`) and "can't block anything until this is enabled"
  (`AccessibilityConsentScreen.kt:168`) in #75; Today setup-line truncation (`MainActivity.kt:306`).
- **F:** E02 iOS optional notification step logic; "SCREEN TIME THIS WEEK" (`TotalActivityView.swift:55`);
  Android Insights error state and count-up truthfulness (`StatsScreen.kt:353,361`; `StatsUiState.kt:113-120`).
- **G:** C20 Sleep tier/off semantics, C03 once G owns editors, editor a11y items in §2/§3 marked
  "H a11y on request".
- **J:** C01, C06, C12, C22, C23, C26-C28 in READMEs/CHANGELOG/research docs.

## 6. Accessibility verification plan and limits

- Per PR: iOS simulator screenshots at Large and AX5 (`accessibility-extra-extra-extra-large`) and with
  Reduce Motion on where motion changed; Android emulator screenshots at font scale 1.0 and 2.0 and at a
  320 dp-wide display where layout changed. Record exact device, OS, build SHA.
- Automated checks: SwiftUI/Compose source review for labels, traits/roles, state descriptions, and
  JVM/XCTest tests for any copy/state mapper H adds.
- **Not covered without hardware:** VoiceOver rotor/traversal on a physical iPhone, TalkBack and Switch
  Access on a physical Android device, shield rendering by Screen Time, and every enforcement claim.
  These remain a human pass before release.
