# ADR-012 — Protection and commitment contract

**Date:** 2026-10-28. **Status:** accepted in `integration/v1` by [PR #96](https://github.com/Prakashmaheshwaran/flint-app/pull/96).
**Owners:** lanes B/C enforce; F records history; G authors rules; H presents the same decisions.
**Scope:** personal, local protection on iOS and Android. No account, analytics, remote
authority, payment, device-owner mode or new release network capability.

Implementation and passing evidence are tracked separately in the
[v1 capability ledger](../acceptance/v1-capability-ledger.md). This accepted contract
does not assert that every requirement or PC case is already implemented or verified.

This supersedes ADR-011's *personal* temporary-access policy choices: separate
Standard/Increasing difficulty, unspecified live all-cause arbitration and period adapters,
and the dormant ledger's lack of a shipping break/pass contract. It retains ADR-011's
versioned storage, atomic consumption, explicit unavailable state, exact cause/revision
identity, monotonic duration foundation, and separate **dormant managed authority**.
It does not authorize managed enforcement. It also supersedes older tier, break, pass and
anti-removal descriptions wherever they conflict with this document.

**Evidence boundary.** All `path:line` source anchors below refer to the frozen
`integration/v1` baseline **`b20fb8a36be1383f589bc5147d54f4732e04cfc9`** ([tree](https://github.com/Prakashmaheshwaran/flint-app/tree/b20fb8a36be1383f589bc5147d54f4732e04cfc9)).
The cited preexisting enforcement/copy files, root/platform READMEs, changelog and ADR index
are unchanged from the audits' `d519ff6` to that baseline; the anchors were rechecked there.
The newer lane F history contract/emission API is incorporated explicitly in §11.
The 2026-10-27 iOS §§2/5/8 and Android §§5/8 audits motivate this decision; they are not
runtime proof of the proposed behavior. Unless explicitly labeled **baseline**, statements
below are requirements, not claims that the current apps already satisfy them.

## 1. Vocabulary, causes and commitment boundaries

A **rule** is a saved template. A **cause** is one materialized occurrence of a rule or
session, with immutable identity `(causeId, occurrenceId)`, a policy revision, owner/origin,
target snapshot, tier, start, finite commitment end where required, and time basis.
An **active commitment** has started and has not terminated; it stays active during an
ordinary break, a Pomodoro rest, or a limit's unspent budget. **Enforcing** means that this
cause currently denies access to a target. Thus a daily Time Limit can be active before it
is exhausted. **Inactive** means no current commitment, not merely “no shield visible.”
Future, disabled and naturally ended occurrences are inactive. An unavailable/failed-to-arm
rule is never presented as protecting the device.

| Cause | Activation and natural end | Tier / special meaning |
| --- | --- | --- |
| Manual / Timer session | Accepted start through selected elapsed duration. | Explicit Easy, Harder or Hardcore. |
| Block Now | Immediate session, with an explicit duration; Easy may be “until I stop.” | Harder/Hardcore require a finite duration; they are not permanent locks. |
| Pomodoro | One parent commitment containing a frozen sequence of focus and planned break rounds; ends after the last focus round. | Tier applies to the entire parent, including edit/stop during a planned break. Planned rests are disclosed at start, not user-requested exceptions. |
| Recurring schedule | One occurrence per selected local start date in the saved IANA zone, including the overnight tail; ends at its resolved end instant. | All tiers; the saved template is distinct from the active occurrence. |
| One-shot future schedule | One absolute start/end pair resolved from the displayed date, time and zone at save. Never repeats. | All tiers; editable freely before its start. |
| Sleep / Wind Down / Morning Assist | Materialized schedule occurrences with explicit targets and tier; a morning occurrence has its own end. | New Wind Down defaults Easy, Full Assist defaults Harder; Hardcore is an explicit choice with the ordinary Hardcore disclosure. Preserve existing saved tiers during migration. |
| Time Limit | Enabled monitoring period (v1: calendar day in saved zone); blocks at the usage threshold until period end. | Own per-rule tier. A positive unused budget is normal access, not a break. Zero budget means immediately exhausted. |
| Open Limit | Enabled daily commitment; shields between accepted timed credits, including before its first credit. | Own tier governs editing and exceptions after exhaustion; a purchased-with-quota credit is ordinary planned access even at Hardcore. No money is involved. |
| Focus filter / Shortcut / Siri / automation / notification start | Creates an owned session/occurrence through the same arbiter, with an origin ID and idempotency key. | Entry route never changes tier rights. A Focus toggle is not authority over another session. |
| Uninstall / disable guard | Derived side effect of eligible, armed Hardcore causes, never an independent perpetual cause. | Exact platform scope is §10; the safety floor always precedes it. |

**Tier order:** Easy < Harder < Hardcore. Easy permits an explicitly confirmed early exit.
Harder requires persisted, cause-scoped friction for a weakening operation. Hardcore denies
ordinary early weakening, with natural expiry and an available Emergency Pass as product
exits. OS permission controls remain reachable (§7); no tier is an inescapable device lock.
All tiers and the pass are free.

New finite sessions and Pomodoro parents are at most **24 hours**. A recurring/daily
occurrence is at most **26 elapsed hours**, allowing a 25-hour DST day; reject a longer
window rather than create an unbounded Hardcore commitment. Adjacent occurrences have
distinct identities and a boundary at which queued edits/off/delete apply **before** the
next occurrence can activate. Never extend a deadline as a side effect of reconciliation.
Open-ended legacy protection follows §12; new open-ended Hardcore is invalid.

For new Focus filters, “while Focus is on” is Easy only. Harder/Hardcore filters require
an explicit finite duration: Focus-off requests an early stop under §2, not natural expiry.
A failed/background request reports a reason or a route to Flint, never silent success.
Sleep's display names never substitute for a tier, and editing a saved app group never
mutates an already-running cause's frozen target set.

**Baseline differences being resolved:** iOS singleton replacement and ungated Harder stop
are at `ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift:39-118`;
Focus-off bypasses that guard at `ios-app/Flint/Features/FocusFilter/FocusFilterController.swift:48-54`.
Android Timer's deliberately broader exit is evidenced at
`docs/verification/android-timer-exit-2026-10-11/README.md:26-36` (emulator scope at `:90-97`).
Sleep currently maps differently at `ios-app/Flint/Features/Sleep/SleepModeController.swift:45-73`
and `android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/SleepRules.kt:20-23,49-52`.

## 2. Action × tier × active/inactive decision matrix

The columns refer to the **affected cause's current tier**, never the requested lower tier.
Every entry point calls one policy authority at commit time. UI-disabled controls alone are
insufficient. A batch touches every affected cause atomically or makes no change.

Cell vocabulary includes the required UI outcome:

- **Allow:** preview scope/deadline, confirm, persist, then show the actual result. For
  edits, retain the draft on failure. Never optimistically display an off switch.
- **Wait:** show “Available in 1 / 5 / 15 minutes,” named scope and final action; §4's
  persisted wait must mature, then the user explicitly confirms. A background route returns
  `confirmation_required` and opens the same flow when possible; no automatic completion.
- **Defer:** “Protected until [end]. Save for after this occurrence” or cancel; show the
  queued change separately from the still-effective version. No immediate reduction.
- **Deny:** show the cause, tier, end and pass status, plus a safe route back. No mutation.
- **N/A:** no current action; explain “Not running” / “No access is blocked.”
- **OS:** OS-owned action remains available; show truthful consequences in Flint (§10).

| Action | Active Easy | Active Harder | Active Hardcore | Inactive Easy | Inactive Harder | Inactive Hardcore |
| --- | --- | --- | --- | --- | --- | --- |
| Start another cause, without retiring any existing one | Allow additive, subject to capacity | Same | Same; existing protection stays | Allow | Allow | Allow after finite-end disclosure |
| Replace running session with dominating protection (§3) | Allow exact preview | Allow exact preview | Allow exact preview; continuity required | N/A; start instead | Same | Same |
| Replace with weaker/incomparable protection | Allow explicit old-session exit | Wait on every weakened cause | Deny immediate; start independently or defer | N/A | N/A | N/A |
| Stop / end current occurrence early | Allow confirmation | Wait | Deny; pass or expiry | N/A | N/A | N/A |
| Ordinary temporary break/access | Allow 300-second scoped grant | Wait, then 300-second scoped grant | Deny; pass only | N/A | N/A | N/A |
| Accept an available Open credit | Allow under §5 | Same; no Harder wait | Same; quota is part of original commitment | N/A | N/A | N/A |
| Edit-strengthen, or cosmetic rename | Allow, validate/arm first | Same | Same; never silently raise the tier | Allow | Allow | Allow |
| Edit-weaken: shorten, remove blocked targets, add allowed apps, raise quota, lower tier, change window/zone | Allow | Wait | Defer current weakening; immutable active snapshot | Allow | Allow | Allow |
| Toggle off | Allow; current occurrence ends early | Wait | Defer off to current end | Allow | Allow | Allow |
| Delete | Confirm; end current occurrence and retain history | Wait + confirm | Defer deletion to current end; retain history | Confirm | Confirm | Confirm |
| Emergency Pass | Explicit confirmation under §6 | Same | Same | No spend for this rule; available only if some personal protection is active | Same | Same |
| Uninstall Flint | OS | OS | iOS: optional disclosed removal restriction; Android: OS (§10) | OS | OS | OS; no guard from inactive rules |
| Disable Accessibility / revoke Screen Time / open recovery Settings | OS | OS | OS; never shield permission recovery | OS | OS | OS |
| Change clock or device time zone | OS; reconcile §9 | Same | Same; no claim of veto | OS; future windows follow saved zone | Same | Same |

“Strengthen” requires a proof that no previously denied target/time becomes allowed and no
exit becomes easier: blocked set grows (allowed set shrinks), tier does not decrease, end
does not move earlier, and any budget does not increase. Mixed edits, changing block/allow
mode, moving a window or changing an opaque category selection are weakening unless that
proof is possible. A rename does not change occurrence identity or reset counters/waits.
Raising the tier requires explicit confirmation of the new rights, including via automation.

Inactive Hardcore templates remain fully editable. Active Hardcore templates may also save
**future-only** changes, including delete/off; the current occurrence retains its snapshot.
During a pass, “End/disable these commitments” may apply those changes immediately as part
of the pass-authorized scope; the UI states that rules otherwise resume (§6). Merely taking
an ordinary break never makes a commitment inactive. A Time Limit below quota is still
active for edit locking, preventing “raise the limit just before it is reached.”

**Android Timer decision:** retain exact-snapshot transactional confirmation and the
two-second hold (with equivalent accessible confirmation). For newly created Timers,
Harder additionally waits and Hardcore cannot use hold-to-end. This intentionally changes
the all-tier exit documented in the baseline: a single meaning for Hardcore across causes
and entry points outweighs imitation of another product. Already-running legacy Timers
retain their displayed rights until they finish (§12); never tighten a commitment retroactively.

## 3. Overlap arbitration and replacement

Order each evaluation: **safety floor → available, reconciled policy state → active pass →
cause-specific valid grants → remaining enforcing causes**. A target is blocked if **any**
remaining cause blocks it. Its displayed tier is the strongest such cause, with all other
contributors discoverable. Equal tiers use latest natural end for the headline, then stable
cause ID as a deterministic tie-break; the tie-break never discards contributors. “Until”
shows when the last known contributor ends; ongoing Easy protection says “until you stop.”

An app allow list exempts that app only from its own cause; it cannot exempt it from a
different block list. Domain rules and app rules compose the same way: releasing a domain
does not release its browser from an app-level cause. Safari restrictions, when enabled,
are a frozen child policy of each selected session cause, not a global mutable switch that
can clear another owner's restrictions. The adapter computes the most restrictive effective
combination; if an intersection cannot be represented, reject the new configuration before
arming and explain the limitation. Android domain protection is limited to supported
browser detection; the UsageStats fallback must not claim equivalent domain coverage.

A replacement **dominates** only if it preserves every old target's protection, at least
the old tier, throughout the old remaining interval (including any previously promised
focus rounds). Extending one subset while dropping another is not dominance. Arm/register
the new version first; commit new occurrence + old `superseded` outcome in one transaction;
then retire only the old version's enforcement resources. Registration/commit failure leaves
the old cause intact. Use immutable occurrence/revision registration names: a delayed old
callback cannot clear the new session. Equal/replayed request IDs return the existing result.

Additive starts can be accepted only when the platform can represent all causes within its
resource budget. Otherwise return `capacity_unavailable`, leaving existing protection intact.
Do not overwrite a singleton as a shortcut. Every UI, Shortcut/Siri, Focus filter, automation
and notification action uses the same revisions, arbitration and durable mutations. Focus
off stops only its owned cause after the tier checks; names such as “Focus Filter” do not
prove ownership. A stale action is rejected with a refreshed preview. Creating a stronger
cause during a weaker grant immediately blocks; grants never extend to future cause IDs.

**Baseline:** iOS Shortcuts reach the singleton directly at
`ios-app/Flint/Features/Shortcuts/FlintShortcutsRunner.swift:47-56,80-85`; schedule toggles/delete
are unconditional at `ios-app/Flint/Features/Schedules/SchedulesView.swift:21-24,183-191`.
Android's live coordinator selects the strongest cause then applies package exemptions at
`android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt:171-195`;
rule mutation has no tier gate at
`android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/DataStoreStores.kt:59-60,83-100`.

## 4. Breaks, friction and automatic re-shielding

**Shared constants:** ordinary break = **300 seconds**; one Open credit = **300 seconds**;
Harder waits = **60, 300, 900 seconds**, capped at 900 thereafter. These are product
constants with cross-platform fixture values, not UI-local timers.

An ordinary grant is scoped to an explicit target set **and** a frozen set of cause
occurrences/revisions. The session screen can request its full selected scope; a shield
requests one app/domain. If other causes block any requested target, preview all contributors
and request authorization from every one; if any is Hardcore, deny the ordinary grant.
An iOS opaque category is a whole-category scope unless individual membership can be proven;
never pretend to release one member while broadly releasing the category. No ordinary grant
is global. End each scope at the earlier of grant deadline and its cause's natural end;
the cause's clock keeps running. Breaks never add session time or pause usage accounting.

For each cause occurrence, `n` counts successfully committed Harder weakening operations
(break, stop, edit-weaken, off or delete). Required delay is `[60,300,900][min(n,2)]`.
A multi-cause operation waits the maximum required delay, then increments each affected
Harder cause's `n` exactly once upon commit. Easy grants do not consume this counter.
Pending waits store operation kind, exact revisions, target scope and elapsed deadline.
Cancellation spends nothing, clears the pending request and restarts the full wait on a
new request; it never resets `n`. A different operation or changed policy requires a new
wait/preview. Process death preserves the wait; its completion does not itself release
anything. A final confirmation is required. Counts reset only at a new natural occurrence
(for daily limits, a trusted daily boundary), not on a screen change, toggle or rename.

Renewing/stacking a live grant is forbidden: show its remaining time, with “Resume now.”
After expiry a fresh request follows the next wait. Hardcore permits no **ordinary** break;
precommitted Pomodoro rests and quota credits are part of its original schedule, and an
Emergency Pass is a distinct action. Planned Pomodoro rests suspend only the parent's own
focus restrictions; other rules remain. Pre-register/validate every required phase before
claiming a chain is armed. Skipping or lengthening a focus round is weakening.

**iOS deadline adapter.** A 15-minute minimum monitoring interval does **not** require
15-minute access. For a duration `N < 15 minutes`, register a non-repeating 15-minute
carrier starting at acceptance, with warning time `15 minutes - N`; the monitor's
`intervalWillEndWarning` reconciles at the logical N-minute deadline. For 5-minute access
the warning is 10 minutes; for a 10-minute session it is 5. Carry the logical deadline,
operation ID and revision in shared durable state, not in the callback name alone.
`intervalDidStart` and `intervalWillStartWarning` never end the grant. An early warning
must not act before the logical deadline; duplicate/late warning and carrier-end callbacks
reconcile idempotently. Carrier end is a recovery opportunity, **not** a successful
five-minute result if the warning was missed. Stopping the carrier must not manufacture a
natural completion. The ShieldAction extension must register through a tested shared
adapter before honoring a credit; absence of that capability means “Access unavailable,”
not an unbounded release or a request to keep Flint open.

This design follows Apple's documented [minimum interval](https://developer.apple.com/documentation/deviceactivity/deviceactivitycenter/monitoringerror/intervaltooshort)
and [warning-time callbacks](https://developer.apple.com/documentation/deviceactivity/deviceactivityschedule/warningtime).
It is a design inference, **not physical-device proof**. Apple also makes boundary callback
delivery dependent on device use ([schedule intervals](https://developer.apple.com/documentation/deviceactivity/deviceactivityschedule/nextinterval)).
Test killed-host, locked-device/resume, midnight, both short sessions and shield-originated
credits. Record requested deadline and actual re-shield latency. A missed deadline on an
actively used device fails the case; a locked device must reconcile before protected access
on resume. Do not advertise exact-second OS delivery. Failed registration denies the grant
without spending. No host-only timer, local notification or usage-duration threshold is a
substitute for an elapsed-time expiry.

Android must re-evaluate the foreground app at grant expiry without an app switch, using
the live enforcement service and a durable deadline that both detection paths read; process
recreation replays desired enforcement, not consumption. An alarm/timer firing is not proof
that a shield was shown. Both platforms require §14's direct boundary evidence.

**Baseline being changed:** iOS's 150-second view wait ends the session at
`ios-app/Flint/Features/Today/SessionView.swift:195-258`; Android's waits are already
60/300/900 at `android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/BreakPolicy.kt:45-65,83-85`,
but its live five-minute grants persist asynchronously at
`android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt:333-356,482-485,498-505`.

## 5. Open Limits: timed credits, never launch-count promises

An **accepted open** is an explicit “Use one credit · 5 minutes” confirmation on a shield
or the matching Flint detail screen, not a raw process launch or foreground event. The app
or domain stays shielded between grants, including while credits remain. Entering/leaving
the target during a live grant neither spends again nor restarts its deadline. A credit
grants elapsed access, not five minutes of measured usage. At expiry it re-shields without
opening Flint. The last credit is usable: if `used < cap`, accept, increment once, and honor
the grant even when the new remaining count is zero. Zero/nonpositive cap grants nothing.

v1 uses **one daily counter per target identity**, shared across covering Open rules. The
effective cap is the minimum of their caps, so duplicate rules cannot multiply credits.
One atomic acceptance increments the shared counter once, creates one bounded grant
covering all current Open causes for that target and records an idempotency key. Enforce
all other contributors first: if a session, schedule, exhausted Time Limit or unprovable
overlap would still block, **do not charge**. Do not charge for a failed registration,
cancelled wait, duplicate callback, stale action, safety-floor target or unavailable state.
Races serialize: with one credit left, one operation wins; a duplicate of that operation
returns its remaining interval, and a different request while it is active spends nothing.

Categories are **not Open-credit targets** on either platform. Pick individual apps/sites.
iOS cannot infer arbitrary membership from opaque category tokens: if any active non-Open
category restriction could cover the requested target and disjointness cannot be proven,
deny credit with “Another category rule may still block this app; no credit used.” This is
deliberately conservative even when the category might be unrelated. Android may prove
membership from its explicit app targets; it still never charges when access is blocked.
There are no hidden per-category/group charges. Legacy category selections are preserved
for editing but marked unsupported (§12); never turn them into unlimited grants.

Reset at the first trusted midnight boundary in the saved quota zone (§9), once per period;
no credit banking. Close live credit grants at reset and re-shield before accepting a new
period credit. A grant spanning midnight is clipped there (display the shorter interval).
Cap increases/removing limits are weakening; cap decreases do not revoke an already accepted
credit by themselves, but a newly added independent cause can still block. Counts survive
off/on, editing and delete/recreate in the same day for the same target; archived counter
identity is not the rule ID. An unverifiable rotated iOS token never silently receives a
fresh pool mid-period: require reselection and conservative carry-over (§12).

After exhaustion, Easy/Harder may request an ordinary scoped break under §4; it consumes
**no additional Open credit** and is displayed as an exception, not a fabricated open.
Hardcore permits only a pass or daily reset. Positive quota credits require no Harder wait.
Corrupt, unavailable or unsupported counter/grant state denies new credit without replacing
it with zero-used defaults. Any already verifiable safety-floor exemption still wins.

**Baseline:** iOS's shared minimum-cap counter is at
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenLimit.swift:322-354`; release lasts until
later re-arm at `:211-215`, and category overlap is undetectable at `:370-394`.
Malformed counters currently become fresh defaults at
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintGroupStore.swift:92-98`.
Android live foreground/open charging is at
`android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt:171-195,344-350`.

## 6. Emergency Pass: one rolling policy and global recovery

Both platforms offer **one free pass per rolling 604,800 seconds after committed use**.
There is no Monday refill and no accumulated bank. A verified first install starts with
one pass; missing/corrupt storage in an established installation does not imply a first
install. Show “Available” or the next eligibility estimate with date, time and zone, plus
clock uncertainty when applicable. Do not say “one left this week.”

Rolling time is easier to explain as “seven days after you use it,” avoids two passes
straddling Sunday/Monday, and uses the same elapsed-time integrity policy as other grants.
It removes a time-zone/week-key reset route; it does not make a local clock tamper-proof.
Same-boot clock changes cannot shorten the cooldown; reboot uncertainty is explicit (§9).

An explicitly confirmed pass starts **60 minutes of global personal-protection recovery**:

1. End every currently active transient manual/Timer/Block Now/automation/Focus/Pomodoro
   parent with `ended_early`, reason `emergency_pass`; cancel its future child rounds.
   A still-on Focus filter cannot recreate that occurrence until a new off→on cycle.
2. Suspend recurring, one-shot future, Sleep and Time/Open Limit enforcement for the recovery
   interval, including an occurrence that starts inside it. Do not delete templates, reset
   usage/counters or advance their natural ends. An occurrence that ends during recovery
   completes at its original end; it is not resumed later. After recovery only causes still
   naturally active resume. A one-shot future schedule is rule-backed, not a transient Timer.
3. Release all eligible removal guards and Safari child restrictions. Cancel pending waits
   and ordinary/credit grants without refunding already consumed counters. Future new manual
   starts during recovery require “Resume protection now” first; do not silently create an
   apparently protected session under a global exception.
4. Offer “End/disable these commitments” during recovery, with an exact list and confirmation.
   This pass-authorized mutation can stop any covered occurrence and disable/delete its
   template; limits' counters remain. Otherwise show which rules resume and when. “Resume
   now” truncates recovery without refunding the pass. A second redemption cannot extend it.

Spend only when at least one personal commitment is active and the full recovery scope is
known. Confirmation lists affected sessions/rules, the resume time and next pass time, with
a two-second hold or equivalent accessible explicit confirmation. No Shortcut, notification,
shield callback, retry or system event silently spends a pass. The action is reachable in
Flint independently of the currently displayed target; emergency calling never requires it.

**Consume then release:** in one durable transaction, validate eligibility/snapshot, consume
the pass, persist the recovery interval and session terminations, and enqueue lifecycle
events/enforcement work. Only after that commit may any protection be released. The OS and
database cannot share a transaction: use recoverable desired state/outbox semantics (§8).
A crash after commit resumes the same remaining recovery without spending twice; a failed
or ambiguous write does not authorize release. Read back the operation ID before retrying.
The iOS host and all extensions and both Android detection paths use this same authority.

**Baseline:** iOS rolling eligibility is at
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintEmergencyPass.swift:5-18`, and it ends only
the active Hardcore session at `ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift:138-152`.
Android Monday keys are at
`android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/EmergencyPassPolicy.kt:22-65`;
its package-only, asynchronous release is at
`android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt:376-384,482-485`.

## 7. Safety floor and target sanitization

The safety floor is a terminal **allow**, before rules, limits, counters, grants, guards or
error fallback. It is never counted as an open and cannot consume a credit/pass. A policy
failure never widens a block onto safety surfaces. Do not require a PIN, pass, completed
wait or active service to reach OS emergency/recovery functions.

| Surface | iOS requirement | Android requirement |
| --- | --- | --- |
| Emergency / calling | Preserve emergency SOS, lock-screen emergency calling, Phone/in-call UI and any supported user-selected default calling app. No private API token guessing. | Static telephony/emergency packages **plus** runtime-resolved default dialer, including a third-party dialer, in-call UI and emergency components. |
| Home / launcher | SpringBoard, Home gestures, app switcher and system navigation remain reachable. | Runtime-resolved default Home/launcher, resolver and system navigation; never bounce Home into a block loop. |
| Flint | Host app and recovery/pass controls cannot be policy targets; extensions must still operate. PIN may protect private app content, but a minimal recovery/pass entry must remain reachable without disclosing it. | Own package, including pass/health/consent/recovery screens, is never a target. |
| Permission / recovery Settings | Screen Time authorization/revocation, app settings, accessibility and system recovery pages remain reachable. | Settings and permission controllers, Accessibility/Usage Access/overlay/notification grants, battery/recovery and uninstall UI remain reachable; no Settings-content interception in release. |
| System UI | Lock screen, system permission prompts, Control Center/notification UI and essential system services are not shield targets. | System UI, keyguard, notification shade, permission dialogs and essential system services are exempt from overlays/Home redirects. |

Exclude known safety identities in pickers; disable selection with a short explanation.
Sanitize again when saving, importing, migrating, materializing a cause and evaluating every
detector/callback. Legacy selections retain their original record in the migration archive,
but effective targets subtract the floor; show what was excluded. Allow lists implicitly
include the floor even if the user selected nothing. Refresh Android default-role resolution
at startup, package/role changes and foreground reconciliation; a failed lookup cannot
justify blocking an unknown calling/Home surface. Until it resolves, decline new broad
allow-list protection and keep health degraded, rather than claiming safety by package list.

iOS tokens are opaque; do not claim that package-name filtering proves a safe category or
allow-list selection. Use OS-supported exclusions/selection restrictions where available;
where a supported OS/default-calling configuration cannot guarantee the floor, decline that
selection/mode and explain reduced coverage. A physical-device check must include supported
custom calling configurations; unsupported configurations are named, never silently treated
as protected. No automated test places a real emergency call.

**Baseline:** Android static floor and runtime roles are at
`android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/SystemSurfaces.kt:13-25`
and `android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/NeverBlockSurfaces.kt:19-30`.
The coordinator can subsequently evaluate Open Limits at
`android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt:171-195`,
so an earlier allow alone is insufficient. Picker filtering is at
`android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/InstalledApps.kt:37-48`.

## 8. Failure policy, transactions and startup reconciliation

Distinguish **saved**, **armed**, **currently enforcing**, **suspended**, **degraded** and
**unavailable**. “On” means the relevant registration/service prerequisites succeeded, not
merely that a Boolean was saved. An accepted OS registration is not physical proof that
every target is blocked. Health shows the time/result of an actual check, its scope and
the platform's coverage, not a new “Checked now” timestamp on an old report.

| Failure | Required result on both platforms |
| --- | --- |
| Permission missing or registration/capacity failure on start | Keep the draft and old causes; show “Not armed” with reason/retry. Emit `failed_to_arm`, not `started`. Roll back only this attempt's resources. Do not leave an indefinite immediate shield after its expiry registration failed. |
| Part of a multi-resource start cannot arm | No whole-cause success. Roll back that new cause; preserve unrelated owners. An independently useful subset needs a new explicit preview/acceptance, never silent partial protection. |
| Counter, grant, pass or policy storage loading/unavailable/corrupt/unknown version | No new release, credit, pass or weakening; retain verifiable last-good protection within its finite bounds. Show “Protection state unavailable” and recovery. Do not create empty/default available state. |
| Missing key in a successfully read, validated new-install/schema state | May initialize once with a durable initialization marker. Missing critical keys after initialization are corruption, not a new install. |
| Ambiguous/cancelled mutation | Query by operation ID and committed sequence. Never repeat consumption speculatively. Persisted success survives caller cancellation. |
| Commit succeeds, OS apply/release fails or process dies | Reconcile the same desired state idempotently, using remaining time; never consume twice. UI reports “Applying/recovery needed,” not successful access. No automatic refund without positive proof that no release occurred. |
| Last-good state cannot identify safe targets or an expiry | Do not invent blanket blocking. Preserve raw data for recovery; release derived removal guards, retain the safety floor and label coverage unavailable. |
| Permissions/service disappear during a cause | Keep its plan and finite deadline, mark the coverage gap/degradation; resume remaining protection if prerequisites return. Do not report uninterrupted success or extend the commitment. |

Implement a serialized policy transaction boundary: iOS requires a crash-safe **cross-process**
shared store/lock or transactional database, not independent UserDefaults read/write pairs;
Android requires one durable mutation domain for rules, quota, grants/pass and lifecycle
outbox (or a journal providing equivalent recoverability). Register any deadline resource
before granting access; the callback tolerates a not-yet-committed operation. Commit the
consumption + grant/termination + event intent before releasing OS restrictions. Desired
enforcement has stable ownership/revision, so reconciliation touches only Flint's resources.
No network authority is introduced. UI success waits for durable acceptance and the adapter's
reported result; it never promises pixels the adapter cannot observe.

Run a single reconciler on host startup/foreground, extension entry, Android service bind/
failover, boot/unlock, time change and permission change. Before new access is accepted:

1. Read validated versioned state and migration status; preserve unavailable distinctly.
2. Refresh authorization/live adapter health and the safety floor, including default roles.
3. Reconcile time, natural ends, phase transitions and pending future edits before starts.
4. Expire/recover grants and waits; restore already committed pass/credit state exactly once.
5. Derive all effective causes, Safari settings and guard ownership; reconcile registrations
   and shields/overlays; remove only positively identified stale Flint resources.
6. Drain the durable event outbox, publish per-cause health and schedule the next boundary.

A missing registration is a health fault, not authority to erase the rule. An unknown
callback cannot fall through to the primary session. Deferred Hardcore changes apply even
if the host never visits Rules. When extension storage is locked/unreadable, never mint a
grant; preserve only the last-good finite resources and reconcile at unlock. Settings and
emergency access remain available. Lane B/C must bound and surface their adapter's recovery
latency rather than treating permission bits as service liveness.

**Baseline:** swallowed session registration failure is at
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift:82-94`; tab-dependent
re-arm is recorded at `ios-app/Flint/FlintApp.swift:106-115`, and old-report timestamps at
`ios-app/Flint/Features/Settings/SettingsView.swift:106-114`.
Android empty-on-IO/default reads are at
`android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/DataStoreStores.kt:43-56,138-143,177-197`.
Its stronger transaction contract exists at
`android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/AccessStateStore.kt:7-29`;
using that contract in production remains implementation work, not established by this ADR.

## 9. Clock, time zone, reboot and real OS limits

Persist a time anchor containing boot identity, continuous monotonic elapsed time (including
sleep), wall time, zone and confidence. Store duration remaining/deadline and last reconciled
checkpoint with every relevant mutation. In the same boot, monotonic time governs finite
sessions, waits, grants and pass cooldown. Compare wall progression with elapsed progression
on every policy entry and system time notification; a discrepancy over **5 seconds** is a
clock discontinuity. Pure zone changes are separate from an elapsed-time discrepancy.

| Situation | Required reconciliation |
| --- | --- |
| Same-boot wall time forward/back | Keep elapsed remaining time unchanged for sessions, waits, access and cooldown. Recompute their displayed dates and OS registrations. Do not mint quota or a pass. Already materialized occurrence endpoints use the anchored timeline. |
| Device zone changes | Display new-zone equivalents, but active occurrence instants do not move. Recurring rules keep their saved IANA zone; an explicit zone edit is weakening under §2. Daily counters share a persisted installation `quotaZone` captured at first limit setup; v1 has no automatic travel reset or manual “reset day.” |
| Trusted normal midnight | Close old-period grants, record period end, apply deferred changes, then create one new limit occurrence and zero its daily counts. Midnight is evaluated in `quotaZone`, not the current device zone. Do not sum per-rule resets into extra target credits. |
| DST in a rule's saved zone | Materialize once per local start date. A missing local endpoint moves forward by the DST gap; an ambiguous start uses the earlier instant and ambiguous end the later instant. Reject nonpositive or >26-hour results. Overnight belongs to the start date. Store resolved UTC instants/offsets so duplicate callbacks cannot create a second occurrence. |
| Host/service process restart, same boot | Restore exact remaining intervals and pending waits from durable state. No new `started`, spend, or deadline extension. |
| Reboot / boot identity unavailable | No trusted cross-boot elapsed duration exists. For an existing finite restriction, use `min(savedRemaining, max(0, wallEnd - nowWall))` as the new bounded remaining duration; a past wall end expires it. Record `time_uncertain`. Do not restart completed Pomodoro rounds or replay missed occurrences. |
| Reboot with ordinary/credit/pass recovery access | Expire the active release interval conservatively; keep its consumed credit/pass and audit record. A process restart alone does not do this. Explain “Access ended after restart; no extra credit was spent.” This is an explicit reboot trade-off, not loss of the ledger. |
| Reboot with pending Harder wait | Cancel the pending confirmation without incrementing `n`; a new request starts a new wait. Never infer that an unobserved wait completed. |
| Reboot with pass cooldown or daily counter | Preserve spent state. Do not infer a refill from a new wall date. Resume the last checkpoint's remaining cooldown and remaining-to-reset on the new monotonic clock, crediting no unverified powered-off interval. At that bounded reset deadline, make one reset and select the next future midnight; never replay missed resets. Show the adjusted next eligibility/reset estimate. |
| Corrupt/future/unsupported time state | Treat affected grants/counters/pass as unavailable (§8), retain valid raw records for recovery and never manufacture a fresh pass. Unknown time cannot arm a new removal guard. |

The reboot policy can **delay** a daily refill (at most its saved remaining period, normally
≤26 hours of subsequently observed time) or pass renewal (at most its saved remaining
604,800 seconds of observed time). It never grants additional quota for a clock jump.
Frequent power-offs can make calendar eligibility later than seven wall-calendar days;
the UI must explain this rather than promise an exact date while time is uncertain. This
conservative local-only choice is flagged for owner review. A user never needs replenished
quota to reach emergency calling or OS permission recovery.

Neither platform can prove elapsed time across arbitrary power-off/clock edits without an
external trusted clock. In particular, the finite-restriction wall fallback can expire a
commitment early after an unmeasured forward edit across reboot. This is an honest limit;
do not invent a server, indefinitely extend the restriction, or claim anti-tamper security.
iOS cannot veto clock edits and may not run until an OS callback/foreground opportunity;
reconcile at the first opportunity and record the coverage gap. Android's receiver must
trigger both live detection paths and stationary-foreground evaluation; do not equate
receiving a broadcast with re-shielding. Clock discrepancy alone never blocks Settings.

**Baseline:** iOS uses wall time in
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintActiveSession.swift:31-39` and same-day
counter comparison at `ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenLimit.swift:31-54`.
Android's existing measured-jump preservation is at
`android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/ClockChangeGuard.kt:109-160`;
its readme acknowledges unmeasured reboot jumps at `android-app/README.md:127-132`.
Apple documents wall/zone and device-use callback constraints in
[DeviceActivitySchedule.nextInterval](https://developer.apple.com/documentation/deviceactivity/deviceactivityschedule/nextinterval);
the adapter must reconcile against the contract, not trust callback arrival as a clock.

## 10. Uninstall/disable guards, disclosure and exits

**Android release:** do not intercept uninstallers, Settings, Accessibility disable, app
info, force-stop, permission revocation or other recovery controls. The existing guard must
be removed from release enforcement, not merely hidden in UI or restricted to English text.
No replacement overlay, Home redirect or device-admin escalation is authorized. The same
safety floor applies to debug personal-protection flows. Explain that disabling required
access reduces/stops protection; allow it. A self-control confirmation inside Flint may
explain the active commitment, but it cannot obstruct the OS controls.

This is an intentional platform divergence from optional iOS removal denial. Google's
current [Permissions and APIs policy, Accessibility API](https://support.google.com/googleplay/android-developer/answer/16558241?hl=en)
prohibits using that API to prevent disabling/uninstalling apps or services outside the
parental-control/enterprise exceptions. Flint's personal self-control posture does not
claim either exception. This is a product design constraint, not a claim of Play approval.
Keep prominent disclosure accurate for foreground-app and supported-browser address access;
remove Settings text harvesting when its only purpose was the removed guard. All permission
entry points use the same affirmative consent, including repair/re-enable routes.

**iOS:** an optional, separately disclosed “Restrict app deletion during Hardcore” preference
may set `denyAppRemoval` only while at least one eligible **active, armed, finite Hardcore
commitment** owns it, including schedule/limit occurrences. The opt-in is captured in that
commitment's snapshot. No Easy/Harder, inactive template, failed registration, unknown time
or unavailable owner state arms it. Planned rests and allowed quota credits do not end the
commitment. Ordinary expiry, pass recovery and proven absence of eligible owners release
it; multiple owners compose with logical OR. Never clear another active owner's guard when
one cause ends. Prefer separately derived guard ownership to a singleton's incidental clear.

Before opt-in, disclose that Apple's setting restricts **app deletion device-wide**, not
only Flint, that Screen Time permission can still be revoked, the exact end, pass availability
and where recovery lives. Do not enable it retroactively for migrated templates. Show
“Requested / unavailable” accurately until the authorized adapter can apply it. Do not assert
that every iOS version honors it solely because the property was assigned; signed-device
verification is required. Apple's [authorization documentation/talk](https://developer.apple.com/videos/play/wwdc2022/110336/)
describes individual deauthorization through Settings; [Apple's framework engineer guidance](https://developer.apple.com/forums/thread/729637)
describes explicit removal denial while authorized. No restriction on revocation or
unrequested system Screen Time passcode setup follows from either.

**Always-available safe routes:** natural finite expiry is scheduled independently of the
host; an eligible pass releases all personal guards and provides recovery; Home, emergency
calling, Flint recovery and OS permission controls never require a pass. When a pass is spent
or unavailable, show that honestly alongside expiry and recovery. An expiry callback can be
late; preserve a reachable OS recovery route and reconcile at the next opportunity. Do not
create an open-ended guard to compensate. Flint is a self-control tool with tested limits,
not parental/enterprise administration or inescapable device control.

**Baseline:** iOS guard scope/setting is documented by
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintUninstallGuard.swift:16-28` and
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift:64-80,126-133`.
Android's broad uninstaller/Settings behavior is at
`android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/UninstallGuard.kt:104-110,129-154`;
its disable promise is at
`android-app/feature/feature-onboarding/src/main/kotlin/com/flint/peakfocus/feature/onboarding/ConsentCopy.kt:6-16`.

## 11. Local lifecycle events and lane F ownership

The **shared policy transaction owner**, not a view, emits durable event intents. The OS
adapter supplies observations to that owner. Lane F consumes an idempotent outbox into local
history; it does not infer successful protection from UI navigation or UsageStats totals.
Each enforcement event has stable event/operation ID, cause/occurrence/revision, parent ID
where relevant, origin route, reason, tier, effective time, observed wall time, time
confidence, emitter, and enforcement-health/coverage context. Target scopes stay in the
enforcement ledger: the focus-history projection contains **no app, package, bundle, token,
category or domain identifiers**, preserving the privacy boundary of the
[focus-history contract](focus-history.md). No URL browsing log, analytics, account or upload
is added. Rule events may be retained as local enforcement diagnostics but are not inserted
as focus sessions in lane F's user-facing log.

| Canonical event | Exact meaning | iOS emitter / Android emitter |
| --- | --- | --- |
| `started` | Occurrence starts after its arm prerequisites and durable acceptance; one per occurrence. A future rule saved/registered is not started until its start boundary. | FlintCore arbiter for immediate starts; monitor extension for scheduled activation, including host dead / shared arbiter invoked by start controller or live boundary evaluator. |
| `completed` | Natural commitment end reached; never a user exit, replacement or mere removal of a UI row. A limit occurrence may complete even if no quota was used. | Monitor warning/end reconciliation at the **logical** deadline, or host recovery / service/boundary/boot reconciler; first durable transition wins. |
| `ended_early(route)` | Explicit accepted early termination before natural end. `route` is the request origin; `reason` distinguishes stop, weaken-that-ends, toggle_off, delete, emergency_pass or legacy_exit. | Shared arbiter called by host/intent/shield-authorized pass / shared arbiter called by UI, automation or block surface. |
| `break_started` | An ordinary scoped release is durably accepted, or a precommitted Pomodoro rest begins. Include kind `ordinary` or `planned`, grant ID, exact scope and end; “Applying” is distinct from adapter acknowledgement. | FlintCore + monitor for planned transitions / shared arbiter + phase evaluator. |
| `break_ended` | The corresponding grant/rest closes at deadline, resume-now, supersession, pass or reboot. Include reason and logical end; re-shield outcome may still be degraded. | Monitor/host reconciler / either detector's shared deadline reconciler. |
| `pass_used` | Atomic pass consumption plus global recovery was committed. Exactly one event, listing the snapshot of affected commitments and recovery end. Not one event per app. | Shared cross-process authority, whoever commits first / shared durable transaction owner. |
| `superseded` (UI: “Replaced”) | A replacement atomically retires this occurrence in favor of named replacement ID while enforcing §3. It is not a natural completion. | Shared replacement arbiter / shared replacement arbiter. |
| `failed_to_arm` | A start/re-arm attempt could not establish all prerequisites. Contains attempt ID, error class, affected scope and whether old protection remains. No fabricated start/completion pair. | Controller/monitor reconciler / controller/service reconciler. |
| `access_started` / `access_ended` | Open-credit interval lifecycle, with credit operation ID and end reason. These are not ordinary breaks or raw app launches. | ShieldAction/shared arbiter; monitor ends / block surface/shared arbiter; service ends. |
| `protection_degraded` / `protection_restored` | A known coverage gap begins/ends while a commitment's plan remains active; permission loss is not successful completion. | Permission/registration reconciler / service/permission reconciler. |

`originRoute` is `app_ui`, `shortcut`, `siri`, `focus_filter`, `automation`, `notification`,
`shield_action`, `monitor`, `service`, `startup_reconciliation` or `migration`; a pass is
a **reason**, not a way to lose that origin. Natural expiry uses the observing component's
origin. Store `sourceObservedAt` separately from a logical deadline so delayed callbacks
do not fabricate an on-time observation. A recovered transition carries `reconciled=true`
(mapped to existing focus history's `inferred`); unknown historical protection duration
stays unknown.

Keep lane F's existing `ended_early.route` wire values readable. The projection maps in this
precedence order: pass → `emergency_pass`; delete → `deleted`; off → `disabled`; legacy
Timer hold → `hold_to_end`; completed Harder friction → `harder_wait`; Shortcut/Siri stop →
`shortcut_stop`; Focus-off → `focus_filter_off`; explicit cancel → `cancel`; other in-app
stop → `manual_stop`; otherwise → `unknown`. Preserve `originRoute`/reason separately in an
additive schema version. `superseded` is the single canonical replacement event, not both a
replacement and an early end. A global `pass_used` is one operation event referencing zero
or more sessions, not a separately consumed event for each session.

The iOS monitor must write terminal/phase events through the same cross-process transaction
while the host is dead; app launch must not be necessary to create history. In particular,
`intervalDidEnd` can also occur after stopping monitoring ([Apple nextInterval](https://developer.apple.com/documentation/deviceactivity/deviceactivityschedule/nextinterval)):
check cause state and logical end, not callback spelling. A stop or replacement must never
be relabeled `completed`. Outbox retries and host/extension races deduplicate by event ID
and transition sequence. A read-only shield configuration extension emits no lifecycle.
If history projection fails, retain the committed outbox; never undo a valid access decision
or invent a second event. If the authoritative transaction fails, do not perform the release.

This section refines the provisional lifecycle rules in `focus-history.md`: persisted alone
does not mean armed; a planned end does not prove uninterrupted protection; a 120-second
early callback allowance cannot authorize completion before the logical deadline. The
existing non-throwing sink API can remain the projection seam, but durable enforcement
event intent/outbox must precede it. Readers tolerate old duplicate records while new
writers emit one terminal transition. Lane F must extend its projection for global pass
events, both-platform breaks, coverage gaps and additive route metadata without rewriting
old wire values. A legacy record lacking coverage detail is labeled legacy/unknown, not
upgraded into proof by migration.

Focus totals use only evidenced focus intervals from manual/Timer/Block Now/Pomodoro focus
causes, subtract ordinary/planned/pass intervals and known unavailable coverage, and union
overlaps before summing. Schedule/limit completion is not a fabricated completed focus
session. At most one terminal outcome (`completed`, `ended_early`, `superseded`) per occurrence.
The Pomodoro parent completes once; child focus/rest phases reference it and cannot double
count its duration. Notifications respect OS permission and are not enforcement evidence.
A notification claiming completion follows a committed terminal outcome. A platform that
must pre-schedule a local alert may do so after an armed start, but its copy says only that
the planned focus time has ended; cancel it on early end/replacement. Do not infer completion
or uninterrupted protection from its delivery.

**Baseline monitor teardown:**
`ios-app/Extensions/DeviceActivityMonitorExtension/FlintMonitor.swift:40-55` clears the
session in the extension, so lane F cannot place the sole completion hook in host UI.

## 12. Versioned migration without user data loss

Use a new schema/contract version and a durable migration marker. Preserve original bytes
and identifiers in a local migration archive until validation succeeds; do not delete
malformed records, preferences, saved selections, PIN data or history to make decoding pass.
Migrate once in the same serialized authority used for protection. On crash, resume or
roll back to the intact old records; do not run two active writers against different schemas.
Unknown newer schemas are unavailable, never “empty.” Old build downgrade is unsupported
for new ledger writes; preserve the archive and show a recovery message rather than clobber it.

| Persisted material | iOS import | Android import | Conservative result |
| --- | --- | --- | --- |
| Rules / saved groups / targets | `flint.rules`, `flint.limits`, `flint.openLimit.rules`, `flint.appGroups`, selection and stored session templates. | `block_rules_v1`, `app_groups_v1`, `time_limits_v1`, `open_limits_v1`; both ten- and eleven-field rule encodings. | Preserve IDs/names/targets/enabled/tier; add revision, contractVersion, zone and occurrence identity. Sanitize only effective safety targets, retaining original selection. Do not manufacture one-shot schedules from recurring rules. |
| Existing finite sessions | Preserve active ID, start, deadline, tier, selection, monitor ownership and already-promised exit behavior. | Preserve native `session-timer-` and older `session-` distinction and exact snapshot. | Current occurrence gets `legacy_v1` rights until end. Keep Android Timer all-tier transactional hold exit and old Easy-only Home cancellation for those records. New starts/replacements use this contract; a replacement cannot inherit the legacy exception. |
| Existing active recurring/Sleep/limit occurrence | Snapshot existing tier and applicable old editing rights through this occurrence. | Same; existing Sleep Easy/Harder mappings preserved. | Do not silently strengthen a running commitment. Explain the new rights for subsequent occurrences; show a dismissible migration notice and queued/new policy version. Safety floor and honest health apply immediately. |
| Open-ended legacy cause | Preserve configuration and current rights; do not convert an old Focus toggle exit into an endless Hardcore lock. | Preserve unscheduled rules/configuration. | Mark “Choose an end time to use the new commitment rules.” Retain legacy stop/edit routes, remove any unbounded derived removal guard, and require explicit finite-duration acceptance before the next strict occurrence. No implicit 24-hour lock. |
| Time Limit tier | Preserve explicit per-rule tier and usage state. | Snapshot `default_break_level_v1` into each old tierless limit; no later global-tier edit silently weakens it. | If an optional legacy tier field was absent in a valid old schema, use documented old default and flag it for review. A malformed tier is unavailable, not Easy. Preserve old per-app versus grouped budget meaning; splitting/merging budgets requires an explicit edit. |
| Open counts | Preserve token keys, count and `dayStart`. | Preserve package counts and `open_counts_day_v1`. | Map into the shared per-target day ledger; retain used counts, including over-cap values. Known previous-day data resets only under §9, not simply because migration ran. Missing/malformed counter data on an established setup stays unavailable. |
| Token/category anomalies | Preserve opaque tokens; compare stable encoded identities. | Preserve package targets and unsupported metadata. | Rotated/unmappable token: keep old counter, require reselection and mark access unavailable for the remainder of the current period unless a mapping is proven. Unsupported Open categories stay visible but unarmed, with individual-app reselection; no silent dropping or charging. |
| Existing ordinary grants/waits | The old view-only 150-second wait has no durable acceptance: cancel it and explain the new flow. An unbounded legacy Open release is re-shielded without charging again. | Import a validated active interval from v2 if present and cause identities/revisions match; resume same-boot remaining duration, capped to its original deadline. Volatile v1 exemptions cannot be reconstructed after process death. | Keep durable consumption. An unprovable release is closed, with an explanation; never recreate a free grant. Preserve pending wait/counter if its exact scope/time basis can be proved, otherwise cancel pending only. |
| Pass usage | Import valid `flint.emergencyPass.lastUsed`. | Import valid `emergency_pass_used_at_v1`, even if an old Monday-key refresh would previously have offered a new pass. | Eligibility becomes no earlier than `lastUse + 604800s`; preserve the more restrictive trustworthy remaining cooldown. A future/invalid timestamp is unavailable. A complete validated legacy read may interpret an absent optional last-use key as the old schema's unused state, once, if no journal/history contradicts it; uncertain or partial reads cannot mint a pass. |
| Legacy Harder counts | No durable count to infer from iOS UI; start at zero for the next new occurrence. | Preserve global `break_session_v1`; conservatively seed each currently active Harder occurrence's `n` from the nonnegative old count, then use per-cause counts. | Do not reset a valid ongoing friction history on migration. New natural occurrences begin at zero. Archive old pending scopes that cannot be attributed; do not guess. |
| Guards / Safari config / preferences | Preserve saved preference/configuration; do not newly opt a user into device-wide removal denial. | Preserve user rules/preferences; remove Settings/uninstaller interception from effective policy. | Re-derive from valid owners. Existing finite iOS guard may remain only for its already-authorized legacy session until its original end/pass; new occurrences require the new explicit opt-in. Never infer consent from tier alone. |
| Local history | Preserve existing data; import only evidenced events. | Same; retain separate managed/debug data untouched. | Add provenance/version. Do not backfill imaginary completed sessions, opens, passes or protection durations from a blank store. |

The optional-key exception applies only during the one-time migration of a validated legacy
schema; a missing required key after the new initialization marker remains corruption. If
old data cannot establish a valid unused state, retain the raw record and expose repair/status;
do not silently grant or permanently erase it. An unavailable pass never
removes OS recovery or finite expiry. Migration acceptance requires before/after fixtures,
crash checkpoints, idempotence, duplicate-ID rejection and a write-failure path that keeps
the original readable. A migration notice describes any bounded access ended during upgrade.

**Baseline key/codec anchors:**
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintGroupStore.swift:13-24,37-69,92-98,126-154`;
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenLimit.swift:180-198`;
`ios-app/Packages/FlintCore/Sources/FlintCore/FlintModel.swift:223-225`;
`android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/DataStoreStores.kt:30-40,173-197`;
`android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/PrefsCodec.kt:57-64,87-91`.
Malformed Android rules are currently dropped by the decoder at the last anchor; migration
must preserve them for repair instead. The old Timer transaction is at
`android-app/core/core-datastore/src/main/kotlin/com/flint/peakfocus/core/datastore/DataStoreStores.kt:86-93`.

## 13. Copy corrections at the frozen integration baseline

These are a handoff to the implementation/copy lanes, **not claims of fixes in this docs-only
PR**. Source anchors use the baseline at the top of this document. Historical changelogs and
verification records should retain their dates/evidence and add a superseding note, not be
rewritten to pretend the new behavior was tested in the past. Do not ship new promises until
the relevant acceptance cases pass. Product copy describes effects and limits; implementation
details such as a carrier monitor belong in developer docs, not the user's flow.

| ID | Current claim / exact source anchor | Replacement wording principle |
| --- | --- | --- |
| C01 | “non-bypassable” Deep Focus: `README.md:53,71,164`; `CHANGELOG.md:45`; `ios-app/README.md:128`; `ios-app/Packages/FlintCore/Sources/FlintCore/FlintUninstallGuard.swift:3-14`. | “Hardcore prevents ordinary early changes in Flint until [end]. An available Emergency Pass provides recovery. OS controls and platform limits remain.” Never promise an unbreakable device lock or guaranteed teardown from code structure alone. |
| C02 | “Can’t be stopped, and Flint can’t be removed … only exit”: `ios-app/Flint/Features/Today/StartFocusSheet.swift:246`. | State the finite commitment and pass, and separately describe optional, device-wide removal restriction and revocable authorization. |
| C03 | “only way out”: `ios-app/Flint/Features/Schedules/SchedulesView.swift:506`; “only exit”: `android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/RuleEditorScreen.kt:523-524`. | “This occurrence cannot be weakened in Flint before [end], except with an available pass. Changes can be saved for after it ends.” Inactive templates remain editable. |
| C04 | “No breaks, no early stop — one … per week”: `android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/Summaries.kt:106`; tier KDoc at `android-app/core/core-model/src/main/kotlin/com/flint/peakfocus/core/model/Models.kt:15-16`. | Name ordinary breaks, rolling renewal and planned quota/Pomodoro access; do not conflate them. Show grandfathered rights on a legacy occurrence. |
| C05 | “sole exit” / “only way out”: `android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/BreakPolicy.kt:17-19`; `android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/EmergencyPassPolicy.kt:6-9`; `ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift:99-107`. | Policy comments must match finite expiry, pass, permission recovery, legacy migration and route-independent checks. |
| C06 | “1 pass left this week” despite rolling eligibility: `ios-app/Flint/Features/Today/SessionView.swift:296-303`; recurring “weekly” copy in `README.md:75,132,156,192,197` and `docs/architecture/00-architecture-decisions.md:219`. | “Available” / “Next pass: [date/time]”; one every seven days after use, with uncertainty status when applicable. |
| C07 | “new one arrives Monday”: `android-app/feature/feature-blockscreen/src/main/kotlin/com/flint/peakfocus/feature/blockscreen/BlockScreen.kt:561`; `android-app/README.md:159-160`. | Replace with actual rolling eligibility and a reason if unavailable. Never imply Monday replenishes it. |
| C08 | Pass ends only the named session: `ios-app/Flint/Features/Today/SessionView.swift:296`; Android package/cause-end description at `android-app/blocking/blocking-overlay/src/main/kotlin/com/flint/peakfocus/blocking/overlay/BlockScreenCoordinator.kt:369-374,500-505`. | Preview all sessions ended and rules paused for 60 minutes, what resumes, and next eligibility. Avoid claiming another rule stays active during global recovery. |
| C09 | Harder uses a 150-second stop-only wait: `ios-app/Flint/Features/Today/SessionView.swift:195-258`. | Separate “Take a 5-minute break” from “End early”; both use the persisted 1/5/15-minute friction policy, with the exact final action. |
| C10 | Shortcuts may stop Harder immediately while copy says it honors tiers: `ios-app/Flint/Features/Shortcuts/FlintShortcutsRunner.swift:80-85`; `ios-app/Flint/Features/Shortcuts/README.md:11`; `ios-app/Flint/Features/Sessions/SessionViewModel.swift:125`. | Explain confirmation-required for Harder, denied-until-end/pass for Hardcore, and precise request errors. Do not report completion when only a wait began. |
| C11 | Focus off always ends even Hardcore: `ios-app/Flint/Features/FocusFilter/FocusFilterController.swift:11-13,48-54`; `ios-app/README.md:144-148`. | Easy may follow Focus on/off; strict finite commitments retain the same exit rules after Focus-off. Say whether a background request was rejected. |
| C12 | “free Hardcore everywhere” / “hard-reset tier”: `ios-app/README.md:108-111`. | Describe the implemented per-cause matrix and actual test evidence. Do not imply persisted tier labels already gate editors or offer a hardware reset barrier. |
| C13 | “true anti-uninstall … prevents … turning blocking off” and unconditional removal prevention: `ios-app/Flint/Features/Settings/SettingsView.swift:197-208`. | System Screen Time controls are separate and user-managed. Flint's optional removal request is device-wide, authorization-dependent and device-tested; it cannot prevent revocation. |
| C14 | iOS guard always tears down / schedule exemptions: `ios-app/README.md:126-138`; `ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift:64-68`; `ios-app/Packages/FlintCore/Sources/FlintCore/FlintUninstallGuard.swift:16-28`. | State eligible finite owners, opt-in and reconciliation; distinguish requested state from verified OS effect. Replace the obsolete session-only scope after implementation. |
| C15 | Safari “while a focus session is running” and “can’t be evaded”: `ios-app/Flint/Features/Settings/WebRestrictionsView.swift:18-20`; `ios-app/README.md:139-143`; presumed caller at `ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictions.swift:47-48,196-197`. | Until wired, label unavailable. After acceptance, say which armed causes apply which Safari restrictions, when they end, and the tested browser/OS limits. A config writer alone proves no lifecycle. |
| C16 | “Cap how many times … opened”: `ios-app/Flint/Features/Limits/OpenLimitsView.swift:64`; `android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/LimitEditorScreen.kt:275`; `android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/BlocklistScreen.kt:500`; `ios-app/README.md:160-164`. | “Choose how many 5-minute access credits you can use each day.” Do not advertise raw launch counting. Display remaining credits and active expiry separately. |
| C17 | Fail-closed Open state / category charges: `ios-app/README.md:165-169`; `CHANGELOG.md:117-118`; `ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenLimit.swift:272-275,335-337`. | Only claim fail-closed after corrupt/missing/unavailable fixtures pass. “No credit used” when overlapping protection is unknown; remove the accepted category-charge exception. |
| C18 | Shared store described as a locked file: `ios-app/Packages/FlintCore/Sources/FlintCore/FlintOpenLimit.swift:185-187`. | Describe the real transaction boundary; the baseline is UserDefaults (`FlintGroupStore.swift:27-32`), not proof of cross-process atomicity. |
| C19 | Shield comments promise notification fallback: `ios-app/Extensions/ShieldActionExtension/FlintShieldAction.swift:5-7`. | Describe implemented actions only. Notification permission/delivery and any later deep link must be separately verified. |
| C20 | Sleep's tier/switch/pass description: `ios-app/Flint/Features/Sleep/SleepModeView.swift:142-148`; `ios-app/Flint/Features/Sleep/SleepModeController.swift:51-52`; Android mapping prose at `android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/SleepRules.kt:20-23`. | Show the saved tier explicitly. Keep current legacy rights visible; new Wind Down/Full Assist defaults Easy/Harder, Hardcore requires a deliberate choice. Remove “turning Sleep off also lifts” for a new active Hardcore occurrence. |
| C21 | Android “turn it off any time” versus Settings interception: `android-app/feature/feature-onboarding/src/main/kotlin/com/flint/peakfocus/feature/onboarding/ConsentCopy.kt:6-16`; `android-app/feature/feature-onboarding/src/main/kotlin/com/flint/peakfocus/feature/onboarding/AccessibilityConsentScreen.kt:135`; guard behavior at `android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/UninstallGuard.kt:104-110,129-154`. | Make the disable promise true by removing interception; do not fix the conflict by removing the user's control. Disclosure covers only actual foreground/URL data use. |
| C22 | Android guard blocks device-wide/unreadable Settings and has no pass exit: `android-app/README.md:87-101`; `CHANGELOG.md:60-63`; `android-app/feature/feature-blockscreen/src/main/kotlin/com/flint/peakfocus/feature/blockscreen/BlockScreenState.kt:86-91`. | Mark historical behavior superseded. Release does not obstruct Settings/uninstall, and neither a pass nor a readable node tree is required for recovery. |
| C23 | Android measured clock guard described too broadly: `README.md:136`; `android-app/README.md:76-85,127-132`; `android-app/blocking/blocking-engine/src/main/kotlin/com/flint/peakfocus/blocking/engine/ClockChangeGuard.kt:68-71,147-153`. | Describe detected same-boot protection, reboot uncertainty, duration/grant handling and actual service re-evaluation. “Fail-closed” is not a veto of clock editing or evidence of continuous foreground checks. |
| C24 | Native Timer all-tier “Hold to end”: `android-app/feature/feature-blocklist/src/main/kotlin/com/flint/peakfocus/feature/blocklist/TimerScreen.kt:691-694`; historical proof at `docs/verification/android-timer-exit-2026-10-11/README.md:26-36`. | Baseline wording is accurate for legacy Timers and must remain there. New Harder shows its wait; new Hardcore shows expiry/pass. Preserve the old evidence, annotate supersession. |
| C25 | “Checked” refreshed on reading old health: `ios-app/Flint/Features/Settings/SettingsView.swift:106-114`. | Show last actual reconciliation/check time, unavailable/stale state and per-cause arming, not a fresh health assertion. |
| C26 | Arbitrary future dates / native Open-count event: `docs/research/01-opal-feature-inventory.md:99-100,180,335`. | Mark historical roadmap claims; one-shot dates require the new absolute occurrence model, and Open credits are not DeviceActivity launch-count events. |
| C27 | Unverified competitor claims attached to pass/tier copy: `ios-app/Packages/FlintCore/Sources/FlintCore/FlintEmergencyPass.swift:3-4`; `ios-app/README.md:109`; `docs/architecture/00-architecture-decisions.md:219`. | State Flint's free local features without relying on unrefreshed competitor omissions/pricing. This contract does not re-audit competitors. |
| C28 | Earlier “no backend directory” history: `docs/architecture/00-architecture-decisions.md:82-83`. | Keep ADR-010/011's debug-only boundary explicit; the release remains no-account/local-first. This contract grants no production managed authority. |

## 14. Acceptance cases and release evidence

These are **required cases, not passing results**. `unit` tests pure policy/time decisions;
`integration` tests real persistence/adapter boundaries with fault injection; `emulator`
tests Android visible behavior and iOS UI only; `physical-device` tests actual OS enforcement.
Where a row lists several levels, all are required for its corresponding claim. For Android,
enforcement rows run with Accessibility primary **and** UsageStats fallback unless the row
explicitly says browser-only/primary; record supported OS and OEM coverage. iOS simulator
success never substitutes for signed physical Screen Time proof. Test boundaries just before,
at and after deadlines; preserve operation IDs, source/build hashes and observed latency.

| Case | Preconditions | Action | Expected iOS result | Expected Android result | Test level |
| --- | --- | --- | --- | --- | --- |
| PC-01 | Idle, valid selection/permissions/storage | Start Easy, Harder and Hardcore separately | One armed occurrence and one `started`; finite strict deadline and truthful UI | Same | unit, integration, emulator, physical-device |
| PC-02 | Existing protected cause; adapter capacity exhausted | Start/add another cause | New cause `failed_to_arm`; no on/success; existing stores intact | Same for adapter failure | integration, physical-device |
| PC-03 | Start operation committed; caller loses response | Retry same request ID | Same occurrence/result, no second `started` or reset | Same | integration |
| PC-04 | Active Hardcore targets A until T | Start Easy replacing A through UI/automation/Shortcut/notification | Deny weakening; original deadline, targets and guard retained | Same for supported routes | unit, integration, emulator, physical-device |
| PC-05 | Hardcore A until T; capacity available | Confirm replacement with Hardcore A+B until T+10m | New version armed before old retired; one `superseded`; continuous A coverage | Same | integration, physical-device |
| PC-06 | Hardcore A+B until T | Replace with longer Hardcore A only | Non-dominating; deny drop of B | Same | unit |
| PC-07 | Active Harder; no prior exceptions | Stop through Shortcut/Siri/background action | Persist 60s wait, confirmation_required; no immediate stop | Same policy for supported automation/notification routes | unit, integration, physical-device |
| PC-08 | New finite Hardcore Focus-owned cause | Turn Focus off before end | Remains committed; denial/status with end/pass; no natural completion | Equivalent automation stop denied; no native Focus filter claimed | integration, physical-device |
| PC-09 | Manual cause and separate Focus/automation cause | Stop the automation's owned occurrence | Only exact owned cause may change; manual cause preserved | Same ownership contract | unit, integration |
| PC-10 | Confirmation displays revision R | Change policy to R+1 before commit | Stale operation rejected/refreshed; no release or spend | Same | integration |
| PC-11 | Active Hardcore occurrence | Shorten, remove apps, add allowed apps, lower tier, move window/zone | No current reduction; offered queued change/end/pass | Same | unit, emulator |
| PC-12 | Active Hardcore A, finite end | Add B or extend within allowed bound | Explicit preview; arm new resources, retain A if failure | Same | unit, integration |
| PC-13 | Inactive Hardcore template, including future one-shot | Edit/downgrade/off/delete | Ordinary validated save/confirmation; no lock/pass required | Same | unit, emulator |
| PC-14 | Active Hardcore with off/delete/weaken queued | Reach exact occurrence end while host dead | Old occurrence completes; queued change applies before adjacent occurrence; no restart loop | Same | integration, physical-device |
| PC-15 | Daily Hardcore Time Limit, budget not yet spent | Increase budget or delete limit | Active commitment gate applies; defer | Same with imported per-rule tier | unit, integration |
| PC-16 | Hardcore Open Limit inside valid credit | Toggle limit off | Credit does not make commitment inactive; defer | Same | unit, emulator |
| PC-17 | Active Harder Timer | Complete wait then hold-to-end | Same rights via explicit accessible confirmation; end once | Preserve exact-snapshot store transaction; end only after wait + hold | integration, emulator |
| PC-18 | One Harder occurrence, three accepted exceptions | Request next exception after each | Waits 60s, 300s, 900s, 900s; no reset on view/rename | Same | unit, integration |
| PC-19 | Two overlapping Harder causes with counts 0 and 2 | Request one scoped break | Wait 900s; commit increments each count once; both scopes recorded | Same | unit, integration |
| PC-20 | Pending Harder wait | Cancel then request again | No consumption; full wait restarts; previous `n` preserved | Same | unit, emulator |
| PC-21 | Durable pending wait, same boot | Kill/recreate host; let deadline pass | Remaining wait restored; no release before final confirmation | Same with service/UI recreation | integration, emulator |
| PC-22 | Accepted ordinary break, host closed, target stationary | Remain in target through 300s | Monitor re-shields at logical deadline without opening Flint; latency recorded | Live service re-evaluates without app switch; both paths re-block | integration, physical-device |
| PC-23 | Live ordinary/credit grant | Repeat request, leave/reenter app | Same remaining interval; no extension/spend/extra break event | Same | unit, integration, physical-device |
| PC-24 | Break for target A from cause X; X also blocks B | Open B during A's break | B still blocked; no global exemption | Same | unit, physical-device |
| PC-25 | Easy + Hardcore overlap on A | Request Easy break or exhaust Open quota then request break | Deny ordinary release; list Hardcore contributor | Same | unit, integration, physical-device |
| PC-26 | A has a valid weaker ordinary grant | Activate new Hardcore cause on A | A blocks immediately at new boundary; old grant does not authorize new cause | Same, including stationary target | integration, physical-device |
| PC-27 | Hardcore Pomodoro parent with planned rests and another rule | Enter rest; try skip/extend rest/end parent | Own focus restrictions rest; other rule remains; parent still locks weakening | Same | unit, integration, physical-device |
| PC-28 | Open cap 3, used 0, no other block | Accept three separate credits through expiry | Each lasts ≤300s; 3 total charges; fourth denied | Same explicit credit UX, no launch-count charging | unit, integration, physical-device |
| PC-29 | One Open credit left; two concurrent distinct requests | Race acceptance from two surfaces/processes | One transaction wins; other sees active grant; at most one increment | Same across both paths/UI | integration |
| PC-30 | Last credit accepted; remaining becomes 0 | Keep target foreground | Last interval honored until deadline; zero count does not instantly re-shield | Same | unit, physical-device |
| PC-31 | Credit active on A | Multiple foreground callbacks and duplicate shield callbacks | No additional charge; deadline unchanged | Same, including resumed/paused events | integration |
| PC-32 | Open credit available but separate session/Time Limit still blocks A | Request credit | No charge or false success; name blocking contributor | Same | unit, integration, physical-device |
| PC-33 | iOS category block may cover A; no membership proof | Request A's Open credit | Deny conservatively; “No credit used,” no category-wide release | Known explicit overlap denies; proven disjoint app may grant | unit, integration, physical-device |
| PC-34 | Two Open rules cover A with caps 2 and 5 | Accept credit, edit/rename one rule | Effective cap 2; one shared target counter; no reset | Same | unit, integration |
| PC-35 | Credit accepted 2 minutes before trusted quota midnight | Cross midnight without launching Flint | Grant clipped at boundary; re-shield; single new quota period | Same | integration, physical-device |
| PC-36 | Used target quota in current day; editable tier | Off/on or delete/recreate same target limit | Used counter remains; no fresh quota by new rule ID | Same | unit, integration |
| PC-37 | Counter missing after initialization, corrupt, IO failure or unknown schema | Request credit | Unavailable, no release/spend/reset; safety floor intact | Same; no emptyPreferences fallback | integration |
| PC-38 | Token identity changes with used quota | Import/reselect unprovable replacement | Preserve old counter; no fresh mid-period credit; explain/recover | Package-identity import preserves mapped count; unknown identity unavailable | integration |
| PC-39 | Pass consumed at t; same boot trusted time | Request at t+604799s and t+604800s | Deny then available; one renewal, no banking | Same | unit |
| PC-40 | Pass spent Sunday evening | Cross Monday / change device zone | No renewal from week boundary/zone; same cooldown | Same, unlike legacy ISO-week policy | unit, integration |
| PC-41 | Manual, Pomodoro, recurring, one-shot, Sleep, Time/Open and Safari causes active | Confirm pass | Transients end; rule causes suspend globally 60m; counters unchanged; all personal guards release | Same global scope; no Settings guard | integration, physical-device |
| PC-42 | Pass recovery active; new schedule window starts | Wait through window start and recovery end | New occurrence suppressed during recovery; only still-active rule resumes; expired one never replays | Same | unit, integration, physical-device |
| PC-43 | Pass eligible; transaction write fails | Confirm pass | No consumption/release/event success; offer retry/status | Same | integration |
| PC-44 | Pass commit succeeds; process dies before OS release | Restart same boot / invoke extension | Recover same remaining recovery; one pass_used; no second charge | Same via service reconciliation | integration, physical-device |
| PC-45 | Pass recovery or cooldown active | Double-tap/retry distinct pass action | No second consumption or extension; show existing interval/eligibility | Same | unit, integration, emulator |
| PC-46 | Hardcore deletion guard active on iOS | Use pass then return to recovery Settings | Guard removed; safe controls remain; no new manual cause until explicit resume | Settings always reachable; pass still releases all app/domain causes | physical-device |
| PC-47 | Established installation; pass state corrupt/future/unavailable | Request pass from any surface | No fresh pass; honest unavailable state; finite expiry/OS recovery retained | Same | integration, emulator |
| PC-48 | Every cause/tier, including exhausted limits and storage faults | Reach emergency/dialer/in-call surfaces | Never shield/count; supported default calling configuration verified without placing emergency call | Same with stock and custom default dialer on both paths | unit, integration, physical-device |
| PC-49 | Active allow-list; default calling/Home app changes | Resolve new role and open it | Supported OS calling surface stays safe; unsupported mode refused honestly | Fresh role resolution exempts new default before any block/open charging | integration, physical-device |
| PC-50 | Broad allow-list, zero quota, and overlapping Hardcore | Use Home, app switcher, notification/system UI and Flint recovery | All reachable; no PIN gate on minimal recovery | All reachable; no overlay/Home loop | emulator, physical-device |
| PC-51 | Any active Hardcore or unreadable Settings UI | Open revoke/disable/app-info/uninstall controls | Revocation and recovery remain reachable; only disclosed OS removal setting may deny deletion | No guard interception, node sweep for guard, overlay or forced Home | integration, physical-device |
| PC-52 | Legacy rule/limit explicitly names a safety target | Migrate/save/materialize/evaluate | Effective targets exclude floor; original record retained and notice shown | Same, including custom dialer in Open Limit and Path B | integration, physical-device |
| PC-53 | Cannot resolve safety identity for broad selection | Arm broad allow-list/category mode | Refuse unsafe/unproven configuration; show coverage limitation | Decline new broad arm until role resolution; no safety-surface fallback block | integration |
| PC-54 | Finite session, wait, credit and spent pass, same boot | Set wall clock forward hours/days | Remaining elapsed durations/cooldown unchanged; no quota minted; reconcile carrier | Same through both paths and stationary foreground | unit, integration, physical-device |
| PC-55 | Same state as PC-54 | Set clock backward | No grant/session extension; no reset/refill; display corrected estimate | Same | unit, integration, physical-device |
| PC-56 | Active occurrence and shared quota zone | Change device time zone, then edit rule zone | Device-zone change only affects display; explicit rule edit obeys tier/defer | Same | unit, integration |
| PC-57 | Overnight recurring rule at DST gap/fold | Materialize/start/end occurrence twice via callbacks | Exactly one occurrence, defined endpoint resolution, no duplicated rest/quota | Same | unit, integration, physical-device |
| PC-58 | Finite active restriction; checkpoint exists | Reboot before/after wall end, including clock rollback | Bounded min-remaining fallback; uncertain flag; past end expires; no replay | Same after credential unlock; actual service recovery evidenced | integration, physical-device |
| PC-59 | Active ordinary/credit/pass recovery grant; consumption durable | Reboot | Access interval closes, consumption retained, explanation shown; no invented refund | Same | integration, physical-device |
| PC-60 | Spent pass and daily quota; wall date jumps across reboot | Reconcile | No inferred refill; resume checkpoint's remaining cooldown/reset; publish delayed estimate | Same | unit, integration, physical-device |
| PC-61 | Permission absent or service not live | Open app and attempt arm | Not armed/degraded; no false “on” from stored Boolean | Distinguish grant bit from live service; honest fallback/domain limits | integration, emulator, physical-device |
| PC-62 | Saved schedule/limit/Safari state; app opens Today only | Startup reconcile without visiting Rules/Settings | All causes and stale resources reconciled; no tab dependency | Same through application/service startup | integration, physical-device |
| PC-63 | Timed/scheduled cause; host killed | Natural end or phase boundary | Monitor writes durable event/outbox while host dead; history later reads one outcome | Service/boundary reconciler writes equivalent event | integration, physical-device |
| PC-64 | Early-ended/replaced cause; duplicate/delayed end callback | Deliver warning/end after stopMonitoring | No completed event for early end; no other cause cleared | Equivalent stale timer callback no-op | integration |
| PC-65 | Grant/pass accepted durably; adapter application fails | Retry reconciliation | Same operation and remaining interval; honest applying/error status; no double spend | Same | integration |
| PC-66 | New cause replaces old monitor registration | Deliver old start/end/threshold callback | Exact identity/revision rejects it; new cause remains | Same for stale alarm/detector action | integration |
| PC-67 | Pre-upgrade running Hardcore native Timer | Upgrade then complete old hold-to-end | N/A to native Android Timer; iOS legacy exits retained as originally promised | Legacy Timer still ends transactionally, preserving independent overlap | integration, emulator |
| PC-68 | Newly created Hardcore Timer under this contract | Try native hold-to-end | No ordinary exit; expiry/pass displayed | Same; old all-tier Timer exit cannot be reached by forged/stale route | unit, integration, emulator |
| PC-69 | Valid old pass use timestamp / valid legacy unused state / partial read | Migrate before seven days elapsed, including new Monday | Preserve cooldown; valid optional absence initializes once; partial read unavailable | No Monday bonus; same unused/partial-read distinction; retain restrictive valid state | unit, integration |
| PC-70 | Old schema with active rules, counts and pass state | Crash at each migration checkpoint, rerun twice | Original bytes preserved; one validated migration; no extra quota/lost rule | Same including v1 and dormant v2 records | integration |
| PC-71 | Malformed rule, duplicate ID or unsupported new schema | Decode/migrate | Quarantine raw record and show repair; no silent drop/default rights | Same; no mapNotNull data loss | integration |
| PC-72 | Two owners of Safari restrictions with different end times | End one / take scoped break / pass / expire last | Effective combination retains other owner; pass clears; last end clears; no stale global setting | Domain/app scopes retain other owners; unsupported Safari features not claimed | integration, physical-device |
| PC-73 | Stored old health report; no new check | Open health screen | Shows actual check timestamp/stale scope, not “checked now” | Same principle for cached health | unit, emulator |
| PC-74 | New 5- and 10-minute session, host killed | Reach logical end before 15-minute carrier end | Correct warning ends once at logical deadline; carrier end adds no event or extension | Native duration ends once without carrier constraint | integration, physical-device |
| PC-75 | Five-minute grant request; warning registration rejected | Accept from shield with Flint closed | Denied/no charge; no indefinite unshield; unavailable UI | Adapter expiry-registration failure likewise denies | integration, physical-device |
| PC-76 | Registered carrier; early, duplicate, late or missed warning; lock/unlock device | Inject callback sequences and measure real device expiry | Early does not release/expire before logical time; duplicate no-op; late reconciles and records failure/latency; resume cannot require opening Flint | Equivalent delayed timer/service behavior, with degradation surfaced | integration, physical-device |
| PC-77 | Active commitments; OS permission revoked and later restored | Use supported Settings controls | No interception; coverage gap recorded; remaining cause reconciled, no extended end | Same; test primary-to-fallback, both revoked, and rebind without treating grant bit as liveness | integration, physical-device |
| PC-78 | Two finite Hardcore guard owners on iOS | End one, then last; separately use pass | First end retains guard, last/pass clears; no guard from inactive template | No release uninstall/disable interception at any tier | unit, integration, physical-device |
| PC-79 | Legacy open-ended Hardcore/Focus rule | Upgrade/reconcile/start a new strict cause | Preserve legacy edit/exit rights, remove unbounded guard, require finite new duration | Same finite-new/legacy-safe principle | integration, emulator |
| PC-80 | Running cause copied from saved app group | Edit/delete the group | Active target snapshot unchanged; new starts see new group | Same | unit, integration |
| PC-81 | One-shot future occurrence already ended while device off | Relaunch/advance a week | Never repeats/replays; history reconciled without fake start | Same | unit, integration, physical-device |
| PC-82 | Old Sleep saved tier; new Sleep draft | Migrate and separately create draft | Old tier retained; new Wind Down Easy/Full Assist Harder; Hardcore explicit | Same new defaults, old data retained | unit, emulator |
| PC-83 | Pass recovery active with rule causes due to resume | Confirm disable/delete list, then end recovery | Chosen rules remain off/deleted; counters/history retained; unchosen active rules resume | Same | integration, emulator |
| PC-84 | Completed focus overlaps another focus and includes breaks/degraded periods | Project history/Insights and send completion notification | Union evidenced intervals, subtract gaps; one terminal event/notification; no invented time saved | Same | unit, integration |

**Owner review checkpoints:** the rolling/global pass policy; five-minute access and the
unproven iOS warning adapter; locking active but not inactive Hardcore templates; the
explicit change to new Android Timer exit rights; optional device-wide iOS removal denial
versus no Android disable/uninstall interception; conservative reboot renewal delays; and
the immediate safety floor. These choices are resolved here for implementation, not left
as undefined policy. A change to one requires an ADR amendment plus corresponding fixtures.
