# Connected accounts, groups and managed policies

Native Compose client for the connected-service development sandbox. Open **Settings →
Connected sandbox (debug)** in a debug build. It connects to the local `demo-flint` Auth emulator (9099) and Flint
API (8787) through `10.0.2.2`. Start instructions live in [`connected-services/`](../../../connected-services/).
Only test account details belong in this environment. The app uses a `debugImplementation`
dependency and separate debug/release entry points. Release APKs contain no connected client
or Firebase account SDK and expose no account navigation. Flint's shipped blocking stays
local-first, account-free and free of analytics.

Implemented: email account creation/sign-in/reset/sign-out, persistent SDK session, profile
name editing, family/school group creation and membership, expiring single-use invitation
creation/preview/acceptance, owner/member permissions and member leave. The server owns all
membership state. Creating an invitation displays its code; it sends no message.

Owners can create, inspect, edit and delete session or recurring policies for their group or
an individual member. Authoring explicitly sends the selected packages/domains, name,
schedule, timezone, targeting and unblocks policy to the local service. Existing personal
rules/selections, usage and device status are not uploaded. Members inspect their accepted
complete policy snapshot, including saved offline state and its availability.

**Managed device blocking is not active in this milestone.** Saved policies do not enter the
personal rule store or blocking bridge. `unblocks.allowed` does not grant a personal break
or enable a managed bypass endpoint. Enforcement integration, bypass permits, reporting,
billing, Google/phone login, account deletion, rewards and production deployment remain separate milestones.
See [the full parity ledger](../../../docs/research/06-opal-android-parity.md).

## Boundaries

- `ConnectedRepository` uses a named Firebase app initialized only when this screen opens.
  SDK auth owns token persistence/refresh. Passwords are never saved by the UI or client.
- Account and managed ViewModels share one application-lived identity session and repository.
  Explicit authentication rotates both a process generation and a durable session fence,
  including a new login for the same UID. Sign-out clears published identity before disk work.
  Late callbacks cannot publish a snapshot or sign out a newer session.
- REST sends a verified-session bearer token, follows no redirects, retries a 401 once after
  refresh, bounds timeouts/response sizes, and never prints tokens, invite codes or payloads.
- Mutations are not replayed after network loss, a server 5xx or invalid success response.
  An uncertain creation requires owner-list refresh and review before the UI permits another
  creation. Updates/deletes carry the expected rule revision; a conflict requires review.
- The dedicated `managed_authority_cache` DataStore stores a backend/project/UID binding,
  durable session fence, complete snapshot, fingerprint, revision watermark, membership
  exclusions and monotonic server-time anchor. It stores no token or password. It is separate
  from personal preferences and is covered by the app's disabled backup policy.
- Snapshot decoding rejects partial/unknown/duplicate fields, wrong types, unknown schemas,
  cross-account/owner targets and oversized input. Acceptance atomically replaces the full
  snapshot, rejects lower revisions and rejects different contents at the same revision.
  Owner listings never populate member authority. A confirmed leave suppresses the exact
  old membership locally and durably even if the next refresh fails.
- Fetch/storage errors retain a same-session last good snapshot as `Stale`; without one,
  state is `Unavailable`, never a fabricated empty success. Corrupt/future local records are
  preserved and cannot be overwritten by normal operations. Sign-out still clears Firebase
  if local storage is unavailable; transient I/O can recover on explicit sign-in retry.
- Clock confirmation uses server time plus elapsed realtime only. Same-boot cache restoration
  preserves that anchor. Reboot, missing boot identity, or elapsed regression yields
  `Unconfirmed` until a successful fetch; device wall-clock changes never rewrite sessions.
  There is no server lease or invented cache expiry, and no anti-reboot enforcement claim.
- `ConnectedViewModel` serializes actions, binds snapshots to UID and purges all account data
  when identity changes. Loaded flags separate errors from successful empty lists.
- Only debug network-security configuration permits HTTP to the emulator host. The local
  API rejects production settings and direct Firestore clients cannot read or write data.
- Dependencies deliberately exclude Firebase Analytics, Crashlytics and Messaging. Auth
  24.0.1 fits the current Kotlin 2.1 compiler; 24.2.0 requires Kotlin 2.3 metadata.

## Verification

`make verify-android` includes strict wire/cache codecs, durable DataStore reopening,
identity-generation races, same-UID reauthentication, old-401 isolation, monotonic snapshot
replacement, leave exclusions, stale/offline clocks and ambiguous mutation handling, as well
as the UI authoring tests. The separate backend integration suite exercises real Auth/Firestore emulators.
Android UI/service round-trip evidence belongs in the dated verification folder, not in
unit-test claims.
