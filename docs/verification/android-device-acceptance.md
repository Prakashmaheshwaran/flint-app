# Android physical-device acceptance checklist

> **Run order and shared results:** use the [consolidated device acceptance plan](../acceptance/device-acceptance-plan.md)
> first. This platform detail was drafted in PR #108 before all ADR-012 runtime work landed;
> its unchecked expectations are not results. The consolidated plan's D13 / ADR-012 §9
> supersede the older §7 wall-clock-following/reset wording below. No device run is claimed.

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

## 14. Evidence

Store per-run evidence under `docs/verification/android-device-<device>-<date>/`: the run record
table, a pass/fail/not-run line per checklist row, screenshots or screen recordings for every
failure and for 3a, 4a, 5a, 6a, latency numbers, and trimmed `adb logcat` (no personal data, no
account identifiers). Link the run from the acceptance ledger.
