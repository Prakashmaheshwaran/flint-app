# Managed-policy Android integration

Verified 2026-10-11 on `flint-test` / emulator-5554: API 35 arm64, 1080×2400,
density 420, font scale 1.0. The API, Firebase Auth and Firestore services ran locally
under `demo-flint`; no Opal server, production provider or paid service was used.

## Exact build and checks

- `make verify-android` passed: **1,136 variant test executions / 666 unique cases**,
  with zero failures, errors or skips. [Results](android-tests.json)
- `make selftest` passed: 47 tooling cases and 39 release-version cases.
- Built, retained and independently pulled installed APK SHA-256:
  `b69e91667a23fe236dee45d8e417cb6ffad214f862cd3b5032f0caa5f8defe81`.
  [Build identity](build.json), [source/config hashes](source-hashes.json)
- Runtime dependency inspection selected coroutines **1.10.2** and Kotlin stdlib
  **2.1.21**. The full gate log has zero Kotlin metadata rewriting/parsing warnings.
  [Gate summary](gate-summary.txt), [compatibility finding and remaining toolchain limits](../android-connected-2026-10-11/build-compatibility.md)
- Backend changes were verified separately with 24 real Auth/Firestore emulator
  integration cases. [Backend evidence](../android-connected-2026-10-11/backend-managed-tests.md)

## Actual owner-to-member flow

1. Restored the synthetic owner account in the final APK and opened **Parity Family →
   Manage policies**. Its initial owner list contained no live rules.
2. Created **Study APK evidence** through the native form: recurring Monday–Friday,
   09:00–15:00, America/New_York, group members, and Chrome selected by its app label.
   The UI showed the saved card. Independent API readback confirmed exactly those fields
   and revision **3**. [Created record](owner-created.json)
3. Edited that same rule to **Study APK evidence updated**, changed its type to a one-time
   session and its scope to **Android Member**. The service retained its ID and advanced
   revision to **4**, with fixed UTC bounds 07:09–08:09. [Member response](member-updated.json)
4. Signed the owner out and signed the synthetic member in through Flint. The member view
   showed the updated name, **Applies to you**, UTC bounds and Chrome, with no owner editor.
5. Used the Android debug firewall to deny networking to Flint alone, then pressed Refresh.
   The UI explicitly reported the failed refresh and retained the last accepted policy.
   Restored the app allow rule and disabled the temporary firewall chain in a `finally`
   block; fresh readback confirmed both original settings. [Network readback](network-readback.json)
6. Force-stopped and relaunched Flint. The member's Firebase session and group access
   restored without entering credentials again. The managed view fetched and displayed the
   same policy. This online restart check does not alone prove offline cache restoration;
   a real DataStore close/reopen and boot-clock handling are covered by
   [the cache/repository tests](../android-connected-2026-10-11/managed-android-repository.md).
7. Signed back in as owner, opened the saved policy, and deleted it through the native
   confirmation dialog. The owner list became empty. Independent member API readback
   contained no live rules and a revision **5** tombstone for the same rule ID.
   [Deletion response](member-deleted.json)
8. Signed in as member again. Its native policy view reported that no policies apply to
   it in the group. Signed out afterward and confirmed that profile/group/policy controls
   disappeared. The final emulator screen is Home with no active test Timer; the test
   managed policy has been removed. Existing synthetic accounts/group remain available.

| Capture | Observed result |
|---|---|
| [Owner initial state](01-owner-empty.png) | Owner role and empty policy list |
| [Created recurrence](02-owner-created.png) | Saved weekdays, time zone and Chrome |
| [Edited session](03-owner-updated.png) | Member-specific scope and fixed UTC session |
| [Member view](04-member-policy.png) | Accepted policy with read-only controls |
| [Failed refresh](05-member-network-failure.png) | Existing policy remains visible with a stale-state notice |
| [Member after restart](06-member-restarted.png) | Same account/group/policy restored and refreshed |
| [Deletion confirmation](07-delete-confirmation.png) | Explicit removal of the selected policy |
| [Owner after deletion](08-owner-deleted.png) | Empty owner list |
| [Member after deletion](09-member-deleted.png) | Complete snapshot removes the policy from the member view |
| [Signed out](10-signed-out.png) | Account/group/policy controls cleared |
| [Final Home](11-final-home.png) | Signed-out Home after the test Timer ended |

Companion XML files retain UI text and bounds. Credentials, bearer tokens and invitation
codes are not included in this evidence. The accounts and their displayed addresses are
synthetic local fixtures, not the user's Opal or Google accounts.

A clean service shutdown/export/restart/import preserved both original synthetic identities,
the group and membership IDs, revision 5, zero live rules and the deletion tombstone.
The API was left running locally. [Service checkpoint](service-checkpoint.json) and
[independent final member readback](final-service-readback.json) record that state.

## Timer regression in this exact APK

The Accessibility service was off; Usage Access and overlay permissions were on. A new
five-minute Easy Timer targeting Chrome exercised the UsageStats backup path:

- [Running Timer](t1-timer-running.png): one target and a persisted deadline.
- [Chrome blocked](t2-chrome-blocked.png): the real blocking surface states that a focus
  session is running. The managed policy feature remains inactive.
- [After force-stop/relaunch](t3-timer-restarted.png): the same deadline and reduced remaining time.
- [Automatic expiry](t4-after-expiry.png): left Chrome behind the blocking surface until
  the deadline. Without another app switch, screen tap or clock change, the blocking surface
  dismissed and Chrome's welcome screen became visible.
- [Finished Timer](t5-timer-finished.png): returning to Flint showed the finished state.

No active test Timer remains. Earlier Easy cancellation and frozen-target checks are in
[the Timer milestone report](../android-timer-access-2026-10-11/README.md), tied to its own APK.

## Explicit limits

**Managed policies are authored and inspected, not enforced by this build.** The pure
managed evaluator is tested, but it is not connected to either detector. `unblocks.allowed`
is stored preference only; no managed bypass route or quota is implemented. The personal
v2 temporary-access ledger is also dormant. Existing personal blocking continues to use
the documented legacy behavior.

No API 23/physical-device, TalkBack, large-text, OEM, managed offline enforcement, push,
billing, reward, reporting or production-provider claim is made. Authenticated Opal visuals
remain unavailable behind its App Check failure. These artifacts prove Flint behavior;
they do not establish a perfect 1:1 copy. [Remaining parity work](../../research/06-opal-android-parity.md)
