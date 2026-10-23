# Flint local connected services

This milestone provides a real local API backed by Firebase Authentication and Firestore emulators: accounts/profiles, household or school groups, member lists, owner-issued one-use invitations, acceptance, member leave, and owner-authored managed sessions/schedules with complete member snapshots. The Android connected client authenticates with the Auth emulator and sends its ID token to this API. Every API request derives its actor from Firebase Admin `verifyIdToken(token, true)`; clients cannot read or write Firestore directly.

This is an **emulator-only development service**. It has no cloud project, deployment command, real email/SMS delivery, real Google sign-in, billing, managed-rule enforcement, or production push service. The `school` group kind does not itself implement institutional verification or school restrictions. No Opal endpoints, keys, assets, or code are used.

## Run and verify

Use Node 24.21.0 (pinned in `.node-version`; Node 22 is also supported) and an existing Java 21+ installation.

```sh
cd connected-services
npm ci --ignore-scripts
npm run check
npm test
npm start
```

If the machine's default Node is another version, an isolated pinned runtime works without changing system Node:

```sh
npm exec --yes --package=node@24.21.0 -- node scripts/test.mjs
npm exec --yes --package=node@24.21.0 -- node scripts/local.mjs
```

The launcher selects the repository machine's existing Homebrew Java 21 when present, otherwise honors `JAVA_HOME`/PATH. It starts only the Auth and Firestore emulators under `demo-flint`, starts the API after emulator readiness, and stops its own emulators on exit. It does not stop conflicting services. Stop with Ctrl-C or SIGTERM and allow the emulator export/shutdown to complete. Repeated termination signals and a terminal hangup are handled without interrupting export. Child output goes to private ignored `logs/` files so closing the parent terminal cannot break a detached exporter's output stream. Firebase emulator logs may contain password-reset links; keep them private. Hard kills or crashes can lose changes made since the previous clean shutdown.

`npm start` imports the previous local Auth/Firestore snapshot from the ignored, owned `.emulator-data/` directory when present and exports both services there on clean shutdown. Accounts, profiles, groups, invitations, managed rules, membership identities and authority revisions therefore survive clean restarts. The launcher enforces directory mode `0700` and a private child-process umask. Treat this directory as private development account data; it is not committed or uploaded. The service does not automatically delete it.

`npm test` uses fresh ephemeral emulator processes without import or export. It neither loads nor clears `.emulator-data/`. Tests require the same ports to be free; stop the development launcher before starting tests. A conflicting live emulator causes test startup to fail rather than letting tests mutate its data.

| Service | Host endpoint |
|---|---|
| Flint API | `http://127.0.0.1:8787` |
| Firebase Auth emulator | `http://127.0.0.1:9099` |
| Firestore emulator | `127.0.0.1:8085` |
| Emulator hub/logging | `127.0.0.1:4400` / `127.0.0.1:4500` |

Firestore uses 8085 because another local service already occupies 8080. Android Emulator can address the host using `10.0.2.2`; a device connected by adb can use explicitly configured `adb reverse`. The API itself binds only `127.0.0.1`. Android API and Auth base URLs must be set consistently. Release builds must not inherit emulator endpoints or cleartext access.

Dependencies are pinned in `package.json` and `package-lock.json`. No real Firebase config file or service-account key is required. A fresh synthetic credential exists only in process memory so the SDK never needs ambient account credentials; metadata-server detection is disabled. The launcher rejects conflicting project/emulator environment settings; the API refuses missing emulator variables, non-loopback/different endpoints, a non-`demo-flint` project, credential-file configuration, and `NODE_ENV=production`. There is deliberately no production starter: production needs a separate reviewed configuration, signed-token verification, deployment, and provider setup.

## Authentication

Use Firebase Auth's emulator SDK configuration or its documented REST API. For example, the local signup path is `/identitytoolkit.googleapis.com/v1/accounts:signUp?key=flint-local-only` on port 9099 with `{email,password,returnSecureToken:true}`. Sign-in uses `accounts:signInWithPassword` with the same shape. The key here is a nonsecret placeholder for a demo project. Keep returned ID/refresh tokens in the client credential store, not source, logs, or UI.

All `/v1/*` requests require `Authorization: Bearer <Firebase ID token>`. The API checks expiration, disabled/deleted user state, and revocation on each request. The health endpoint is public and returns `{status:"ok",mode:"emulator-only",projectId:"demo-flint"}`.

Firebase's Auth emulator uses **unsigned development tokens**; it is not a production identity security boundary. The strict demo/loopback guard contains that test behavior. These are the official references used for setup: [Auth emulator](https://firebase.google.com/docs/emulator-suite/connect_auth), [session revocation](https://firebase.google.com/docs/auth/admin/manage-sessions), [Firestore emulator](https://firebase.google.com/docs/emulator-suite/connect_firestore).

## API contract

Mutation requests use `Content-Type: application/json`. JSON bodies must be objects. Unknown properties are rejected. Timestamps are UTC ISO-8601 strings. IDs are opaque strings. Collections return arrays inside named objects.

