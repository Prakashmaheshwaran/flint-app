# Opal 4.14.0: rules and deliberate unblocking evidence

Analyzed the user-supplied APKM, using its extracted `base.apk` and local JADX output. This is static analysis of this Android build, not confirmation that every route is available to every subscription, region, or account. No Opal server was called for this analysis. Decompiled source remains under the ignored `android-app/build/opal-analysis/decompiled/sources/` directory; paths below are relative to that directory.

## Rule model and evaluation

| Behavior observed in code | Evidence |
| --- | --- |
| A rule has a source, enabled state, optional snooze deadline, type, creation time, optional managed authority/deletion deadline, and timezone. | `gb/r.java:491` creates `blocking_rules`; `zp/n.java` is its domain model. |
| Three distinct rule models exist: session, time limit, and open limit. Schedules are a repeating activation form of a session rather than a fourth enforcement type. | `zp/v0.java:16-22`, `zp/e1.java:22-29`, `zp/j0.java:22-27`; evaluator branches in `o10/c.java:335-421`. |
| Session activation can be a finite absolute interval, a repeating day/time range, or always on with an optional end. Strict sessions produce strict blocking; ordinary sessions produce a state that permits unblocking. | `o10/c.java:379-421`. |
| A repeating range checks selected weekdays and local start/end times. Its implementation includes overnight continuation on the day after a selected start day. Ordinary ranges include the start and exclude the end. | `zp/l0.java:28-70`, period construction at `zp/l0.java:122-137`. JADX warns about reconstructed loops here; runtime DST/overnight behavior has not been exercised. |
| The evaluator filters disabled/snoozed/expired rules, then distinguishes a selected block list from “all except” selection. | `o10/c.java:120-138`; selection models `zp/r.java`, `zp/q.java`. |
| Multiple rules are combined. Strict blocking wins over ordinary blocking or temporary allowance, and covering rule IDs are retained. Two ordinary blocks permit the block-screen unblock action only if both allow it. | `o10/c.java:273-331`. |

## Time limits and open limits

Time limits keep the monitored selection separate from the blocked selection. Configuration includes the duration threshold, optional warning lead time, an end-of-day or fixed-duration lock, and strict/ordinary enforcement. The database records the active period, threshold-reached time, and last reset time (`gb/r.java:522`; `zp/u1.java:43`; `zp/e1.java:22-49`).

The usage evaluation is actual method logic, not merely resource text: `kl/a.java:958-1040` resolves an active period and selected package IDs, chooses the usage interval beginning at the maximum relevant period/reset/lower-bound timestamp, and requests usage for that interval. `kl/a.java:1064-1075` compares the resulting duration to the configured threshold and produces state with `reachedAt` when it is met. After a fixed lock expires, the new measurement interval begins after that lock (`kl/a.java:1015-1029`). The evaluator only blocks when the range is active, threshold state is current, and lock duration has not expired (`o10/c.java:359-377`; `mn/p.java:1255-1262`). This identifies the mechanism; it does not establish measurement accuracy on a physical device.

“Open limit” is implemented as a budget of **timed unblock credits** in this build. Its stored fields are `unblockDurationMillis`, `numberOfUnblocks`, `resetsAllowed`, period bounds, and `stateUnblocks` (`gb/r.java:506`). `zp/j0.java:30-32` compares the current period's count to the budget, while `zp/k0.java:18-22` disregards a count outside its stored period. `mu/g.java:1170-1225` increments the session/open-limit unblock count and starts a new period when needed. `o10/c.java:351-355` emits a strict block after the budget is exhausted; before exhaustion it creates a constrained unblock policy with the configured duration and count. This supports timed access passes; it should not be described as evidence of counting every raw Android package launch.

Reset eligibility is also explicit: open limits must allow resets, be active, and have exhausted their credits; time limits must be ordinary enforcement, active, and currently locked (`s40/a.java:210-246`). A time-limit reset sets a fresh reset timestamp and clears reached state (`p10/n.java:133-155`). JADX could not fully reconstruct that coroutine's persistence tail, so the database schema and state construction are stronger evidence than its displayed control flow.

