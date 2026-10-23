# Local connected backend verification

Verified 2026-10-11. Scope: `connected-services/` authenticated API, Firebase Auth emulator, and Firestore emulator. This is evidence for the local account/group/invitation milestone, not production service or complete Opal parity.

## Automated checks

- Syntax checks passed with `npm run check`.
- Real-emulator integration command: `npm exec --yes --package=node@24.21.0 -- node scripts/test.mjs` from `connected-services/`.
- Runtime: Node 24.21.0, existing Homebrew OpenJDK 21.0.11, Firebase Admin 14.5.0, Firebase CLI 15.33.0, Firestore emulator 1.22.0.
- Final backend integration run: **12 passed, 0 failed, 0 skipped**, approximately 5.9 seconds of test execution after emulator startup. [Sanitized result log](backend-tests.txt) contains test names and results only.
- Tests launched fresh emulators under `demo-flint`; no import/export arguments were used and the persistent development snapshot was not loaded or cleared.

| Boundary | Verified result |
|---|---|
| Emulator guard | Refuses production mode, real project IDs, remote emulator hosts, credential-file settings, and missing fixed emulator configuration. |
| Session checks | Missing/malformed, expired, disabled-user, deleted-user, and revoked sessions receive 401. Every protected API request uses `verifyIdToken(token, true)`. |
| Profile isolation | Updates derive user ID from the verified session. Supplied identity/unknown fields are rejected. |
| Group authority | Creator becomes owner. Other accounts cannot read the group/member list or create invitations. Members cannot create invitations. Owner cannot orphan a group by leaving. |
| Invitation lifecycle | Preview is authenticated and has no membership side effect. Only a hash of the random code is stored. Acceptance updates invitation, group, and membership index in one transaction. |
| Race handling | Two different accounts consuming the same code concurrently yield exactly one 200 and one 409, and exactly one new membership. |
| Retry/expiry | Same accepting account can retry while still a member. Expired codes fail without joining or consumption. |
| Leave/replay | Leave removes membership access. The consumed code cannot rejoin a departed member. |
| Direct database access | Firestore rules reject authenticated direct reads and writes; only authorized API operations succeed. |
| Input bounds | Unknown fields, unsupported kinds/roles, malformed JSON, excessive names/lifetimes/body size, and user rate limits are enforced. |

## Clean restart readback

The development launcher was exercised through two launches and graceful shutdown, using only an owned synthetic test identity:

1. Created an Auth emulator account, saved its profile through the API, and created a group.
2. Stopped the owned launcher, allowing Auth and Firestore exports to finish in `.emulator-data/`.
3. Restarted the launcher. It imported both Auth accounts and Firestore data.
4. Signed in again through the Auth REST endpoint and read back the saved profile and the exact group ID through the API.
5. Removed the exact synthetic fixture records/account, then completed another clean export/shutdown.
6. Started the local service again for Android verification. Its public health readback was `{status:"ok",mode:"emulator-only",projectId:"demo-flint"}`.

The revised development launcher stops the API before signaling the emulator process, preventing duplicate export/shutdown races. Its clean shutdown completed one successful export and stopped its owned emulators. The snapshot root is ignored by git and has filesystem mode `0700`; new launches enforce that permission plus a private umask. A hard kill/crash can still lose writes after the last successful export.

## Environment and limits

The API binds `127.0.0.1:8787`, Auth binds `127.0.0.1:9099`, and Firestore binds `127.0.0.1:8085`. Existing OrbStack on 8080 was preserved. Listener readback confirmed the loopback bindings. Firebase's reserved UI websocket on 9150 also bound loopback; the browser Emulator UI is disabled.

The machine's default Node 26 produces an unsupported-engine warning in one Firebase CLI dependency. The installed Homebrew Node 22 cannot run because of a missing library. Verification used the isolated pinned Node 24 runtime and did not modify system Node or Java.

Firebase Auth emulator tokens are unsigned development tokens. Loopback/demo-only constraints are essential; these tests do not validate production cryptographic identity. No real Firebase project, third-party login, email/SMS delivery, cloud deployment, push provider, or payment system was configured. General cross-device personal-rule sync and school-managed enforcement remain outside this backend milestone.

No Firebase request/debug logs, token values, invitation codes, account credentials, or local snapshot data are included in this evidence folder.
