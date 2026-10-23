# Timer and Digital Detox: early-exit trace

## Finding and evidence scope

**The active Timer and Digital Detox screens implement the same confirmed early exit.**
Completing the shared hold control invokes an operation that removes the current user's
active Timer metadata and deletes the referenced blocking rule in a Room transaction.
The manual stop path does not test the rule's `Strict` enforcement setting or require
the Timer to have expired. Timer creation nevertheless explicitly uses `Strict`.
Thus the recovered code separates strict blocking from the ability to end one's Timer.
It does not create an ordinary break, emergency pass, or exemption covering other rules.

Reference: user-supplied Opal Android **4.14.0 / 4014000**. Extracted base APK SHA-256:
`9b4348953e5da3e6a01e87ff3e76e9e059f5d3909e69a95b4d29c3d56b10710a`.
This is static JADX and Android SDK DEX evidence, not an authenticated runtime observation.
REA tools were unavailable; no REA evidence identifiers are claimed. No authentication,
attestation, licensing, account state, or reference-app binary was changed for this trace.

Source anchors below are relative to
`android-app/build/opal-analysis/decompiled/sources/`; resource anchors are relative to
`android-app/build/opal-analysis/decompiled/resources/`.
Line numbers are physical lines in these extracted files, not original Kotlin lines.

## Ordinary Timer: UI to stop operation

| Step | Recovered behavior | Source anchor |
|---|---|---|
| Active screen | Observes `k20.j` state and its navigation event. Binds `onCloseClick`, `confirmLeave`, and confirmation dismissal. | `s40/a.java:734–797` |
| Close | `an.j` variant 15 sets `isLeaveConfirmationVisible = true`; variant 17 clears it. | `an/j.java:282–298`; field names in `k20/g.java:75–83` |
| Confirmation | Visible state renders shared `a.a.b(confirm, dismiss)`. | `s40/a.java:780–797` |
| Sheet contents | Title “Leave early?”, “Hold to leave” action, “Never mind” cancellation. | `a/a.java:659–715`; `res/values/strings.xml:1144–1149` |
| Hold completion | Sheet wrapper collapses the sheet, then invokes the supplied confirmation action. | `iq/c.java:66–97`; `j20/q0.java:29–32` |
| Stop launch | `an.j` variant 16 launches `k20.h` variant 0 in the ViewModel coroutine scope. | `an/j.java:288–292` |
| Await stop | Calls injected `h0.p1.r`; only after successful return does it emit the navigate-back event. | `k20/h.java:69–91` |

The active Timer renderer passes its close callback independently of whether it has
blocked content; the latter condition controls the blocked-content navigation callback
(`s40/a.java:710`, `847–860`). This is evidence of the supplied UI actions, not proof of
runtime touch geometry or accessibility behavior.

## Hold behavior: DEX resolves misleading decompilation

The sheet calls the shared `yr.w.a` component with default-argument mask `888`, which
includes bit `32`. That bit selects the declared **2-second** default hold duration
(`a/a.java:705`; `yr/w.java:55–58`, `359`, `440–441`).

The state machine names are `Idle`, `Holding`, and `Finished` (`yr/c.java:23–30`).
During Holding, elapsed frame time includes an initial **300ms collapse phase**; the
component itself uses that description and requires the hold duration to exceed it
(`yr/w.java:476–483`). At the hold-duration threshold it enters Finished
(`yr/t.java:180–205`). This corrects the initial description of 300ms as a finish animation;
the companion [Timer creation trace](opal-4.14.0-timer-trace.md) now records this correction.

JADX reconstructed the Finished branch incorrectly (`yr/t.java:144–156`). DEX shows:

1. While finish progress is below 1, request frames using `x.r` variant 6.
2. That updater advances progress using elapsed time divided by **400ms**.
3. After progress reaches 1, run `wt.c` variant 1 in `NonCancellable` context.
4. That coroutine delays **400ms**, then invokes the commit callback.

Anchors: `timer-exit-hold.smali:269–376`;
`x/r.java:66–71` and `timer-exit-finish-animation.smali:360` (literal `0x190`);
`wt/c.java:72–88`; `b50/a2.java` (`NonCancellable`).
These are configured phase durations, not a measured 2.8-second end-to-end response time;
frame scheduling and database work add runtime effects.

