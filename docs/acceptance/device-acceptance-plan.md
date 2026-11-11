# v1 physical-device acceptance plan

**No device results are recorded by this document.** Start every result as `NOT RUN`.
This is the single runnable order for both platforms; it consolidates
[lane B's merged iOS details](../verification/ios-device-acceptance.md) and
[lane C's Android details](../verification/android-device-acceptance.md).
Both platform checklists are merged source requirements, not passing runs. This
2026-10-30 refresh preserves every NOT RUN result; D13 supersedes older Android §7
wall-clock-following expectations. The 2026-11-11 refresh adds D22–D35 for the feature and
fix PRs opened that day. Each starts `NOT RUN` and is `BLOCKED` until its linked PR merges,
except D29, which tests behaviour already on main. Once a PR merges, run its row at the
listed priority on a build that contains the merge.
Use [ADR-012](../architecture/protection-contract.md) for required semantics and the
[ledger](v1-capability-ledger.md) for current implementation gaps. Mark an unavailable
feature `BLOCKED`, with its issue/PR, rather than executing an obsolete substitute.

## Hard pre-release decisions

- **R-12:** iOS Open credits are enabled (#189), source-complete and runtime
  DEVICE-PENDING. D08 must cover signed-device PC-28..38 with Flint killed through
  300 s expiry, midnight, callback re-registration and lock contention. Failure requires
  the one-line `FlintOpenCredit.shieldAcceptanceEnabled` revert plus fallback shield copy.
- **R-26:** new empty Allow Lists are refused; legacy saved empty lists keep running with
  attention (#218). D01 must explicitly check Phone, default calling app, in-call UI and
  SOS under `.all(except:)` and all-category shields. Move to option B if shielding occurs.
- **R-08:** #226 default-off captured removal consent/derived runtime is merged. Observe
  pending off, two-owner overlap, upgrade release and R08-08 corrupt/installing-owner
  retention past end with positive committed-pass release; no OS effect is proven by assignment.
- **Android:** force-stop unbinds Accessibility. #173/#229 refuse Timer arming without a live
  detector; test setup re-grants do not prove automatic recovery. Include overlay mount
  failure and truthful Degraded health in D10/D12. #249 source-closes mount-failure health
  (`block-surface-mount-failed` / `block-surface-fallback-only`, Armed withheld); D10/D12 must
  still check a silently dropped `BlockActivity` fallback and real `BadTokenException` timing.
- **Time/reset:** #225 trusted Android saved-zone days and retained usage are source-closed;
  D13/D14 still require exact-device clock/reboot/measurement/migration evidence.
  #248 adds dedicated `time-limit-measurement-unavailable` copy ("Time limits can't measure
  app use right now") and keeps primary protection Active on the glance; verify it in D19.
- **iOS migration (#251):** R-07 anchorless records re-anchor at the retained start; R-20 reads a
  v0.1.0 install that still holds session shield keys with no session as *unavailable* (shield
  kept, recovery via global recovery) rather than idle; R-04 restores an open-ended predecessor
  behind an unapplied replacement. D13/D14 must cover each on a signed upgrade.
- Open source findings in the [wind-down ledger](v1-capability-ledger.md#open-at-v1-wind-down)
  can block a case. Keep it BLOCKED until a fixing build is selected; no whole-case pass
  follows from a PC-tagged fixture.

**Open owner decisions from the 2026-11-11 PRs.** None is decided here. Each PR stays
unmerged until the owner reviews it; the named rows collect the device evidence.

- **Allow List session ([#255], D22):** the iOS allow-list writer does not exclude Flint. If D22
  shows Flint shielded, End early and the Emergency Pass can't be reached in Flint, so a fix
  must be chosen before release. Are unavailable ordinary breaks during an iOS Allow List
  session acceptable for v1? Should Shortcuts and the Focus filter get an Allow List option?
- **Android app-open PIN ([#256], D24):** re-locking drops a half-built rule or limit draft.
  Block-screen recovery, reachable without the PIN, still shows commitment names; should it be
  redacted whenever a PIN is set, at the cost of per-commitment choices? Is the lockout
  schedule right (4 free attempts, then 30 s rising to a 1 h cap; iOS has no throttle)? Should
  Flint re-lock on every `onStop`, including the return from a Settings hand-off?
- **One-time sessions ([#257], D30):** confirm that a finished one-time session stays listed as
  Finished instead of being removed, that a rule can't be converted between Repeats and One
  time, and the length difference (iOS 15 min to under 24 h; Android above 0 to under 24 h).
- **Android re-key ([#258], D23):** does a Google Play Console record already exist under
  `com.flint.peakfocus`? A Play package name can't change after the first upload. Accept that
  v0.1.0 installs can't update in place and that their local data doesn't carry over.
- **Pomodoro ([#259], [#261], D31–D32):** a bare iOS Start Pomodoro shortcut runs one round
  unless Rounds is set; should it offer "use saved cadence"? Accept that iOS Safari
  restrictions can stay on through a planned rest (fails closed)?
- **iOS legal screens ([#260], D35):** accept that edits to the shared `docs/privacy-policy.md`
  must stay inside the iOS Markdown subset. No hosted policy URL is linked yet.
- **Red-team fixes ([#262]–[#266], D25–D29):** #262 declined a reviewer Medium. After an
  interrupted bootstrap, a first Open rule saved before any ledger transaction still falls
  back to "unavailable until reset" (availability only); accept? The #266 record asks three
  questions. Should credits and ordinary breaks stay permitted when a period's earlier usage
  is unknown? Should a `U|CORRUPT` Time capture latch stay permanent? Should a process-wide
  block-surface mount failure mark every target's app-detail state degraded?
- **iOS break and pass history ([#267], D33):** is a separate pass-end record wanted? Should
  intents evicted before delivery surface as "some events may be missing"?
- **Android quick start ([#268], D34):** should other apps get an automation route that starts
  focus? Should a one-tap preset start replace "open the sheet, then Begin"? May opening Quick
  Settings ask the live detectors for a fresh check?

## Run once per exact build/device

Record the following before the first case. Repeat after any relevant build change;
do not carry a passing result between devices, detection paths or artifacts.

| Field | Result |
| --- | --- |
| Tester, date, evidence-directory link | |
| Git commit / integration checkpoint | |
| Version/build; release/debug; artifact SHA-256 | |
| iOS signed archive/install provenance; signing team/profile IDs without secrets | |
| Android APK signer certificate SHA-256 and install/update provenance | |
| Device model; OS/build/security patch; OEM skin | |
| Authorization/special access; notification permission; battery settings | |
| Default calling app, launcher, browser and versions | |
| Android applicationId and launcher component (`com.flint.peakfocus` before [#258], `app.flint.focus` after) | |
| Time zone, automatic date/time setting; prior install/migration source | |
| Backup/recovery plan for local test data | |

Minimum coverage: physical iPhone on minimum/current supported iOS, applicable iPad;
stock Android on current major plus a materially different OEM. Add the oldest Android
API actually claimed as supported. Record unavailable combinations explicitly.
Use only owned test apps/domains and synthetic session names; trim personal content,
opaque tokens, URLs, identity/account data and credentials from retained evidence.

**Safety:** never place a real emergency call. Check that emergency/calling screens open,
then cancel. Use only an owned non-emergency number if a call is needed. Keep OS recovery
and another communication route available. Do not disable the only working recovery path.

For every row below, fill `result`, `observed / latency`, `evidence`, and `failure / owner`.
Allowed result values are `NOT RUN`, `PASS`, `FAIL`, `BLOCKED`, `NOT APPLICABLE` with reason.
Expectation text is not a recorded observation. Repeat Android enforcement cases on
Path A (Accessibility connected) and Path B (Accessibility off, Usage Access + overlay on).

## P0 — safety, strict commitments and recovery first

Stop the campaign on a safety failure. Restore OS access, retain evidence and assign an
owner before continuing. These rows take priority over presentation or store screenshots.

| Case / PC IDs | Runnable action and required observation | Result | Observed / latency | Evidence | Failure / owner |
| --- | --- | --- | --- | --- | --- |
| D01 Safety floor; PC-48–52 | With an active app block, Allow List, exhausted Time Limit and Open Limit in turn, open Home/app switcher, Phone/in-call, lock-screen Emergency, system UI, permission recovery, uninstaller (Android) and Flint recovery. No shield/bounce or credit/pass charge. Repeat with supported custom default calling app and Android custom launcher. Never dial emergency services. Immediate Allow List sessions: D22. | NOT RUN | | | |
| D02 Consent/revocation; PC-51, PC-61, PC-77 | Fresh install: decline then accept each optional/required grant through Flint. Revoke/re-enable through OS Settings during Hardcore. Android disclosure precedes every Accessibility-enable handoff; no Settings-text/uninstall interception. Health reports real loss/degradation and supported fallback. | NOT RUN | | | |
| D03 Finite Hardcore/new vs legacy; PC-04–17, PC-67–68, PC-79 | Start a finite new Hardcore Timer/session. Try weaker replacement, stop, edit, off/delete and automation/Focus-off; no unauthorized reduction. Inactive templates remain editable; queued changes apply before adjacent recurrence. Separately update a running legacy Timer/cause and verify its originally promised exits remain. | NOT RUN | | | |
| D04 Harder friction; PC-07, PC-17–21 | Request successive weakening operations on the same occurrence: 60/300/900/900 s waits, explicit final confirmation. Cancel/re-request, restart host/service and try a background stop; no auto-release, count reset or lost wait. | NOT RUN | | | |
| D05 Pass global scope; PC-39–47, PC-69, PC-83 | Preview and explicitly confirm pass with transient session, recurring/Sleep and Time/Open rules active. Transients end early; all personal rules suspend 60 min, finite ends/counters remain, selected disable changes persist. Show actual next eligibility seven days after use, with no calendar-week bonus; reject duplicate redemption. | NOT RUN | | | |
| D06 Pass crash/write failure; PC-43–45, PC-59–60, PC-65 | In an owner-approved fault build, fail transaction before release, then kill after durable commit/before apply. Failure never authorizes release; same-boot restart resumes only the original remainder with one spend. Reboot closes active recovery while retaining consumption/uncertain cooldown. No production data corruption required. | NOT RUN | | | |
| D07 Ordinary break/overlap; PC-22–26 | Grant 300 s to A while A/B share a cause; B stays blocked. Add independent Hardcore to A; grant cannot bypass it. Keep A foreground with Flint closed through expiry: re-shield without app switch, record requested/observed boundary and latency on each Android path/iOS signed build. | NOT RUN | | | |
| D08 Open credits; PC-28–38, PC-75–76 | Confirm explicit credits one at a time to cap; shield between intervals, honor final credit, no charge on overlap/unprovable iOS category or safety target. Reentry/repeated callback/concurrent last-credit request does not re-spend/extend. Host-dead 300 s expiry re-shields; cross midnight, callback re-registration and lock contention, retaining exact grant/charge/deadline. Failed registration denies without charge. | NOT RUN | | | |
| D09 Optional iOS removal restriction; PC-46–47, PC-78 | Verify explicit device-wide opt-in and finite eligible armed owners; do not infer from assignment. End first of two owners, last owner, pass, failed arm and inactive template. Correct requested/unavailable status; revocation/recovery remains reachable. Android: confirm no removal/disable guard at every tier. | NOT RUN | | | |
| D22 Immediate Allow List session — **BLOCKED until [#255] merges**; PC-01, PC-04, PC-48–50, PC-53 | iOS ALS-01–09: start an Easy 30 min Allow List session from Start focus keeping Messages only (not Phone), then force-quit Flint. Open **Flint itself** from Home, the app switcher and Spotlight, then Phone, a call to an owned number and its in-call screen, the Emergency SOS countdown (cancel it), a supported third-party default calling app (iOS 18.2+) and Settings → Screen Time. None is shielded, and End early works in Flint. Repeat with Hardcore: no End early, and the Emergency Pass works in Flint. Messages opens; other apps, category picks and websites stay shielded; the break control reads "Breaks aren't available during an Allow List session."; a block↔allow replacement asks for confirmation (Easy), waits (Harder) or is refused (Hardcore) on every route. Android §14 on Path A and B: an Allow List Timer keeping one app. Stock and custom dialer, in-call, lock-screen Emergency, stock and custom Home, Settings/permission/uninstall UI, the notification shade and Flint are never shielded; a newly chosen default dialer or Home is exempt before any block. A shielded Flint or safety surface is a P0 stop. | NOT RUN | | | |
| D23 Android package re-key — **BLOCKED until [#258] merges**; PC-48, PC-50, PC-61, PC-77 | Android §17 on a device with no Flint installed: the build installs as `app.flint.focus`, and the launcher resolves `app.flint.focus/com.flint.peakfocus.MainActivity`. Accessibility, Usage Access, overlay and notification settings each list one Flint, and grants made through Flint show in health. During an active Path A and Path B shield, opening Flint from the launcher, Recents or its notification is never shielded. The widget provider registers and updates under the new package. Installing it next to v0.1.0 (`com.flint.peakfocus`) does not update that app: both stay installed, nothing is imported, Accessibility lists two Flint entries and the old app is an ordinary pickable app. Record that, then uninstall the old app. Once merged, run this row before the other Android rows; they use the new package. | NOT RUN | | | |
| D24 Android app-open PIN — **BLOCKED until [#256] merges**; PC-50–51, PC-54–55, PC-58 | Android §15: set a PIN in You → App lock. The gate shows on cold launch, Home then reopen, screen off/on, an app-switcher return and the return from a Settings hand-off. Rotation and font-size changes do not re-lock, and no tab or content is reachable behind the gate. After leaving Flint, its Recents card is blank (API 33+) or secure with screenshots blocked (API 23–32); record the live-tile limit. From the locked gate, Emergency Pass & recovery opens redacted recovery whose pass works without the PIN, and Open Flint's App info opens, during a lockout too. Four wrong PINs are free; the fifth waits 30 s, then 1, 2 and 5 min. Force-stop, reboot and wall-clock edits never shorten an active lockout or revive an expired one. TalkBack reaches only gate nodes; maximum font and display size keep every gate control reachable; rules keep enforcing while locked. | NOT RUN | | | |
| D25 iOS break re-shield with an unreadable session record — **BLOCKED until [#264] merges**; PC-22, PC-26, PC-65 | iOS N02-01–02. On a signed debug fixture build, in an Easy session on A+B, take a 5-minute break for A, force-quit Flint, remove the session projection and stay in A: A re-shields at the break deadline without opening Flint, B stays shielded and nothing is refunded. On a normal build, start the session with the Start Session shortcut and make an unrelated rule's queued change fall due during the break: A still re-shields at the deadline (record the delay). A Hardcore session started during a break shields A and B at once; Stop Session during a break ends the session and nothing is re-applied afterwards. | NOT RUN | | | |
| D26 iOS restored legacy predecessor — **BLOCKED until [#263] merges**; PC-05, PC-41, PC-44, PC-79 | iOS R04-01, owner-approved fault build only. On a prior signed build, start legacy session X on A+B and update in place. Start replacement Y on A only and crash right after acceptance, before the native apply. Relaunch after Y's planned end while X still runs: X is restored with A and B still shielded (no picker draft applied), the Emergency Pass preview is available and the pass ends only X, and ordinary stop follows X's legacy rights. Without such a build, record NOT APPLICABLE with the reason. | NOT RUN | | | |
| D27 Android stale Time evidence after a stricter edit — **BLOCKED until [#265] merges**; PC-15, PC-25, PC-32 | Android §19a on Path A and B. Put an Easy 30-minute daily Time Limit and an Easy Open Limit (cap 2) on one control app and use it for about 20 minutes. Tighten the Time Limit to 1 minute. Within seconds, before Flint re-measures, open the app and press Use one credit; separately request an ordinary break where one is offered. Both are refused with nothing charged and the Time Limit named; credits left are unchanged after the next measurement. | NOT RUN | | | |

## P1 — OS timing, persistence and coverage

| Case / PC IDs | Runnable action and required observation | Result | Observed / latency | Evidence | Failure / owner |
| --- | --- | --- | --- | --- | --- |
| D10 Arm/capacity; PC-01–03, PC-61–62, PC-73 | Deny permission and inject registration/capacity refusal on new/additive start. No false success or indefinite shield; old causes preserved, draft retained. Cold-launch directly to Today: every layer reconciles without visiting Rules; health shows actual check age/scope. | NOT RUN | | | |
| D11 Logical end with host dead; PC-63–66, PC-74–76 | Run 5/10/15-minute iOS sessions and Android finite session. Kill host, lock/resume, inject early/late/duplicate/stale callbacks in a test build. Natural end occurs once at logical end; record delay, no old callback clears replacement, early stop is never relabelled completion. | NOT RUN | | | |
| D12 Stationary foreground/failover; PC-22, PC-26, PC-61, PC-77 | Leave target foreground untouched >60 s; cross schedule start, positive measured budget and expiry. Revoke/restore Accessibility; exactly one effective shield, truthful detector status. Capture first visible window and settled text separately, plus query/tick latency when available. | NOT RUN | | | |
| D13 Clock/reset/reboot; PC-35, PC-39–40, PC-53–60 | Same-boot forward/back wall edit must not change finite elapsed remainder or mint quota/pass. Device zone change does not move active instants/saved quota zone. Cross trusted midnight/DST once; reboot restriction uses bounded fallback, active releases close, spent state survives and uncertainty/delayed reset is shown. Do not reuse the older Android checklist's wall-clock-following expectation. | NOT RUN | | | |
| D14 Persistence/migration/failure; PC-10, PC-37–38, PC-65, PC-69–71, PC-80 | Update prior signed build with rules/counts/history/PIN. Verify raw archive/idempotent migration; newer/corrupt/unavailable state shows repair, no default pass/quota or lost draft. Test exact-revision race/write failure and group edit without mutating active frozen selection. | NOT RUN | | | |
| D15 Reboot/overnight/Doze; PC-54–60, PC-63, PC-77 | Reboot and unlock during schedule/Timer, leave overnight ≥8 h on default OEM battery settings, repeat with documented exemption, and exercise Doze. Record actual recovery gaps and no first-unlock promise; no force-stop survival guarantee. | NOT RUN | | | |
| D16 Websites/Safari; PC-24–26, PC-32–33, PC-72 | On supported browser/version, test typed URL, link, redirect/subdomain/same-tab, private mode, unavailable address bar, app-rule overlap and unsupported browser. iOS: cause-owned Safari settings across overlap/end/pass/relaunch and Private Browsing/history effect. Path B is app-only; no invented browser-wide guarantee. | NOT RUN | | | |
| D17 Automation/Sleep/future/Pomodoro; PC-07–09, PC-14, PC-27, PC-79–82 | Test supported Focus/Siri/Shortcut routes for ownership/errors and Sleep overnight/morning boundaries. Full Pomodoro chains run in D31/D32 and future-date starts in D30, each BLOCKED until its PR merges. No “first round worked” substitute for a complete chain. | NOT RUN | | | |
| D28 iOS first Open quota after initialization — **BLOCKED until [#262] merges**; PC-28, PC-36–37, PC-70 | iOS N03-01–03. On a fresh install, the first Open Limit (cap 2) shows "2 of 2 credits left today" and a credit spends. Update in place from a prior signed build that used sessions and schedules but never an Open Limit, then add the first Open Limit: its first credit is available at once, not unavailable until the reset. A prior build that spent a credit and then deleted its Open Limit keeps that count after the update; no fresh pool. The interrupted-bootstrap path needs a fault build. | NOT RUN | | | |
| D29 Red-team device-pending paths on main (record in [#266]); PC-22, PC-54–60, PC-77 | Not blocked: the behaviour is on main; only the dated record is in an unmerged docs PR. iOS RT-01–02: an anchorless legacy schedule, updated while running and then taken through a device zone change with Flint closed, starts and ends once at its saved-zone instants (R-07). The first launch after updating with a session that is just ending never shows idle while the shield is applied, and lifts it once (R-20). Android §19b–d: an overlay hidden after a successful attach by an owned test app that hides overlays (Android 12+), a Path B fallback dropped by background-launch limits, real `BadTokenException` timing, and the app-detail state after a reported mount failure (R-30). Real Usage Access accounting across zone changes east, west and to a fixed offset, a backward edit and a reboot, and an unexhausted limit after Usage Access is revoked (R-18b). | NOT RUN | | | |
| D30 Date-specific one-time sessions — **BLOCKED until [#257] merges**; PC-11, PC-13–14, PC-54–58, PC-81 | iOS ONE-01–07 and Android §16. Author one-time sessions 7 and 30+ days ahead, reboot once and don't reopen Flint: each shields at its planned start and lifts at its end (record the delay; far-future non-repeating DeviceActivity delivery is the unproven iOS part). DST: with the device zone set to America/New_York while authoring, author windows across the 2027-03-14 gap and the 2027-11-07 fold. The editor shows each resolved length and refuses 24 h or longer (and, on iOS, under 15 min); on each date record the actual instants. A same-boot wall-clock change forward never starts one early, and back never extends one; a device zone change moves only the display; a phone powered off across the whole window shows Finished and never replays a week later. Pending and finished edits are free; an active Hardcore one-time session defers shortening or moving. | NOT RUN | | | |
| D31 iOS Pomodoro chain — **BLOCKED until [#259] merges**; PC-27, PC-41, PC-63–64, PC-74, PC-76, PC-84 | iOS POMO-01–09 with Flint force-quit and target A in the chain's apps. Run 5/2×3 (focus/rest × rounds) with A untouched in front on an unlocked phone. Run 5/5×3 and 5/15×2 on a locked phone, unlocking 30 s after each rest end and opening A at once. Record each rest start and focus resume. A rest never releases early. Focus resuming only at rest start + 15 min (the padded carrier) is a FAIL. Another rule stays enforced during rests; the tier covers the whole chain; there is no skip or extend; the pass ends every remaining round; a reboot only moves the chain forward; history credits each round once and excludes rest time. | NOT RUN | | | |
| D32 Android Pomodoro chain — **BLOCKED until [#261] merges**; PC-22, PC-27, PC-41, PC-58, PC-63, PC-84 | Android §18 on Path A and B, plan 5/2×3 with the target untouched in front: it unblocks at each rest start and re-blocks at each rest end without an app switch (record the latency). Repeat with `dumpsys deviceidle force-idle` across a rest start and across a rest end, then on the OEM device with default battery settings and with battery optimization off. The glance shows "Rest · focus resumes HH:MM" or "Pomodoro phase needs a new check", never a stale phase. Process kill, reboot and clock edits never skip or lengthen a round; rights as in D31; completion shows "Pomodoro · k of N rounds". | NOT RUN | | | |

## P2 — outcomes, glance, accessibility and artifact delivery

| Case / PC IDs | Runnable action and required observation | Result | Observed / latency | Evidence | Failure / owner |
| --- | --- | --- | --- | --- | --- |
| D18 History/Insights; PC-63–66, PC-84 | Complete/early-end/replace with host killed, cross day boundary and restart. One confirmed outcome; missing terminal stays Unconfirmed without completion/streak credit, uncertain break/pass/rest/coverage gaps reduce a conservative bound, absent vs zero and truthful counters; clear while a session runs and verify intended retention. Real Screen Time report delivery stays distinct from local focus events. | NOT RUN | | | |
| D19 Notifications/glance; PC-61, PC-73, PC-84 | Deny/revoke/allow notifications and opt in/out Live Activity. Add each widget/lock-screen family, check current/stale/unavailable/idle, own cause end/next time, history clear and cold/host-dead updates. No authority-changing action. Android detector handoff has no duplicate optional status; mandatory FGS remains. Record lock-screen privacy and planned-time wording. | NOT RUN | | | |
| D20 Accessibility/recovery; PC-17, PC-46, PC-50 | VoiceOver/TalkBack/Switch Access through onboarding, PIN/recovery, picker, editors, break/pass and completion; large text/display, compact screen, keyboard and reduced motion. Equivalent explicit confirmation works without a hold gesture; recovery is reachable without private content. | NOT RUN | | | |
| D21 Legal/offline/update | Airplane mode: inspect available policy/licenses and real source inventory; iOS bundled screens run in D35 (BLOCKED until [#260] merges). Install prior signed release then update intended final artifact without data loss. Verify artifact hash/signer/source, privacy manifests/notices and release fixture/network exclusions. | NOT RUN | | | |
| D33 iOS break and pass history — **BLOCKED until [#267] merges**; PC-22, PC-44, PC-59, PC-63, PC-84 | iOS BHIST-01–05. In an Easy 30-minute session, take a 5-minute break, force-quit Flint, let the monitor re-shield and the session end, then open Flint: Today, Complete and Insights count 25 minutes and show the break once. Resume now after 2 minutes subtracts 2. An Emergency Pass confirmed just before force-quitting Flint shows one pass use and one early end after two relaunches, with no completion or streak credit. A reboot during a break leaves its end time-uncertain and invents no completion. | NOT RUN | | | |
| D34 Android Quick Settings tile and launcher shortcuts — **BLOCKED until [#268] merges**; PC-01, PC-04, PC-50, PC-61, PC-73 | Android §20. Add the Start focus tile. When idle it reads Not active or Status unconfirmed. A tap opens Flint's Start focus sheet (on the lock screen, after unlocking first), and nothing starts before Begin. With a Timer running, the tile shows its name and "Until HH:MM", and a tap never replaces, ends or edits it. Long-pressing the launcher icon offers Start focus and up to three groups; a group pre-fills only its installed apps; a pinned shortcut for a deleted group says "That group is no longer saved." Starting the entry activity with `adb shell am start` is refused. With [#256]'s PIN set, the tile and shortcuts wait behind the gate. TalkBack reads the tile's full description; repeat on the OEM System UI. | NOT RUN | | | |
| D35 iOS offline legal screens — **BLOCKED until [#260] merges**; D21 (no PC ID) | iOS LEG-01–04 in Airplane mode. You → Legal → Privacy policy and Open-source licenses open offline and scroll to their last heading (Shippori Mincho in the licenses). Links appear as "label (url)" text and never open Safari. VoiceOver moves by headings and reads each list item and license paragraph as one element. At AX5 nothing truncates or overlaps and the last row clears the tab bar. Spot-check the policy against `docs/privacy-policy.md` at the tested commit. | NOT RUN | | | |

## Recording and release decision

Retain sanitized run record, one filled result per row/path, screenshots plus accessibility
trees for meaningful transitions, actual elapsed deadlines/latencies and failure diagnostics
under `docs/verification/<platform>-device-<model>-<date>/`. Use approved device skills and
ownership cleanup if an agent assists; no other device is implicitly disposable.
Link the exact evidence into the capability ledger before upgrading a status. A failed,
blocked or unrun required row stays visible. Only an actual physical-device run may earn
`DEVICE-VERIFIED`; fixtures, emulator runs, CI and a signed archive alone cannot.

Human release owners then review the failures/unsupported combinations, authorize the
supported scope and follow the [single store submission checklist](../store/README.md).
This plan performs no signing-account, upload, purchase, review or publication action.

[#255]: https://github.com/Prakashmaheshwaran/flint-app/pull/255
[#256]: https://github.com/Prakashmaheshwaran/flint-app/pull/256
[#257]: https://github.com/Prakashmaheshwaran/flint-app/pull/257
[#258]: https://github.com/Prakashmaheshwaran/flint-app/pull/258
[#259]: https://github.com/Prakashmaheshwaran/flint-app/pull/259
[#260]: https://github.com/Prakashmaheshwaran/flint-app/pull/260
[#261]: https://github.com/Prakashmaheshwaran/flint-app/pull/261
[#262]: https://github.com/Prakashmaheshwaran/flint-app/pull/262
[#263]: https://github.com/Prakashmaheshwaran/flint-app/pull/263
[#264]: https://github.com/Prakashmaheshwaran/flint-app/pull/264
[#265]: https://github.com/Prakashmaheshwaran/flint-app/pull/265
[#266]: https://github.com/Prakashmaheshwaran/flint-app/pull/266
[#267]: https://github.com/Prakashmaheshwaran/flint-app/pull/267
[#268]: https://github.com/Prakashmaheshwaran/flint-app/pull/268