```text
User:       { id, email: string|null, displayName, createdAt, updatedAt }
Group:      { id, name, kind: "family"|"school", ownerId,
              role: "owner"|"member", memberCount, createdAt, updatedAt }
Member:     { userId, displayName, role: "owner"|"member", joinedAt, membershipId? }
```

| Endpoint | Body | Result | Authority |
|---|---|---|---|
| `GET /v1/me` | — | `{user: User}` | Self |
| `PATCH /v1/me` | `{displayName}` | `{user: User}` | Self |
| `GET /v1/groups` | — | `{groups: Group[]}` | Own memberships |
| `POST /v1/groups` | `{name, kind}` | 201 `{group: Group}` | Authenticated user becomes owner |
| `GET /v1/groups/:id` | — | `{group: Group}` | Member or owner |
| `GET /v1/groups/:id/members` | — | `{members: Member[]}` | Member or owner |
| `POST /v1/groups/:id/invites` | `{}` or `{expiresInHours}` | 201 `{invite:{code,groupId,expiresAt,role:"member"}}` | Owner only |
| `GET /v1/invites/:code` | — | `{invite:{groupId,groupName,kind,role:"member",expiresAt}}` | Signed-in holder of an unused code, or its accepting member |
| `POST /v1/invites/:code/accept` | `{}` | `{group: Group}` | Signed-in holder of an unused code |
| `POST /v1/groups/:id/leave` | `{}` | `{left:true}` | Member only; owner receives 409 |

Invitations return a code to the owner; this service does not send invitations. A preview does not create membership. Invite acceptance is a Firestore transaction across invitation, group, and user's membership index. Concurrent users cannot consume the same code twice. The accepting user can retry successfully while still a member. After leaving, their old consumed code cannot rejoin them. A different account receives 409 after consumption. New invites are always member-role invites; a supplied `role` is rejected.

Display/group names must contain 1–80 trimmed characters with no control characters. Invitation lifetime defaults to 24 hours and allows 1–168 hours. Limits are 20 groups per account, 100 members per group, 20 unexpired pending invitations per group, 8 KiB request bodies, 120 authenticated requests per minute per user, 300 requests per minute per source address, and 20 invite-create attempts per hour per user. Rate counters are local to the single API process and reset on restart; durable distributed rate limiting is outside this local milestone.

Errors use `{error:{code,message}}`. Statuses include 400 malformed JSON, 401 invalid/missing/revoked session, 403 authorization/origin denial, 404 missing resource, 409 used invite/capacity/owner-leave conflict, 410 expired invite, 413 body limit, 415 content type, 422 invalid fields, 429 rate limit, and 503 unavailable backend.

## Managed authoring and member snapshots

The frozen [managed contract](MANAGED_CONTRACT.md) specifies owner GET/POST/PUT/DELETE routes, validated session and recurrence fields, explicit IANA zones, group/member targeting, per-rule revision conflicts, retained tombstones, and `GET /v1/me/managed-state`. Managed snapshots are complete and actor-bound; consumers must replace their isolated managed state atomically and reject obsolete identity/revision responses. Legacy memberships acquire opaque membership IDs on their first managed read; every later join generates a new ID.

Group-scoped rules target non-owner members. This and the authoring API are explicit Flint policies, not recovered Opal server behavior. The backend stores `unblocks.allowed` but provides no bypass grants or quota. It does not demonstrate Android managed enforcement or DST schedule evaluation. The debug-only Android client supports account/group flows, managed-policy authoring and cached snapshot inspection; none of those policies activates device blocking.

## Stored data and limits

- `users/{uid}`: profile, scoped only to that user.
- `groups/{id}`: group metadata, owner, bounded membership map/index, hashed pending invitation IDs.
- `userGroups/{uid}`: transactionally maintained group ID list for enforcing account limits.
- `invites/{sha256(code)}`: group/creator/expiry and consumer metadata. The original random 256-bit code is returned once and never stored. Logs exclude request URLs, bodies, tokens, and invitation codes.
- `managedGroups/{groupId}`: bounded live rule map and scoped deletion tombstones. Owner mutations and membership authorization are checked in one transaction.
- `managedMetadata/state`: persistent monotonically increasing authority revision, also advanced by membership changes. Complete snapshots read all relevant records consistently.

Firestore rules deny every direct client operation; the API uses Admin access and checks permissions itself. Member lists expose display names/roles, not other members' account email addresses. Browser-origin requests are rejected; the current consumer is the native client. Development data persists only through the local import/export flow above; test data is ephemeral.

Integration tests exercise real emulators: identity/profile isolation, owner/member/foreign authorization, one-use and concurrent invite acceptance, expiration, leave/replay behavior, direct-Firestore denial, invalid/expired/disabled/deleted/revoked sessions, invalid payloads, body limits, rate limits, managed CRUD/targeting, concurrent revision conflicts, tombstones, membership migration/rejoin, and API-process recreation. They do not prove production Firebase policy, Android UI behavior, or the broader connected feature set.

The upstream evidence and unrecovered server boundaries are in [the connected-contract investigation](../docs/research/opal-4.14.0-connected-contracts.md).
