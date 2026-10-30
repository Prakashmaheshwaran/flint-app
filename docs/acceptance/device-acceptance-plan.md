# v1 physical-device acceptance plan

**No device results are recorded by this document.** Start every result as `NOT RUN`.
This is the single runnable order for both platforms; it consolidates
[lane B's merged iOS details](../verification/ios-device-acceptance.md) and
[lane C's Android details](../verification/android-device-acceptance.md).
Both platform checklists are merged source requirements, not passing runs. This
2026-10-30 refresh preserves every NOT RUN result; D13 supersedes older Android §7
wall-clock-following expectations.
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
  failure and truthful Degraded health in D10/D12; the R-30 remainder is still open.
- **Time/reset:** #225 trusted Android saved-zone days and retained usage are source-closed;
  D13/D14 still require exact-device clock/reboot/measurement/migration evidence. Dedicated
  `time-limit-measurement-unavailable` UI/glance copy is absent; generic degraded is current.
- Open source findings in the [wind-down ledger](v1-capability-ledger.md#open-at-v1-wind-down)
  can block a case. Keep it BLOCKED until a fixing build is selected; no whole-case pass
  follows from a PC-tagged fixture.

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
| D01 Safety floor; PC-48–52 | With an active app block, Allow List, exhausted Time Limit and Open Limit in turn, open Home/app switcher, Phone/in-call, lock-screen Emergency, system UI, permission recovery, uninstaller (Android) and Flint recovery. No shield/bounce or credit/pass charge. Repeat with supported custom default calling app and Android custom launcher. Never dial emergency services. | NOT RUN | | | |
| D02 Consent/revocation; PC-51, PC-61, PC-77 | Fresh install: decline then accept each optional/required grant through Flint. Revoke/re-enable through OS Settings during Hardcore. Android disclosure precedes every Accessibility-enable handoff; no Settings-text/uninstall interception. Health reports real loss/degradation and supported fallback. | NOT RUN | | | |
| D03 Finite Hardcore/new vs legacy; PC-04–17, PC-67–68, PC-79 | Start a finite new Hardcore Timer/session. Try weaker replacement, stop, edit, off/delete and automation/Focus-off; no unauthorized reduction. Inactive templates remain editable; queued changes apply before adjacent recurrence. Separately update a running legacy Timer/cause and verify its originally promised exits remain. | NOT RUN | | | |
| D04 Harder friction; PC-07, PC-17–21 | Request successive weakening operations on the same occurrence: 60/300/900/900 s waits, explicit final confirmation. Cancel/re-request, restart host/service and try a background stop; no auto-release, count reset or lost wait. | NOT RUN | | | |
| D05 Pass global scope; PC-39–47, PC-69, PC-83 | Preview and explicitly confirm pass with transient session, recurring/Sleep and Time/Open rules active. Transients end early; all personal rules suspend 60 min, finite ends/counters remain, selected disable changes persist. Show actual next eligibility seven days after use, with no calendar-week bonus; reject duplicate redemption. | NOT RUN | | | |
| D06 Pass crash/write failure; PC-43–45, PC-59–60, PC-65 | In an owner-approved fault build, fail transaction before release, then kill after durable commit/before apply. Failure never authorizes release; same-boot restart resumes only the original remainder with one spend. Reboot closes active recovery while retaining consumption/uncertain cooldown. No production data corruption required. | NOT RUN | | | |
| D07 Ordinary break/overlap; PC-22–26 | Grant 300 s to A while A/B share a cause; B stays blocked. Add independent Hardcore to A; grant cannot bypass it. Keep A foreground with Flint closed through expiry: re-shield without app switch, record requested/observed boundary and latency on each Android path/iOS signed build. | NOT RUN | | | |
| D08 Open credits; PC-28–38, PC-75–76 | Confirm explicit credits one at a time to cap; shield between intervals, honor final credit, no charge on overlap/unprovable iOS category or safety target. Reentry/repeated callback/concurrent last-credit request does not re-spend/extend. Host-dead 300 s expiry re-shields; cross midnight, callback re-registration and lock contention, retaining exact grant/charge/deadline. Failed registration denies without charge. | NOT RUN | | | |
| D09 Optional iOS removal restriction; PC-46–47, PC-78 | Verify explicit device-wide opt-in and finite eligible armed owners; do not infer from assignment. End first of two owners, last owner, pass, failed arm and inactive template. Correct requested/unavailable status; revocation/recovery remains reachable. Android: confirm no removal/disable guard at every tier. | NOT RUN | | | |

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
| D17 Automation/Sleep/future/Pomodoro; PC-07–09, PC-14, PC-27, PC-79–82 | Test supported Focus/Siri/Shortcut routes for ownership/errors and Sleep overnight/morning boundaries. Full Pomodoro and future-date starts execute only when implemented; otherwise BLOCKED. No “first round worked” substitute for a complete chain. | NOT RUN | | | |

## P2 — outcomes, glance, accessibility and artifact delivery

| Case / PC IDs | Runnable action and required observation | Result | Observed / latency | Evidence | Failure / owner |
| --- | --- | --- | --- | --- | --- |
| D18 History/Insights; PC-63–66, PC-84 | Complete/early-end/replace with host killed, cross day boundary and restart. One confirmed outcome; missing terminal stays Unconfirmed without completion/streak credit, uncertain break/pass/rest/coverage gaps reduce a conservative bound, absent vs zero and truthful counters; clear while a session runs and verify intended retention. Real Screen Time report delivery stays distinct from local focus events. | NOT RUN | | | |
| D19 Notifications/glance; PC-61, PC-73, PC-84 | Deny/revoke/allow notifications and opt in/out Live Activity. Add each widget/lock-screen family, check current/stale/unavailable/idle, own cause end/next time, history clear and cold/host-dead updates. No authority-changing action. Android detector handoff has no duplicate optional status; mandatory FGS remains. Record lock-screen privacy and planned-time wording. | NOT RUN | | | |
| D20 Accessibility/recovery; PC-17, PC-46, PC-50 | VoiceOver/TalkBack/Switch Access through onboarding, PIN/recovery, picker, editors, break/pass and completion; large text/display, compact screen, keyboard and reduced motion. Equivalent explicit confirmation works without a hold gesture; recovery is reachable without private content. | NOT RUN | | | |
| D21 Legal/offline/update | Airplane mode: inspect available policy/licenses and real source inventory; missing iOS bundled screens remain BLOCKED. Install prior signed release then update intended final artifact without data loss. Verify artifact hash/signer/source, privacy manifests/notices and release fixture/network exclusions. | NOT RUN | | | |

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
