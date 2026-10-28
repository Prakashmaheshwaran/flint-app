# Storage readiness and durable grants

Production stores expose `StorageState.Loading`, `Ready(value)`, or
`Unavailable(IO | CORRUPT | UNSUPPORTED_VERSION)`. A missing key after a
successful read retains the existing v1 defaults. An unsuccessful read never
publishes those defaults. Compatible value flows emit only successful reads;
`rulesOrError` additionally throws an explicit storage exception for Timer retry.
The rule bridge retains its last good rules on unavailable reads and never
sweeps an unreadable snapshot.

Malformed/unknown v1 records invalidate the whole affected value. Writes decode
strictly inside `edit`, refuse the write, and leave the original preference
bytes intact. There is no destructive repair or corruption handler. Existing
10/11-field rules and v1 accounting keys keep their wire formats.

`FocusStateStore.transact` atomically reads current v1 accounting, applies a pure
reducer, and commits both accounting and additive `focus_exemptions_v1` receipts
in one `edit`. Only its returned `Committed` acknowledges durability. Receipts
include request id, package (`*` represents global scope), cause key, kind and
epoch deadline; per-cause waits and independent pass last-used timestamps remain
representable. Expired receipts are retained for idempotent retries but excluded
from the enforcement projection. There is no automatic receipt pruning.

The dormant v2 access store's transaction/failure pattern was reused rather than
activating its distinct resistance policy, monotonic/boot-scoped durations and
personal-only targets, which would change the current v1 policy contract. Its
data and tests remain independent. Phase 1 exposes the transaction and restore
APIs; the coordinator switches live grant decisions in phase 2.
