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
epoch deadline and boot identity; per-cause waits and independent pass last-used timestamps remain
representable. A store-owned monotonic revision covers accounting and receipts together;
the process projection rejects older revisions, including delayed pre-commit reads.
Initial additive-v1 headers without a revision decode as revision zero and migrate on
the next successful change. Both v1 headers (`H|1` and `H|1|revision`) read unchanged;
the next change writes `H|2|revision|minimumRequestEpochMs`, adding a monotonic retry
retention watermark. Version 2 receipt rows append the boot identity (or `-` for
an unproven legacy boot). Legacy accounting keys retain their exact formats;
old six-field receipt rows remain readable with an unknown boot. Expired receipts
and receipts from another or an unknown boot cannot authorize access. Consumption
and audit receipts survive reboot. A same-boot process restart restores remaining
access when Android supplies its boot count; if that identity is unavailable, a
process-scoped fallback conservatively closes access on process restart.

Downgrade behavior: builds that only understand `H|1` report the `H|2` ledger as
`Unavailable(UNSUPPORTED_VERSION)` and preserve its bytes. Use a compatible newer
build to recover; downgrading never resets the ledger or refunds consumed access.

The dormant v2 access store's transaction/failure pattern was reused rather than
activating its distinct resistance policy, monotonic/boot-scoped durations and
personal-only targets, which would change the current v1 policy contract. Its
data and tests remain independent. Phase 1 exposes the transaction and restore
APIs. The live coordinator now uses a plain-JVM durable grant controller to commit
credits, extra Open-Limit debits and receipts together before acknowledging an exit.
It restores acknowledged/Ready same-boot receipts and schedules their remaining expiry on startup.
Loading/Unavailable never enables a grant or a new counted Open-Limit visit; loaded
rules still enforce and existing acknowledged grants retain their deadlines.

Recovery follow-up R5-RECOVERY: this build has no in-app repair path for a corrupt
file or record. Add local export/preservation of the original backing bytes and an
explicit owner-approved restore/repair UI before offering any destructive reset;
a future-version payload instead needs a compatible newer build. Until that work
lands, preserve the data and report Unavailable; never reset automatically. The
focus transaction strictly validates its shared accounting snapshot; malformed
accounting can therefore disable break/pass/counter commits until repaired.
Configuration keys have independent readiness: an ordinary grant requires its
cause's configuration source, while a package Emergency Pass requires the focus
ledger only. Last-known-good loaded rules still enforce. R5-RECOVERY must explain
which storage domain needs repair.

R5-RECEIPT-RETENTION: live requests carry an issued-at epoch and random identity in
the existing opaque request-id field (`f1:<epoch>:<uuid>:<scope-index>`). The controller
keeps every active deadline, plus inactive receipts for a one-hour retry horizon;
it prunes older inactive receipts inside the next transaction, including a
capacity-refused request that cannot fit after partial reclamation. Requests outside
that horizon cannot create a debit, even after their receipt has been pruned.
The persisted watermark never rewinds, so clock rollback and process recreation
cannot make a pruned callback fresh again. A clock earlier than that watermark
refuses new grant/counter requests until time catches up; no credit is consumed.
An existing active receipt can still acknowledge its original request without a
second spend. Old valid receipt IDs are preserved while active and may be removed
after expiry; live request APIs never issue or accept those older token shapes.
The controller caps retained entries at 1,024 and applies a conservative escaping
budget below the codec's 4 MiB bound. Capacity refusal is explicit and spends no
credit; active grants are never evicted to make room. Operations have a five-second
timeout, below the retry horizon; external cancellation propagates. A timed-out
acknowledgement is explicitly unconfirmed (it may follow a commit), so the surface
keeps its request identity for retry/Ready-read reconciliation rather than claiming
the credit is unspent. Only an acknowledged or successfully read receipt can lift
protection.

Only apps with a configured Open Limit write normal-open accounting. Unrestricted
app switches create neither a counter write nor receipt pressure. Normal counted
foreground opens use inactive (`until=0`) receipts to deduplicate one debit
across both detectors. Those receipts never exempt a rule. A shared in-process
visit becomes paid only after commit; its final allowed open survives poll ticks
and system/Flint bounces. A real departure ends the visit. Process death forgets
normal visit identity and re-decides against durable counts (the existing strict
policy), while remaining break/pass access is restored. The transaction API accepts
package sets/global scope and duration inputs; today's host still supplies a
five-minute package break or a package pass ending with its cause (30 minutes for
open-ended causes). It does not activate ADR-012's later policy change.

A counted open remains policy-blocked until its commit is acknowledged. Surface
mounting has a 100 ms grace so fast ordinary saves do not flash a shield; a longer
hold can mount, but never emits an intercepted-open history event. The production
detector wake callback clears the hold immediately after acknowledgement. A measured
same-boot wall-clock rollback clips receipt deadlines atomically with accounting;
forward jumps may end access early and never extend it. Pure zone changes and
unmeasured jumps leave receipt deadlines alone. The forthcoming trusted elapsed
contract will replace this conservative wall-clock behavior.
