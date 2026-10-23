# Managed Android repository verification

Date: 2026-10-11. Scope: local connected owner authoring, authenticated complete member snapshots, durable cache and account isolation. This report does **not** establish managed blocking, bypass authorization, production connectivity or Opal server equivalence.

## Implementation

- Account and managed UI factories share `ConnectedRuntimes` for one named Firebase app, identity session, transport and managed repository per local backend configuration.
- `ConnectedIdentitySession` fences responses with backend/UID, persistent session ID and process generation. Explicit same-UID authentication rotates the fence. Sign-out invalidates the hot identity before suspension. Startup restoration checks its captured generation before/after disk access; a suspended old restore cannot publish after sign-out. Old HTTP 401 responses cannot sign out a newer session.
- Owner CRUD uses rule revisions and strict typed input/output; owner results never populate member authority. Network loss, server 5xx and malformed successful mutation responses report ambiguity. Only an explicit 401 causes one token-refresh retry.
- `ManagedWireCodec` requires v1 complete snapshots, exact actor identity, applicable membership/scope, bounded rows/targets, exact known fields and types, unique IDs, valid IANA zones, valid calendar timestamps and bounded integers. Weekday range is checked before integer narrowing. Input rule JSON is capped at 2 KiB; snapshots at 1 MiB.
- `ManagedCacheStore` is a separate managed-only Preferences DataStore. It saves the active identity fence, raw complete snapshot/fingerprint, revision watermark, leave exclusions and server-time/elapsed-time anchor together. Partial, unknown, wrong-typed, corrupt or unsupported records remain unavailable and are preserved. Failed writes do not publish accepted state.
- Snapshot acceptance replaces all rows, rejects revision regression and differing contents at the same revision, and rejects known tombstone resurrection. Failed refresh retains last good same-session data as stale. A confirmed leave durably excludes the old membership until a complete snapshot confirms its absence.
- Cache restoration rebinds a matching persistent session to the new process generation. Same-boot elapsed time can confirm its server-time anchor; reboot/elapsed regression yields `Unconfirmed`. No wall-clock lease, cache expiry or device enforcement was invented. Published clock values are inspection-time readings; future enforcement needs fresh elapsed-time evaluation.
- Existing personal rules, usage and selections remain separate. Only explicit owner-managed policy fields/selections are sent to the local service. No tokens/passwords are stored in managed cache or evidence.

## Executed checks

```sh
cd android-app
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew \
  :feature:feature-connected:testDebugUnitTest \
  :core:core-datastore:testDebugUnitTest --console=plain
```

Final focused run: **BUILD SUCCESSFUL in 8s**, 42 actionable tasks (5 executed, 37 up-to-date).

| Suite | Tests | Failures/errors |
|---|---:|---:|
| ConnectedIdentitySessionTest | 6 | 0 |
| ConnectedTransportTest | 4 | 0 |
| ManagedPolicyRepositoryTest | 8 | 0 |
| ManagedWireCodecTest | 8 | 0 |
| ManagedPolicyDraftTest | 7 | 0 |
| ManagedPolicyViewModelTest | 14 | 0 |
| Existing connected input/state tests | 15 | 0 |
| **feature-connected total** | **62** | **0** |
| ManagedCacheStoreTest | 6 | 0 |
| Existing core-datastore tests | 60 | 0 |
| **core-datastore total** | **66** | **0** |

Coverage includes a real Preferences DataStore close/reopen, atomic disk-failure retention,
partial/unknown/wrong-type/future cache records, corrupt initialization without process crash,
transient storage recovery, suspended-startup/sign-out race, same-UID relogin during an
in-flight fetch, late401 isolation, complete omission/revision checks, durable membership
exclusion, boot mismatch/elapsed regression, malformed mutation responses and 500-after-
possible-commit uncertainty.

The focused run uses fake Firebase/HTTP boundaries for deterministic Android repository
races; it does not substitute for emulator service/device round trips. Real backend tests
are recorded separately in `backend-managed-tests.md`. Full Android gate and device
owner/member evidence are recorded by the root integration task after its final source freeze.

## Remaining boundaries

- Corrupt/future local storage is preserved, not silently reset. Its explicit unavailable
  state requires storage repair/application-data recovery; a transient I/O error can be
  retried through explicit authentication. Sign-out still clears Firebase when cache writes fail.
- No production service, managed evaluator activation, all-cause block integration, push
  refresh, server bypass route, billing, reward or reporting behavior is claimed here.
- Saved stale policies are inspection data in this slice. Activation must explicitly define
  enforcement for unavailable/unconfirmed clocks, use fresh clock reads, preserve membership
  and rule revision authority, and prevent personal grants from covering managed causes.