The pointer coroutine waits for a press, enters Holding unless already Finished, then
waits for release/cancellation. On return it cancels vibration and resets to Idle **only
if the state is still Holding**. It does not reset Finished on release
(`yr/u.java:58–124`; corroborated by `timer-exit-pointer.smali:139–328`).
This supports cancellation before completion in the traced pointer path. Alternative
accessibility actions and the exact gesture boundary remain unobserved.

## Injected operation and actual deletion

The same dependency factory supplies both ViewModels:
`go/x.java:55` creates `k20.j` using `gVar.D()`; `go/x.java:75` creates `m20.k` using it.
`go/g.java:427–429` builds `p1(I(), blockingRuleRepository, v())`.
Its matching constructor selects **dispatcher variant 5** (`h0/p1.java:1658–1663`).
Metadata and transaction dependencies resolve from the same `OpalDatabase` provider
(`go/g.java:451–456`, `575–577`).

The manual stop is `h0.p1.r` variant 5 (`h0/p1.java:604–646`):

1. Read the current active Timer metadata using `v0.j.n`.
2. If absent, return `Unit` without writes.
3. Otherwise call transaction wrapper `tr.j.E` with `androidx.room.r0` variant 3,
   capturing that metadata's blocking-rule ID.

JADX loses the immediate-return coroutine branch around `h0/p1.java:619–626`.
DEX confirms that both immediate and resumed reads converge on the same null check and
transaction invocation (`timer-exit-p1.smali:1488–1568`; dispatcher mapping at `2227`).
Do not interpret the broken Java rendering as an unconditional suspended return.

Inside the transaction, deletion is ordered as follows:

| Operation | Selector and SQL | Anchors |
|---|---|---|
| Read metadata | Current user ID; `SELECT * FROM active_timer_sessions WHERE userID = ? LIMIT 1` | `v0/j.java:1097–1100`; `f20/a.java:100–139`; `pr/l0.java:295–310` |
| Delete metadata | `f20.a` variant 0 obtains the current user ID; `pr.l0` variant 10 executes `DELETE FROM active_timer_sessions WHERE userID = ?` | `androidx/room/r0.java:224–239`; `f20/a.java:61–99`; `pr/l0.java:312–323` |
| Delete rule | `yp.c` variant 0 sends the captured metadata's `blockingRuleID` to `nb.u` variant 11: `DELETE FROM blocking_rules WHERE id = ?` | `androidx/room/r0.java:249–261`; `yp/c.java:61–85`; `nb/u.java:306–316` |

The second delete follows completion of the first; the transaction callback's own
coroutine decompilation is also incomplete. DEX confirms selector 0 for each repository
operation and both synchronous/resumed continuations
(`timer-exit-transaction.smali:2286–2395`).

`tr/j.java:279–281` and `androidx/room/t0.java:35–51` enter the Room transaction wrapper.
The wrapper begins a transaction, awaits the callback, marks success, and ends it; a
throwing path ends without marking success and rethrows
(`androidx/room/r0.java:137–162`; `timer-exit-transaction.smali:3015–3066`).
Schema declarations give both `session_rule_details.ruleID` and the Timer metadata's
`blockingRuleID` foreign keys `ON DELETE CASCADE` (`gb/r.java:519`, `523`). This establishes
the transaction/cascade design; rollback under an injected database fault was not tested.

### Why Strict does not prevent this action

Creation generates a `timer-session-` rule ID and creates a session rule with
`zp.a1.f41367a` enforcement (`tk/s.java:1353–1389`), whose string identity is `Strict`
(`zp/a1.java:18–19`). Manual-stop variant 5 never reads that enforcement or remaining
duration. It is distinct from variant 4, the expiry helper, which checks remaining time
before invoking stop (`h0/p1.java:557–603`).

**Static conclusion:** the implemented confirmed early-exit action can delete this
Strict Timer rule before expiry. It is not evidence that a generic strict rule can be
bypassed, or that deleting this one rule makes every target accessible: unrelated
overlapping rules are not deleted by the traced operation. Flint's existing policy
for ending a HARDCORE Timer should not be described as Opal parity on this evidence.

## Digital Detox comparison

