# Android per-target protection reads

`TargetProtectionReader.observe(TargetRef.App(packageName))` and
`observe(TargetRef.Site(domain))` return a flow of `TargetProtection` for Android
App/Site detail. This is a display API, never authority to grant access or change
protection. Lane H must copy its resolved summary rather than derive a blocking
decision from attribution rows or the aggregate `ProtectionStatus` inventory.

Production hosts call `createTargetProtectionReader(context, scope,
readFloorIdentity)` from the app module, passing the existing enforcement
coordinator's retained floor accessor. The factory creates no coordinator and
starts no reconciler. `ObservedTargetProtectionReader` is the injectable flow
implementation for tests/other hosts. It shares the strict snapshot decoder
once, projects cached records on a shared one-second clock, conflates results,
and stops upstream work when its last collector leaves. Usage is sampled only
for the requested app/current browser with a positive budget, at most once per
15 seconds; Usage Access is rechecked before returning any cached measurement.
Projection takes a fresh clock sample after gathering observations: a health or
store publication newer than the invalidating tick is never mistaken for a
clock regression.
Saved rules are compared with the enforcement bridge's published definitions
and occurrence anchors. Loading, failed or lagging publication is unknown,
including the gap after a rule was deleted from storage. A policy health check
older than the decoded snapshot also cannot establish target enforcement or an
empty/unprotected decision. These checks never publish or reconcile rules.

The reader has no edit, reconcile, migrate, checkpoint, grant, debit or event
delivery entry point. `ProtectionSnapshotReader` reuses the protection transaction
store's atomic decoder through its read-only state flow. Its `openCredits` flow
preserves `Loading`, `Unavailable` and validated absent legacy state; it never
creates a zero-used pool. A read cannot initialize the new protection contract.

`TargetProtection` exposes:

- `readiness`: `Readiness.Loading`, `Ready` or `Unavailable(reason)`.
- `floor`: independently known `FloorIdentity`, including the retained runtime
  source's last successful elapsed resolution time and degradation. Static and
  self identities do not depend on policy storage. Reading retained provenance
  does not refresh the floor or relabel an old lookup as a new check.
- `coveringCauses`: target attribution, including inactive templates. Each
  `CoveringCause` has a stable `causeKey`, optional `occurrenceId`, stored tier,
  activity, nullable enforcement, finite end estimate and next boundary.
  A recurrence projected before its owner publishes its new occurrence has a
  null `occurrenceId`; consumers must not turn that absence into a new identity
  or use this display API to authorize edits/access.
- `blockingNow`, `blockingUntilWall`, `blockingOngoing`, `headline` and
  `recoveryEndsAtWall`: the resolved decision. The headline is the strongest
  enforcing tier, ties by latest end and stable identity. The total blocking end
  is the latest enforcing contributor, which can differ from the headline's end.
- `openCredit`: readable used/cap accounting independently of availability,
  active accepted-credit end and quota-period end. Until timed-credit enforcement
  can honor the access, `available` is false and the reason is explicit.
  `activeCreditEndsAtWall` remains null while that runtime path is unavailable;
  readable ledger data must not be treated as access the shield will honor.
- `timeLimit`: effective quota, stored tier and optional measured usage. A null
  measurement is unavailable usage, never a fresh zero; zero quota is exhausted
  even without Usage Access.
- `nextChangeAtWall`: the earliest known future boundary, including a recovery
  end. These wall values are display estimates, not new deadlines.
- `websiteCoverage`: target-scoped supported-browser health. Accessibility and
  actual browser observations bound it; it is never an all-websites promise.
  A retained observation from a browser that is no longer foreground is shown
  as `AWAITING_BROWSER`. Site counts are not browser-app counts: `openCredit`
  and `timeLimit` are null for Sites, while an identified current browser's
  app/limit causes still contribute to attribution and the blocking summary.

`active` describes a commitment, while `enforcingNow` describes its current
target denial. A break, global pass recovery or unspent Time Limit can leave a
commitment active while it is not enforcing. Null enforcement is unknown; it
must never become “not protected.” `blockingOngoing` means known open-ended
enforcement, not merely a missing or uncertain end. If a contributor is unknown,
an exact combined end is not asserted.

Ordinary recovery uses enforcement's exact frozen occurrence/definition set.
A newly added or changed contributor cannot inherit a grant. Global recovery
covers all personal targets for its remaining committed interval. Same-boot
elapsed time governs both; reboot expires access without refunding consumption.
Neither projection mutates the durable recovery lifecycle or outbox.

Each observation returns only causes and counters for the requested identity.
Names are display-sanitized. It does not return lists of other apps/domains or
record URLs, browsing history, analytics or network data. Unit and fake-clock
evidence for this API does not prove actual on-device shielding or service
survival.
