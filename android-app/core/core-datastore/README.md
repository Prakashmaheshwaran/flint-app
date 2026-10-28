# Storage readiness and durable grants

Production stores expose `StorageState.Loading`, `Ready(value)`, or
`Unavailable(IO | CORRUPT | UNSUPPORTED_VERSION)`. A missing key after a
successful read retains the existing v1 defaults. An unsuccessful read never
publishes those defaults. Compatible value flows emit only successful reads;
`rulesOrError` additionally throws an explicit storage exception for Timer retry.
The rule bridge retains its last good rules on unavailable reads and never
sweeps an unreadable snapshot.

Only transient IO is retried automatically. File corruption emits `CORRUPT` once
and stops; corrupt/unsupported payloads emit an unavailable state without a polling
loop and can recover only after valid backing data is supplied. Bounded work uses
`snapshotOrNull` on readiness flows, never `first()` on a compatible value-only
flow. The clock receiver also bounds its entire async operation before releasing
its broadcast result.

Malformed/unknown v1 records invalidate the whole affected value. Writes decode
strictly inside `edit`, refuse the write, and leave the original preference
bytes intact. There is no destructive repair or corruption handler. Existing
10/11-field rules and v1 accounting keys keep their wire formats.

`FocusStateStore.transact` atomically reads current v1 accounting, applies a pure
reducer, and commits both accounting and additive `focus_exemptions_v1` receipts
in one `edit`. Only its returned `Committed` acknowledges durability. Receipts
include request id, package (`*` represents global scope), cause key, kind and
epoch deadline; per-cause waits and independent pass last-used timestamps remain
representable. A store-owned monotonic revision covers accounting and receipts together;
the process projection rejects older revisions, including delayed pre-commit reads.
Initial additive-v1 headers without a revision decode as revision zero and migrate on
the next successful change. Expired receipts are retained for idempotent retries but excluded
from the enforcement projection. There is no automatic receipt pruning.

The dormant v2 access store's transaction/failure pattern was reused rather than
activating its distinct resistance policy, monotonic/boot-scoped durations and
personal-only targets, which would change the current v1 policy contract. Its
data and tests remain independent. Phase 1 exposes the transaction and restore
APIs; the coordinator switches live grant decisions in phase 2.

Recovery follow-up R5-RECOVERY: this build has no in-app repair path for a corrupt
file or record. Add local export/preservation of the original backing bytes and an
explicit owner-approved restore/repair UI before offering any destructive reset;
a future-version payload instead needs a compatible newer build. Until that work
lands, preserve the data and report Unavailable; never reset automatically.

Phase-2 prerequisite R5-RECEIPT-RETENTION: cap/prune the currently retained receipts
before wiring live grants, with tests proving active deadlines and duplicate
callbacks remain safe across the retention boundary. The present 4 MiB encoding
bound is a fail-closed storage bound, not a retention policy.
