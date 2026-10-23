# APK-backed Timer early exit

Verified 2026-10-11 on `flint-test` / emulator-5554, API 35 arm64,
1080×2400, density 420, font scale 1.0. This follows the
[managed-policy milestone](../android-managed-2026-10-11/README.md) and uses its own APK.

## Exact build and automated checks

- `make verify-android` passed: **1,166 variant test executions / 681 unique cases**,
  zero failures, errors or skips. [Test results](android-tests.json), [gate summary](gate-summary.txt)
- Retained APK and independently pulled installed APK SHA-256 both equal
  `ced55cd1bb06e0a56d34a0ff6a0feaffbcdfc55c55c292f0376956fca8dbdcfa`.
  [Build identity](build.json), [234 source/config hashes](source-hashes.json)
- Fifteen added unique cases cover native Timer eligibility, exact confirmation phases,
  incomplete release/reset, screen exit, process recreation, expiry, stale snapshots,
  persistence failure, duplicate commits, concurrency and preservation of other rules.
- No tracked script or Makefile changed in this follow-up. The earlier `make selftest`
  result remains 47 tooling cases plus 39 release-version cases, all passing.

## Recovered behavior and implementation

The [original DEX trace](../../research/opal-4.14.0-timer-exit-trace.md) establishes that
Opal's confirmed early exit removes its active Timer and associated blocking rule even
when that Timer was created as Strict. It does not issue a break covering other rules.

Flint now gives its native `session-timer-` records **Leave early → Hold to leave** at
every existing Flint break level. The hold lasts two seconds, including an initial 300ms
visual compression; it then runs a 400ms finish phase and 400ms callback delay. A release
before completion resets progress. Release or dismissal after completion does not cancel
the accepted confirmation. Success is shown only after the store reports deletion.

Deletion compares the complete displayed rule against the current persisted rule in one
transaction. Changed targets, name, tier, schedule, enabled state or deadline require a
new confirmation. Duplicate IDs are rejected. No other rule, temporary grant, limit,
managed cache or account state is modified by this operation. Older Home session records
retain their existing Easy-only cancellation contract.

Flint's animation is an original treatment. Its gesture uses Compose's documented
`awaitEachGesture`/release-cancellation primitives. [Android gesture documentation](https://developer.android.com/develop/ui/compose/touch-input/pointer-input/understand-gestures)
Keyboard focus loss cancels an incomplete hold. Accessibility activation starts a
cancellable countdown so sustained touch is not required; that fallback is a Flint
design choice, and real TalkBack/Switch Access checks remain outstanding.

## Actual emulator flow

The native UI created a temporary Easy rule named **Exit verification overlap**, targeting
Chrome, plus a 30-minute **Hardcore** Timer targeting Chrome. Those records survived the
final APK update. All linked captures below were refreshed on the exact final APK.

1. Confirmed that the separate overlap rule and the active Hardcore Timer were present.
2. Opened **End timer** and held the confirmation for only one second. The control returned
   to **Hold to leave**. **Never mind** returned to the still-running Timer.
3. Force-stopped and relaunched Flint. The Timer restored with the identical deadline and
   reduced remaining time; no early exit had been committed.
4. Opened Chrome. The real UsageStats backup path displayed its Hardcore blocking surface.
   A stale “no early stop” sentence discovered during the first run was corrected before
   rebuilding and repeating these final captures.
5. Used **Open Flint → Timer → End timer**, held for 2.2 seconds, and observed **Timer ended.
   Other rules may still apply.** after persistence. The native Timer view returned to idle.
6. Opened Chrome again. It remained blocked, now with the Easy break affordance. Returning
   to My Apps showed the original **Exit verification overlap** rule still enabled and
   active. This verifies that ending the Hardcore Timer did not grant access through it.
7. Deleted only that temporary overlap rule through its native confirmation. My Apps
   returned to **Nothing blocked yet**, Chrome's welcome screen became accessible,
   and Flint was left on Home with no active test Timer. The local connected API remains
   healthy in emulator-only mode; this follow-up made no account or managed-policy changes.

| Capture | Observed result |
|---|---|
| [Separate rule](01-overlap-created.png) | Chrome overlap rule, enabled and active |
| [Hardcore Timer](02-hardcore-timer.png) | Native Timer and persisted deadline |
| [Confirmation](03-leave-confirmation.png) | Hold-to-leave and cancellation controls |
| [Short release](04-short-release.png) | One-second hold returns to idle confirmation |
| [Still running](05-still-running.png) | Same Timer and deadline after cancellation |
| [After restart](06-restarted.png) | Same deadline restored after force-stop/relaunch |
| [Before ending](07-blocked-before-ending.png) | Real Hardcore blocking surface |
| [Timer ended](08-timer-ended.png) | Persisted success and idle Timer screen |
| [Overlap still blocks](09-overlap-still-blocks.png) | Chrome still blocked, Easy affordance |
| [Overlap preserved](10-overlap-preserved.png) | Separate named rule still enabled |
| [Test rule removed](11-overlap-removed.png) | Native empty rule list after confirmed deletion |
| [Chrome accessible](12-chrome-accessible.png) | No residual test block after cleanup |
| [Final Home](13-final-home.png) | No active Timer; ready to set a new one |

Companion XML records UI text and bounds. [Readback assertions](ui-readback.json) and
[artifact hashes](SHA256SUMS) tie these observations to retained files. These artifacts
contain no credentials, tokens or invitation codes.

## Limits

The device check exercised the UsageStats/overlay path with Accessibility off. Exact
phase boundaries are established by deterministic coroutine tests; screenshot timing is
not a precision latency measurement. Accessibility-path, physical-device, API 23,
large-text, keyboard and assistive-service runtime checks remain outstanding.

The legacy block screen calls an unscheduled Easy rule a “focus session”; the named-rule
readback above identifies the actual remaining rule. The existing break tiers and emergency
pass behavior are unchanged. Finite Timer exit is not full Opal rule or visual parity.
Infinity, site/allow-only selection, separate access-limit settings and Digital Detox
media are still pending.

Connected owner/member authoring, cache retention and restart evidence belongs to the
[preceding APK report](../android-managed-2026-10-11/README.md); its connected unit tests
also pass in this build. Managed enforcement and the v2 temporary-access ledger remain
dormant. Production providers, push, billing and rewards are not implemented here.
See the [complete delivery ledger](../../research/06-opal-android-parity.md).