| Aspect | Digital Detox evidence |
|---|---|
| Close | `m20/e.java:298–305` binds `an.j` variant 29; `an/j.java:374–379` sets leave-confirmation state. |
| Confirmation | `m20/e.java:310–322` renders the same `a.a.b` sheet; `m20/d.java:75–85` launches `m20.g` variant 0 or dismisses. |
| Stop and navigation | `m20/g.java:70–92` awaits the same injected `p1.r`, then emits navigate-back. No Strict or expiry check is present in that manual-confirm branch. |
| Initial engagement phase | `m20/k.java:116–123` computes the first five seconds from session start; `m20/e.java:204–206`, `243–254` uses it for blur/dim/phase visuals while still supplying the close callback. Runtime hit testing during that visual phase is unverified. |
| Natural expiry | `m20/k.java:86–146` displays remaining or elapsed time, and also calls the stop operation on expiry. `detox-expiry.smali:151–211` corroborates expiry and successful navigation despite another incomplete Java continuation. |

No distinct Digital Detox early-exit quota, cooldown, minimum elapsed duration, or
enforcement check appears in these shared/manual-stop paths. This is a bounded statement
about the traced paths, not a claim about every observer or feature elsewhere in Opal.

## Failure cases and remaining unknowns

- Missing active metadata makes stop a no-op; the confirmation coroutine can still emit
  navigate-back after that successful return.
- With no current user ID, the metadata repository skips its SQL operation. Metadata read
  and deletion each obtain the current identity; the rule delete uses the earlier captured
  blocking-rule ID. Concurrent account/session changes were not exercised, so race behavior is
  not established here.
- The manual-confirm coroutines contain no local exception handler or explicit in-flight
  guard. A propagated failure prevents their subsequent navigation update. The sheet wrapper
  collapses before launching stop, so visible failure recovery cannot be inferred from it.
- Hold completion uses a non-cancellable callback context; this does not prove the separate
  ViewModel stop coroutine or database work survives process death.
- Overlay refresh latency, stopped-session history, global analytics/observers, background
  termination, process restart, accessibility activation, and on-device visuals were not
  traced or tested. No claim of authenticated runtime verification is made.

## Reproduce the DEX checks

From repository root, with the existing local JDK and Android SDK:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 apkanalyzer dex code \
  --class h0.p1 --method 'r(Lh40/c;)Ljava/lang/Object;' \
  android-app/build/opal-analysis/base.apk

JAVA_HOME=/opt/homebrew/opt/openjdk@21 apkanalyzer dex code \
  --class androidx.room.r0 --method 'invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;' \
  android-app/build/opal-analysis/base.apk
```

For the other coroutine checks, substitute class `yr.t`, `yr.u`, `k20.h`, or `f20.a`
with the same `invokeSuspend` signature. `m20.k` used
`a(Lm20/k;Lh20/d;Ljava/time/Instant;Lh40/c;)Ljava/lang/Object;`;
`yr.w` was dumped as a whole class, and `x.r` used
`invoke(Ljava/lang/Object;)Ljava/lang/Object;`.
Local raw outputs are in ignored `android-app/build/opal-analysis/`:

| File | SHA-256 |
|---|---|
| `timer-exit-p1.smali` | `64d8d63ccca02a33c07e2f1972fe797f5cb0fd841ae7976047d412b0d2435134` |
| `timer-exit-transaction.smali` | `382292158f116d51af5f4bdc85419df1c7056df9fcc464dfe240f7d92efc4aeb` |
| `timer-exit-hold.smali` | `f1099e639800687cd7520955215573701a6be00785aa4b1501de7f4b89cf9c86` |
| `timer-exit-hold-component.smali` | `a7bb655ced692904ffbcfc02e84bf5ec034f28843d308552d63f8ce15dbf31bd` |
| `timer-exit-pointer.smali` | `b5b12dc2ef2a9bef396d146b63ffbce1c861a46ab4379edf9457c999dd88e63b` |
| `timer-exit-finish-animation.smali` | `636abd9b2455e19a3ee6f369fc9ea6ecad9099cab3b410a2211ee8e24852f177` |
| `timer-exit-confirm.smali` | `739cf5a9546dc97357355a338bd045abe32953dba91efe21e9a5b73730ad4e72` |
| `timer-exit-metadata.smali` | `7b4ae9e00ea7d601e3cb743d2b77dabe2dceaa76d4f5f7b7b3b12ab3de8b1b98` |
| `detox-expiry.smali` | `0243197f39a7a1699b9ec480d562f693cbfbcce065f8278d4431918d6ed5cc33` |
