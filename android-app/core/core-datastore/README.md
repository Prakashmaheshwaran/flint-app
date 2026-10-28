# Storage readiness and durable grants

Production stores expose `StorageState.Loading`, `Ready(value)`, or
`Unavailable(IO | CORRUPT | UNSUPPORTED_VERSION)`. Legacy stores retain their
documented optional-key defaults before migration; initialized ADR-012 ledgers
require every critical key. An unsuccessful read never
publishes those defaults. Compatible value flows emit only successful reads;
`rulesOrError` additionally throws an explicit storage exception for Timer retry.
The rule bridge retains its last good rules on unavailable reads and never
sweeps an unreadable snapshot.

## ADR-012 timed Open-credit authority (R-11/R-19)

`OpenCreditStore.kt` supplies synchronous `prepareOpenCredits`,
`prepareOpenCreditPeriod`, `readOpenCredits` and `MutablePreferences.putOpenCredits`
helpers. They run inside the existing `flint_preferences` edit; none opens a
nested transaction. Runtime wiring must prepare the quota period first, run editor
preparation for due daily changes, evaluate current caps/overlapping causes, then
commit the pure `OpenCreditLedgerPolicy` result and its receipt together. Only the
outer edit's acknowledgement permits access. This seam does not itself remove the
older foreground-debit coordinator path described below; C4b owns that integration.

`open_credit_schema_v1=1` requires all of `open_credit_state_v1` (`S|1`),
`open_credit_counts_v1` (`O|1`), `open_credit_legacy_archive_v1` (`A|1`) and
`quota_zone_v1`. Missing/malformed/unsupported initialized data is unavailable;
there is no fallback to an empty counter or the legacy archive. The archive retains
exact old `open_counts_v1` bytes and `open_counts_day_v1` with presence flags, and
original legacy keys remain intact. Valid over-cap and removed-target counts survive
import. A previous-day label alone does not reset them. Only a validated unused
legacy Open domain may initialize explicit empty state once. Downgrade is unsupported;
older builds must not become a second ledger writer.
That one-time unused case includes configured legacy Open limits with both count
keys absent and no Open receipt: old builds wrote those keys only on first use.
The editor sidecar's new `D|periodId` row also makes pre-R-11 builds report the
whole editor state as unsupported; preserve the archive and use a compatible build.

The saved IANA `quota_zone_v1` is shared with Time Limits and captured at the first
committed limit. One monotonic reset clock governs every target, including targets
whose rule was removed/recreated. Actual accepted credits last at most 300 seconds,
clipped at the reset. The ledger retains accepted operation IDs for the current and
previous period, and distinguishes Accepted, AlreadyActive, Duplicate and Denied.
Only Accepted spends; retries never restart the original interval. The caller passes
the shared `ProtectionClock` stamp; this ledger adds no boot identity source.

Wall discrepancies over five seconds mark uncertainty without minting quota.
Reboot closes active credit access, retains spend, and resumes the last checkpoint's
remaining-to-reset without credit for powered-off time. One subsequent reset selects
the next saved-zone midnight; missed resets are never replayed. Daily editor occurrence
IDs, queued boundaries and Harder counts use that same reset rather than finite-session
wall fallback. An unprovable old Open release leaves a durable ended-access notice flag.
Read-driven reconciliation retains equivalent anchors and does not persist a new
checkpoint on every read. Actual policy entries/mutations and observed rollover,
expiry, reboot or new uncertainty still checkpoint durably.

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

ADR-012 Time Limits extend each `time_limits_v1` row from `package~minutes` to
`package~minutes~tier`. First-load migration durably snapshots
`default_break_level_v1` for tierless rows and archives the original bytes in
`editor_migration_time_limits_v1`; explicit tiers then remain independent of the
global default. Both row shapes are readable here. Unknown tiers or additional
fields report `Unavailable(UNSUPPORTED_VERSION)` and preserve the complete value.
Pre-tier builds with the strict two-field reader likewise report a three-field
row as `UNSUPPORTED_VERSION`; use a compatible newer build to recover. Historical
lenient readers are not safe downgrade targets: there is no automatic downgrade
rewrite that discards a selected tier, and the archive is not a live fallback.

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
An existing active same-boot receipt can still acknowledge its original request
without a second spend. Old non-`f1` receipt IDs remain decodable and their
consumption is preserved; without a provable current boot they authorize no
access and may be reclaimed at the next request. Live APIs never issue or accept
those older token shapes. A legacy row with a proven current boot remains active
until its original deadline, then becomes eligible for reclamation.
The controller caps retained entries at 1,024 and applies a conservative escaping
budget below the codec's 4 MiB bound. Capacity refusal is explicit and spends no
credit; active grants are never evicted to make room. Operations have a five-second
timeout, below the retry horizon; external cancellation propagates. A timed-out
acknowledgement is explicitly unconfirmed (it may follow a commit), so the surface
keeps its request identity for retry/Ready-read reconciliation rather than claiming
the credit is unspent. Only an acknowledged or successfully read receipt can lift
protection.

On devices that do not expose a stable boot identity, process restart ends active
access conservatively: “Access ended after restart; no extra credit was spent.”
Consumed counters/pass and replay protection remain. This can shorten a previously
accepted interval; R5-RECOVERY must display this reason instead of implying a lost
credit or offering an automatic replacement grant.

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
