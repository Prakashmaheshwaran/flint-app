# iOS protection status snapshot v1

`flint.status.v1` is a privacy-safe, advisory projection in the App Group. Dates
are seconds since 1970. It never authorizes a start, weakening, grant or pass;
those actions read the protection ledger and their committed operation instead.

The wire states remain `protected`, `not_protected`, `degraded`, `suspended` and
`unavailable`. Readers reject unsupported versions, unavailable records, future
observations and expired validity bounds. An unknown degradation reason must
produce `degraded`, with no enforcement, headline or armed-session assertion.
Keep its original reason string for diagnosis rather than interpreting it as
success or ignoring it.

`degradedReasons` uses additive string codes:

- `state_unavailable`: required state or a rule's scope could not be read.
- `record_unreadable`: persisted intent exists but cannot be decoded.
- `not_authorized`: Screen Time authorization is absent.
- `time_uncertain`: a relevant duration anchor is uncertain or discontinuous.
- `registration_failed`: an enabled cause lacks successful registration.
- `registration_unobserved`: the expected native registration was not observed.
- `recovery_applying`: a committed Emergency Pass still has release, event,
  pass-authorized disable or recovery-end resume work awaiting acknowledgement.

While `recovery_applying` is present, publish `degraded`, no headline/session and
no enforcing causes. A pending release must not publish `suspended`; pending
restoration must not publish `protected`. Once release is acknowledged, a still
active logical recovery publishes `suspended`. After recovery, only acknowledged
and observed naturally active causes may publish protection. Missing/corrupt
ledger state takes precedence as `unavailable`.

The logical recovery and next-pass dates may remain in `pass` while work is
applying; they describe the committed interval, not observed shield removal.
Pending work bounds snapshot validity to at most 60 seconds. Writers publish
again after the outbox acknowledgement. DeviceActivity delivery while locked and
actual shield/guard timing require physical-device evidence.

The wire fixture is
`ios-app/Tests/Fixtures/ProtectionStatus/recovery-applying.json`.
Reader tests also cover unknown future reasons and inconsistent success or
suspension claims. No new state enum case or opaque target data is introduced.
