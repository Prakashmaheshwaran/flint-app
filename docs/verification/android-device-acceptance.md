# Android physical-device acceptance checklist

> **Run order and shared results:** use the [consolidated device acceptance plan](../acceptance/device-acceptance-plan.md)
> first. This platform detail was drafted in PR #108 before all ADR-012 runtime work landed;
> its unchecked expectations are not results. The consolidated plan's D13 / ADR-012 §9
> supersede the older §7 wall-clock-following/reset wording below. No device run is claimed.
> Sections 14–20 (2026-11-11) cover PRs opened that day. Every item is `NOT RUN`, and a section
> for an unmerged PR is `BLOCKED` until that PR merges (§19b–19d test behaviour already on
> main). The package is `com.flint.peakfocus` until [#258] merges and `app.flint.focus` after
> it; use the installed one in every `adb` command, including 5b.

Human-run acceptance for Flint's Android enforcement on **real hardware**, against the protection
contract `docs/architecture/protection-contract.md` (ADR-012); `PC-xx` references are its §14
acceptance cases. Emulator runs
(`docs/verification/android-*`) and JVM/Robolectric tests do not satisfy these rows: a row is
`DEVICE-VERIFIED` only when a person ran it on the listed hardware build and recorded the
evidence below. Leave a row `NOT RUN` rather than inferring it from another device or path.

> **Phone safety rule:** never place a real emergency call to test anything here. Verify that
> emergency surfaces are *reachable* (dialer opens, the lock-screen "Emergency call" screen
> appears, emergency information is viewable) and stop there. Use a non-emergency number you own
> for any call-placement check, or skip the step.

## 1. Run record (fill once per device run)

| Field | Value |
| --- | --- |
| Tester / date | |
| Device model, OEM skin + version | |
| Android version, API level, security patch | |
| Flint build | variant (release / debug), version name/code |
| APK SHA-256 | `shasum -a 256 app-release.apk` |
| Git commit | |
| Install path | fresh install / update from previous build (which) |
| Battery optimization for Flint | optimized / unrestricted (record OEM setting names) |
| Default dialer / launcher / browser | package names |

Minimum matrix for a v1 store submission: **one stock-Android device on the current major
release** (e.g. Pixel) **and one materially different OEM** (Samsung One UI, Xiaomi HyperOS,
OPPO/OnePlus ColorOS or similar). Add the oldest supported API you intend to claim if it differs
by more than three major releases from the stock device. Record unsupported combinations
explicitly rather than omitting them.

## 2. Setup and consent

- [ ] Fresh install; first launch shows the Accessibility disclosure **before** any Settings
      deep link; declining keeps Flint usable with the Usage Access path.
- [ ] Grant Accessibility, Usage Access, "Display over other apps" and notifications through
      Flint's own prompts; each returns to Flint and Flint's health view shows the grant.
- [ ] Re-enable flow: turn Accessibility off in Settings, reopen Flint — health shows it off and
      the recovery path re-shows the disclosure.

## 3. Safety floor (audit R2; contract §7; PC-48, PC-49, PC-50, PC-52) — both detectors

Run 3a–3d once with Accessibility **on** (Path A) and once with it **off** but Usage Access +
overlay granted (Path B).

- [ ] 3a. Create an allow-list ("brick phone") rule active now that allows one unrelated app.
      Home button, the default dialer, Settings → Apps / Accessibility / Special app access,
      the permission controller (e.g. Settings → Apps → Flint → Permissions) and Flint itself
      all open without a shield. Lock-screen "Emergency call" opens; do not dial. The system
      uninstaller (Settings → Apps → any app → Uninstall) opens and can be cancelled.
- [ ] 3b. Same with a Hardcore block-list rule, a daily Time Limit already exhausted and an Open
      Limit already exhausted, each configured on an ordinary control app — the control app is
      shielded, the surfaces in 3a are not.
- [ ] 3c. Install a third-party dialer and make it the default dialer; install a third-party
      launcher and make it default. Repeat 3a–3b: neither is ever shielded, and neither appears
      in Flint's app pickers.
- [ ] 3d. Incoming call while a shield is up: the in-call screen is usable; ending the call
      returns to the shield, not to an unblocked app.

## 4. Continuous evaluation (audit R1; PC-62) — both detectors

- [ ] 4a. Open an ordinary app, leave it in front without touching it; a schedule that starts
      two minutes later shields it within the recorded latency (note seconds).
- [ ] 4b. Same with a 1-minute daily Time Limit on that app: it is shielded once the budget runs
      out, with no tap or app switch.
- [ ] 4c. A shield whose schedule ends drops on its own; the app is usable without a tap.
- [ ] 4d. Rule edits made while another app is in front take effect without an app switch
      (only when the protection contract allows that edit).

## 5. Detector failover and process death (audit R1/R6; PC-61, PC-77)

- [ ] 5a. With a blocked app in front, turn Accessibility off in Settings: within a few seconds
      Path B shields the app again without opening Flint. Turn it back on: exactly one shield.
- [ ] 5b. `adb shell am kill com.flint.peakfocus` (process death, not force-stop) while a break is
      active: after recovery the remaining break time is preserved and no extra credit is spent.
- [ ] 5c. Force-stop Flint from Settings: record what happens (Android disables the services
      until the next launch — this is expected and must be documented, not hidden).

## 6. Reboot, Doze and overnight (audit R6; contract §9)

- [ ] 6a. Reboot during an active schedule: after unlock, enforcement resumes without opening
      Flint (record seconds after unlock). Note: services cannot run before first unlock.
- [ ] 6b. Reboot during an active finite Timer: countdown resumes with the original end time.
- [ ] 6c. Overnight Sleep rule with the screen off ≥ 8 h, Flint left on the OEM default battery
      setting: at wake the Sleep allow-list is enforced and Morning Assist ends on time. Repeat
      once with battery optimization disabled; record any OEM "app sleeping"/auto-start prompts.
- [ ] 6d. `adb shell dumpsys deviceidle force-idle`, then wake: enforcement resumes.

## 7. Clock and time-zone changes (audit R6; contract §9)

- [ ] 7a. Move the clock forward past a schedule end; then back. Shields follow the new wall
      clock; spent Open Limits / passes are **not** re-granted by moving the clock back.
- [ ] 7b. Change time zone across midnight: daily counters roll forward only.
- [ ] 7c. If a DST transition is reachable (or simulate via time-zone change), schedules that
      span it behave as documented.

## 8. Permission loss and storage (contract §8; PC-37, PC-61)

- [ ] 8a. Revoke Usage Access, then "Display over other apps", then notifications, one at a time:
      Flint's health view reports each loss honestly; no screen claims protection it lacks.
- [ ] 8b. Low storage (< 100 MB free): saving a rule either succeeds or shows a retry; a failed
      break grant keeps the shield and shows an error instead of unblocking.

## 9. Breaks, passes and protection tiers (contract §2, §4–§6)

- [ ] 9a. Breaks are 300 s and scoped to the shielded app; Easy grants after confirmation,
      Harder waits 60 s, then 300 s, then 900 s for successive weakening operations on the same
      occurrence; Hardcore denies ordinary breaks. Overlapping Easy + Hardcore on one app: the
      Hardcore rule wins. At expiry the app re-shields with no app switch (record latency).
- [ ] 9b. Open Limit: the shield offers "Use one credit · 5 minutes"; the app stays shielded
      between credits; the last credit is usable; leaving and re-entering during a credit does
      not spend again; no credit is charged while another rule still blocks the app.
- [ ] 9c. Emergency Pass: one per rolling 7 days after use (not a Monday refill); confirming it
      starts 60 minutes of global recovery (sessions end early, schedules/limits suspend and
      resume afterwards, counters are not refunded); the next-eligibility time is shown; a
      process kill during recovery resumes the same recovery without spending twice.
- [ ] 9d. Edit/off/delete per the §2 matrix: Easy allows with confirmation, Harder waits, active
      Hardcore defers weakening to the occurrence end ("Save for after this occurrence"); a
      newly created Hardcore Timer cannot use hold-to-end; inactive rules are fully editable.

## 10. No uninstall or disable guard (contract §10, PC-51)

- [ ] 10a. While a Hardcore rule is active, Settings → Apps → Flint (app info, Force stop,
      Uninstall), Settings → Accessibility → Flint (turn off), and permission revocation all open
      and work normally — no shield, overlay or forced Home at any tier.
- [ ] 10b. Turning Accessibility off is allowed; Flint then reports reduced protection honestly
      and Path B (if granted) continues app blocking.

## 11. Websites (audit R7)


For each browser in the published supported-browser matrix (and at least one unsupported one):

- [ ] 11a. Typed URL, link tap, redirect, subdomain, and same-tab navigation to a blocked domain
      are shielded; an allowed domain is not.
- [ ] 11b. Private/incognito tab behavior matches the published matrix.
- [ ] 11c. Unsupported browser or unreadable address bar: Flint reports degraded web coverage.

## 12. Assistive technology (contract §2 accessible confirmations)

- [ ] 12a. TalkBack: the block screen is announced with its reason; "Go back", break and pass
      actions are reachable and correctly labeled; focus does not escape to the blocked app.
- [ ] 12b. TalkBack through onboarding, rule editor, limit editor and Timer: every control has a
      label and the save/retry states are announced.
- [ ] 12c. Switch Access (one switch, auto-scan): reach "Go back" on a shield and save a rule.
- [ ] 12d. Font size max + display size max: block screen and editors remain usable.

## 13. Update path and data preservation (contract §12)

- [ ] 13a. Install the previous public build, create rules/limits/Sleep, update in place: all
      data present and enforced; no duplicate or dropped records.

## 14. Immediate Allow List Timer (PR #255; plan D22; PC-01, PC-04, PC-48–50, PC-53) — both detectors

**Status:** every item `NOT RUN`; BLOCKED until [#255] merges. Never dial emergency services.
Start from Timer → Start focus, choose **Allow list**, pick one ordinary app under "What stays
available", then Begin. Run 14a–14e on Path A and again on Path B.

- [ ] 14a. Easy 30-minute Allow List Timer keeping one app. The running card reads "Allow list ·
      1 app allowed". The kept app opens; every other launchable app is shielded.
- [ ] 14b. During the Timer, open the stock dialer, lock-screen "Emergency call" (don't dial), a
      call to a number you own and its in-call screen, Home, Settings → Apps / Accessibility /
      Special app access, the permission controller, the system uninstaller (cancel it), the
      notification shade and Quick Settings, and Flint (including Emergency Pass & recovery).
      None is shielded and nothing loops to Home. Repeat with a third-party default dialer and
      launcher (3c).
- [ ] 14c. While the Timer runs, change the default dialer, then the default Home app. Each new
      default opens unshielded straight away (PC-49).
- [ ] 14d. With nothing picked, Begin stays disabled and reads "Choose at least one app to
      allow." If Flint reports "Calling or Home safety could not be checked…", no allow-list
      Timer starts (PC-53); record when that message appears.
- [ ] 14e. Exits: an Easy end asks for confirmation, Harder waits, and Hardcore offers no end;
      the Emergency Pass ends a Hardcore Allow List Timer. `adb shell am kill <package>` during
      the Timer: it resumes with the same end and the same kept app.
- [ ] 14f. TalkBack reads the Block list / Allow list control with its selected state, the
      picker title and the running card.

## 15. App-open PIN (PR #256; plan D24; PC-50, PC-51, PC-54, PC-55, PC-58)

**Status:** every item `NOT RUN`; BLOCKED until [#256] merges. Set the PIN in You → App lock →
Set PIN (4–6 digits, twice). Record the API level: Recents protection differs between API 23–32
and 33+. Use a test install; 15g erases Flint's data.

- [ ] 15a. Lock on reopen. With a PIN set: (i) swipe Flint away and relaunch it; (ii) go Home and
      reopen it; (iii) turn the screen off and on with Flint in front; (iv) open the app switcher
      and return; (v) start a Settings hand-off from Flint and come back. Each shows the "Enter
      PIN" gate, and no tab, sheet, dialog or menu is reachable behind it. (vi) Rotating or
      changing the font size while unlocked does not re-lock. Record whether a half-built rule
      draft survives a re-lock (owner question).
- [ ] 15b. Recents. Unlock, go Home and open Recents: Flint's card is blank (API 33+) or secure
      (API 23–32, where a screenshot inside Flint is also blocked). Opening Recents straight from
      an unlocked Flint shows Android's live tile of the current screen (documented limit);
      record it. After leaving, the card is blank and Flint reopens at the gate.
- [ ] 15c. Recovery while locked. Precondition: an Emergency Pass is available; this item
      consumes it. With a Hardcore Timer running, tap "Emergency Pass & recovery" on the gate.
      The panel shows no rule or session names. "Open Emergency Pass & recovery" opens recovery
      labelled "Active session" / "Rule commitment" with "Commitment names are hidden because
      recovery was opened from Flint's PIN screen."; the pass works without the PIN. "Open
      Flint's App info" opens the OS page with Uninstall, Clear storage and Permissions. Repeat
      during an active lockout (15d). Block screens and Flint's notification stay usable without
      the PIN.
- [ ] 15d. Lockout. Enter four wrong PINs: no wait. The fifth shows "Too many attempts. Try again
      in 0:30."; keep failing to reach 1, 2 and 5 minutes. Force-stop and relaunch: the lockout
      continues and is not shorter. During a 2-minute lockout, reboot and unlock: it restarts in
      full and is never shorter. During a lockout, turn automatic time off and move the clock
      forward 1 h, then back 1 h: the lockout is never shortened. Restore automatic time. Let a
      lockout expire, then reboot: no lockout comes back. Record the wait after a correct PIN
      followed by a wrong one.
- [ ] 15e. TalkBack at the gate: swiping reaches only gate controls, never Today, Rules or
      history content. Wrong-PIN and lockout messages are announced, and the recovery panel is
      readable. Maximum font and display size: the gate scrolls and every control stays
      reachable.
- [ ] 15f. Change PIN and Remove PIN ask for the current PIN. Enter it in Change PIN, go Home and
      return: it asks again. After Remove PIN, the next launch shows no gate.
- [ ] 15g. Forgot PIN explains that Clear storage or uninstall is the only route and deletes all
      Flint data. On the test install, Clear storage: the PIN and all rules and history are gone.
- [ ] 15h. With the gate showing, let a rule's start boundary pass: its apps shield on time; the
      PIN never delays enforcement.

## 16. Date-specific one-time rules (PR #257; plan D30; PC-11, PC-13, PC-14, PC-54–58, PC-81)

**Status:** every item `NOT RUN`; BLOCKED until [#257] merges. Create one with Rules → new rule →
**One time**, then a date, a start and an end; the rule saves the device zone. Android accepts
any length above 0 and under 24 hours. Record the saved zone, the planned instants in UTC and
the observed shield times.

- [ ] 16a. Far future. Author a Hardcore one-time rule 7 days ahead and another 30 or more days
      ahead, each blocking a control app. Reboot once in between, don't reopen Flint, and keep the
      OEM default battery setting. At the start, with the control app untouched in front, it
      shields without an app switch (record the latency); at the end it unblocks. The list shows
      "One time · <date> · <window>" with a pending, then active, then Finished pill, and the
      switch is hidden once finished.
- [ ] 16b. DST. Turn automatic time zone off, set America/New_York, author these, then restore
      your zone: (i) 2027-03-14 01:30–03:30 lasts 1 h; (ii) 2027-03-14 02:30–04:00 moves the
      missing start forward to 03:30 EDT, 30 min; (iii) 2027-03-14 02:30–03:40 resolves to 10 min
      and is accepted (no OS floor on Android); (iv) 2027-11-07 01:30–02:30 takes the earlier
      01:30 EDT, 2 h; (v) 2027-11-07 00:30–01:30 takes the later 01:30 EST, 2 h; (vi) 2027-11-07
      00:30–00:30 would last 25 h and is refused with "A one-time session must end within 24
      hours of its start." Record the length shown, and on each date the actual instants.
- [ ] 16c. Clock and zone, same boot, automatic time off. A pending rule starting in 20 minutes:
      move the clock forward 1 h; it doesn't start; restore automatic time; it starts at the real
      start. An active rule: move the clock back 1 h; it still ends at its original end. A pending
      rule: move the device zone 3 h east; the list shows new-zone times and it starts and ends at
      the original instants.
- [ ] 16d. Power off before a one-time rule starts and on after it ends: no shield, the rule shows
      Finished, history has no invented start, and nothing happens on the same weekday a week
      later.
- [ ] 16e. Edit rights. Pending Hardcore: change its date and times or delete it freely. Active
      Hardcore: shortening it or moving its date is saved for after this occurrence; a later end
      applies now. Finished: delete it or move it to a new future date. A start more than a
      minute in the past is refused with "Choose a start time in the future."

## 17. Package re-key to `app.flint.focus` (PR #258; plan D23; PC-48, PC-50, PC-61, PC-77)

**Status:** every item `NOT RUN`; BLOCKED until [#258] merges. Run this section first once #258
merges: every other section then uses the new package.

- [ ] 17a. On a device with no Flint installed, install the build under test.
      `adb shell pm list packages | grep -i flint` lists only `package:app.flint.focus`.
      `adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER app.flint.focus`
      ends with `app.flint.focus/com.flint.peakfocus.MainActivity`. Record the signer
      certificate SHA-256 (`apksigner verify --print-certs`).
- [ ] 17b. Grant Accessibility through Flint's disclosure. Settings → Accessibility lists one
      Flint, and `adb shell settings get secure enabled_accessibility_services` contains
      `app.flint.focus/com.flint.peakfocus.blocking.accessibility.FlintAccessibilityService`.
      Usage Access, "Display over other apps" and notification settings list Flint, and Flint's
      health shows each grant.
- [ ] 17c. With a block-list Timer shielding a control app, open Flint from the launcher, Recents
      and its notification: Flint is never shielded and nothing loops to Home. Repeat on Path B.
- [ ] 17d. Add the Flint widget from the launcher's widget picker. It renders and updates for
      idle, active and ended Timers; `adb shell dumpsys appwidget` lists the provider
      `app.flint.focus/com.flint.peakfocus.glance.FlintGlanceWidgetReceiver`.
- [ ] 17e. Not an update over v0.1.0. On a test device, install `flint-v0.1.0-android-debug.apk`
      (`com.flint.peakfocus`) and save one rule; then install the build under test. Both stay
      installed; the new app opens at onboarding with no rules or history; the old app keeps its
      rule. Settings → Accessibility lists two Flint entries, and the old Flint appears in the
      new app's picker as an ordinary app. Record this, uninstall the old app, and check the new
      app is unaffected.
- [ ] 17f. Update in place within the new identity: install an earlier `app.flint.focus` build
      signed with the same certificate, save rules, then update. All data remains. If no earlier
      `app.flint.focus` build exists, record NOT APPLICABLE.

## 18. Repeated Pomodoro chain (PR #261; plan D32; PC-22, PC-27, PC-41, PC-58, PC-63, PC-84) — both detectors

**Status:** every item `NOT RUN`; BLOCKED until [#261] merges. Start from Timer → Start focus →
**Repeated rounds**, Focus 5, Rest 2, Rounds 3 (19 min), blocking one control app, unless an
item says otherwise. Times are from Begin.

- [ ] 18a. Screen on, control app untouched in front from Begin: it unblocks at 5:00 and 12:00,
      re-blocks at 7:00 and 14:00 and unblocks for good at 19:00, each without an app switch;
      record each latency. The shield reads "Focus round 1 of 3 · rest at <time>"; the running
      card shows "Focus · round k of 3 · <left> left" or "Rest · focus resumes in <left>"; the
      glance shows "Rest · focus resumes <time>" during a rest, never "Protection active".
- [ ] 18b. Doze. Start the plan, turn the screen off and, before 5:00, run
      `adb shell dumpsys deviceidle force-idle`. At 6:00 run `adb shell dumpsys deviceidle unforce`, wake the phone
      and open the control app: it opens. Keep it in front through 7:00: it re-blocks (record the
      latency). In a new run, stay idle across 7:00, wake at 7:30 and open the control app: it is
      blocked at once. After each wake, the widget and notification show the current phase or
      "Pomodoro phase needs a new check", never a past phase.
- [ ] 18c. Repeat 18a and 18b on the OEM device with its default battery setting (record the
      OEM setting names), then with battery optimization off for Flint.
- [ ] 18d. Rights. During focus and during a rest: an Easy end asks for confirmation, Harder
      waits and Hardcore offers no end; there is no skip, extend or edit. A second Timer is
      refused during a rest. A separate rule blocking another app stays enforced during rests.
      The Emergency Pass ends the chain and every remaining round (consumes the pass).
- [ ] 18e. `adb shell am kill <package>` during a rest: the phases continue on time. Reboot during
      round 2 and unlock: the chain resumes forward only and never restarts a round; time is
      marked uncertain.
- [ ] 18f. Automatic time off. During a focus round, move the clock forward 1 h: the rest doesn't
      start early. Move it back: the round isn't lengthened. Restore automatic time.
- [ ] 18g. History. The completion reads "Pomodoro · 3 of 3 rounds" and focus time excludes rests
      (15 min). After a chain run with Flint's process dead throughout, history lists its rests
      as inferred and credits rounds only from an observed rest or the completion.

## 19. Red-team fix and device-pending paths (PR #265 and the #266 record; plan D27, D29)

**Status:** every item `NOT RUN`. 19a is BLOCKED until [#265] merges. 19b–19d test behaviour
already on main; their dated record is in unmerged docs PR [#266]. Use only owned test apps.

- [ ] 19a. Stale Time evidence (R2p2-N01; PC-15, PC-25, PC-32), Path A and Path B. Add an Easy
      30-minute daily Time Limit and an Easy Open Limit (cap 2) on one control app, and use the
      app for about 20 minutes (record Flint's used time). Tighten the Time Limit to 1 minute.
      Within a few seconds, before Flint re-measures, open the app and press "Use one credit";
      separately, request an ordinary break where one is offered. Both are refused, nothing is
      charged and the Time Limit is named; credits left are unchanged after the next measurement.
- [ ] 19b. Block-surface failures (R-30; PC-61, PC-77). On Android 12+, block an owned test app
      that hides overlay windows over itself, open it and record whether a shield is visible and
      what health reports. On Path B, record whether a background-launch limit drops the
      `BlockActivity` fallback, and capture real `BadTokenException` timing in logcat. After a
      reported mount failure, open the target's app detail and record whether it still says
      blocking now (owner question). Also record whether a target left in front after a failed
      Path A mount stays unshielded until the next boundary.
- [ ] 19c. Real Usage Access accounting (R-18b; PC-54–56, PC-58). Set a 10-minute Time Limit and
      use 5 minutes. Change the device zone east, then west, then to a fixed offset; move the
      clock back; reboot. Used time never resets or refills, and an exhausted limit stays
      exhausted until the trusted reset. Record Flint's minutes against Digital Wellbeing.
- [ ] 19d. Revoke Usage Access while a limit isn't yet exhausted, keep using the app, and record
      whether use is still measured. An exhausted day stays enforced.

## 20. Quick Settings tile and launcher shortcuts (PR #268; plan D34; PC-01, PC-04, PC-50, PC-61, PC-73)

**Status:** every item `NOT RUN`; BLOCKED until [#268] merges.

- [ ] 20a. Add the **Start focus** tile from Quick Settings edit. When idle it reads "Not active",
      or "Status unconfirmed" without a fresh check. Tapping it opens Flint's Start focus sheet;
      nothing starts before Begin. With no live detector, the sheet shows "Not armed. Turn on
      Accessibility or grant Usage Access." and Begin stays disabled.
- [ ] 20b. With a Timer running, the tile shows its name and "Until <time>". A tap opens Flint
      on the running Timer and never replaces, ends or changes it, Hardcore included (PC-04).
      Record the tile after the end passes ("Planned end reached" or "Status unconfirmed"),
      while degraded ("Check protection") and during pass recovery ("Recovery until <time>").
- [ ] 20c. On the lock screen, tapping the tile asks to unlock first, then opens the sheet.
- [ ] 20d. Long-press the Flint launcher icon: it offers "Start focus" and up to three saved
      groups ("Focus: <group>"). A group fills the sheet with that group's installed apps only,
      with a note naming the group; duration and break level are unchanged. Pin a group
      shortcut, delete the group in Flint, then tap the pin: the plain sheet opens with "That
      group is no longer saved."
- [ ] 20e. `adb shell am start -n <package>/com.flint.peakfocus.quickstart.QuickStartActivity` is
      refused as not exported, and nothing opens.
- [ ] 20f. With [#256]'s PIN set (once it merges), the tile and the shortcuts show the PIN gate
      first. Unlocking within 2 minutes opens the sheet; after a longer wait, such as a lockout,
      Today opens without it.
- [ ] 20g. TalkBack reads the tile's full description, for example "Deep study, Hardcore.
      Protection active at last check, until 14:30. Opens Flint.", and the shortcut labels.
      Repeat 20a–20c on the OEM device's System UI.

## 21. Evidence

Store per-run evidence under `docs/verification/android-device-<device>-<date>/`: the run record
table, a pass/fail/not-run line per checklist row, screenshots or screen recordings for every
failure and for 3a, 4a, 5a, 6a, latency numbers, and trimmed `adb logcat` (no personal data, no
account identifiers). Link the run from the acceptance ledger.

[#255]: https://github.com/Prakashmaheshwaran/flint-app/pull/255
[#256]: https://github.com/Prakashmaheshwaran/flint-app/pull/256
[#257]: https://github.com/Prakashmaheshwaran/flint-app/pull/257
[#258]: https://github.com/Prakashmaheshwaran/flint-app/pull/258
[#261]: https://github.com/Prakashmaheshwaran/flint-app/pull/261
[#265]: https://github.com/Prakashmaheshwaran/flint-app/pull/265
[#266]: https://github.com/Prakashmaheshwaran/flint-app/pull/266
[#268]: https://github.com/Prakashmaheshwaran/flint-app/pull/268