## Waiting room and resistance

The waiting room handles an app, a rule, a blanket unblock, or a standalone countdown (`ww/s.java:59-148`). Standard resistance waits **5 seconds**. Increasing resistance waits:

```text
min(120, 20 × (max(current unblock count, 0) + 1)) seconds
```

That gives 20, 40, 60, 80, 100, then 120 seconds. The app-specific count computation takes the largest relevant count from matching rules (`p10/l.java:51-84`), and `ww/s.java:175-225` selects the app/rule/blanket count source. The generic unblock-duration picker is initialized with the range 1–15 (`ww/t.java:8`); the UI renders these values as minutes (`ww/p.java:249`). Rule policy can constrain the picker, so this is not proof that every rule offers every duration.

**Bytecode cross-check:** JADX marks `tu/a0.java:232-348` incorrectly decompiled and reverses the apparent Standard/Increasing branch. The original DEX was inspected with:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 apkanalyzer dex code \
  --class tu.a0 android-app/build/opal-analysis/base.apk
```

In method `k(Ljava/lang/Object;)Ljava/lang/Object;`, DEX source line 113 jumps from ordinal zero to `:cond_87`, which assigns 5 at line 136. Ordinal one goes through `:cond_77`, adds one, multiplies by `0x14` (20), and caps at `0x78` (120), source lines 120–127. `r20/j.java:27-33` maps Standard to ordinal zero and Increasing to ordinal one. The durations above use the DEX result rather than the inverted Java reconstruction.

## Emergency pass

`xv/a.java:16-22` defines one hour of access and a seven-day cooldown. `aw/b0.java:137-166` selects the latest redemption and derives available/active/cooldown UI state. `te/d.java:1268-1269` checks that the current time is at least seven days after the last redemption; earlier coroutine gates also require eligible account/rule state, so the cooldown alone is not the full eligibility policy.

On eligible redemption, `bt/p.java:2078-2094` creates a temporary unblock with ID `emergency-pass`, an all-content selection, and a one-hour interval. The evaluator explicitly recognizes the IDs `emergency-pass` and `school-bypass` as overrides of strict blocks (`o10/c.java:206-245`). Emergency redemption history is a local database entity (`rr/h.java:35`); the database also stores temporary-unblock intervals, cancellation time, and separate unblock history (`gb/r.java:520`, `gb/r.java:526`). This does not establish server-side eligibility or resistance to clock tampering.

## Managed and group rules

This Android build contains managed authorities for School and Family (`zp/i0.java:18-24`). The DTO-to-domain mapper validates group or member scope, requires a user ID for member scope, distinguishes all apps versus distracting apps, parses recurring weekday/time schedules, and parses finite sessions (`q00/b.java:39-94`). Thus these are executable data mappings, not only dormant display labels.

Managed and personal rules are evaluated separately with separate always-allowed selections, then their resulting states are merged (`o10/c.java:64-114`). Locally persisted managed rules include target group, rule scope and block scope; managed bypasses include an interval, self-request flag, and whether they also bypass personal rules (`gb/r.java:502-504`). The bypass DTO mapper reads those properties (`q00/b.java:97-110`). Self-bypass status carries enabled/remaining/limit/period/reset values (`com/withopal/opal/remote/rest/managedrule/dto/SelfBypassStatusDto.java:61-69`; mapping `q00/b.java:113-132`). The APK cannot reveal the server's authorization checks or prove a family's or school's live configuration.

## Implications for an independent Flint feature

The clearest transferable behavior is an explicit, temporary access pass with a waiting period, a visible remaining budget, and automatic return to blocking. Keep the rule state, waiting state, active pass, and period reset distinct. Combining overlapping rules must preserve the strongest active restriction; an ordinary break must not silently bypass a stricter rule. Persist the pass deadline and usage count across process restarts.

Flint can implement temporary access independently using its own local data and interface.
The user's expanded request separately includes connected accounts and managed groups; that
scope is recorded in [the parity ledger](06-opal-android-parity.md). These local rule traces
do not establish Opal's server behavior, complete parity, or the need for its analytics or assets.
